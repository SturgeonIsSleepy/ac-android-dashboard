"""Read the running AC mirror stream, without injecting telemetry or moving the car."""
import hashlib
import io
import socket
import struct
import time
from pathlib import Path
from PIL import Image, ImageStat

output = Path('artifacts/mirror-actual'); output.mkdir(parents=True,exist_ok=True)

def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.recv(size-len(data)); assert part
        data.extend(part)
    return data

def subscribe(stream, view):
    hello = bytearray(96)
    struct.pack_into('<iiqqqqiii',hello,0,0x31464341,3,time.monotonic_ns(),-1,0,0,682,422,1)
    hello[52:64] = b'mirror-check'
    struct.pack_into('<qi',hello,84,-1,view)
    stream.sendall(struct.pack('<i',len(hello))+hello)

hashes = []
with socket.create_connection(('127.0.0.1',9877),1) as stream:
    stream.settimeout(2)
    for view in (1,2,3):
        subscribe(stream,view); start = ping = time.monotonic(); frames = 0; telemetry = 0
        while time.monotonic()-start < 3:
            if time.monotonic()-ping > .5: subscribe(stream,view); ping = time.monotonic()
            size = struct.unpack('<i',read_exact(stream,4))[0]; assert 8 <= size <= 512016
            packet = read_exact(stream,size); magic, kind = struct.unpack_from('<ii',packet); assert magic == 0x31464341
            if kind == 1: telemetry += 1
            if kind != 6 or struct.unpack_from('<i',packet,8)[0] != view: continue
            jpg = packet[16:]; image = Image.open(io.BytesIO(jpg)); assert image.size == (1024,576)
            assert max(ImageStat.Stat(image).stddev) > 20, 'Mirror must have visible scene detail'
            if frames == 0:
                output.joinpath(f'view-{view}.jpg').write_bytes(jpg)
                hashes.append(hashlib.sha256(jpg).hexdigest())
            frames += 1
        assert frames >= 20 and telemetry >= 30, (view,frames,telemetry)
        print('Actual AC mirror',view,'frames',frames,'telemetry',telemetry,flush=True)
    assert len(set(hashes)) == 3, 'Left, centre and right must show distinct fields'
    subscribe(stream,0)
print('Real mirror selection, nonblank JPEG frames, distinct fields and simultaneous telemetry passed')
