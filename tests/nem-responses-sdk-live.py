"""Real DeepSeek end-to-end using the official OpenAI Python SDK."""
from openai import OpenAI
import json, urllib.request
base='http://127.0.0.1:8090'
def admin(data):
 req=urllib.request.Request(base+'/web/nem/agent-integrations/monkey-code',json.dumps(data).encode(),{'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=10) as f:return json.load(f)
client=OpenAI(base_url=base+'/v1',api_key='not-required',timeout=200,max_retries=0,default_headers={'X-NemApi-Agent':'monkey-code'})
try:
 admin({'enabled':True,'features':{'incremental_context':True,'tool_calls':True}})
 response=client.responses.create(model='deepseek-chat',input='Reply exactly with SDK_JSON_OK.')
 assert 'SDK_JSON_OK' in response.output_text,response
 print('SDK_RESPONSES_JSON_OK',flush=True)
 stream=client.responses.create(model='deepseek-chat',input='Reply exactly with SDK_STREAM_OK.',stream=True)
 events=list(stream);types=[e.type for e in events];done=[e for e in events if e.type=='response.completed']
 text=''.join(e.delta for e in events if e.type=='response.output_text.delta')
 assert 'SDK_STREAM_OK' in text and len(done)==1,(types,text)
 assert [e.sequence_number for e in events]==list(range(len(events))),types
 assert done[0].response.usage.total_tokens>0
 print('SDK_RESPONSES_STREAM_OK',flush=True)
 tool={'type':'function','name':'read_file','description':'read file','parameters':{'type':'object','properties':{'path':{'type':'string'}},'required':['path'],'additionalProperties':False},'strict':False}
 events=list(client.responses.create(model='deepseek-chat',input='Call read_file for sdk-check.txt.',tools=[tool],tool_choice={'type':'function','name':'read_file'},stream=True))
 calls=[e.item for e in events if e.type=='response.output_item.done' and e.item.type=='function_call']
 assert len(calls)==1 and calls[0].name=='read_file' and json.loads(calls[0].arguments)['path']=='sdk-check.txt',events
 assert [e.sequence_number for e in events]==list(range(len(events)))
 print('SDK_RESPONSES_TOOL_STREAM_OK',flush=True)
finally:
 client.close();admin({'enabled':False})
