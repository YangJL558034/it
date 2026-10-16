-- Keep MES groups together above the standard framework administration groups.
update sys_menu set order_num=case menu_id when 1 then 10 when 2 then 11 when 3 then 12 end where parent_id=0 and menu_id in(1,2,3);
create index ix_mes_line_dept on mes_line(dept_id);
create index ix_mes_product_dept on mes_product(dept_id);
create index ix_mes_station_line on mes_station(line_id);
create index ix_mes_station_dept on mes_station(dept_id);
create index ix_mes_order_line on mes_work_order(line_id);
create index ix_mes_order_product on mes_work_order(product_id);
create index ix_mes_order_dept on mes_work_order(dept_id);
create index ix_mes_batch_order on mes_batch(work_order_id);
create index ix_mes_batch_dept on mes_batch(dept_id);
