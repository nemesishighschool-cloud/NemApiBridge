"""Responses multimodal test through DeepSeek; requires provider image selectors."""
import base64,json,struct,zlib,urllib.request,urllib.error
w=h=128; raw=b''.join(b'\x00'+b'\xff\x00\x00'*w for _ in range(h))
def chunk(t,d): return struct.pack('!I',len(d))+t+d+struct.pack('!I',zlib.crc32(t+d)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
data={'model':'DeepSeek','input':[{'role':'user','content':[{'type':'input_text','text':'What is the dominant color of this image? Respond with only the English color name.'},{'type':'input_image','image_url':'data:image/png;base64,'+base64.b64encode(png).decode()}]}]}
req=urllib.request.Request('http://127.0.0.1:8090/v1/responses',json.dumps(data).encode(),{'Content-Type':'application/json'})
try:
 with urllib.request.urlopen(req,timeout=180) as r:
  obj=json.load(r); answer=obj['output'][0]['content'][0]['text']; print('ANSWER',repr(answer)); assert 'red' in answer.lower(); print('RESPONSES_IMAGE_OK')
except urllib.error.HTTPError as e:
 print('ERROR',e.code,e.read().decode());raise
