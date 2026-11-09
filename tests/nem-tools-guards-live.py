"""HTTP-level validation: malformed tools must fail before opening the provider UI."""
import json,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def post(path,data):
 req=urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=10) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
chat={'model':'DeepSeek','messages':[{'role':'user','content':'hi'}]}
resp={'model':'DeepSeek','input':'hi'}
cases=[
 ('/v1/responses',{**resp,'tools':'invalid'},'invalid_request'),
 ('/v1/responses',{**resp,'tools':[{'type':'function','name':'f','parameters':[]}]},'invalid_request'),
 ('/v1/responses',{**resp,'tools':[{'type':'function','function':'oops'}]},'invalid_request'),
 ('/v1/responses',{**resp,'tools':[{'type':'function','name':42}]},'invalid_request'),
 ('/v1/chat/completions',{**chat,'tools':[{'type':'function','function':{'name':'f','parameters':[]}}]},'invalid_request'),
 ('/v1/chat/completions',{**chat,'tools':[{'type':'web_search'}]},'unsupported_tool'),
 ('/v1/chat/completions',{**chat,'tools':[{'type':'function','function':{'name':'f'}},{'type':'function','function':{'name':'f'}}],'tool_choice':'none'},'invalid_request'),
 ('/v1/chat/completions',{**chat,'tools':[{'type':'function','function':'oops'}],'tool_choice':'none'},'invalid_request'),
]
for path,body,code in cases:
 status,result=post(path,body)
 assert status==400 and result['error']['code']==code,(path,status,result)
print('MALFORMED_TOOLS_REJECTED_OK',len(cases))
