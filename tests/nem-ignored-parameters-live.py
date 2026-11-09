# Real DeepSeek/Android test: warnings expose ignored controls without claiming enforcement.
import json, urllib.request, urllib.error
base='http://127.0.0.1:8090'
def post(path, body):
 req=urllib.request.Request(base+path,json.dumps(body).encode('utf-8'),{'Content-Type':'application/json'})
 try: return urllib.request.urlopen(req,timeout=180)
 except urllib.error.HTTPError as e:
  details=e.read().decode(errors="replace")
  raise AssertionError(f"HTTP {e.code}: {details}") from e
chat={'model':'DeepSeek','messages':[{'role':'user','content':'Reply exactly WARN_TEST_OK.'}],'max_tokens':2,'temperature':0}
with post('/v1/chat/completions',chat) as r:
 ignored=r.headers.get('X-NemApi-Ignored-Parameters','')
 assert set(ignored.replace(' ','').split(','))=={'max_tokens','temperature'},ignored
 assert 'X-NemApi-Ignored-Parameters' in r.headers.get('Access-Control-Expose-Headers','')
 data=json.load(r);assert 'WARN_TEST_OK' in data['choices'][0]['message']['content'],data
print('CHAT_IGNORED_PARAMETER_HEADER_OK')
responses={'model':'DeepSeek','input':'Reply exactly WARN_STREAM_OK.','max_output_tokens':2,'top_p':0.4,'stream':True}
with post('/v1/responses',responses) as r:
 ignored=r.headers.get('X-NemApi-Ignored-Parameters','')
 assert set(ignored.replace(' ','').split(','))=={'max_output_tokens','top_p'},ignored
 events=[]
 for line in r:
  if line.startswith(b'data: '):
   value=line[6:].strip()
   if value != b'[DONE]': events.append(json.loads(value))
 completed=[e['response'] for e in events if e.get('type')=='response.completed']
 assert len(completed)==1,events
 result=completed[0];assert result['temperature'] is None and result['top_p'] is None and result['max_output_tokens'] is None,result
 assert 'WARN_STREAM_OK' in json.dumps(result),result
print('RESPONSES_STREAM_IGNORED_HEADER_AND_UNKNOWN_SAMPLING_OK')
