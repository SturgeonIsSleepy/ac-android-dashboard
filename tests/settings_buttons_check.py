"""Check actual native button glyph bounds on the X Flip external display."""
from pathlib import Path
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

import os

ADB = os.environ.get('ADB','adb')
OUT = Path('artifacts/buttons-0.6.1'); OUT.mkdir(parents=True,exist_ok=True)
report = []
def adb(*args): return subprocess.check_output([ADB,*args],timeout=25).decode('utf-8',errors='replace')
def bounds(node): return tuple(map(int,re.findall(r'\d+',node.attrib['bounds'])))
def check_page(label):
    adb('shell','uiautomator','dump','/sdcard/acflip-buttons.xml')
    source = adb('shell','cat','/sdcard/acflip-buttons.xml')
    OUT.joinpath(label+'.xml').write_text(source,encoding='utf-8')
    root = ET.fromstring(source)
    adb('shell','screencap','-p','-d','4630946993847360387','/sdcard/acflip-buttons.png')
    adb('pull','/sdcard/acflip-buttons.png',str(OUT/(label+'.png')))
    image = Image.open(OUT/(label+'.png')).convert('RGB')
    found = {}
    for node in root.iter('node'):
        if node.attrib.get('class') != 'android.widget.Button': continue
        name = node.attrib['text']; x,y,r,b = bounds(node)
        assert 0 <= x < r <= 682 and 0 <= y < b <= 422, (name,(x,y,r,b))
        pixels = [(a,c) for c in range(b-y) for a in range(r-x) if max(image.getpixel((x+a,y+c))) > 180]
        assert pixels, 'Missing button text: '+name
        left,right = min(a for a,c in pixels),max(a for a,c in pixels)
        top,bottom = min(c for a,c in pixels),max(c for a,c in pixels)
        assert bottom-top+1 >= 20, (name,'Glyph height',bottom-top+1)
        assert min(left,top,r-x-1-right,b-y-1-bottom) >= 5, (name,'Text touches button edge')
        report.append({'page':label,'button':name,'bounds':[x,y,r,b],'glyph_height':bottom-top+1,'margins':[left,top,r-x-1-right,b-y-1-bottom]})
        found[name] = node
    assert found, 'No buttons on '+label
    print(label+': '+', '.join(found),flush=True)
    return found
def click(buttons,name):
    x,y,r,b = bounds(buttons[name]); adb('shell','input','-d','1','tap',str((x+r)//2),str((y+b)//2)); time.sleep(.6)

before = adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
adb('shell','am','start','-W','--display','1','-n','cn.acflip.dash/.MainActivity'); time.sleep(2.5)
adb('shell','input','-d','1','keycombination','-t','120','24','25'); time.sleep(.6)
home = check_page('home'); assert '返回仪表' in home
click(home,'换挡转速'); rpm = check_page('rpm'); assert '恢复默认' in rpm
click(rpm,'返回设置'); home = check_page('home-after-rpm')
click(home,'全部样式'); gallery = check_page('gallery'); click(gallery,'返回设置')
home = check_page('home-after-gallery'); click(home,'电脑连接')
connection = check_page('connection'); click(connection,'电脑 IP')
dialog = check_page('connection-dialog'); click(dialog,'取消')
connection = check_page('connection-after-dialog'); click(connection,'返回设置')
home = check_page('home-final'); click(home,'返回仪表')
after = adb('shell','run-as','cn.acflip.dash','cat','shared_prefs/MainActivity.xml')
for attribute in ('layout','shift:ks_maserati_alfieri','host'):
    pattern = r'<[^>]*name="'+re.escape(attribute)+r'"[^>]*>'
    assert re.findall(pattern,before) == re.findall(pattern,after), 'User preference changed: '+attribute
OUT.joinpath('button-bounds.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('Native button text is complete on home, RPM, gallery, connection and dialog; preferences preserved',flush=True)
