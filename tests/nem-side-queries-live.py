"""Local agent side queries while DeepSeek is NOT the foreground app."""
import json,subprocess,urllib.request,urllib.error,time
base='http://127.0.0.1:8090'
def post(path,data,agent=False):
 h={'Content-Type':'application/json'}
 if agent:h['X-NemApi-Agent']='qwen-code'
 r=urllib.request.Request(base+path,json.dumps(data).encode(),h)
 try:
  with urllib.request.urlopen(r,timeout=20) as f:
   raw=f.read().decode();return f.status,(raw if data.get('stream') else json.loads(raw))
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def chat(messages,**kwargs):return {'model':'DeepSeek','messages':messages,**kwargs}
def tools(schema,name='respond_in_schema'):return [{'type':'function','function':{'name':name,'parameters':schema}}]
msg=[{'role':'user','content':'[SUGGESTION MODE: provide a suggestion]'}]
schema={'type':'object','properties':{'selected_memories':{'type':'array'}},'required':['selected_memories']}
safety={'type':'object','properties':{'shouldBlock':{'type':'boolean'}},'required':['shouldBlock']}
try:
 post('/web/nem/agent-integrations/qwen-code',{'enabled':True,'features':{'side_queries_local':True}})
 adb='/opt/android-sdk/platform-tools/adb';subprocess.run([adb,'shell','am','start','-n','com.ialocalbridge/.MainActivity'],check=True,stdout=subprocess.DEVNULL);time.sleep(3)
 status,body=post('/v1/chat/completions',chat(msg),True);assert status==200 and body['choices'][0]['message']['content']=='',body
 status,body=post('/v1/chat/completions',chat(msg,stream=True),True);assert status==200 and '[DONE]' in body,body
 status,body=post('/v1/chat/completions',chat([{'role':'user','content':'choose'}],tools=tools(schema),tool_choice='required'),True)
 assert status==200 and json.loads(body['choices'][0]['message']['tool_calls'][0]['function']['arguments'])=={'selected_memories':[]},body
 status,body=post('/v1/chat/completions',chat([{'role':'user','content':'safety'}],tools=tools(safety),tool_choice='required'),True)
 assert status==400 and body['error']['code']=='side_query_not_answered',body
 status,body=post('/v1/chat/completions',chat([{'role':'user','content':'read file'}],tools=tools(schema,'read_file'),tool_choice='required'),True)
 assert status==409 and body['error']['code']=='wrong_app',body
 general={'type':'object','properties':{'answer':{'type':'string'}},'required':['answer']}
 status,body=post('/v1/chat/completions',chat([{'role':'user','content':'answer a genuine task'}],tools=tools(general),tool_choice='required'),True)
 assert status==409 and body['error']['code']=='wrong_app',body
 status,body=post('/v1/chat/completions',chat(msg,tools=tools(schema,'read_file'),tool_choice='required'),True)
 assert status==409 and body['error']['code']=='wrong_app',body
 print('SIDE_LOCAL_STREAM_SCHEMA_SAFETY_OK')
finally:
 post('/web/nem/agent-integrations/qwen-code',{'enabled':False})
 subprocess.run(['/opt/android-sdk/platform-tools/adb','shell','am','start','-n','com.deepseek.chat/.MainActivity'],stdout=subprocess.DEVNULL)
