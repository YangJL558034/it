"""Copy the fixed developer legacy DB into Java, preserving UUIDs and immutable history.
No production connection argument is accepted. Existing target history is never overwritten.
Requires stopped Java target (server-side writes must not race the offline import).
"""
import argparse,json,os,subprocess,uuid,hashlib,secrets,shutil
from pathlib import Path
import bcrypt
from decimal import Decimal
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--profile',choices=['operations','development'],required=True);args=parser.parse_args()
legacy=json.loads((root/'.local/development.json').read_text(encoding='utf-8-sig'))
config=json.loads((root/('.local/ruoyi-ops-test/development.json' if args.profile=='operations' else '.local/ruoyi/development.json')).read_text(encoding='utf-8-sig'))
target=config['database'] if args.profile=='operations' else 'mes_ruoyi_development'
assert target.startswith('mes_ruoyi_ops_test_') if args.profile=='operations' else target=='mes_ruoyi_development'
data=Path(config['dataDirectory']) if args.profile=='operations' else root/'.local/ruoyi/data'
source_storage=root/'.local/storage';pg=root/'.tools/pg-runtime/pgsql/bin'
run=root/'.local/migration/history'/str(uuid.uuid4());run.mkdir(parents=True)
env={**os.environ,'PGPASSWORD':legacy['dbPassword'],'PGCLIENTENCODING':'UTF8'}
def sql(database,text):
    return subprocess.run([str(pg/'psql.exe'),'-X','-qAt','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p','55432','-U','mesdev','-d',database],input=text,encoding='utf-8',capture_output=True,env=env,check=True).stdout.strip()
def lit(v):
    if v is None:return 'null'
    if isinstance(v,bool):return 'true' if v else 'false'
    if isinstance(v,(int,float,Decimal)):return str(v)
    if isinstance(v,(dict,list)):v=json.dumps(v,ensure_ascii=False,separators=(',',':'))
    return "'"+str(v).replace("'","''")+"'"
tables=['Line','Product','ProcessStep','Station','WorkOrder','Batch','User','StationGrant','BarcodeRule','SopVersion','SopFile','Unit','Passage','Device','TestTask','TestResult','TestItem','Receipt','Message','MessageAck','Audit','ExportJob','BackupJob','Setting','ScanRequest','RolePermission']
source_text=sql('mes_development','begin isolation level repeatable read read only; select pg_advisory_xact_lock_shared(74215001); select jsonb_build_object('+','.join(lit(t)+', (select coalesce(jsonb_agg(to_jsonb(x)),\'[]\') from "'+('Users' if t=='User' else t)+'" x)' for t in tables)+');commit;')
snapshot=json.loads(source_text)
exact_items=json.loads(source_text,parse_float=Decimal)["TestItem"]
# Missing source master records may be appended; existing relationships are never reassigned.
missing={}
for source,table,fields in [('Line','mes_line',{'Id':'id'}),('Product','mes_product',{'Id':'id'}),('ProcessStep','mes_process',{'Id':'id','Sequence':'sequence'}),('Station','mes_station',{'Id':'id','LineId':'line_id','ProcessStepId':'process_step_id'}),('WorkOrder','mes_work_order',{'Id':'id','LineId':'line_id','ProductId':'product_id'}),('Batch','mes_batch',{'Id':'id','WorkOrderId':'work_order_id'})]:
    target_rows=json.loads(sql(target,"select coalesce(jsonb_agg(to_jsonb(t)),'[]') from "+table+" t"))
    lookup={r['id']:r for r in target_rows};missing[source]=[]
    for row in snapshot[source]:
        if row['Id'] not in lookup:missing[source].append(row);continue
        if any(str(row[old])!=str(lookup[row['Id']][new]) for old,new in fields.items()):
            raise SystemExit('Master-data relationship drift: '+source+' '+row['Id']+'; refusing automatic reassignment.')
raw=source_text.encode();digest=hashlib.sha256(raw).hexdigest();(run/'snapshot.json').write_bytes(raw)
if sql(target,"select count(*) from mes_history_import where sha256="+lit(digest))!='0':raise SystemExit('This exact snapshot was already imported; no writes performed.')
for table in ['mes_legacy_user','mes_sop_version','mes_unit','mes_test_task','mes_barcode_rule','mes_message']:
    if sql(target,'select count(*) from '+table)!='0':raise SystemExit('Target has existing business history: '+table+'; merge mapping is required, refusing overwrite.')
# Require no running target HTTP connection pool. The source stays read-only and running.
if int(sql(target,"select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()")):
    raise SystemExit('Stop the Java target before import. Existing DB connections detected.')
