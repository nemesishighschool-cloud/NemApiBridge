"""Verify historic images are reattached even after a manual DeepSeek new chat."""
import base64,json,struct,zlib,urllib.request,subprocess,xml.etree.ElementTree as E,re,time
w=h=128;raw=b''.join(b'\x00'+b'\xff\x00\x00'*w for _ in range(h))
def c(t,d):return struct.pack('!I',len(d))+t+d+struct.pack('!I',zlib.crc32(t+d)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+c(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+c(b'IDAT',zlib.compress(raw))+c(b'IEND',b'')
first={'role':'user','content':[{'type':'text','text':'Reply exactly READY, without naming or describing the image.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png).decode()}}]}
def send(msg):
 d={'model':'DeepSeek','messages':msg};req=urllib.request.Request('http://127.0.0.1:8090/v1/chat/completions',json.dumps(d).encode(),{'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=180) as r:return json.load(r)['choices'][0]['message']
a=send([first]);print('INITIAL',repr(a['content']),flush=True);assert 'READY' in a['content'].upper() and 'RED' not in a['content'].upper(),a
adb='/opt/android-sdk/platform-tools/adb';subprocess.run([adb,'shell','uiautomator','dump','/sdcard/history-check.xml'],check=True,stdout=subprocess.DEVNULL)
xml=subprocess.check_output([adb,'shell','cat','/sdcard/history-check.xml']);nodes=[n for n in E.fromstring(xml).iter('node') if n.get('content-desc')=='New chat'];assert len(nodes)==1
bounds=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));subprocess.run([adb,'shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2)],check=True);time.sleep(3)
b=send([first,a,{'role':'user','content':'What was the dominant color of the image? Respond with just the English color name.'}]);print('FOLLOWUP',repr(b['content']),flush=True);assert 'red' in b['content'].lower(),b
print('IMAGE_HISTORY_NEW_CHAT_OK')
