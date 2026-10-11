"""Windows bridge lifecycle regression, with actual ADB and no synthetic telemetry.

Run only with the bridge stopped. Keeps a real ADB server alive while testing
duplicate launch and abrupt console exit. The child inheritance reproduction
is covered separately by BridgeSocketLifetimeCheck.cs.
"""
import argparse
import os
from pathlib import Path
import socket
import shutil
import struct
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--bridge', required=True, type=Path)
parser.add_argument('--adb', required=True, type=Path)
args = parser.parse_args()
bridge, adb = args.bridge.resolve(), args.adb.resolve()
magic = 0x31464341

def available():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.bind(('0.0.0.0', 9876))
    with socket.socket() as tcp:
        tcp.bind(('0.0.0.0', 9877))

def server_pid():
    return subprocess.check_output(['powershell', '-NoProfile', '-Command',
        "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'adb.exe' -and $_.CommandLine -like '*fork-server server*' } | Select-Object -ExpandProperty ProcessId"], text=True).strip()

def probe():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.settimeout(.5)
        packet = struct.pack('<iiq', magic, 7, time.monotonic_ns())
        udp.sendto(packet, ('127.0.0.1', 9876))
        pong, _ = udp.recvfrom(1200)
        assert pong == packet[:4] + struct.pack('<i', 4) + packet[8:]
    with socket.create_connection(('127.0.0.1', 9877), .5) as tcp:
        tcp.settimeout(1)
        hello = struct.pack('<iiq', magic, 3, time.monotonic_ns())
        tcp.sendall(struct.pack('<i', len(hello)) + hello)
        frames, pong = [], False
        def read(size):
            result = b''
            while len(result) < size:
                part = tcp.recv(size - len(result))
                assert part, 'Unexpected TCP close'
                result += part
            return result
        while len(frames) < 10 or not pong:
            size = struct.unpack('<i', read(4))[0]
            assert 8 <= size <= 1200
            body = read(size)
            identifier, kind = struct.unpack_from('<ii', body)
            assert identifier == magic
            if kind == 4:
                assert body[8:] == hello[8:]
                pong = True
            if kind == 1:
                assert size == 1032
                frames.append(struct.unpack_from('<i', body, 8)[0])
        assert all(a < b for a, b in zip(frames, frames[1:]))

available()
subprocess.run([str(adb), 'start-server'], check=True)
env = {**os.environ, 'ANDROID_HOME': str(adb.parent.parent)}
log_path = Path('artifacts/bridge-lifetime-check.log')
duplicate_path = Path('artifacts/ACFlipBridge-lifetime-copy.exe').resolve()
shutil.copyfile(bridge, duplicate_path)
with log_path.open('w', encoding='utf-8') as log:
    first_server = None
    for cycle in range(3):
        process = subprocess.Popen([str(bridge)], env=env, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline = time.monotonic() + 8
            while True:
                assert process.poll() is None, 'Bridge exited during startup'
                try:
                    probe()
                    break
                except (OSError, AssertionError):
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.1)
            if cycle == 0:
                deadline = time.monotonic() + 10
                while not first_server and time.monotonic() < deadline:
                    first_server = server_pid()
                    if not first_server:
                        time.sleep(.2)
                assert first_server
            duplicate = subprocess.run([str(duplicate_path)], env=env, stdin=subprocess.DEVNULL,
                capture_output=True, timeout=3)
            assert duplicate.returncode == 0
            assert b'9876' not in duplicate.stdout and duplicate.stdout.strip()
            probe()
            assert process.poll() is None, 'Duplicate interrupted original bridge'
        finally:
            process.kill()
            process.wait(timeout=3)
        available()
        assert server_pid() == first_server, 'ADB server must survive bridge exit unchanged'
        print(f'Cycle {cycle + 1}: duplicate ignored, original stream intact, ports released with ADB alive')
print('Bridge restart and ADB socket ownership checks passed')
