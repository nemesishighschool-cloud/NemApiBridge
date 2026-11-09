"""Exercise two waiting slots and queue timeout against a real long generation."""
import concurrent.futures, json, time, urllib.request, urllib.error
base='http://127.0.0.1:8090/v1/chat/completions'
def ask(text,stream=False):
 body={'model':'DeepSeek','messages':[{'role':'user','content':text}],'stream':stream}
 req=urllib.request.Request(base,json.dumps(body).encode(),{'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=180) as r:
   if stream:
    first=r.readline(); assert first.startswith(b'data:'),first
    print('FIRST_STREAM_EVENT_OK',flush=True)
    return ('stream',r.read().decode())
   return ('ok',json.load(r))
 except urllib.error.HTTPError as e:
  return ('error',json.loads(e.read()))
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 one=pool.submit(ask,'Print exactly 100 numbered lines. Each line must contain its number followed by the words concurrency validation. Do not summarize or omit lines.',True)
 time.sleep(3)
 two=pool.submit(ask,'Reply NEMQUEUETWO only.')
 time.sleep(1)
 three=pool.submit(ask,'Reply NEMQUEUETHREE only.')
 c=three.result(timeout=30)
 print('THIRD',c,flush=True)
 assert c[0]=='error' and c[1]['error']['code']=='queue_full',c
 b=two.result(timeout=30)
 print('SECOND',b,flush=True)
 assert b[0]=='error' and b[1]['error']['code']=='queue_timeout',b
 a=one.result(timeout=180)
 assert '[DONE]' in a[1] and '100' in a[1],a[1][-1000:]
 print('CONCURRENCY_QUEUE_OK')
