-- UUIDs are retained when copying legacy MES business records. No production seed data.
create table mes_line (
 id uuid primary key, code varchar(128) not null unique, name varchar(128) not null,
 workshop varchar(128) not null, dept_id bigint not null references sys_dept(dept_id),
 version integer not null default 1, created_by bigint references sys_user(user_id),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table mes_product (
 id uuid primary key, code varchar(128) not null unique, name varchar(128) not null,
 dept_id bigint not null references sys_dept(dept_id), version integer not null default 1,
 created_by bigint references sys_user(user_id), created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table mes_process (
 id uuid primary key, sequence integer not null unique check(sequence between 1 and 5), name varchar(128) not null
);
create table mes_station (
 id uuid primary key, code varchar(128) not null unique, name varchar(128) not null,
 line_id uuid not null references mes_line(id), process_step_id uuid not null references mes_process(id),
 dept_id bigint not null references sys_dept(dept_id), version integer not null default 1,
 created_by bigint references sys_user(user_id), created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table mes_work_order (
 id uuid primary key, code varchar(128) not null unique, line_id uuid not null references mes_line(id),
 product_id uuid not null references mes_product(id), plan integer not null check(plan>0),
 status varchar(16) not null check(status in ('Open','Closed')), dept_id bigint not null references sys_dept(dept_id),
 version integer not null default 1, created_by bigint references sys_user(user_id),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table mes_batch (
 id uuid primary key, code varchar(128) not null unique, work_order_id uuid not null references mes_work_order(id),
 plan integer not null check(plan>0), dept_id bigint not null references sys_dept(dept_id), version integer not null default 1,
 created_by bigint references sys_user(user_id), created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
-- Guards copied from the legacy snapshot until the full production history is migrated.
create table mes_legacy_guard (
 resource varchar(32) not null, row_id uuid not null, produced integer not null default 0,
 pending_tests integer not null default 0, reason varchar(256) not null,
 primary key(resource,row_id)
);
create table mes_import_run (
 id uuid primary key, source varchar(128) not null, manifest jsonb not null, created_at timestamptz not null default now()
);
create table mes_module (
 module_key varchar(40) primary key, title varchar(80) not null, group_name varchar(80) not null,
 route varchar(128) not null unique, permission varchar(100) not null,
 status varchar(20) not null check(status in ('available','migrating')), description varchar(1000) not null, sort integer not null
);
insert into mes_module values
 ('orders','生产工单','生产运营','/mes-production/orders','mes:orders:list','available','维护工单、产品、产线和计划数量；保护已关联批次及生产历史。',10),
 ('batches','生产批次','生产运营','/mes-production/batches','mes:batches:list','available','批次关联具体工单；批次数量合计不能超过工单计划。',20),
 ('processes','工艺路线','生产运营','/mes-production/processes','mes:processes:list','available','查看已确认的五道工序及顺序；工序流转业务仍在迁移。',30),
 ('trace','产品追溯','生产运营','/mes-production/trace','mes:trace:list','migrating','主码、电芯、SN、包装映射及完整过站、测试和复测记录。',40),
 ('quality','质量检验与复测','质量与工艺','/mes-quality/quality','mes:quality:list','migrating','保留全部 PASS、FAIL、参数和复测；不合格不得放行。',50),
 ('sop','SOP 作业指导书','质量与工艺','/mes-quality/sop','mes:sop:list','migrating','按工序管理多图片、PDF、视频，审核发布、版本归档及自动播放。',60),
 ('devices','设备与测试仪','质量与工艺','/mes-quality/devices','mes:devices:list','migrating','独立设备凭证、工位绑定、授权及心跳。',70),
 ('protocols','仪器协议适配','质量与工艺','/mes-quality/protocols','mes:protocols:list','migrating','按厂家协议实现用户信息、工单、工艺检查及测试上传；协议文档待提供。',80),
 ('receipts','接口接收日志','质量与工艺','/mes-quality/receipts','mes:receipts:list','migrating','上传去重、异常接收、原始报文和处理原因。',90),
 ('lines','产线管理','资源与协同','/mes-resource/lines','mes:lines:list','available','维护车间、产线及若依部门数据范围。',100),
 ('stations','工位管理','资源与协同','/mes-resource/stations','mes:stations:list','available','工位关联产线与工序；历史关联变更受保护。',110),
 ('products','产品管理','资源与协同','/mes-resource/products','mes:products:list','available','产品基础资料及工单关联保护。',120),
 ('grants','工位授权','资源与协同','/mes-resource/grants','mes:grants:list','migrating','通过若依用户、角色及工位授权约束终端操作。',130),
 ('barcodes','条码规则','资源与协同','/mes-resource/barcodes','mes:barcodes:list','migrating','主码、电芯、SN、包装分别配置多前缀、长度和字符类型。',140),
 ('messages','消息中心','资源与协同','/mes-resource/messages','mes:messages:list','migrating','消息发布、接收与重要消息确认。',150),
 ('reports','生产报表','资源与协同','/mes-resource/reports','mes:reports:list','migrating','按工单与批次筛选导出，完整保留 PASS、FAIL、复测与参数。',160),
 ('backup','备份与恢复','资源与协同','/mes-resource/backup','mes:backup:list','migrating','数据库、SOP 和附件一致性备份，恢复前校验、确认及写入控制。',170);

insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
values (50000,'生产运营',0,0,'mes-production',null,1,0,'M','0','0','','dashboard','migration',now()),
 (50100,'质量与工艺',0,1,'mes-quality',null,1,0,'M','0','0','','validCode','migration',now()),
 (50200,'资源与协同',0,2,'mes-resource',null,1,0,'M','0','0','','tree','migration',now());
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
select 51000+sort,title,case group_name when '生产运营' then 50000 when '质量与工艺' then 50100 else 50200 end,
 sort,module_key,case when status='available' and module_key<>'processes' then 'mes/master/index' when module_key='processes' then 'mes/process/index' else 'mes/module/index' end,
 1,1,'C','0','0',permission,case when status='available' then 'list' else 'time' end,'migration',now(),description from mes_module;
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 60000+m.sort*10+a.n,m.title||a.title,51000+m.sort,a.n,'','',1,0,'F','0','0',
 'mes:'||m.module_key||':'||a.operation,'#','migration',now()
from mes_module m cross join (values(1,'新增','add'),(2,'修改','edit'),(3,'删除','remove')) a(n,title,operation)
where m.status='available' and m.module_key<>'processes';
-- Framework source and licenses stay in the repository; remove the external demo link from navigation.
update sys_menu set visible='1' where menu_id=4 and path='http://ruoyi.vip';
select setval(pg_get_serial_sequence('sys_menu','menu_id'),(select max(menu_id) from sys_menu));
