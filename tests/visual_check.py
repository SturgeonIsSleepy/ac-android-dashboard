"""Native X Flip visual checks. Stop the real bridge before running this fixture."""
from pathlib import Path
import argparse
import os
import struct
import subprocess
import time

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/visual-0.3')
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument('--views-only', action='store_true')
args = parser.parse_args()
frame = bytearray(Path('artifacts/live-0.3/telemetry.bin').read_bytes())

def adb(*args):
    return subprocess.check_output([ADB, *args], timeout=15).decode('utf-8', errors='replace')

def put(offset, fmt, *values):
    struct.pack_into('<'+fmt, frame, offset, *values)
    flush()

def name(offset, value):
    frame[offset:offset+64] = value.encode('utf-8')[:63].ljust(64, b'\0')
    flush()

def flush():
    temp = OUT/'fixture.tmp'
    temp.write_bytes(frame)
    for attempt in range(20):
        try:
            os.replace(temp, OUT/'fixture.bin')
            return
        except PermissionError:
            time.sleep(.01)
    raise RuntimeError('Fixture packet is locked')

def capture(label, page=None):
    if page is not None:
        adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','page',str(page))
    time.sleep(.8)
    remote = '/sdcard/acflip-qa.png'
    adb('shell','screencap','-p','-d','4630946993847360387',remote)
    adb('pull',remote,str(OUT/(label+'.png')))
    print(label, flush=True)

put(16, '4i', 258, 5, 7150, 7500)
put(32, '3f', 235, 32.5, 100)
put(44, '3i', 45124, 84923, 84271)
put(60, '3i', 0, 0, 0)
put(72, 'f', -.248)
put(88, '4f', 42, 118, 86, 45)
put(120, '5f', 0, 0, 0, 0, 0)
put(268, '4f', 100, 60, 10, 30)
put(300, 'i', 0)
name(204, 'VISUAL QA')
name(348, 'VISUAL CHECK')
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve())], creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    time.sleep(1)
    capture('gear-redline',0)
    capture('tyres-hot-cold-wear',2)
    put(36, 'f', 2.3)
    capture('fuel-low',2)
    put(36, 'f', 32.5)
    for count in (0,1,2,4,6,12):
        name(140, 'QA-SECTORS-'+str(count))
        put(44,'4i',1000,0,0,0)
        put(284,'3i',0,0,count)
        time.sleep(.25)
        for sector in range(1,count):
            put(44,'i',sector*30000+1000)
            put(284,'2i',sector,30000)
            time.sleep(.25)
        put(44,'4i',1000,max(1,count)*30000,max(1,count)*30000,1)
        put(284,'2i',0,30000)
        capture('timing-'+str(count)+'-sectors',1)
    for label, drs, cues in (
        ('guide-0L-1R-2L-drs-ready',3,((-1,0,150),(1,1,240),(-1,2,510))),
        ('guide-3R-4L-5R-no-drs',0,((1,3,150),(-1,4,240),(1,5,510))),
        ('guide-6L-0R-1L-drs-open',7,((-1,6,150),(1,0,240),(-1,1,510))),
        ('guide-2R-3L-4R-no-drs',0,((1,2,150),(-1,3,240),(1,4,510))),
        ('guide-5L-6R-drs-closed',1,((-1,5,150),(1,6,240))),
    ):
        put(300,'2i',drs,len(cues))
        for i in range(3):
            put(308+i*12,'iif',*(cues[i] if i < len(cues) else (0,0,-1)))
        capture(label,3)
    if not args.views_only:
        put(16,'i',259)
        capture('paused-before-dim',3)
        time.sleep(66)
        idle = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
        OUT.joinpath('idle-metrics.txt').write_text(idle,encoding='utf-8')
        assert 'dim=true' in idle and 'status=259' in idle, 'Idle test interrupted or dimming did not trigger'
        capture('paused-dimmed')
        OUT.joinpath('idle-window.txt').write_text(adb('shell','dumpsys','window','windows'),encoding='utf-8')
        adb('shell','input','-d','1','tap','590','395')
        time.sleep(6)
        touched = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
        OUT.joinpath('touch-metrics.txt').write_text(touched,encoding='utf-8')
        assert 'dim=false' in touched, 'Touch must restore brightness'
        put(16,'i',258)
        time.sleep(6)
        live = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
        OUT.joinpath('live-metrics.txt').write_text(live,encoding='utf-8')
        assert 'dim=false' in live and 'status=258' in live, 'Live data must retain brightness'
    print('Native visual and idle checks captured', flush=True)
finally:
    fixture.terminate()
    fixture.wait(timeout=10)
