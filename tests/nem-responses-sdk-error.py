from openai import OpenAI, APIError
import subprocess,time
adb='/opt/android-sdk/platform-tools/adb'
client=OpenAI(base_url='http://127.0.0.1:8090/v1',api_key='unused',max_retries=0,timeout=20)
try:
 subprocess.run([adb,'shell','am','start','-n','com.ialocalbridge/.MainActivity'],check=True,stdout=subprocess.DEVNULL);time.sleep(2)
 stream=client.responses.create(model='deepseek-chat',input='hi',stream=True)
 seen=[]
 try:
  for event in stream:seen.append(event.type)
 except APIError as err:
  assert getattr(err,'code',None)=='wrong_app' or 'wrong_app' in str(err),str(err)
  assert seen[:2]==['response.created','response.in_progress'],seen
  print('SDK_RESPONSES_STREAM_ERROR_OK',seen,flush=True)
 else:
  errors=[e for e in seen if e=='error'];assert errors,seen
  print('SDK_RESPONSES_STREAM_ERROR_EVENT_OK',seen,flush=True)
finally:
 client.close();subprocess.run([adb,'shell','am','start','-n','com.deepseek.chat/.MainActivity'],stdout=subprocess.DEVNULL)
