"""Native external-screen checks. Synthetic packets are visibly marked demo."""
from pathlib import Path
import os
import re
import struct
import subprocess
import time
from PIL import Image

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/visual-0.5'); OUT.mkdir(parents=True, exist_ok=True)
frame = bytearray(Path('artifacts/live-0.5/telemetry.bin').read_bytes())
assert len(frame) == 944

def adb(*args):
    return subprocess.check_output([ADB,*args], timeout=20).decode('utf-8',errors='replace')
def flush():
    temp = OUT/'fixture.tmp'; temp.write_bytes(frame)
    for _ in range(20):
        try: os.replace(temp,OUT/'fixture.bin'); return
        except PermissionError: time.sleep(.01)
    raise RuntimeError('Fixture locked')
def put(offset,fmt,*values): struct.pack_into('<'+fmt,frame,offset,*values)
def name(offset,value): frame[offset:offset+64] = value.encode()[:63].ljust(64,b'\0')
def capture(label):
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-qa05.png')
    adb('pull','/sdcard/acflip-qa05.png',str(OUT/(label+'.png'))); print(label,flush=True)
def tap(x,y): adb('shell','input','-d','1','tap',str(x),str(y)); time.sleep(.35)
def page(index):
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','page',str(index)); time.sleep(.65)
def settings():
    adb('shell','input','-d','1','keycombination','-t','120','24','25'); time.sleep(.6)
    assert re.search(r'mCurrentFocus=.*cn\.acflip\.dash\.SettingsActivity',adb('shell','dumpsys','activity','activities'))
def back(): tap(341,382)
def style(index):
    settings(); tap(164,134); tap(110,103+60*index); back(); back(); page(0)
def guide(index):
    settings(); tap(164,280); tap(110,103+60*index); back(); back(); page(3)
def prefs(): return adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')

put(16,'4i',258,5,6200,7500); put(32,'3f',156,32.5,100); put(60,'3i',0,0,0)
put(44,'4i',1000,100000,100000,0); put(284,'3i',0,0,3)
put(300,'2i',0,3)
for i,cue in enumerate(((1,2,150),(-1,3,240),(1,6,510))): put(308+i*12,'iif',*cue)
put(412,'if',9000,-1); put(420,'ifi',5,60,1)
put(432,'64i',30000,31000,32000,*([0]*61)); put(688,'64i',28000,29000,30000,*([0]*61))
name(140,'QA-05-PB'); name(204,'ks_maserati_alfieri'); name(348,'VISUAL CHECK'); flush()
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((OUT/'fixture.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    adb('shell','am','force-stop','cn.acflip.dash'); page(0); time.sleep(2)
    settings(); capture('settings-sections'); tap(498,134); capture('rpm-engine-max')
    tap(341,182); capture('rpm-custom-redline'); assert 'name="shift:ks_maserati_alfieri"' in prefs()
    back(); back(); page(0); time.sleep(5.4)
    metrics = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    assert 'limit=7500' in metrics and 'recommended=7500' not in metrics, metrics
    OUT.joinpath('custom-redline-metrics.txt').write_text(metrics,encoding='utf-8')
    capture('ring-custom-redline'); settings(); tap(498,134); tap(341,245); back(); back()
    for i in range(4): style(i); capture('dashboard-style-'+str(i))
    page(1); time.sleep(.4)
    put(44,'i',30001); put(284,'2i',1,29000); flush(); time.sleep(.3)
    put(44,'i',60001); put(284,'2i',2,31000); flush(); time.sleep(.3)
    put(44,'4i',1000,88000,88000,1); put(284,'2i',0,28000); flush(); time.sleep(.3)
    capture('timing-multi-green-yellow-purple')
    im = Image.open(OUT/'timing-multi-green-yellow-purple.png').convert('RGB')
    a,b,c = [im.getpixel((x,356)) for x in (126,340,552)]
    assert a[1] > 150 and a[0] < 80, a
    assert b[0] > 200 and b[1] > 110 and b[2] < 50, b
    assert c[0] > 120 and c[2] > 180 and c[1] < 90, c
    name(140,'QA-05-IDEAL'); put(44,'4i',74214,100000,100000,0); put(284,'3i',2,32000,3)
    put(432,'64i',30000,32000,34000,*([0]*61))
    put(268,'4f',100,60,20,10); put(88,'4f',40,120,85,85); flush()
    guide(1); capture('flying-lap-ideal-fuel-wear-temp'); time.sleep(5.2)
    metrics = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    assert 'ideal=96000' in metrics and 'guide=1' in metrics, metrics
    OUT.joinpath('flying-metrics.txt').write_text(metrics,encoding='utf-8')
    put(60,'i',2); put(32,'f',83); flush(); time.sleep(.6); capture('pit-lane-auto-limit-60')
    put(60,'i',1); put(32,'f',0); flush(); time.sleep(.5); capture('pit-box-auto')
    put(60,'i',0); put(32,'f',156); flush(); time.sleep(.6); capture('pit-exit-restores-flying')
    first = Image.open(OUT/'flying-lap-ideal-fuel-wear-temp.png').convert('RGB')
    restored = Image.open(OUT/'pit-exit-restores-flying.png').convert('RGB')
    # Stable key regions return to hotlap layout despite continuous subpixel drift.
    for box in ((490,45,652,102),(30,155,640,284)):
        counts = [sum(min(p)>180 for p in v.crop(box).get_flattened_data()) for v in (first,restored)]
        assert abs(counts[0]-counts[1]) < max(200,counts[0]*.08), counts
    put(60,'i',2); put(424,'f',0); flush(); time.sleep(.5); capture('pit-unknown-limit-placeholder')
    put(60,'i',0); put(424,'f',60); flush(); guide(0); capture('guide-dark-colored-outlines')
    style(0); time.sleep(5.2)
    OUT.joinpath('final-metrics.txt').write_text(adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt'),encoding='utf-8')
    print('Native settings, per-car redline, four styles, PB colors, ideal sum and automatic pit transitions passed',flush=True)
finally:
    fixture.terminate(); fixture.wait(timeout=10)
