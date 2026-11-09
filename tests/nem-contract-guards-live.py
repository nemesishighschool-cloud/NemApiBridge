import json,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def post(path,data):
 req=urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=15) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
flat={'type':'function','name':'f','parameters':{'type':'object','properties':{}},'strict':True}
nested={'type':'function','function':{'name':'f','parameters':{'type':'object'},'strict':True}}
cases=[('/v1/responses',{'input':'hi','model':'DeepSeek','store':True}),('/v1/responses',{'input':'hi','model':'DeepSeek','parallel_tool_calls':False}),('/v1/responses',{'input':'hi','model':'DeepSeek','text':{'format':{'type':'json_schema','name':'test','schema':{'type':'object'}}}}),('/v1/responses',{'input':'hi','model':'DeepSeek','tools':[flat]}),('/v1/chat/completions',{'model':'DeepSeek','messages':[{'role':'user','content':'hi'}],'tools':[nested]}),('/v1/chat/completions',{'model':'DeepSeek','messages':[{'role':'user','content':'hi'}],'response_format':{'type':'json_object'}})]
for path,body in cases:
 status,result=post(path,body);assert status==400 and result['error']['code']=='unsupported_parameter',(path,status,result)
print('UNSUPPORTED_CONTRACT_GUARDS_OK',len(cases))
