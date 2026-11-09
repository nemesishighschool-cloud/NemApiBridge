# The provider UI cannot honor output choices, stop sequences or log probabilities.
import json, urllib.request, urllib.error
base='http://127.0.0.1:8090'
def post(path,data):
 request=urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,timeout=10) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
chat={'model':'DeepSeek','messages':[{'role':'user','content':'hi'}]}
response={'model':'DeepSeek','input':'hi'}
cases=[('/v1/chat/completions',{**chat,'n':2}),('/v1/chat/completions',{**chat,'stop':'END'}),('/v1/chat/completions',{**chat,'stop':['END']}),('/v1/chat/completions',{**chat,'logprobs':True}),('/v1/chat/completions',{**chat,'top_logprobs':2}),('/v1/responses',{**response,'top_logprobs':2})]
for path,body in cases:
 status,result=post(path,body);assert status==400 and result['error']['code']=='unsupported_parameter',(path,status,result)
for body in [{**chat,'n':0},{**chat,'n':'two'},{**chat,'stop':2},{**chat,'logprobs':'true'}]:
 status,result=post('/v1/chat/completions',body);assert status==400 and result['error']['code']=='invalid_request',(status,result)
print('OUTPUT_CONTRACT_GUARDS_OK',len(cases),4)
