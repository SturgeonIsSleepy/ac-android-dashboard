"""Native verification from selected unmodified AC telemetry values, marked as replay."""
from pathlib import Path
import os
import struct
import subprocess
import time
from PIL import Image

ADB = os.environ.get('ADB','adb')
out = Path('artifacts/record-replay-0.8.1'); out.mkdir(parents=True,exist_ok=True)
promo = Path('artifacts/promo-0.8.1')
raw = Path('artifacts/drive-0.8.1/real-drive.acftrace').read_bytes(); pos = 0; frames=[]
while pos+12 <= len(raw):
    length,stamp = struct.unpack_from('<iq',raw,pos);pos+=12
    if pos+length>len(raw):break
    frames.append(bytearray(raw[pos:pos+length]));pos+=length
def field(b,offset,fmt='i'): return struct.unpack_from('<'+fmt,b,offset)[0]
def choose(predicate): return next(b for b in frames if predicate(b))
def adb(*args): return subprocess.check_output([ADB,*args],timeout=30).decode('utf-8',errors='replace')
def feed(b):
    b=bytearray(b); struct.pack_into('<i',b,16,field(b,16)|256)
    temp=out/'next.tmp';temp.write_bytes(b)
    for _ in range(30):
        try:os.replace(temp,out/'current.bin');return
        except PermissionError:time.sleep(.01)
    raise RuntimeError('Replay packet busy')
def capture(name,destination=None,display='4630946993847360387'):
    adb('shell','screencap','-p','-d',display,'/sdcard/acflip-replay.png')
    path=out/(name+'.png');adb('pull','/sdcard/acflip-replay.png',str(path))
    if destination: (promo/destination).write_bytes(path.read_bytes())
    print(name,flush=True);return path
def metrics(name):
    text=adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt')
    (out/(name+'-metrics.txt')).write_text(text,encoding='utf-8');return text

pit=choose(lambda b:field(b,60)!=0)
healthy=choose(lambda b:field(b,60)==0 and field(b,56)==2 and all(60<=x<110 for x in struct.unpack_from('<4f',b,88)) and field(b,64)<3)
cold=choose(lambda b:field(b,56)==2 and 0<field(b,92,'f')<60 and field(b,60)==0)
near=choose(lambda b:field(b,56)==2 and field(b,296,'f')>.985 and field(b,60)==0)
finish_outlap=choose(lambda b:field(b,56)==3 and field(b,284)==0)
s1=choose(lambda b:field(b,56)==3 and field(b,284)==1)
s2=choose(lambda b:field(b,56)==3 and field(b,284)==2)
finish=choose(lambda b:field(b,56)==4 and field(b,284)==0)
next_s1=choose(lambda b:field(b,56)==4 and field(b,284)==1)
feed(pit)
fixture=subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((out/'current.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
try:
    adb('shell','am','force-stop','cn.acflip.dash');adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','layout','10')
    time.sleep(1.5);capture('pit-actual-record')
    feed(healthy);time.sleep(1.2);capture('healthy-green')
    feed(cold);time.sleep(1.2);path=capture('cold-red-problem')
    image=Image.open(path).convert('RGB')
    assert sum(r>130 and g<70 for r,g,b in image.crop((4,83,20,414)).get_flattened_data())>400,'Real cold tyre must cause red side warning'
    feed(near);time.sleep(1.2);capture('approaching-line')
    feed(finish_outlap);time.sleep(5.3);text=metrics('first-flying')
    assert 'phase=0 runLap=1 runStyle=4' in text,text
    feed(s1);time.sleep(.6);feed(s2);time.sleep(.6);feed(finish);time.sleep(1)
    path=capture('finish-retains-three','finish-retained.png')
    image=Image.open(path).convert('RGB')
    for box in ((26,338,225,391),(236,338,435,391),(446,338,646,391)):
        assert sum(min(p)>180 for p in image.crop(box).get_flattened_data())>450,'Finished sector value was cleared'
    time.sleep(5.3);text=metrics('after-hold-retained')
    assert 'shownSectors=[46816, 70453, 46135]' in text,text
    capture('after-hold-still-retained')
    feed(next_s1);time.sleep(5.3);text=metrics('next-first-sector')
    assert 'shownSectors=[77033, 0, 0]' in text,text
    feed(s2);adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','layout','0');time.sleep(1)
    capture('gear-real-record','gear-live.png')
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','layout','8');time.sleep(1)
    capture('automotive-separate-columns','automotive-live.png')
    adb('shell','input','-d','1','keycombination','-t','120','24','25');time.sleep(1)
    adb('shell','input','-d','1','tap','341','96');time.sleep(1)
    capture('actual-gallery','gallery-live.png')
    adb('shell','input','-d','1','keyevent','4');adb('shell','input','-d','1','keyevent','4')
    adb('shell','am','force-stop','cn.acflip.dash')
    adb('shell','am','start','-W','--display','0','-n','cn.acflip.dash/.MainActivity','--ei','layout','0');time.sleep(2)
    capture('main-screen-horizontal','ordinary-android.png',display='4630947227182689922')
    adb('shell','am','force-stop','cn.acflip.dash');adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','layout','10')
    print('Real-record replay: healthy/red/cold/start bar, first profile, three finish splits, next split, nonoverlapping automotive UI and Android main-screen capture complete',flush=True)
finally:
    fixture.terminate();fixture.wait(timeout=10)
