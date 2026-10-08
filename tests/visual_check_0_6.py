"""Actual native gallery, fullscreen layouts and fault-lamp checks on display 1."""
from pathlib import Path
import os
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/visual-0.6'); OUT.mkdir(parents=True,exist_ok=True)
frame = bytearray(Path('artifacts/live-0.6/telemetry.bin').read_bytes())
names = ['环形转速','F1 圆形灯','F1 分段灯','极简挡位','F1 计时','车况','弯道指引','飞驰圈','汽车计时仪表']
def adb(*args): return subprocess.check_output([ADB,*args],timeout=25).decode('utf-8',errors='replace')
def put(offset,fmt,*values): struct.pack_into('<'+fmt,frame,offset,*values)
def flush():
    temp = OUT/'fixture.tmp'; temp.write_bytes(frame)
    for _ in range(20):
        try: os.replace(temp,OUT/'fixture.bin'); return
        except PermissionError: time.sleep(.01)
    raise RuntimeError('Packet locked')
def tap(x,y): adb('shell','input','-d','1','tap',str(x),str(y)); time.sleep(.35)
def capture(label):
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-qa06.png')
    adb('pull','/sdcard/acflip-qa06.png',str(OUT/(label+'.png'))); print(label,flush=True)
def xml():
    adb('shell','uiautomator','dump','/sdcard/acflip-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/acflip-ui.xml'))
def select(index):
    adb('shell','input','-d','1','keycombination','-t','120','24','25'); time.sleep(.6); tap(330,110)
    if index == 0: capture('gallery-thumbnails-top')
    for direction in ('up','down'):
        for _ in range(12):
            nodes = xml().iter('node')
            match = next((n for n in nodes if n.attrib.get('content-desc') == names[index]+'，点击选择'),None)
            if match is not None:
                a,b,c,d = map(int,re.findall(r'\d+',match.attrib['bounds']))
                if d-b > 60:
                    if index == 8: capture('gallery-automotive-selected-tile')
                    tap((a+c)//2,(b+d)//2)
                    pref = adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
                    assert f'name="layout" value="{index}"' in pref, pref
                    assert 'cn.acflip.dash.SettingsActivity type=1' not in adb('shell','dumpsys','activity','activities').split('mCurrentFocus=')[-1]
                    time.sleep(.5); return
            if direction == 'up': adb('shell','input','-d','1','swipe','200','315','200','195','700')
            else: adb('shell','input','-d','1','swipe','200','195','200','315','700')
            time.sleep(.35)
    raise AssertionError('Gallery tile not found: '+names[index])

put(16,'4i',258,5,6800,7500); put(32,'3f',214,24.5,80); put(44,'4i',84214,85162,85162,4)
put(60,'3i',0,0,0); put(72,'f',-.348); put(88,'4f',85,85,85,85); put(120,'5f',0,0,0,0,0)
put(268,'4f',100,84,68,52); put(284,'3i',2,27388,3); put(300,'2i',3,3)
for i,cue in enumerate(((-1,3,150),(1,2,340),(1,6,630))): put(308+i*12,'iif',*cue)
put(412,'if',9000,0); put(420,'ifi',1,80,1)
put(432,'64i',28571,27388,26910,*([0]*61)); put(688,'64i',28571,27388,26910,*([0]*61))
frame[140:204] = b'QA06'.ljust(64,b'\0'); frame[348:412] = b'VISUAL CHECK'.ljust(64,b'\0'); flush()
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((OUT/'fixture.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    adb('shell','am','force-stop','cn.acflip.dash')
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--es','host',os.environ['ACFLIP_HOST'],'--ei','layout','0'); time.sleep(2.5)
    for i in range(len(names)):
        select(i); capture('layout-'+str(i))
        tap(85,395)
        pref = adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
        assert f'name="layout" value="{i}"' in pref, 'Former bottom tabs must not switch layout'
    put(36,'f',1); put(64,'2i',3,2); put(88,'4f',120,85,85,85); put(120,'f',20); put(268,'4f',100,10,100,100); flush(); time.sleep(.5)
    capture('automotive-six-fault-lamps')
    boxes = ((12,65,77,126),(12,166,77,231),(12,274,77,336),(604,65,668,127),(604,167,668,234),(604,274,668,337))
    active = Image.open(OUT/'automotive-six-fault-lamps.png').convert('RGB')
    for box in boxes: assert sum(p[0]>160 and p[1]<190 and p[2]<100 for p in active.crop(box).get_flattened_data()) > 70, box
    put(36,'f',24.5); put(64,'2i',0,0); put(88,'4f',85,85,85,85); put(120,'f',0); put(268,'4f',100,100,100,100); flush(); time.sleep(.5)
    capture('automotive-fault-lamps-cleared')
    clear = Image.open(OUT/'automotive-fault-lamps-cleared.png').convert('RGB')
    for box in boxes: assert sum(max(p)>120 for p in clear.crop(box).get_flattened_data()) < 10, box
    put(60,'i',2); put(32,'f',85); flush(); time.sleep(.6); capture('pit-auto-fullscreen')
    put(60,'i',0); flush(); time.sleep(.6); capture('pit-exit-restores-automotive')
    select(4)
    frame[140:204] = b'QA06-six'.ljust(64,b'\0'); put(284,'3i',0,0,6); put(44,'4i',1000,0,0,0)
    put(432,'64i',*([30000]*6),*([0]*58)); put(688,'64i',*([30000]*6),*([0]*58)); flush(); time.sleep(.3)
    for sector in range(1,6): put(44,'i',sector*30000+1000); put(284,'2i',sector,30000); flush(); time.sleep(.25)
    put(44,'4i',1000,180000,180000,1); put(284,'2i',0,30000); flush(); time.sleep(.3); capture('timing-six-sectors-fullscreen')
    select(8); time.sleep(5.2)
    metrics = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    assert 'display=1 view=682x422 page=4' in metrics and 'ideal=180000' in metrics, metrics
    OUT.joinpath('metrics.txt').write_text(metrics,encoding='utf-8')
    print('Nine actual gallery selections, persistence, bottom-hit removal, six fault lamps, pit restore and variable sectors passed',flush=True)
finally:
    fixture.terminate(); fixture.wait(timeout=10)
