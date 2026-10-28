"""Real Java/PostgreSQL five-stage acceptance, disposable operations profile only."""
import os
os.environ['MES_TEST_PROFILE']='operations'
import json,time,uuid,urllib.request,urllib.error,concurrent.futures,datetime,secrets
from ruoyi_helpers import *
assert config['database'].startswith('mes_ruoyi_ops_test_')
token=login();checks=[];stamp=str(time.time_ns())[-10:]
def check(name,condition):
    if not condition:raise AssertionError(name)
    checks.append(name);print('PASS '+name,flush=True)
def ok(r):
    assert r.get('code',200)==200,ascii(r)
    return r.get('data',r)
def call(path,body=None,method='POST',auth=token):return api(path,method,body,auth)
def create(kind,body):return ok(call('/mes/master/'+kind,body))
def scan(n,code,request=None,batch=None,sop=None):return call('/api/station/scan',{'requestId':request or str(uuid.uuid4()),'stationId':stations[n]['id'],'batchId':batch or batches[0]['id'],'code':code,'sopVersionId':sop or globals().get('sops',{}).get(n)})
def device(operation,body):
    req=urllib.request.Request(base+'/api/instruments/generic-v1/'+operation,json.dumps({**device_base,**body}).encode(),{'Content-Type':'application/json','X-Device-Key':device_key})
    try:r=urllib.request.urlopen(req,timeout=20)
    except urllib.error.HTTPError as e:r=e
    return json.load(r)
for i in range(7):check('station wrong password remains retryable '+str(i),call('/api/auth/station/login',{'account':'admin','password':'incorrect-password'}).get('code')==401)
auth=call('/api/auth/station/login',{'account':'admin','password':config['adminPassword'],'native':True,'rememberStation':True})
check('RuoYi station login succeeds after seven failures',auth.get('authenticated') is True and bool(auth.get('stationToken')))
req=urllib.request.Request(base+'/api/auth/station/resume',json.dumps({'stationToken':auth['stationToken']}).encode(),{'Content-Type':'application/json','X-MES-Station-Auth':'1'})
check('persistent native credential resumes without password',json.load(urllib.request.urlopen(req)).get('authenticated') is True)
line=create('lines',{'code':'PL'+stamp,'name':'Java closed loop','workshop':'QA','deptId':100})
product=create('products',{'code':'PP'+stamp,'name':'QA product','deptId':100})
processes=ok(api('/mes/master/processes/list',token=token));stations={}
for p in processes:stations[p['sequence']]=create('stations',{'code':'PS'+str(p['sequence'])+stamp,'name':p['name'],'lineId':line['id'],'processStepId':p['id']})
sops={n: (api('/api/sop/current/'+s['processStepId'],token=token).get('version') or {}).get('id') for n,s in stations.items()}
batches=[]
for i in range(2):
    o=create('orders',{'code':'PO'+str(i)+stamp,'lineId':line['id'],'productId':product['id'],'plan':20,'status':'Open'})
    batches.append(create('batches',{'code':'PB'+str(i)+stamp,'workOrderId':o['id'],'plan':20}))
