# Test-only UNIX HTTP relay. The isolated test can reach only the model API.
import http.client,http.server,socketserver,os,urllib.parse,json,sys
socket_path=sys.argv[1]
try:os.unlink(socket_path)
except FileNotFoundError:pass
class H(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.0'
 def do_GET(self): self.forward()
 def do_POST(self): self.forward()
 def forward(self):
  path=urllib.parse.urlsplit(self.path).path
  if (self.command,path) not in {('GET','/status'),('GET','/v1/models'),('GET','/health'),('POST','/v1/chat/completions')}:
   self.send_error(403);return
  try:
   n=int(self.headers.get('Content-Length','0'))
   if n<0 or n>2_000_000: self.send_error(413);return
   body=self.rfile.read(n)
   if self.command=='POST' and json.loads(body).get('stream',False): self.send_error(403);return
   conn=http.client.HTTPConnection('127.0.0.1',8090,timeout=240)
   headers={'Content-Type':'application/json; charset=UTF-8','Connection':'close'}
   if self.headers.get('X-NemApi-Agent'): headers['X-NemApi-Agent']=self.headers['X-NemApi-Agent']
   conn.request(self.command,self.path,body=body,headers=headers)
   res=conn.getresponse(); data=res.read(10_000_001)
   if len(data)>10_000_000: self.send_error(502);return
   self.send_response(res.status);self.send_header('Content-Type',res.getheader('Content-Type','application/json'));self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);conn.close()
  except Exception as e:
   self.send_error(502,str(e))
 def log_message(self,fmt,*args):print(fmt % args,flush=True)
class S(socketserver.ThreadingMixIn,socketserver.UnixStreamServer):daemon_threads=True
with S(socket_path,H) as server:
 os.chmod(socket_path,0o600);print('RELAY_READY',flush=True);server.serve_forever()
