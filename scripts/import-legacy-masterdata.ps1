param([long]$DepartmentId=100)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$pg=Join-Path $root '.tools/pg-runtime/pgsql/bin/psql.exe'
$password=(Get-Content -Raw (Join-Path $root '.local/development.json') | ConvertFrom-Json).dbPassword
$folder=Join-Path $root '.local/migration/masterdata'
New-Item -ItemType Directory -Force $folder | Out-Null
# Development databases are deliberately fixed. Never point this script at production.
function Sql([string]$database,[string]$query){
    $queryFile=Join-Path $folder ('query-'+[guid]::NewGuid()+'.sql')
    try{
        [IO.File]::WriteAllText($queryFile,$query,[Text.UTF8Encoding]::new($false))
        $output=& $pg -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 -U mesdev -d $database -f $queryFile
        if($LASTEXITCODE -ne 0){throw "SQL failed in $database"}
        return ($output -join "`n").Trim()
    }finally{Remove-Item -LiteralPath $queryFile -ErrorAction SilentlyContinue}
}
$env:PGPASSWORD=$password
$priorPgEncoding=$env:PGCLIENTENCODING
$env:PGCLIENTENCODING='UTF8'
try{
    $occupied=Sql 'mes_ruoyi_development' 'select (select count(*) from mes_import_run)+(select count(*) from mes_line)+(select count(*) from mes_product)+(select count(*) from mes_process)'
    if($occupied -ne '0'){throw '目标 MES 表已有数据，禁止覆盖或重复导入。请使用隔离库并核对现有迁移记录。'}
    if((Sql 'mes_ruoyi_development' "select count(*) from sys_dept where dept_id=$DepartmentId and status='0' and del_flag='0'") -ne '1'){throw '目标部门不存在或已停用'}
    $source=@'
begin isolation level repeatable read read only;
with gate as materialized (select pg_advisory_xact_lock_shared(74215001))
select jsonb_build_object(
 'lines',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "Line" x),
 'products',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "Product" x),
 'processes',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "ProcessStep" x),
 'stations',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "Station" x),
 'orders',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "WorkOrder" x),
 'batches',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from "Batch" x),
 'guards',(
  select coalesce(jsonb_agg(to_jsonb(g)),'[]') from (
   select 'batches' as resource,b."Id" as id,(select count(*) from "Unit" u where u."BatchId"=b."Id") as produced,
    (select count(*) from "TestTask" t where t."BatchId"=b."Id" and t."Status"='Pending') as pending,'已有原 MES 生产记录' as reason
   from "Batch" b where exists(select 1 from "Unit" u where u."BatchId"=b."Id")
   union all
   select 'stations',s."Id",0,0,'已有原 MES 过站或设备关联' from "Station" s
   where exists(select 1 from "Passage" p where p."StationId"=s."Id") or exists(select 1 from "Device" d where d."StationId"=s."Id")
  ) g
 )) from gate;
commit;
'@
    $json=Sql 'mes_development' $source
    $snapshot=Join-Path $folder 'legacy-masterdata-snapshot.json'
    [IO.File]::WriteAllText($snapshot,$json)
    $hash=(Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash
    $escaped=$json.Replace("'","''")
    $run=[guid]::NewGuid().ToString()
    $query=@"
begin;
select pg_advisory_xact_lock(74215003);
create temporary table migration_snapshot(payload jsonb);
insert into migration_snapshot values('$escaped'::jsonb);
insert into mes_line(id,code,name,workshop,dept_id)
select x."Id",x."Code",x."Name",x."Workshop",$DepartmentId from migration_snapshot,jsonb_to_recordset(payload->'lines') as x("Id" uuid,"Code" text,"Name" text,"Workshop" text);
insert into mes_product(id,code,name,dept_id)
select x."Id",x."Code",x."Name",$DepartmentId from migration_snapshot,jsonb_to_recordset(payload->'products') as x("Id" uuid,"Code" text,"Name" text);
insert into mes_process(id,sequence,name)
select x."Id",x."Sequence",x."Name" from migration_snapshot,jsonb_to_recordset(payload->'processes') as x("Id" uuid,"Sequence" integer,"Name" text);
insert into mes_station(id,code,name,line_id,process_step_id,dept_id)
select x."Id",x."Code",x."Name",x."LineId",x."ProcessStepId",$DepartmentId from migration_snapshot,jsonb_to_recordset(payload->'stations') as x("Id" uuid,"Code" text,"Name" text,"LineId" uuid,"ProcessStepId" uuid);
insert into mes_work_order(id,code,line_id,product_id,plan,status,dept_id)
select x."Id",x."Code",x."LineId",x."ProductId",x."Plan",x."Status",$DepartmentId from migration_snapshot,jsonb_to_recordset(payload->'orders') as x("Id" uuid,"Code" text,"LineId" uuid,"ProductId" uuid,"Plan" integer,"Status" text);
insert into mes_batch(id,code,work_order_id,plan,dept_id)
select x."Id",x."Code",x."WorkOrderId",x."Plan",$DepartmentId from migration_snapshot,jsonb_to_recordset(payload->'batches') as x("Id" uuid,"Code" text,"WorkOrderId" uuid,"Plan" integer);
insert into mes_legacy_guard(resource,row_id,produced,pending_tests,reason)
select x.resource,x.id,x.produced,x.pending,x.reason from migration_snapshot,jsonb_to_recordset(payload->'guards') as x(resource text,id uuid,produced integer,pending integer,reason text);
do `$`$ declare pair record; expected bigint; actual bigint; begin
 for pair in select * from (values('lines','mes_line'),('products','mes_product'),('processes','mes_process'),('stations','mes_station'),('orders','mes_work_order'),('batches','mes_batch')) as t(key,table_name) loop
  select jsonb_array_length(payload->pair.key) into expected from migration_snapshot;
  execute format('select count(*) from %I',pair.table_name) into actual;
  if expected<>actual then raise exception 'migration count mismatch: %',pair.key; end if;
 end loop;
end `$`$;
insert into mes_import_run(id,source,manifest) values('$run','mes_development',jsonb_build_object('sha256','$hash','departmentId',$DepartmentId,'counts',(select jsonb_object_agg(k,jsonb_array_length(v)) from migration_snapshot,jsonb_each(payload) as kv(k,v))));
commit;
"@
    $sqlFile=Join-Path $folder 'apply-masterdata.sql'
    [IO.File]::WriteAllText($sqlFile,$query)
    # File input avoids Windows command-line length limits and shell interpolation.
    & $pg -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 -U mesdev -d mes_ruoyi_development -f $sqlFile
    if($LASTEXITCODE -ne 0){throw '目标导入失败；整个导入事务已回滚'}
    Sql 'mes_ruoyi_development' "select jsonb_build_object('id',id,'manifest',manifest) from mes_import_run where id='$run'" | Set-Content -Encoding utf8 (Join-Path $folder 'import-result.json')
    Write-Host '基础资料快照已导入隔离若依库；主外键与逐表数量验证完成。生产历史及账号尚未导入，不代表完整业务切换。'
}finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue;$env:PGCLIENTENCODING=$priorPgEncoding}
