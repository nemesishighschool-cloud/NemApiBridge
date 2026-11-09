"""Live two-image request through Android; overwrites the system clipboard."""
import base64,json,struct,zlib,urllib.request,urllib.error

def png(rgb):
 w=h=128;raw=b''.join(b'\x00'+bytes(rgb)*w for _ in range(h))
 def chunk(t,d):return struct.pack('!I',len(d))+t+d+struct.pack('!I',zlib.crc32(t+d)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
def image(rgb):return {'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png(rgb)).decode()}}
body={'model':'DeepSeek','messages':[{'role':'user','content':[{'type':'text','text':'There are two images. Name the dominant colors in order. Use the English words for the colors.'},image((255,0,0)),image((0,0,255))]}]}
req=urllib.request.Request('http://127.0.0.1:8090/v1/chat/completions',json.dumps(body).encode(),{'Content-Type':'application/json'})
try:
 with urllib.request.urlopen(req,timeout=180) as r:
  ans=json.load(r)['choices'][0]['message']['content'];print('ANSWER',repr(ans),flush=True);assert 'red' in ans.lower() and 'blue' in ans.lower(),ans; print('MULTI_IMAGE_OK')
except urllib.error.HTTPError as e:
 print('ERROR',e.code,e.read().decode(),flush=True);raise
