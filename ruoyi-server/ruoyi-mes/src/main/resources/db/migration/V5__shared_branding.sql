create table mes_branding (
 id integer primary key check(id=1), name varchar(128) not null, logo_key varchar(64),
 revision bigint not null default 1, updated_by bigint references sys_user(user_id), updated_at timestamptz not null default now()
);
insert into mes_branding(id,name) values(1,'聚星数据平台-制造MES');
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
values (66000,'平台外观',1,20,'mes-branding','mes/branding/index',1,1,'C','0','0','mes:branding:list','component','migration',now()),
 (66001,'修改平台外观',66000,1,'','',1,0,'F','0','0','mes:branding:edit','#','migration',now());
select setval(pg_get_serial_sequence('sys_menu','menu_id'),(select max(menu_id) from sys_menu));
