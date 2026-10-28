create table mes_test_order_mark(work_order_id uuid primary key references mes_work_order(id),marked_by bigint not null references sys_user(user_id),reason varchar(256) not null,at timestamptz not null default now());
update sys_menu set component='mes/monitor/index' where menu_id=111 and perms='monitor:druid:list';
update sys_menu set parent_id=1,order_num=21,path='mes-backup',component='mes/maintenance/index' where perms='mes:backup:list' and menu_type='C';
update mes_module set route='/system/mes-backup',group_name='系统管理',status='available' where module_key='backup';
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
values (66020,'测试数据清理',1,22,'mes-test-cleanup','mes/maintenance/cleanup',1,1,'C','0','0','mes:maintenance:cleanup','example','migration',now()),
 (66021,'创建数据库备份',51170,1,'','',1,0,'F','0','0','mes:backup:create','#','migration',now()),
 (66022,'下载数据库备份',51170,2,'','',1,0,'F','0','0','mes:backup:download','#','migration',now()),
 (66023,'恢复数据库备份',51170,3,'','',1,0,'F','0','0','mes:backup:restore','#','migration',now()),
 (66024,'备份策略设置',51170,4,'','',1,0,'F','0','0','mes:backup:config','#','migration',now()),
 (66025,'删除备份档案',51170,5,'','',1,0,'F','0','0','mes:backup:remove','#','migration',now());
insert into sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark) values
 ('MES 定时备份开关','mes.backup.enabled','false','Y','migration',now(),'由系统管理的备份策略维护'),
 ('MES 备份间隔小时','mes.backup.intervalHours','24','Y','migration',now(),'1–720'),
 ('MES 备份保留天数','mes.backup.retentionDays','7','Y','migration',now(),'1–3650；恢复/清理前的保护备份不自动删除');
select setval(pg_get_serial_sequence('sys_menu','menu_id'),(select max(menu_id) from sys_menu));
