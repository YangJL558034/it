-- These routes now execute Java MES business services; this is not an assertion of physical-device or installer acceptance.
update mes_module set status='available' where module_key in ('trace','quality','sop','devices','protocols','receipts','grants','barcodes','messages','reports');
update mes_module set description='按已确认的五道工序流转；服务端校验前序、电芯绑定、当前工单及最新测试结果。' where module_key='processes';
update mes_module set description='独立适配器、设备凭证与接收日志；通用 HTTP 已联调，厂家协议文档及真实设备验证待提供。' where module_key='protocols';
