"""Real image attachment and recognition through Android. Clipboard is overwritten."""
import base64,json,struct,zlib,urllib.request,urllib.error
w=h=128
raw=b''.join(b'\x00'+b'\xff\x00\x00'*w for _ in range(h))
def chunk(tag,data): return struct.pack('!I',len(data))+tag+data+struct.pack('!I',zlib.crc32(tag+data)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
data={'model':'DeepSeek','messages':[{'role':'user','content':[{'type':'text','text':'What is the dominant color of this image? Respond with only the English color name.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png).decode()}}]}]}
req=urllib.request.Request('http://127.0.0.1:8090/v1/chat/completions',json.dumps(data).encode(),{'Content-Type':'application/json'})
try:
 with urllib.request.urlopen(req,timeout=180) as r:
  obj=json.load(r); answer=obj['choices'][0]['message']['content'];print('ANSWER',repr(answer),flush=True);assert 'red' in answer.lower(),answer;print('IMAGE_END_TO_END_OK')
except urllib.error.HTTPError as e:
 print('IMAGE_API_ERROR',e.code,e.read().decode(),flush=True);raise