subprocess.run([str(pg/'pg_dump.exe'),'-h','127.0.0.1','-p','55432','-U','mesdev','-Fc','--no-owner','-f',str(run/'target-before.dump'),target],env=env,check=True)
if data.exists():shutil.copytree(data,run/'data-before',ignore=shutil.ignore_patterns('backups','maintenance'))
copied=[]
for f in snapshot['SopFile']:
    relative=Path(f['StorageName']);source=(source_storage/'sop'/relative).resolve();destination=(data/'files/legacy'/relative).resolve()
    if source_storage.resolve() not in source.parents or data.resolve() not in destination.parents:raise SystemExit('Unsafe legacy file path')
    if not source.is_file() or source.stat().st_size!=f['Size'] or hashlib.sha256(source.read_bytes()).hexdigest().lower()!=f['Sha256'].lower():raise SystemExit('Legacy SOP file mismatch: '+f['StorageName'])
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest().lower()!=f['Sha256'].lower():raise SystemExit('Target attachment collision')
    copied.append((source,destination))
statements=['begin;select pg_advisory_xact_lock(74215003);']
for source,table,fields,parent in [
 ('Line','mes_line',{'Id':'id','Code':'code','Name':'name','Workshop':'workshop'},None),
 ('Product','mes_product',{'Id':'id','Code':'code','Name':'name'},None),
 ('ProcessStep','mes_process',{'Id':'id','Sequence':'sequence','Name':'name'},'none'),
 ('Station','mes_station',{'Id':'id','Code':'code','Name':'name','LineId':'line_id','ProcessStepId':'process_step_id'},('mes_line','LineId')),
 ('WorkOrder','mes_work_order',{'Id':'id','Code':'code','LineId':'line_id','ProductId':'product_id','Plan':'plan','Status':'status'},('mes_line','LineId')),
 ('Batch','mes_batch',{'Id':'id','Code':'code','WorkOrderId':'work_order_id','Plan':'plan'},('mes_work_order','WorkOrderId'))]:
    for row in missing[source]:
        columns=list(fields.values());values=[lit(row[k]) for k in fields]
        if parent!='none':
            columns.append('dept_id');values.append('100' if parent is None else '(select dept_id from '+parent[0]+' where id='+lit(row[parent[1]])+')')
        statements.append('insert into '+table+'('+','.join(columns)+') values('+','.join(values)+');')
user_ids={}
next_id=int(sql(target,'select coalesce(max(user_id),0)+1 from sys_user'))
occupied_names=set(json.loads(sql(target,"select coalesce(jsonb_agg(user_name),'[]') from sys_user")))
for user in snapshot['User']:
    uid=next_id;next_id+=1;user_ids[user['Id']]=uid
    account=user['Account'] if 3<=len(user['Account'])<=30 and user['Account'] not in occupied_names else 'legacy_'+user['Id'].replace('-','')[:22]
    occupied_names.add(account)
    # Historical identities are disabled, get unique new passwords, and receive no inherited administration role.
    password=bcrypt.hashpw(secrets.token_urlsafe(24).encode(),bcrypt.gensalt()).decode()
    statements.append("insert into sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,create_by,create_time,remark) values("+','.join(map(lit,[uid,100,account,user['Name'][:30],'00',password,'1','0','migration']))+",now(),"+lit('Legacy account: '+user['Account']+'; enable and assign RuoYi role only after administrator review')+");")
    statements.append('insert into mes_legacy_user values('+','.join(map(lit,[user['Id'],uid,user['Account'],user['Role']]))+');')
def mapping(source,target,fields,user_fields=(),json_fields=()):
    for row in (exact_items if source=='TestItem' else snapshot[source]):
        columns=[];values=[]
        for old,new in fields.items():
            value=row[old]
            if (source=='SopFile' and old=='Sha256') or (source=='Device' and old=='KeyHash'):value=value.lower()
            if source=='SopFile' and old=='StorageName':value='files/legacy/'+value.replace('\\','/')
            if old in user_fields:value=user_ids.get(value) if value else None
            if old in json_fields and isinstance(value,str):value=json.loads(value)
            columns.append(new);values.append(lit(value))
        statements.append('insert into '+target+'('+','.join(columns)+')'+(' overriding system value' if target=='mes_test_task' else '')+' values('+','.join(values)+');')
