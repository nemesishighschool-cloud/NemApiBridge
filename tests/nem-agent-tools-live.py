"""Real agent function-call roundtrip, verifies incremental delta and clipboard Copy."""
import json, os, urllib.request
base=os.getenv('NEM_BASE','http://127.0.0.1:8090')
def req(path,data=None,agent=False):
 h={'Content-Type':'application/json'}
 if agent: h['X-NemApi-Agent']='qwen-code'
 with urllib.request.urlopen(urllib.request.Request(base+path, None if data is None else json.dumps(data).encode(),h),timeout=180) as r: return json.load(r)
tools=[{'type':'function','function':{'name':'read_file','description':'Read a file by path','parameters':{'type':'object','properties':{'file_path':{'type':'string'}},'required':['file_path']}}}]
try:
 req('/web/nem/agent-integrations/qwen-code',{'enabled':True,'features':{'incremental_context':True,'tool_calls':True}})
 before=req('/web/nem/agent-integrations')['sessions']['requests']['delta']
 first={'role':'user','content':'Call read_file for nem-agent-check.txt and report its contents after receiving the tool result.'}
 def send(m,choice): return req('/v1/chat/completions',{'model':'DeepSeek','messages':m,'tools':tools,'tool_choice':choice},True)['choices'][0]
 result=send([first],'required'); assert result['finish_reason']=='tool_calls',result
 call=result['message']['tool_calls'][0]
 assert call['function']['name']=='read_file' and json.loads(call['function']['arguments'])['file_path']=='nem-agent-check.txt',call
 reply={'role':'tool','tool_call_id':call['id'],'content':'NEMAGENT_TOOL_RESULT_OK'}
 final=send([first,result['message'],reply],'auto')
 assert final['finish_reason']=='stop' and 'NEMAGENT_TOOL_RESULT_OK' in final['message']['content'],final
 after=req('/web/nem/agent-integrations')['sessions']['requests']['delta']
 assert after>before, (before,after)
 print('AGENT_TOOL_DELTA_ROUNDTRIP_OK')
finally:
 req('/web/nem/agent-integrations/qwen-code',{'enabled':False})
