"""Stress eight distinct image attachments through the Android picker."""
import base64,json,struct,zlib,urllib.request,urllib.error

def png(rgb):
 w=h=128; raw=b''.join(b'\x00'+bytes(rgb)*w for _ in range(h))
 def c(t,d):return struct.pack('!I',len(d))+t+d+struct.pack('!I',zlib.crc32(t+d)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+c(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+c(b'IDAT',zlib.compress(raw))+c(b'IEND',b'')
parts=[{'type':'text','text':'There are eight images, alternating red and blue. For each image, list its dominant color in order, one numbered line per image.'}]
for i in range(8):
 data=base64.b64encode(png((255,0,0) if i%2==0 else (0,0,255))).decode()
 parts.append({'type':'image_url','image_url':{'url':'data:image/png;base64,'+data}})
body={'model':'DeepSeek','messages':[{'role':'user','content':parts}]}
req=urllib.request.Request('http://127.0.0.1:8090/v1/chat/completions',json.dumps(body).encode(),{'Content-Type':'application/json'})
try:
 with urllib.request.urlopen(req,timeout=240) as r:
  ans=json.load(r)['choices'][0]['message']['content'];print('ANSWER',repr(ans),flush=True)
  assert ans.lower().count('red')>=4 and ans.lower().count('blue')>=4,ans
  print('EIGHT_IMAGE_OK')
except urllib.error.HTTPError as e:
 print('EIGHT_IMAGE_ERROR',e.code,e.read().decode(),flush=True);raise
