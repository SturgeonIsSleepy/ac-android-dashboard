"""Check framing and non-subscribing RTT probes against a running real AC bridge."""
import socket
import struct
import time
from pathlib import Path

MAGIC = 0x31464341

def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.recv(size-len(data))
        assert part, 'TCP closed during a framed packet'
        data.extend(part)
    return data

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
    probe.settimeout(1)
    sent = time.monotonic_ns()
    probe.sendto(struct.pack('<iiq', MAGIC, 7, sent), ('127.0.0.1', 9876))
    reply, _ = probe.recvfrom(1200)
    assert struct.unpack('<iiq', reply) == (MAGIC, 4, sent)
    probe.settimeout(.1)
    try:
        probe.recvfrom(1200)
        raise AssertionError('An RTT-only probe must not subscribe to telemetry')
    except socket.timeout:
        pass

with socket.create_connection(('127.0.0.1', 9877), 1) as stream:
    stream.settimeout(2)
    hello = bytearray(92)
    struct.pack_into('<iiqqqqiii', hello, 0, MAGIC, 3, time.monotonic_ns(), -1, 0, 0, 682, 422, 1)
    hello[52:66] = b'protocol-check'
    struct.pack_into('<q', hello, 84, -1)
    stream.sendall(struct.pack('<i', len(hello))+hello)
    pong = route = False
    frames = []
    for _ in range(64):
        size = struct.unpack('<i', read_exact(stream, 4))[0]
        assert 8 <= size <= 1200, size
        body = read_exact(stream, size)
        magic, kind = struct.unpack_from('<ii', body)
        assert magic == MAGIC
        if kind == 4:
            assert size == 16 and body[8:] == hello[8:16]
            pong = True
        elif kind == 2:
            route = True
        elif kind == 1:
            assert size == 1032
            frames.append(struct.unpack_from('<i', body, 8)[0])
            Path('artifacts/tcp-real-0.8.2.bin').write_bytes(body)
    assert pong and route and len(frames) >= 60
    assert all(a < b for a, b in zip(frames, frames[1:])), 'TCP frames must never go backwards'
print('Real AC TCP framing, 92-byte USB subscription, route, pong and RTT-only UDP probe passed')
