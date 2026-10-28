"""Destructive operations run ONLY against the disposable operations profile, never development/production."""
import os
os.environ['MES_TEST_PROFILE'] = 'operations'
import json, time, subprocess, urllib.request, urllib.error, zipfile, hashlib, uuid
import bcrypt
from pathlib import Path
from ruoyi_helpers import api, login, root, config, base

assert config['database'].startswith('mes_ruoyi_ops_test_')
data = Path(config['dataDirectory']).resolve()
assert (root / '.local/ruoyi-ops-test').resolve() in data.parents
legacy = json.loads((root / '.local/development.json').read_text(encoding='utf-8-sig'))
env = {**os.environ, 'PGPASSWORD': legacy['dbPassword'], 'PGCLIENTENCODING': 'UTF8'}
def sql(text):
    return subprocess.run([str(root / '.tools/pg-runtime/pgsql/bin/psql.exe'), '-h','127.0.0.1','-p','55432','-U','mesdev','-d',config['database'],'-v','ON_ERROR_STOP=1','-At'],input=text,encoding='utf-8',capture_output=True,env=env,check=True).stdout.strip()

# Only the cloned disposable test account is initialized; the user's development password is untouched.
hashed = bcrypt.hashpw(config['adminPassword'].encode(), bcrypt.gensalt()).decode()
sql("update sys_user set password='"+hashed+"' where user_id=1;")
token = login()
checks=[]
def check(name, condition):
    if not condition: raise AssertionError(name)
    checks.append(name);print('PASS '+name,flush=True)
def ok(result):
    assert result.get('code')==200, ascii(result)
    return result['data'] if 'data' in result else result
def wait(job, expected='Completed'):
    for _ in range(150):
        req=urllib.request.Request(base+'/api/maintenance/status/'+job['id'],headers={'X-MES-Maintenance-Token':job['pollToken']})
        result=json.load(urllib.request.urlopen(req,timeout=10))
        if result.get('status') in ('Completed','Failed'):
            check('job '+job['kind']+' '+expected,result['status']==expected)
            if result['status']!=expected: print(ascii(result))
            return result
        time.sleep(.2)
    raise AssertionError('job timeout')
def create(kind,body):return ok(api('/mes/master/'+kind,'POST',body,token))
def confirm(plan,phrase):return {**{k:plan[k] for k in ('planId','confirmationToken')},'phrase':phrase,'password':config['adminPassword']}

check('anonymous maintenance rejected',api('/mes/maintenance/jobs').get('code')==401)
monitor=ok(api('/mes/monitor/database',token=token))
check('real PostgreSQL monitor returns data',bool(monitor))
routers=json.dumps(api('/getRouters',token=token),ensure_ascii=False)
check('backup cleanup and monitor menu registered',all(s in routers for s in ['备份','测试数据清理','mes/monitor/index']))
policy=ok(api('/mes/maintenance/settings',token=token))
check('PostgreSQL tools configured',policy['toolsAvailable'])
check('invalid retention rejected',api('/mes/maintenance/settings','PUT',{'enabled':False,'intervalHours':0,'retentionDays':0},token).get('code')!=200)

stamp=str(time.time_ns())[-12:]
line=create('lines',{'code':'OPS-L'+stamp,'name':'Operations fixture','workshop':'Isolated QA','deptId':100})
product=create('products',{'code':'OPS-P'+stamp,'name':'Before backup','deptId':100})
orders=[]
for suffix in ['selected','preserved']:
    order=create('orders',{'code':'OPS-'+suffix+stamp,'lineId':line['id'],'productId':product['id'],'plan':10,'status':'Open'})
    batch=create('batches',{'code':'OPS-B'+suffix+stamp,'workOrderId':order['id'],'plan':10})
    unit=str(uuid.uuid4());code='OPS-M'+suffix+stamp
    sql(f"insert into mes_unit(id,batch_id,main_code) values('{unit}','{batch['id']}','{code}');insert into mes_barcode(code,kind,unit_id) values('{code}','main','{unit}');")
    orders.append((order,batch,unit))
