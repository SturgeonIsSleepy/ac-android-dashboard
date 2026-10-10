"""Check the running real AC bridge, then restore the one FOV value changed here.

No telemetry injection or driving controls. Keep AC running with the relay loaded,
and avoid editing mirror settings from another client during this check.
"""
import argparse
import contextlib
import hashlib
import math
import socket
import struct
import time
from pathlib import Path

MAGIC = 0x31464341
MINIMUM = (20, -60, -35, -2, -1, -3)
MAXIMUM = (100, 60, 35, 2, 1, 3)
ROOT = Path(__file__).resolve().parents[1]


def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.recv(size-len(data))
        if not part:
            raise EOFError('Bridge closed the connection')
        data.extend(part)
    return data


def config(packet):
    assert len(packet) == 84, 'Configuration body must be exactly 84 bytes'
    revision, *values = struct.unpack_from('<i18f', packet, 8)
    assert all(math.isfinite(value) and MINIMUM[i % 6] <= value <= MAXIMUM[i % 6]
               for i, value in enumerate(values)), 'Invalid configuration field'
    return revision, values


class Client:
    def __init__(self, args, view, length=100, capability=1):
        self.stream = socket.create_connection((args.host, args.port), 2)
        self.view, self.length, self.capability = view, length, capability
        self.next_ping = 0
        self.telemetry = self.pongs = 0

    def subscribe(self):
        hello = bytearray(100)
        struct.pack_into('<iiqqqqiii', hello, 0, MAGIC, 3, time.monotonic_ns(), -1, 0, 0, 682, 422, 1)
        hello[52:73] = b'mirror-settings-check'
        struct.pack_into('<qii', hello, 84, -1, self.view, self.capability)
        self.send(hello[:self.length])
        self.next_ping = time.monotonic()+.5

    def send(self, body):
        self.stream.sendall(struct.pack('<i', len(body))+body)

    def packet(self, deadline):
        if time.monotonic() >= self.next_ping:
            self.subscribe()
        self.stream.settimeout(max(.01, deadline-time.monotonic()))
        size = struct.unpack('<i', read_exact(self.stream, 4))[0]
        assert 8 <= size <= 512016, ('Invalid body length', size)
        packet = read_exact(self.stream, size)
        magic, kind = struct.unpack_from('<ii', packet)
        assert magic == MAGIC, 'Foreign packet magic'
        self.telemetry += kind == 1
        self.pongs += kind == 4
        return kind, packet

    def wait_config(self, accept, timeout=6):
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            kind, packet = self.packet(deadline)
            if kind == 8:
                revision, values = config(packet)
                if accept(revision, values):
                    return revision, values
        raise AssertionError('Timed out waiting for configuration revision')

    def command(self, view, field, value):
        self.send(struct.pack('<iiiif', MAGIC, 9, view, field, value))

    def close(self):
        self.stream.close()


def near(actual, expected):
    return len(actual) == len(expected) and all(math.isclose(a, b, abs_tol=.00001) for a, b in zip(actual, expected))


def stored(path):
    rows = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        view, fields = line.split('=', 1)
        rows[int(view)] = [float(value) for value in fields.split(',')]
    assert set(rows) == {1, 2, 3} and all(len(row) == 6 for row in rows.values())
    return rows[1]+rows[2]+rows[3]


def wait_files(args, peer, values, persisted=True):
    folder = args.game_dir/'apps'/'lua'/'acflip_relay'
    deadline = time.monotonic()+6
    while time.monotonic() < deadline:
        try:
            state = dict(line.split('=', 1) for line in (folder/'mirror-status.txt').read_text(encoding='utf-8').splitlines())
            rendered = [float(value) for value in state.get('settings', '').split(',')]
            if ((not persisted or near(stored(args.settings_file), values)) and near(stored(folder/'mirror-config.txt'), values)
                    and int(state.get('ready', '0')) == args.view
                    and math.isclose(float(state.get('fov', 'nan')), values[(args.view-1)*6], abs_tol=.00001)
                    and near(rendered, values[(args.view-1)*6:args.view*6])):
                return
        except (OSError, ValueError, AssertionError):
            pass
        peer.packet(deadline)
    raise AssertionError('Saved PC/app settings and actual Lua render state did not agree')


def image_hash(peer, previous=None):
    deadline = time.monotonic()+6
    while time.monotonic() < deadline:
        kind, packet = peer.packet(deadline)
        if kind != 6 or struct.unpack_from('<i', packet, 8)[0] != peer.view:
            continue
        assert len(packet) > 20 and packet[16:18] == b'\xff\xd8' and packet[-2:] == b'\xff\xd9', 'Invalid mirror JPEG'
        digest = hashlib.sha256(packet[16:]).hexdigest()
        if digest != previous:
            return digest
    raise AssertionError('No changed mirror image hash received')


def legacy_clients(args):
    for length in (16, 84, 92, 96, 100):
        with contextlib.closing(Client(args, 0, length, 0)) as peer:
            deadline = time.monotonic()+1
            while time.monotonic() < deadline:
                try:
                    kind, _ = peer.packet(deadline)
                except socket.timeout:
                    if time.monotonic() >= deadline:
                        break
                    raise
                assert kind not in (6, 8, 10), ('Instrument-only client received mirror/config/status body', length, kind)
            assert peer.telemetry > 0 and peer.pongs > 0, ('Legacy telemetry and RTT missing', length)
            print('Instrument-only hello', length, 'telemetry', peer.telemetry, 'no types 6/8/10', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=9877)
    parser.add_argument('--game-dir', type=Path, default=Path('D:/Program Files/steam/steamapps/common/assettocorsa'))
    parser.add_argument('--settings-file', type=Path, default=ROOT/'dist'/'mirror-settings.txt', help='mirror-settings.txt beside the running bridge EXE')
    parser.add_argument('--view', type=int, choices=(1, 2, 3), default=1)
    parser.add_argument('--image-check', action='store_true', help='Also require a changed JPEG hash after the FOV update')
    args = parser.parse_args()
    legacy_clients(args)
    with contextlib.closing(Client(args, args.view)) as peer:
        revision, original = peer.wait_config(lambda revision, values: True)
        wait_files(args, peer, original, persisted=False)
        before_hash = image_hash(peer) if args.image_check else None
        field = (args.view-1)*6
        changed = original[:]
        changed[field] += 5 if original[field] <= 95 else -5
        try:
            peer.command(args.view, 0, changed[field])
            updated, actual = peer.wait_config(lambda version, values: version > revision and near(values, changed))
            wait_files(args, peer, actual)
            print('Type 9 FOV update', original[field], '->', actual[field], 'revision', revision, '->', updated,
                  'saved to PC/app and applied by actual Lua render', flush=True)
            if args.image_check:
                after_hash = image_hash(peer, before_hash)
                print('Changed actual mirror JPEG hash', before_hash, '->', after_hash, flush=True)
            assert peer.telemetry > 0, 'Primary telemetry must continue while configuring mirrors'
        finally:
            # A fresh connection also restores settings if the test connection failed.
            with contextlib.closing(Client(args, args.view)) as restore:
                before, _ = restore.wait_config(lambda revision, values: True)
                restore.command(args.view, 0, original[field])
                restore.wait_config(lambda revision, values: revision > before and near(values, original))
                wait_files(args, restore, original)
                print('Original settings restored on PC, in app config and actual Lua render', flush=True)
            peer.view = 0
            try:
                peer.subscribe()
            except OSError:
                pass
    print('Real mirror settings transport, persistent configuration and legacy compatibility passed', flush=True)


if __name__ == '__main__':
    main()