for kind,prefix in [('main','M'),('cell','C'),('sn','S'),('pack','P')]:ok(call('/mes/business/barcodes',{'kind':kind,'length':11,'prefixes':[prefix],'charset':'alnum'}))
main='M'+stamp;cell='C'+stamp;sn='S'+stamp;pack='P'+stamp
check('barcode validation enforced on server',scan(1,'invalid').get('code')==422)
rid=str(uuid.uuid4())
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:results=list(pool.map(lambda _:scan(1,main,rid),range(6)))
check('concurrent identical request idempotent',all(r.get('message')=='主码过站成功' for r in results))
check('physical repeated barcode reports duplicate',scan(1,main).get('duplicate') is True)
check('unit counted once',api('/api/station/summary?stationId='+stations[1]['id']+'&batchId='+batches[0]['id'],token=token)['completed']==1)
check('cross-order barcode rejected',scan(1,main,batch=batches[1]['id']).get('code')==409)
check('jumping to SN before cell/test rejected',scan(4,main).get('code')==422)
check('cell station selects product',scan(2,main).get('waiting')=='电芯码')
check('cell binding committed automatically',scan(2,cell).get('message')=='绑定成功')
check('instrument station rejects manual scan',scan(3,main).get('code')==422)
registration=ok(call('/mes/business/devices',{'code':'D'+stamp,'name':'HTTP simulator only','stationId':stations[3]['id'],'programVersion':'STD1','requiredItems':[{'name':'voltage','unit':'V','lowerLimit':3,'upperLimit':4}]}))
device_key=registration['deviceKey'];device_base={'deviceCode':'D'+stamp,'stationId':stations[3]['id'],'batchId':batches[0]['id'],'mainCode':main,'programVersion':'STD1'}
check('SN blocked without PASS',scan(4,main).get('code')==422)
check('instrument rejects wrong batch',not device('check',{'taskId':'T-wrong'+stamp,'batchId':batches[1]['id']})['accepted'])
check('instrument creates authorized test task',device('check',{'taskId':'T1'+stamp})['accepted'])
def result(task,record,value,verdict):return {'taskId':task,'recordId':record,'testedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'verdict':verdict,'items':[{'name':'voltage','value':value,'unit':'V','lowerLimit':3,'upperLimit':4,'verdict':verdict}]}
fail=result('T1'+stamp,'R1'+stamp,2,'FAIL')
check('FAIL fully retained',device('results',fail)['accepted'])
check('FAIL blocks downstream SN',scan(4,main).get('code')==422)
check('repeat result upload deduplicates',device('results',fail)['data']['duplicate'])
check('changed retry content rejected',not device('results',{**fail,'verdict':'PASS'})['accepted'])
check('retest creates new task',device('check',{'taskId':'T2'+stamp})['accepted'])
passing=result('T2'+stamp,'R2'+stamp,3.5,'PASS')
check('PASS retest retained separately',device('results',passing)['accepted'])
check('SN stage accepts tested product',scan(4,main).get('waiting')=='SN')
check('SN binding committed',scan(4,sn).get('message')=='绑定成功')
check('package resolves SN and full trace',scan(5,sn).get('trace',{}).get('cellCode')==cell)
check('package binding completes product',scan(5,pack).get('message')=='绑定成功')
trace=ok(api('/mes/business/trace/'+results[0]['trace']['id'],token=token))
check('all five passages retained',len(trace['passages'])==5)
check('FAIL and PASS retest with parameters retained',len(trace['tests'])==2 and all(len(t['items'])==1 for t in trace['tests']))
check('completed product cannot be retested',not device('check',{'taskId':'T3'+stamp})['accepted'])
board=api('/api/tv?lineId='+line['id'])
check('TV uses actual production quantity',board['started']==1 and board['completed']==1 and board['passed']==1)
check('TV displays actual five processes',len(board['processes'])==5 and all(p['completed']==1 for p in board['processes']))
check('rejected instrument receipts retained',len(ok(api('/mes/business/receipts',token=token)))>=3)
legacy_main='M'+str(int(stamp)+1).zfill(10);legacy_cell='C'+str(int(stamp)+1).zfill(10)
ok(scan(1,legacy_main));ok(scan(2,legacy_main));ok(scan(2,legacy_cell))
def legacy(operation,body):
    req=urllib.request.Request(base+'/api/instrument/v1/'+operation,json.dumps(body).encode(),{'Content-Type':'application/json','X-Device-Key':device_key})
    return json.load(urllib.request.urlopen(req,timeout=20))
old_check={'externalTaskId':'LEGACY-'+stamp,'mainCode':legacy_main,'workOrderId':batches[0]['workOrderId'],'batchId':batches[0]['id'],'deviceCode':device_base['deviceCode'],'stationCode':stations[3]['code'],'programVersion':'STD1'}
old_task=legacy('check',old_check)
check('legacy instrument check endpoint remains compatible',old_task['accepted'])
old_result={'recordId':'LEGACY-R'+stamp,'taskId':old_task['data']['taskId'],'mainCode':legacy_main,'deviceCode':device_base['deviceCode'],'stationCode':stations[3]['code'],'testedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'programVersion':'STD1','verdict':'PASS','items':[{'name':'voltage','value':3.5,'unit':'V','lower':3,'upper':4,'verdict':'PASS'}]}
check('legacy instrument result contract accepted',legacy('results',old_result)['accepted'])
check('legacy retry remains idempotent',legacy('results',old_result)['data']['duplicate'])
report={'passed':len(checks),'checks':checks,'lineId':line['id'],'database':config['database']}
(root/'.local/migration/terminals/production-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(str(len(checks))+' production checks passed; fixtures retained in disposable test DB.')
