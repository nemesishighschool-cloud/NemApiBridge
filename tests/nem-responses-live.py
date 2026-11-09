"""Live Responses API checks against DeepSeek. Uses real generations/clipboard."""
import json, os, time, urllib.request, urllib.error
base=os.environ.get('NEM_BASE','http://127.0.0.1:8090')
def post(path, obj):
    return urllib.request.urlopen(urllib.request.Request(base+path,json.dumps(obj).encode(),{'Content-Type':'application/json'}),timeout=180)
start=time.monotonic()
with post('/v1/responses',{'model':'deepseek-chat','input':'Reply with NEMRESPONSEJSON and nothing else.'}) as r: obj=json.load(r)
assert obj['status']=='completed' and obj['object']=='response',obj
assert 'NEMRESPONSEJSON' in obj['output'][0]['content'][0]['text'],obj
print('RESPONSES_JSON_OK %.1fs'%(time.monotonic()-start))
start=time.monotonic()
with post('/responses',{'model':'DeepSeek','input':'Reply with NEMRESPONSESTREAM and nothing else.','stream':True}) as r:
    names=[];events=[]; first=None
    for line in r:
        if line.startswith(b'event: '): names.append(line[7:].decode().strip())
        if line.startswith(b'data: ') and b'[DONE]' not in line:
            if first is None: first=time.monotonic()-start
            events.append(json.loads(line[6:]))
assert first<5,first
assert names[0]=='response.created' and 'response.output_text.delta' in names and names[-1]=='response.completed',names
final=next(e for e in events if e['type']=='response.completed')['response']
assert 'NEMRESPONSESTREAM' in final['output'][0]['content'][0]['text'], final
print('RESPONSES_SSE_OK first=%.2fs total=%.1fs'%(first,time.monotonic()-start))
tools=[{'type':'function','name':'read_file','description':'Read a file','parameters':{'type':'object','properties':{'file_path':{'type':'string'}},'required':['file_path']}}]
start=time.monotonic()
with post('/v1/responses',{'model':'DeepSeek','input':'Use read_file to read src/a.py now.','tools':tools,'tool_choice':'required'}) as r: obj=json.load(r)
call=next(x for x in obj['output'] if x['type']=='function_call')
assert call['name']=='read_file' and json.loads(call['arguments'])['file_path']=='src/a.py',call
print('RESPONSES_TOOL_OK %.1fs'%(time.monotonic()-start))
try: post('/responses',{'model':'DeepSeek','input':'hello','previous_response_id':'resp_unknown'})
except urllib.error.HTTPError as e:
    assert e.code==400 and json.loads(e.read())['error']['code']=='unsupported_parameter'
else: raise AssertionError('previous_response_id silently ignored')
print('RESPONSES_UNSUPPORTED_STATE_EXPLICIT_OK')
