"""Record real AC UDP frames and cover-screen transitions without game input."""
from pathlib import Path
import concurrent.futures
import argparse
import json
import socket
import struct
import subprocess
import time

import os

ADB = os.environ.get('ADB','adb')
parser = argparse.ArgumentParser(); parser.add_argument('--output',default='artifacts/drive-0.8.1'); parser.add_argument('--seconds',type=float,default=600)
args = parser.parse_args(); out = Path(args.output); out.mkdir(parents=True,exist_ok=True)
pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
def screen(label):
    try:
        subprocess.run([ADB,'shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-drive.png'],check=True,timeout=15,capture_output=True)
        subprocess.run([ADB,'pull','/sdcard/acflip-drive.png',str(out/(label+'.png'))],check=True,timeout=15,capture_output=True)
        metrics = subprocess.check_output([ADB,'shell','run-as','cn.acflip.dash','cat','files/metrics.txt'],timeout=10)
        out.joinpath(label+'-metrics.txt').write_bytes(metrics)
    except Exception as e: print('Capture:',e,flush=True)

magic = 0x31464341
previous = None; started = time.monotonic(); hello_at = 0; periodic = 0; frames = 0
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock, out.joinpath('real-drive.acftrace').open('wb') as trace, out.joinpath('events.jsonl').open('w',encoding='utf-8') as events:
    sock.connect(('127.0.0.1',9876)); sock.settimeout(.1)
    while time.monotonic()-started < args.seconds and not out.joinpath('stop').exists():
        now = time.monotonic()
        if now >= hello_at:
            sock.send(struct.pack('<iiq',magic,3,time.perf_counter_ns())); hello_at = now+.3
        try: data = sock.recv(1200)
        except (socket.timeout,ConnectionResetError): continue
        if len(data) != 1032 or struct.unpack_from('<i',data,4)[0] != 1: continue
        stamp = time.perf_counter_ns(); trace.write(struct.pack('<iq',len(data),stamp)+data); frames += 1
        status,current,last,best,lap,pit,sector = struct.unpack_from('<i',data,16)[0],*struct.unpack_from('<4i',data,44),struct.unpack_from('<i',data,60)[0],struct.unpack_from('<i',data,284)[0]
        state = (lap,pit,sector)
        if state != previous:
            item={'seconds':round(now-started,3),'lap':lap,'pit':pit,'sector':sector,'status':status,'current':current,'last':last,'best':best,'progress':struct.unpack_from('<f',data,296)[0]}
            events.write(json.dumps(item)+'\n'); events.flush(); print(item,flush=True)
            label=f'{round(now-started):03d}-lap{lap}-pit{pit}-sector{sector}'
            pool.submit(screen,label); previous=state
        if now >= periodic:
            pool.submit(screen,f'{round(now-started):03d}-periodic'); periodic=now+15
        if frames%120 == 0: trace.flush()
pool.shutdown(wait=True)
print('Recorded real AC frames:',frames,flush=True)
