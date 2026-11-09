"""JSON HTTP bodies preserve non-ASCII without a charset, as real clients send."""
import json,urllib.request,urllib.error
base='http://127.0.0.1:8090'
def call(path,method='GET',body=None,contenttype='application/json'):
 req=urllib.request.Request(base+path,None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8'),{'Content-Type':contenttype},method=method)
 try:
  with urllib.request.urlopen(req,timeout=15) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,json.load(e)
names=['Café — 中文 😀 甲','Été — 日本語']
try:
 for name,ctype in zip(names,['application/json','application/json; charset=UTF-8']):
  status,r=call('/web/nem/api-keys','POST',{'name':name},ctype);assert status==200,r
 keys=call('/web/nem/api-keys')[1]['api_keys']
 for name in names:assert sum(k['name']==name for k in keys)==1,[(k['name']) for k in keys]
 status,r=call('/web/nem/api-keys','POST',{'name':'not-created'},'application/json; charset=iso-8859-1')
 assert status==400 and r['error']['code']=='unsupported_parameter',r
 print('UTF8_HTTP_NO_CHARSET_EXPLICIT_AND_REJECTION_OK')
finally:
 keys=call('/web/nem/api-keys')[1]['api_keys']
 for k in keys:
  if k['name'] in names:call('/web/nem/api-keys/'+k['id'],'DELETE')
