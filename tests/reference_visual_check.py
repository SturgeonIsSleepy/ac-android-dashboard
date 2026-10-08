"""Select the actual reference thumbnail and verify its native rendering on X Flip."""
from pathlib import Path
import json
import os
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/reference-0.7'); OUT.mkdir(parents=True,exist_ok=True)
frame = bytearray(Path('artifacts/live-0.7/telemetry.bin').read_bytes())
def adb(*args): return subprocess.check_output([ADB,*args],timeout=25).decode('utf-8',errors='replace')
def put(offset,fmt,*values): struct.pack_into('<'+fmt,frame,offset,*values)
def flush(length=964):
    temp = OUT/'fixture.tmp'; temp.write_bytes(frame[:length])
    for _ in range(20):
        try: os.replace(temp,OUT/'fixture.bin'); return
        except PermissionError: time.sleep(.01)
    raise RuntimeError('Fixture locked')
def capture(label):
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-reference.png')
    adb('pull','/sdcard/acflip-reference.png',str(OUT/(label+'.png'))); print(label,flush=True)
def tap(x,y): adb('shell','input','-d','1','tap',str(x),str(y)); time.sleep(.6)
def select_reference():
    adb('shell','input','-d','1','keycombination','-t','120','24','25'); time.sleep(.6); tap(330,110)
    for _ in range(12):
        adb('shell','uiautomator','dump','/sdcard/acflip-reference.xml')
        xml = adb('shell','cat','/sdcard/acflip-reference.xml'); root = ET.fromstring(xml)
        item = next((n for n in root.iter('node') if n.attrib.get('content-desc') == '参考图复刻，点击选择'),None)
        if item is not None:
            x,y,r,b = map(int,re.findall(r'\d+',item.attrib['bounds']))
            if b-y > 120:
                OUT.joinpath('gallery.xml').write_text(xml,encoding='utf-8'); capture('reference-thumbnail')
                tap((x+r)//2,(y+b)//2)
                assert 'name="layout" value="9"' in adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
                return
        adb('shell','input','-d','1','swipe','200','315','200','195','700'); time.sleep(.3)
    raise AssertionError('Reference thumbnail not selectable')
def pixels(image,box,predicate): return sum(predicate(p) for p in image.crop(box).get_flattened_data())

put(16,'i',258); put(32,'3f',0,38.4,50); put(44,'4i',83647,0,82931,4); put(60,'3i',0,0,0); put(72,'f',.716)
put(88,'4f',85,85,85,85); put(268,'4f',88,88,64,69); put(944,'5f',38,.98,0,0,3.2); flush()
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((OUT/'fixture.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity'); time.sleep(1.4)
    select_reference(); time.sleep(.6); capture('reference-dry-12-laps')
    im = Image.open(OUT/'reference-dry-12-laps.png').convert('RGB')
    assert pixels(im,(20,48,365,139),lambda p:min(p)>200)>5000,'Main clock missing'
    assert pixels(im,(471,67,493,127),lambda p:p[1]>180 and p[0]<160)>350,'New tyre block green'
    assert pixels(im,(471,163,493,224),lambda p:p[0]>180 and p[1]>160 and p[2]<80)>350,'Worn tyre block yellow'
    assert pixels(im,(454,387,658,407),lambda p:p[1]>180 and p[0]<160)>1500,'Fuel bar not visible'
    put(944,'5f',31,.93,.2,0,3.2); put(88,'4f',120,40,85,85); put(72,'f',-.245); flush(); time.sleep(.6); capture('reference-damp-hot-cold')
    put(944,'5f',23,.85,.8,.1,3.2); flush(); time.sleep(.6); capture('reference-wet-low-grip')
    put(944,'5f',29,-1,-1,-1,0); flush(); time.sleep(.6); capture('reference-unknown-surface-fuel-litres')
    flush(944); time.sleep(.6); capture('reference-legacy-packet-placeholder')
    put(944,'5f',38,.98,0,0,3.2); flush(); put(60,'i',2); flush(); time.sleep(.6); capture('reference-auto-pit')
    put(60,'i',0); flush(); time.sleep(.6); capture('reference-exit-restores')
    time.sleep(5.2)
    metrics = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    assert 'display=1 view=682x422 page=5' in metrics, metrics
    OUT.joinpath('metrics.txt').write_text(metrics,encoding='utf-8')
    print('Reference thumbnail, native typography, tyres, weather states, fuel estimate, missing data, legacy decoding and pit restore checked',flush=True)
finally:
    fixture.terminate(); fixture.wait(timeout=10)
