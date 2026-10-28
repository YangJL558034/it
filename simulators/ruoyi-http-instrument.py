"""Generic HTTP simulator only. This does not connect to a real instrument."""
import argparse,os,json,urllib.request,urllib.error,datetime
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--server',required=True);p.add_argument('--operation',choices=['user','orders','check','results','heartbeat'],required=True);p.add_argument('--payload',type=Path,required=True)
a=p.parse_args();key=os.environ.get('MES_DEVICE_KEY')
if not key:raise SystemExit('Set MES_DEVICE_KEY to the independently issued device credential. Do not use an administrator password.')
payload=json.loads(a.payload.read_text(encoding='utf-8-sig'))
request=urllib.request.Request(a.server.rstrip('/')+'/api/instruments/generic-v1/'+a.operation,json.dumps(payload).encode(),{'Content-Type':'application/json','X-Device-Key':key})
try:response=urllib.request.urlopen(request,timeout=20)
except urllib.error.HTTPError as error:response=error
print(json.dumps(json.load(response),ensure_ascii=False,indent=2))