selected=orders[0][0]['id'];preserved=orders[1][0]['id']
check('unmarked business order cannot be cleaned',api('/mes/maintenance/test-orders/preview','POST',{'orderIds':[selected]},token).get('code')!=200)
mark={'orderIds':[selected],'reason':'Disposable integration fixture only','password':config['adminPassword'],'phrase':'标记为测试工单'}
check('incorrect admin password cannot mark',api('/mes/maintenance/test-orders/mark','POST',{**mark,'password':'incorrect-password'},token).get('code')==403)
ok(api('/mes/maintenance/test-orders/mark','POST',mark,token))
plan=ok(api('/mes/maintenance/test-orders/preview','POST',{'orderIds':[selected]},token))
check('cleanup preview includes selected descendants',plan['scope']['counts']['units']==1 and plan['scope']['counts']['orders']==1)
check('cleanup requires exact confirmation',api('/mes/maintenance/test-orders/cleanup','POST',confirm(plan,'wrong'),token).get('code')!=200)
job=ok(api('/mes/maintenance/test-orders/cleanup','POST',confirm(plan,'清理选中的测试工单'),token))
result=wait(job)
check('cleanup created protected database backup',bool(result.get('preBackupId')))
check('selected business rows removed',sql(f"select count(*) from mes_work_order where id='{selected}'")=='0')
check('unselected order and production preserved',sql(f"select count(*) from mes_unit where id='{orders[1][2]}'")=='1')
check('shared definitions preserved',sql(f"select count(*) from mes_product where id='{product['id']}'")=='1')

attachment=data/'files/ops-fixture.txt';attachment.parent.mkdir(parents=True,exist_ok=True);attachment.write_text('before',encoding='utf-8')
backup=wait(ok(api('/mes/maintenance/backups','POST',{},token)))
archive=data/'backups'/backup['fileName']
check('archive SHA matches ledger',hashlib.sha256(archive.read_bytes()).hexdigest()==backup['sha256'])
with zipfile.ZipFile(archive) as z:
    check('real PostgreSQL custom dump included',z.read('database.dump').startswith(b'PGDMP'))
    check('business attachments included',z.read('data/files/ops-fixture.txt')==b'before')
    manifest=json.loads(z.read('manifest.json'))
    check('manifest bound to isolated test database',manifest['sourceDatabase']==config['database'])
attachment.write_text('after',encoding='utf-8')
sql(f"update mes_product set name='After backup' where id='{product['id']}'")
plan=ok(api('/mes/maintenance/backups/'+backup['id']+'/preview','POST',{},token))
check('restore preview identifies target database',plan['targetDatabase']==config['database'])
check('restore refuses incorrect password',api('/mes/maintenance/restore','POST',{**confirm(plan,plan['confirmationPhrase']),'password':'wrong-password'},token).get('code')==403)
restore=ok(api('/mes/maintenance/restore','POST',confirm(plan,plan['confirmationPhrase']),token))
result=wait(restore)
check('restore automatically backed up current state',bool(result.get('preBackupId')))
check('database restored from backup',sql(f"select name from mes_product where id='{product['id']}'")=='Before backup')
check('attachment restored consistently',attachment.read_text(encoding='utf-8')=='before')
check('old authentication revoked after restore',api('/getInfo',token=token).get('code')==401)
token=login()
check('health healthy after maintenance',api('/api/health')['status']=='ok')
check('operations audit saved after restore',int(sql("select count(*) from sys_oper_log where method like 'mes-maintenance:%'"))>0)
report=root/'.local/migration/terminals/operations-results.json';report.parent.mkdir(parents=True,exist_ok=True)
report.write_text(json.dumps({'database':config['database'],'checks':checks,'passed':len(checks),'backupId':backup['id'],'restoreId':restore['id']},ensure_ascii=False,indent=2),encoding='utf-8')
print(f'{len(checks)} operations checks passed; disposable fixtures retained only in isolated test database.')
