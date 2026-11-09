"""Real model output converted from native agent JSON fence, no declared OpenAI tools."""
import json, os, urllib.request
base=os.getenv('NEM_BASE','http://127.0.0.1:8090')
def req(path,data=None,agent=False):
 h={'Content-Type':'application/json'}
 if agent: h['X-NemApi-Agent']='nemesis'
 with urllib.request.urlopen(urllib.request.Request(base+path, None if data is None else json.dumps(data).encode(),h),timeout=180) as r: return json.load(r)
try:
 req('/web/nem/agent-integrations/nemesis',{'enabled':True,'features':{'native_tool_calls':True}})
 messages=[{'role':'system','content':'You are NEMESIS. For tool requests, output a fenced json block containing an object with tool and parameters.'},{'role':'user','content':'Use the read_file tool on nem-native.txt. Output only the JSON tool call in a fenced json block. Set tool to read_file and parameters to an object with file_path set to nem-native.txt.'}]
 result=req('/v1/chat/completions',{'model':'DeepSeek','messages':messages},True)['choices'][0]
 assert result['finish_reason']=='tool_calls',result
 call=result['message']['tool_calls'][0]
 assert call['function']['name']=='read_file' and json.loads(call['function']['arguments'])['file_path']=='nem-native.txt',call
 print('NEMESIS_NATIVE_TOOL_OK')
finally:
 req('/web/nem/agent-integrations/nemesis',{'enabled':False})
