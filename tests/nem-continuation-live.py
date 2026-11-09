"""Ask DeepSeek for an intentionally truncated tool call; require safe automatic continuation."""
import json,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def req(path,data=None,agent=False):
 h={'Content-Type':'application/json'}
 if agent:h['X-NemApi-Agent']='qwen-code'
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,None if data is None else json.dumps(data).encode(),h),timeout=240) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
tool={'type':'function','function':{'name':'write_file','description':'write a text file','parameters':{'type':'object','properties':{'path':{'type':'string'},'content':{'type':'string'}},'required':['path','content']}}}
try:
 req('/web/nem/agent-integrations/qwen-code',{'enabled':True,'features':{'continuation':True,'tool_calls':True,'side_queries_local':True}})
 before=req('/web/nem/agent-integrations')[1]['sessions']['requests'].get('continuations',0)
 text='This is a test of interrupted output. Output ONLY this exact incomplete text, stopping at the final letter, without adding closing quotation marks or braces or tags: <tool>{"name":"write_file","arguments":{"path":"nem-cont.txt","content":"NEM_CONTINUATION_UNIQUE_1234567890_ABCDEFGHIJKLMNOPQRSTUVWXYZ_START'
 status,result=req('/v1/chat/completions',{'model':'DeepSeek','messages':[{'role':'user','content':text}], 'tools':[tool], 'tool_choice':'auto'},True)
 after=req('/web/nem/agent-integrations')[1]['sessions']['requests'].get('continuations',0)
 print('CONTINUATION_ATTEMPTS',after-before,'HTTP',status,'RESPONSE',json.dumps(result,ensure_ascii=False),flush=True)
 assert after>before, 'Model did not produce an incomplete tool call; test inconclusive.'
 assert status==200 and result['choices'][0]['finish_reason']=='tool_calls',result
 args=json.loads(result['choices'][0]['message']['tool_calls'][0]['function']['arguments'])
 assert args['path']=='nem-cont.txt' and 'NEM_CONTINUATION_UNIQUE' in args['content'],args
 print('CONTINUATION_LIVE_OK')
finally:req('/web/nem/agent-integrations/qwen-code',{'enabled':False})
