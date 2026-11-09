"""Stop an active DeepSeek generation and prove an already-queued request is not sent.
Changes the current chat and overwrites the clipboard. Requires Android ADB forward.
The target-stop assertion currently requires an experimental DeepSeek profile with
allow_stop_gesture enabled and is known to fail on the test emulator.
"""
import concurrent.futures,json,time,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def req(path,body=None,timeout=120):
 request=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),{'Content-Type':'application/json'})
 return urllib.request.urlopen(request,timeout=timeout)
def status():
 with req('/status',timeout=5) as r:return json.load(r)
def queued():
 body={'model':'DeepSeek','messages':[{'role':'user','content':'Reply with NEM_CANCELLED_QUEUE_MUST_NOT_RUN'}]}
 try:
  with req('/v1/chat/completions',body) as r:return ('ok',json.load(r))
 except urllib.error.HTTPError as e:return ('error',e.code,json.load(e))
def extra():
 body={'model':'DeepSeek','messages':[{'role':'user','content':'Reply with NEM_CANCEL_THIRD_MUST_NOT_RUN'}]}
 try:
  with req('/v1/chat/completions',body,30) as r:return ('ok',json.load(r))
 except urllib.error.HTTPError as e:return ('error',e.code,json.load(e))
def first():
 body={'model':'DeepSeek','stream':True,'messages':[{'role':'user','content':'Write exactly 150 numbered lines, each line with its number and the text queue cancellation test. No abbreviations or omissions.'}]}
 with req('/v1/chat/completions',body,180) as r:
  initial=r.readline(); assert initial.startswith(b'data:'),initial
  return (initial+r.read()).decode()
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 one=pool.submit(first)
 deadline=time.monotonic()+30
 while not status()['queue_busy']:
  if time.monotonic()>deadline:raise AssertionError('first request did not start')
  time.sleep(.25)
 time.sleep(10)
 two=pool.submit(queued)
 time.sleep(2)
 third=extra()
 assert third[0]=='error' and third[2]['error']['code']=='queue_full',third
 print('QUEUED_REQUEST_CONFIRMED',flush=True)
 with req('/web/nem/stop',{},timeout=45) as r: stopped=json.load(r)
 assert stopped['stopped'] is True,stopped
 print('STOP_REQUEST_ACCEPTED',stopped,flush=True)
 answer=two.result(timeout=35)
 assert answer[0]=='error' and answer[2]['error']['code']=='cancelled',answer
 print('QUEUED_REQUEST_CANCELLED',flush=True)
 output=one.result(timeout=45)
 assert '"code":"cancelled"' in output and '[DONE]' in output,output[-1500:]
 print('ACTIVE_STREAM_CANCELLED',flush=True)
print('BRIDGE_CANCEL_QUEUE_OK')
assert stopped['target_stop_clicked'] is True,stopped
assert stopped['target_generation_inactive'] is True,stopped
print('TARGET_INACTIVE_OBSERVED_OK')
