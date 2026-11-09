"""Two-turn real Android DeepSeek incremental-context test. Overwrites clipboard."""
import json, os, urllib.request
base=os.environ.get('NEM_BASE','http://127.0.0.1:8090')
def req(path,data=None,agent=False):
    headers={'Content-Type':'application/json'}
    if agent: headers['X-NemApi-Agent']='qwen-code'
    request=urllib.request.Request(base+path,json.dumps(data).encode() if data is not None else None,headers)
    with urllib.request.urlopen(request,timeout=180) as r: return json.load(r)
try:
    req('/web/nem/agent-integrations/qwen-code',{'enabled':True,'features':{'incremental_context':True,'tool_calls':True}})
    first={'role':'user','content':'For a connection test, reply with exactly NEMAGENTFIRST and nothing else.'}
    a=req('/v1/chat/completions',{'model':'DeepSeek','messages':[first]},True)['choices'][0]['message']
    assert 'NEMAGENTFIRST' in a['content'],a
    nextmsg={'role':'user','content':'Now reply with exactly NEMAGENTSECOND and nothing else.'}
    b=req('/v1/chat/completions',{'model':'DeepSeek','messages':[first,a,nextmsg]},True)['choices'][0]['message']
    assert 'NEMAGENTSECOND' in b['content'],b
    snapshot=req('/web/nem/agent-integrations')
    print('SESSION',json.dumps(snapshot['sessions'],ensure_ascii=False))
    assert snapshot['sessions']['requests']['delta']>=1,snapshot['sessions']
    assert snapshot['sessions']['chars_saved']>0,snapshot['sessions']
    print('INCREMENTAL_TWO_TURNS_OK')
finally:
    req('/web/nem/agent-integrations/qwen-code',{'enabled':False})
