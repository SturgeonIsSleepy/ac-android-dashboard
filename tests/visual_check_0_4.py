"""Native display checks using the test-only sender at the allowed bridge path."""
from pathlib import Path
import os
import re
import struct
import subprocess
import time
from PIL import Image

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/visual-0.4')
OUT.mkdir(parents=True, exist_ok=True)
frame = bytearray(Path('artifacts/live-0.4/telemetry.bin').read_bytes())

def adb(*args):
    return subprocess.check_output([ADB,*args],timeout=20).decode('utf-8',errors='replace')

def flush():
    temp = OUT/'fixture.tmp'
    temp.write_bytes(frame)
    for _ in range(20):
        try:
            os.replace(temp,OUT/'fixture.bin')
            return
        except PermissionError:
            time.sleep(.01)
    raise RuntimeError('Fixture packet is locked')

def put(offset,fmt,*values):
    struct.pack_into('<'+fmt,frame,offset,*values)
    flush()

def name(offset,value):
    frame[offset:offset+64] = value.encode()[:63].ljust(64,b'\0')
    flush()

def capture(label):
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-qa-04.png')
    adb('pull','/sdcard/acflip-qa-04.png',str(OUT/(label+'.png')))
    print(label,flush=True)

def page(index):
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--ei','page',str(index))
    time.sleep(.8)

def style(index):
    adb('shell','input','-d','1','keycombination','-t','120','24','25')
    time.sleep(.5)
    state = adb('shell','dumpsys','activity','activities')
    assert re.search(r'mCurrentFocus=.*cn\.acflip\.dash\.SettingsActivity',state), 'Volume chord must open settings'
    if index == 0: capture('settings-volume-chord')
    adb('shell','input','-d','1','tap','110',str(103+60*index))
    adb('shell','input','-d','1','tap','341','382')
    time.sleep(.7)
    pref = adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
    assert f'name="style" value="{index}"' in pref, 'Style must persist'
    page(0)

put(16,'4i',258,5,7150,7500)
put(32,'3f',235,32.5,100)
put(44,'4i',45214,84923,84271,3)
put(60,'3i',0,0,0)
put(72,'f',-.248)
put(300,'i',1)
put(412,'if',9000,300)
name(140,'VISUAL QA')
name(204,'STYLE CHECK')
name(348,'STYLE CHECK')
fixture = subprocess.Popen([str(Path('dist/ACFlipBridge.exe').resolve()),str((OUT/'fixture.bin').resolve())],creationflags=subprocess.CREATE_NEW_CONSOLE)
recorder = None
try:
    adb('shell','am','force-stop','cn.acflip.dash')
    adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity','--es','host',os.environ['ACFLIP_HOST'],'--ei','page','0')
    time.sleep(.4)
    capture('startup-short-hint')
    time.sleep(2)
    style(0); capture('ring-large-gear-real-scale')
    style(1); capture('f1-round-approach-start')
    recorder = subprocess.Popen([ADB,'shell','screenrecord','--display-id','4630946993847360387','--size','682x422','--bit-rate','2M','--time-limit','12','/sdcard/acflip-drs-04.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    time.sleep(1)
    for distance in (250,175,100,50):
        put(416,'f',distance); time.sleep(.8)
        if distance == 175: capture('f1-round-drs-approaching')
    put(300,'i',3); put(416,'f',0); time.sleep(.8); capture('f1-round-drs-ready')
    put(300,'i',7); time.sleep(.8); capture('f1-round-drs-open-faded')
    recorder.wait(timeout=20)
    assert recorder.returncode == 0, recorder.stderr.read().decode(errors='replace')
    adb('pull','/sdcard/acflip-drs-04.mp4',str(OUT/'drs-animation.mp4'))
    style(2); capture('f1-segment-lamps')
    put(300,'i',0); put(416,'f',-1); time.sleep(.7); capture('f1-no-drs-car')
    style(3); capture('minimal-large-gear')
    for count in (3,6):
        name(140,'QA-SECTORS-'+str(count))
        put(44,'4i',1000,0,0,0); put(284,'3i',0,0,count); time.sleep(.25)
        for sector in range(1,count):
            put(44,'i',sector*30000+1000); put(284,'2i',sector,30000); time.sleep(.25)
        put(44,'4i',1000,count*30000,count*30000,1); put(284,'2i',0,30000)
        page(1); capture('timing-'+str(count)+'-sectors')
    put(300,'2i',0,3)
    for i,cue in enumerate(((1,2,150),(-1,3,240),(1,6,510))): put(308+i*12,'iif',*cue)
    page(3); capture('guide-colored-outlines')
    page(0); style(0)
    for label in ('f1-round-approach-start','f1-round-drs-approaching','f1-round-drs-ready','f1-round-drs-open-faded','f1-segment-lamps'):
        screenshot = Image.open(OUT/(label+'.png')).convert('RGB')
        for box, minimum in (((25,300,140,344),350),((525,295,660,344),500),((250,180,435,345),5000)):
            assert sum(min(pixel)>180 for pixel in screenshot.crop(box).get_flattened_data()) > minimum, label+' has missing readout'
    approach = Image.open(OUT/'f1-round-drs-approaching.png').convert('RGB')
    ready = Image.open(OUT/'f1-round-drs-ready.png').convert('RGB')
    opened = Image.open(OUT/'f1-round-drs-open-faded.png').convert('RGB')
    assert approach.getpixel((70,50))[1] > 150 and approach.getpixel((300,50))[1] < 80, 'Approach fills outer sides'
    assert ready.getpixel((300,50))[1] > 150, 'Ready fills center'
    assert 45 < opened.getpixel((300,50))[1] < 120, 'Open fades green'
    time.sleep(11)
    OUT.joinpath('metrics.txt').write_text(adb('shell','run-as','cn.acflip.dash','cat','files/metrics.txt'),encoding='utf-8')
    print('Styles, stored selection, volume chord, DRS animation, sectors and outlines captured',flush=True)
finally:
    if recorder is not None and recorder.poll() is None: recorder.terminate()
    fixture.terminate(); fixture.wait(timeout=10)
