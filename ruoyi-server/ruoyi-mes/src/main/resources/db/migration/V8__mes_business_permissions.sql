insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 67000+m.sort,m.title||'配置',51000+m.sort,9,'','',1,0,'F','0','0','mes:'||m.module_key||':edit','#','migration',now()
from mes_module m where m.module_key in ('grants','barcodes','devices','messages','sop');
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
values (67901,'SOP 审核',51060,10,'','',1,0,'F','0','0','mes:sop:review','#','migration',now()),
(67902,'SOP 发布',51060,11,'','',1,0,'F','0','0','mes:sop:publish','#','migration',now()),
(67903,'批次报表导出',51160,10,'','',1,0,'F','0','0','mes:reports:export','#','migration',now());
update sys_menu set component='mes/business/index' where menu_id in (51040,51050,51070,51080,51090,51130,51140,51150);
update sys_menu set component='mes/sop/index' where menu_id=51060;
select setval(pg_get_serial_sequence('sys_menu','menu_id'),(select max(menu_id) from sys_menu));
