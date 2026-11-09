"""DeepSeek Android 13 image-path test. Requires Pillow with WebP support.
Starts a fresh DeepSeek chat before each image. Clipboard is overwritten.
"""
import base64,io,json,re,subprocess,time,urllib.error,urllib.request,xml.etree.ElementTree as ET
from PIL import Image,features
assert features.check('webp')
adb='/opt/android-sdk/platform-tools/adb'
def new_chat():
 subprocess.run([adb,'shell','uiautomator','dump','/sdcard/image-format-test.xml'],check=True,timeout=30,stdout=subprocess.DEVNULL)
 view=subprocess.check_output([adb,'shell','cat','/sdcard/image-format-test.xml'],timeout=10)
 nodes=[n for n in ET.fromstring(view).iter('node') if n.get('content-desc')=='New chat']
 assert len(nodes)==1,'New chat control not uniquely visible'
 b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))))
 subprocess.run([adb,'shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)],check=True,timeout=10)
 time.sleep(3)
failures=[]
for fmt,mime,color,expected in [('JPEG','image/jpeg',(0,0,255),'blue'),('WEBP','image/webp',(0,255,0),'green')]:
 try:
  new_chat()
  image=Image.new('RGB',(128,128),color);out=io.BytesIO();image.save(out,fmt,quality=95)
  payload={'model':'DeepSeek','messages':[{'role':'user','content':[{'type':'text','text':'What is the dominant color of this image? Respond with only the English color name.'},{'type':'image_url','image_url':{'url':'data:'+mime+';base64,'+base64.b64encode(out.getvalue()).decode()}}]}]}
  req=urllib.request.Request('http://127.0.0.1:8090/v1/chat/completions',json.dumps(payload).encode(),{'Content-Type':'application/json'})
  try:
   with urllib.request.urlopen(req,timeout=200) as r: reply=json.load(r)['choices'][0]['message']['content']
  except urllib.error.HTTPError as e:
   raise AssertionError(f'HTTP {e.code}: {e.read().decode()}') from e
  print(fmt,'ANSWER',repr(reply),flush=True)
  assert expected in reply.lower(),(fmt,reply)
  print('IMAGE_'+fmt+'_END_TO_END_OK',flush=True)
 except Exception as e:
  failures.append((fmt,str(e)));print('IMAGE_'+fmt+'_FAILED',repr(e),flush=True)
if failures:raise SystemExit('Image tests failed: '+repr(failures))
