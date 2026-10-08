"""Real cover-screen profile UI and phase transitions, visibly marked synthetic telemetry."""
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
OUT = Path('artifacts/phases-0.8'); OUT.mkdir(parents=True,exist_ok=True)
frame = bytearray(Path('artifacts/live-0.8/telemetry.bin').read_bytes())
def adb(*args): return subprocess.check_output([ADB,*args],timeout=25).decode('utf-8',errors='replace')
def put(offset,fmt,*values): struct.pack_into('<'+fmt,frame,offset,*values)
def flush():
    temp = OUT/'fixture.tmp'; temp.write_bytes(frame)
    for _ in range(20):
        try: os.replace(temp,OUT/'fixture.bin'); return
        except PermissionError: time.sleep(.01)
    raise RuntimeError('Fixture locked')
def capture(label):
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-phase.png')
    adb('pull','/sdcard/acflip-phase.png',str(OUT/(label+'.png'))); print(label,flush=True)
def tree():
    adb('shell','uiautomator','dump','/sdcard/acflip-phase.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/acflip-phase.xml'))
def tap_node(node):
    x,y,r,b = map(int,re.findall(r'\d+',node.attrib['bounds'])); adb('shell','input','-d','1','tap',str((x+r)//2),str((y+b)//2)); time.sleep(.6)
def button(text):
    for _ in range(6):
        root = tree(); node = next((n for n in root.iter('node') if n.attrib.get('class') == 'android.widget.Button' and text in n.attrib.get('text','')),None)
        if node is not None:
            x,y,r,b = map(int,re.findall(r'\d+',node.attrib['bounds']))
            if b-y >= 50: tap_node(node); return
        adb('shell','input','-d','1','swipe','200','310','200','190','700'); time.sleep(.3)
    raise AssertionError(text)
def thumbnail(name):
    for _ in range(12):
        root = tree(); node = next((n for n in root.iter('node') if n.attrib.get('content-desc') == name+'，点击选择'),None)
        if node is not None:
            x,y,r,b = map(int,re.findall(r'\d+',node.attrib['bounds']))
            if b-y > 80: tap_node(node); return
        adb('shell','input','-d','1','swipe','200','310','200','190','700'); time.sleep(.3)
    raise AssertionError('No thumbnail '+name)
def prefs(): return adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
def stage(label,expected_phase,expected_lap,expected_style):
    time.sleep(5.3); metrics = adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    for value in (f'phase={expected_phase}',f'runLap={expected_lap}',f'runStyle={expected_style}'):
        assert value in metrics,(label,value,metrics)
    OUT.joinpath(label+'-metrics.txt').write_text(metrics,encoding='utf-8'); capture(label)

put(16,'4i',258,5,6800,7500); put(32,'3f',75,24.5,80); put(44,'4i',74000,83647,82931,4)
put(60,'3i',0,0,0); put(72,'f',-.348); put(88,'4f',40,42,85,115); put(268,'4f',100,83,67,51)
put(944,'5f',38,.98,0,0,2.5); frame[964:1028] = b'Street (ST)'.ljust(64,b'\0'); flush()
put(432,'64i',28571,27388,26910,*([0]*61)); put(688,'64i',28571,27388,26910,*([0]*61)); flush()
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((OUT/'fixture.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    adb('shell','am','force-stop','cn.acflip.dash'); adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--es','host',os.environ['ACFLIP_HOST'],'--ei','layout','8'); time.sleep(2.5)
    capture('generated-automotive')
    adb('shell','input','-d','1','keycombination','-t','120','24','25'); time.sleep(.7); button('阶段样式'); capture('phase-settings')
    while '<string name="lapStyles">' in prefs() and ',' in next(n.text for n in ET.fromstring(prefs()).iter('string') if n.attrib.get('name') == 'lapStyles'):
        button('删最后一圈')
    button('第1飞驰圈'); thumbnail('汽车计时仪表'); button('增加一圈'); button('第2飞驰圈'); thumbnail('参考图复刻')
    capture('per-lap-plan'); button('启用阶段切换'); assert '<string name="lapStyles">8,9</string>' in prefs(),prefs()
    stage('initial-normal',0,1,8)
    put(60,'i',2); put(44,'i',80000); flush(); stage('pit-rich-status',1,1,8)
    put(60,'i',0); put(44,'i',1000); flush(); stage('outlap-four-tyre-thermal',2,1,8)
    put(56,'i',5); put(44,'i',1000); flush(); stage('first-flying-automotive',0,1,8)
    put(56,'i',6); put(44,'i',1000); flush(); stage('second-flying-reference',0,2,9)
    put(56,'i',8); flush(); stage('later-laps-repeat-final',0,4,9)
    put(60,'i',1); put(44,'i',15000); flush(); stage('second-pit',1,4,9)
    put(60,'i',0); put(44,'i',1000); flush(); stage('second-outlap',2,1,8)
    put(56,'i',9); flush(); stage('pit-restarts-plan',0,1,8)
    image = Image.open(OUT/'outlap-four-tyre-thermal.png').convert('RGB')
    def count(box,color): return sum(color(p) for p in image.crop(box).get_flattened_data())
    assert count((8,96,78,190),lambda p:p[2]>180 and p[0]<100)>100,'Left-front cold blue'
    assert count((604,232,678,333),lambda p:p[0]>180 and p[1]<120)>100,'Right-rear hot red'
    assert count((110,140,574,268),lambda p:max(p)>180)>4000,'Generated clock must be readable'
    print('Native per-lap settings, generated clock and thermal assets, pit/outlap/full plan/restart checked',flush=True)
finally:
    fixture.terminate(); fixture.wait(timeout=10)
