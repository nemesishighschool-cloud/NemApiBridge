"""Close an SSE client during a real generation, then probe safe subsequent behavior."""
import json,time,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def request(data):
 return urllib.request.Request(base+'/v1/chat/completions',json.dumps(data).encode(),{'Content-Type':'application/json'})
long={'model':'DeepSeek','stream':True,'messages':[{'role':'user','content':'Print 150 numbered lines. Each line should contain its number followed by disconnect safety validation. Do not omit or summarize any line.'}]}
r=urllib.request.urlopen(request(long),timeout=180); first=r.readline(); assert first.startswith(b'data:'); r.close(); print('SSE_CLOSED_AFTER_FIRST_EVENT',flush=True)
time.sleep(22)
try:
 data={'model':'DeepSeek','messages':[{'role':'user','content':'NEMAFTERDISCONNECT'}]}
 try:
  with urllib.request.urlopen(request(data),timeout=40) as response:
   value=json.load(response); print('LATER_REQUEST_COMPLETED',value['choices'][0]['finish_reason'],flush=True)
 except urllib.error.HTTPError as e:
  error=json.loads(e.read())['error']['code']; print('LATER_REQUEST_REJECTED',error,flush=True)
  assert error in ('user_intervention_required','queue_timeout','queue_full'),error
finally:
 try:
  r=urllib.request.urlopen(urllib.request.Request(base+'/web/nem/stop',b'{}',{'Content-Type':'application/json'}),timeout=5); print('STOP',r.read().decode(),flush=True)
 except Exception as e: print('STOP_NOT_REQUIRED_OR_FAILED',str(e),flush=True)
print('DISCONNECT_PROBE_COMPLETED')
