"""Live SSE timing and OpenAI format test. Requires DeepSeek in foreground.
Overwrites clipboard and spends a generation; no external tools are executed.
"""
import json, os, time, urllib.request
url=os.environ.get('NEM_API_URL','http://127.0.0.1:8090/v1/chat/completions')
model=os.environ.get('NEM_MODEL','DeepSeek')
def ask(payload):
    req=urllib.request.Request(url, json.dumps(payload).encode(), {'Content-Type':'application/json'})
    start=time.monotonic()
    with urllib.request.urlopen(req,timeout=180) as r:
        assert r.headers.get_content_type()=='text/event-stream'
        events=[]
        for line in r:
            if line.startswith(b'data: '):
                data=line[6:].decode().strip()
                events.append((time.monotonic()-start,data))
                if data=='[DONE]': break
    assert events and events[-1][1]=='[DONE]', events[-5:]
    assert events[0][0]<5, events[0]
    parsed=[json.loads(event) for _,event in events[:-1]]
    for event in parsed:
        assert 'error' not in event, event
    return events, parsed
messages=[{'role':'user','content':'Write exactly 60 numbered short lines, from LINE-001 through LINE-060, one per line. Do not use a code fence.'}]
events, chunks=ask({'model':model,'messages':messages,'stream':True})
text=''.join(c['choices'][0]['delta'].get('content') or '' for c in chunks)
assert 'LINE-001' in text and 'LINE-060' in text,text
empty=[t for (t,_), c in zip(events,chunks) if c['choices'][0]['delta'].get('content')=='' and 'role' not in c['choices'][0]['delta']]
if events[-1][0]>12: assert empty, 'Long stream sent no keep-alive'
print('STREAM_OK first=%.2fs total=%.2fs keepalives=%d chars=%d'%(events[0][0],events[-1][0],len(empty),len(text)))
tools=[{'type':'function','function':{'name':'read_file','description':'Read a file','parameters':{'type':'object','properties':{'file_path':{'type':'string'}},'required':['file_path']}}}]
events,chunks=ask({'model':model,'messages':[{'role':'user','content':'Call read_file to read src/main.py now.'}],'tools':tools,'tool_choice':'required','stream':True})
calls=[call for c in chunks for call in c['choices'][0]['delta'].get('tool_calls',[])]
assert len(calls)==1,calls
assert calls[0]['function']['name']=='read_file',calls
assert json.loads(calls[0]['function']['arguments'])['file_path']=='src/main.py',calls
assert any(c['choices'][0]['finish_reason']=='tool_calls' for c in chunks),chunks
print('STREAM_TOOL_OK first=%.2fs total=%.2fs'%(events[0][0],events[-1][0]))