mapping('StationGrant','mes_station_grant',{'UserId':'user_id','StationId':'station_id'},['UserId'])
mapping('BarcodeRule','mes_barcode_rule',{'Id':'id','Kind':'kind','Version':'version','Prefixes':'prefixes','Length':'length','Charset':'charset','PublishedAt':'published_at'})
mapping('SopVersion','mes_sop_version',{'Id':'id','ProcessStepId':'process_step_id','Version':'version','Title':'title','Status':'status','CreatedBy':'created_by','ReviewedBy':'reviewed_by','PublishedAt':'published_at'},['CreatedBy','ReviewedBy'])
mapping('SopFile','mes_sop_file',{'Id':'id','SopVersionId':'sop_version_id','Name':'name','StorageName':'storage_name','ContentType':'content_type','Sha256':'sha256','Position':'position','Seconds':'seconds','Size':'size'})
mapping('Unit','mes_unit',{'Id':'id','BatchId':'batch_id','MainCode':'main_code','CellCode':'cell_code','SnCode':'sn_code','PackCode':'pack_code','CreatedAt':'created_at'})
for unit in snapshot['Unit']:
    for kind,field in [('main','MainCode'),('cell','CellCode'),('sn','SnCode'),('pack','PackCode')]:
        if unit[field]:statements.append('insert into mes_barcode values('+','.join(map(lit,[unit[field],kind,unit['Id']]))+');')
mapping('Device','mes_device',{'Id':'id','Code':'code','Name':'name','StationId':'station_id','KeyHash':'key_hash','Active':'active','LastSeen':'last_seen','Protocol':'protocol','ProgramVersion':'program_version','RequiredItems':'required_items'},json_fields=['RequiredItems'])
mapping('Passage','mes_passage',{'Id':'id','UnitId':'unit_id','StationId':'station_id','ProcessStepId':'process_step_id','UserId':'user_id','SopVersionId':'sop_version_id','BarcodeRuleId':'barcode_rule_id','At':'at'},['UserId'])
mapping('TestTask','mes_test_task',{'Id':'id','SopVersionId':'sop_version_id','UnitId':'unit_id','DeviceId':'device_id','StationId':'station_id','BatchId':'batch_id','ExternalId':'external_id','ProgramVersion':'program_version','Specification':'specification','CreatedAt':'created_at','Status':'status','Sequence':'sequence'},json_fields=['Specification'])
# Preserve test ordering explicitly (the identity values must remain compatible with retest history).

mapping('TestResult','mes_test_result',{'Id':'id','TestTaskId':'test_task_id','DeviceId':'device_id','RecordId':'record_id','TestedAt':'tested_at','ReceivedAt':'received_at','Verdict':'verdict','PayloadHash':'payload_hash','Raw':'raw'})
mapping('TestItem','mes_test_item',{'Id':'id','TestResultId':'test_result_id','Name':'name','Value':'value','Unit':'unit','Lower':'lower_limit','Upper':'upper_limit','Verdict':'verdict'})
mapping('Receipt','mes_receipt',{'Id':'id','DeviceId':'device_id','Operation':'operation','RecordId':'record_id','Raw':'raw','Status':'status','Reason':'reason','At':'at'})
mapping('Message','mes_message',{'Id':'id','Title':'title','Body':'body','LineId':'line_id','Important':'important','CreatedBy':'created_by','At':'at'},['CreatedBy'])
mapping('MessageAck','mes_message_ack',{'MessageId':'message_id','UserId':'user_id','At':'at'},['UserId'])
for table in ['Audit','Setting','ExportJob','BackupJob','ScanRequest','RolePermission']:
    for row in snapshot[table]:statements.append('insert into mes_legacy_record(source,id,payload) values('+','.join(map(lit,[table,row['Id'],row]))+');')
for source,table in [('Unit','mes_unit'),('Passage','mes_passage'),('SopFile','mes_sop_file'),('TestTask','mes_test_task'),('TestResult','mes_test_result'),('TestItem','mes_test_item')]:
    statements.append("do $$begin if (select count(*) from "+table+")<>"+str(len(snapshot[source]))+" then raise exception 'History count mismatch: "+table+"';end if;end$$;")
counts={t:len(snapshot[t]) for t in tables}
statements+=['select setval(pg_get_serial_sequence(\'sys_user\',\'user_id\'),(select max(user_id) from sys_user));',"select setval(pg_get_serial_sequence('mes_test_task','sequence'),coalesce((select max(sequence) from mes_test_task),1),exists(select 1 from mes_test_task));",'insert into mes_history_import(id,source,sha256,counts) values('+','.join(map(lit,[str(uuid.uuid4()),'mes_development',digest,counts]))+');','delete from mes_legacy_guard;','commit;']
query='\n'.join(statements);(run/'apply.sql').write_text(query,encoding='utf-8')
for source,destination in copied:destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
try:sql(target,query)
except subprocess.CalledProcessError as e:
    (run/'error.txt').write_text(e.stderr,encoding='utf-8');raise SystemExit('Import transaction rolled back. Diagnostic retained in '+str(run))
(run/'result.json').write_text(json.dumps({'target':target,'sha256':digest,'counts':counts,'filesVerified':len(copied),'legacyUsersDisabled':len(user_ids),'appendedMasterRecords':{k:len(v) for k,v in missing.items()},'backup':'target-before.dump'},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'target':target,'counts':counts,'filesVerified':len(copied),'evidence':str(run)},ensure_ascii=False))
