-- JDBC Boolean parameters must target PostgreSQL boolean columns.
alter table sys_role alter column menu_check_strictly drop default;
alter table sys_role alter column dept_check_strictly drop default;
alter table sys_role alter column menu_check_strictly type boolean using menu_check_strictly <> 0;
alter table sys_role alter column dept_check_strictly type boolean using dept_check_strictly <> 0;
alter table sys_role alter column menu_check_strictly set default true;
alter table sys_role alter column dept_check_strictly set default true;
select setval(pg_get_serial_sequence('sys_dept', 'dept_id'), (select max(dept_id) + 1 from sys_dept), false);
