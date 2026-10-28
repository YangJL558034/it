import os
os.environ['MES_TEST_PROFILE']='operations'
import json,time,uuid,urllib.request,urllib.error,secrets,csv,io
from ruoyi_helpers import *
assert config['database'].startswith('mes_ruoyi_ops_test_')
token=login();checks=[];stamp=str(time.time_ns())[-10:]
def check(name,value):
    if not value:raise AssertionError(name)
    checks.append(name);print('PASS '+name,flush=True)
def ok(r):
    assert r.get('code',200)==200,ascii(r)
    return r.get('data',r)
def call(path,body=None,method='POST',t=token):return api(path,method,body,t)
menus=ok(api('/system/menu/list',token=token));role_key='sopqa'+stamp
permissions=['mes:sop:list','mes:sop:review','mes:station:operate']
ok(call('/system/role',{'roleName':role_key,'roleKey':role_key,'roleSort':1,'status':'0','dataScope':'1','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[m['menuId'] for m in menus if m.get('perms') in permissions]}))
role=next(r['roleId'] for r in api('/system/role/list',token=token)['rows'] if r['roleKey']==role_key)
password='Mes9-'+secrets.token_hex(9);username='sopqa'+stamp
ok(call('/system/user',{'userName':username,'nickName':'SOP QA reviewer','password':password,'deptId':100,'status':'0','roleIds':[role],'postIds':[]}))
user=next(r['userId'] for r in api('/system/user/list?userName='+username,token=token)['rows'] if r['userName']==username);reviewer=login(username,password)
check('reviewer cannot edit barcode rules',call('/mes/business/barcodes',{'kind':'main','length':8,'prefixes':[],'charset':'alnum'},t=reviewer).get('code')==403)
check('reviewer cannot backup database',call('/mes/maintenance/backups',{},t=reviewer).get('code')==403)
check('reviewer cannot clean test data',api('/mes/maintenance/test-orders',token=reviewer).get('code')==403)
check('reviewer cannot monitor database',api('/mes/monitor/database',token=reviewer).get('code')==403)
process=ok(api('/mes/master/processes/list',token=token))[0]['id']
version=ok(call('/mes/sop',{'processId':process,'title':'Multi-file SOP QA '+stamp}))['id']
check('empty SOP cannot be reviewed',call('/mes/sop/'+version+'/review',{},t=reviewer).get('code')!=200)
check('SOP creator cannot self-review',call('/mes/sop/'+version+'/review',{}).get('code')!=200)
boundary='MESQA'+uuid.uuid4().hex
png=(root/'web/public/branding/logo.png').read_bytes()
# A valid single-page PDF, so the same fixture can be rendered by browser and Android PDF engines.
content=b'BT /F1 24 Tf 72 720 Td (MES SOP acceptance) Tj ET'
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',b'<< /Length '+str(len(content)).encode()+b' >>\nstream\n'+content+b'\nendstream']
pdf=b'%PDF-1.4\n';offsets=[0]
for i,obj in enumerate(objects,1):offsets.append(len(pdf));pdf+=str(i).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
xref=len(pdf);pdf+=b'xref\n0 6\n0000000000 65535 f \n'+b''.join(('%010d 00000 n \n'%n).encode() for n in offsets[1:])+b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+str(xref).encode()+b'\n%%EOF'

parts=[]
for name,mime,content in [('first.png','image/png',png),('second.pdf','application/pdf',pdf)]:parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="files"; filename="'+name+'"\r\nContent-Type: '+mime+'\r\n\r\n').encode()+content+b'\r\n')
payload=b''.join(parts)+('--'+boundary+'--\r\n').encode()
req=urllib.request.Request(base+'/mes/sop/'+version+'/files',payload,{'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary})
check('multiple SOP files uploaded in one request',json.load(urllib.request.urlopen(req))['code']==200)
manifest=ok(api('/mes/sop/'+version,token=token));files=manifest['files']
check('SOP manifest preserves all file metadata',len(files)==2 and files[0]['contentType']=='image/png')
ok(call('/mes/sop/'+version+'/files',[{'id':f['id'],'seconds':i+7} for i,f in enumerate(reversed(files))],method='PUT'))
manifest=ok(api('/mes/sop/'+version,token=token));check('file order and dwell time persisted',manifest['files'][0]['name']=='second.pdf' and manifest['files'][0]['seconds']==7)
ok(call('/mes/sop/'+version+'/review',{},t=reviewer));ok(call('/mes/sop/'+version+'/publish',{}))
check('published SOP delivered by process',api('/api/sop/current/'+process,token=token)['version']['id']==version)
check('published SOP immutable',call('/mes/sop/'+version+'/files',[],method='PUT').get('code')==409)
v2=ok(call('/mes/sop',{'processId':process,'title':'Successor '+stamp}))['id']
req=urllib.request.Request(base+'/mes/sop/'+v2+'/files',payload,{'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary});ok(json.load(urllib.request.urlopen(req)))
ok(call('/mes/sop/'+v2+'/review',{},t=reviewer));ok(call('/mes/sop/'+v2+'/publish',{}))
check('publishing new version archives old version',next(v['status'] for v in ok(api('/mes/sop',token=token)) if v['id']==version)=='Archived')
fixture=json.loads((root/'.local/migration/terminals/production-results.json').read_text(encoding='utf-8'))
line=fixture['lineId'];options=ok(api('/mes/business/options',token=token));station=next(s for s in options['stations'] if s['lineId']==line and s['processStepId']==process)
ok(call('/mes/business/grants',{'userId':user,'stationId':station['id'],'enabled':True}))
check('station grant restricts available stations',len(api('/api/station/options',token=reviewer)['stations'])==1)
ok(call('/mes/business/grants',{'userId':user,'stationId':station['id'],'enabled':False}))
check('revoking station grant immediately removes station',not api('/api/station/options',token=reviewer)['stations'])
ok(call('/mes/business/messages',{'title':'QA important '+stamp,'body':'Confirm this instruction','important':True,'lineId':line}))
message=next(m for m in api('/api/messages',token=token) if m['title']=='QA important '+stamp)
check('important message delivered',message['important'] and not message['acked'])
ok(call('/api/messages/'+message['id']+'/ack',{}));ok(call('/api/messages/'+message['id']+'/ack',{}))
check('repeated acknowledgement idempotent',next(m for m in api('/api/messages',token=token) if m['id']==message['id'])['acked'])
units=ok(api('/mes/business/trace?keyword=M',token=token));unit=next(u for u in units if u['lineId']==line and u['packCode'])
order=unit['workOrderId'];batch=unit['batchId']
check('export cannot default to all data',call('/mes/reports/jobs',{'kind':'tests'}).get('code')!=200)
other_batch=next(b['id'] for b in options['batches'] if b['workOrderId']!=order)
check('export rejects mismatched order and batch',call('/mes/reports/jobs',{'kind':'tests','orderId':order,'batchId':other_batch}).get('code')!=200)
def export(kind,verdict=''):
    job=ok(call('/mes/reports/jobs',{'kind':kind,'orderId':order,'batchId':batch,'keyword':unit['mainCode'],'verdict':verdict}))['id']
    for _ in range(40):
        found=next(j for j in ok(api('/mes/reports/jobs',token=token)) if j['id']==job)
        if found['status'] in ['Completed','Failed']:break
        time.sleep(.2)
    check('background '+kind+' export completed',found['status']=='Completed')
    req=urllib.request.Request(base+'/mes/reports/jobs/'+job+'/download',headers={'Authorization':'Bearer '+token})
    return list(csv.DictReader(io.StringIO(urllib.request.urlopen(req).read().decode('utf-8-sig'))))
tests=export('tests');check('test export contains FAIL and PASS retest parameters',len(tests)==2 and {t['result'] for t in tests}=={'FAIL','PASS'} and all(t['name']=='voltage' for t in tests))
check('test export follows current FAIL filter',len(export('tests','FAIL'))==1)
check('product export respects exact batch and barcode',len(export('products'))==1)
check('passage export includes five actual steps',len(export('passages'))==5)
report={'passed':len(checks),'checks':checks,'database':config['database']}
(root/'.local/migration/terminals/sop-reports-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(str(len(checks))+' SOP/report checks passed; isolated fixtures retained.')
