# 通用仪器 API v1 与厂家适配边界

这里只定义本系统通用协议。设备界面给出的 `/HQAPI/MES/` 没有已确认子路径，本系统没有注册或猜测厂家路径。厂家文档到齐后新增 `IInstrumentProtocol` 实现和对应入口，调用同一领域服务，原设备继续按原协议工作。

基础地址：`http(s)://服务器:端口/api/instrument/v1`。所有请求携带 `X-Device-Key`（后台登记设备时仅显示一次，数据库只存摘要）。正式部署使用 TLS 或受控隔离网段。JSON UTF-8，UTC ISO8601 时间。用户 Bearer 与设备密钥不可互用。

| 方法/路径 | 功能 |
|---|---|
| POST /user | `{account}`，返回被授权该设备工位的在职用户信息，不返回密码，不代替人员登录 |
| GET /work-orders | 设备所在产线未关闭工单与批次 UUID |
| POST /heartbeat | `{}`，更新设备心跳 |
| POST /check | 创建/重试测试任务，工艺检查成功才允许设备测试 |
| POST /results | 追加 PASS/FAIL/复测结果及完整参数 |

工艺检查请求：
```json
{"externalTaskId":"设备自己的唯一任务编号","mainCode":"MC000000000001","workOrderId":"工单UUID","batchId":"批次UUID","deviceCode":"ATE-001","stationCode":"A01-03","programVersion":"ATE-STD-1"}
```

结果上传请求：
```json
{"recordId":"设备自己的唯一结果编号","taskId":"check返回的任务UUID","mainCode":"MC000000000001","deviceCode":"ATE-001","stationCode":"A01-03","testedAt":"2026-10-15T08:00:00Z","programVersion":"ATE-STD-1","verdict":"PASS","items":[{"name":"Voltage","value":5.08,"unit":"V","lower":4.8,"upper":5.2,"verdict":"PASS"}],"rawData":"完整仪器原始报文","rawFileReference":null}
```

必须与后台设备受控标准项目、单位及上下限完全匹配；服务器重新计算参数判定和总判定。先检查主码过站、电芯绑定、工单/批次/产线及设备工位关联，再创建任务。任务创建后阻止 SN 放行，直到有效结果 PASS。已流转 SN 后不接受新的正常测试任务。

接收接口的 HTTP 200 表示接收日志保存成功，必须继续检查 `accepted`：
- 成功 `{accepted:true,data:{taskId,...}}` 或 `{accepted:true,data:{resultId,duplicate,verdict}}`。
- 业务拒绝 `{accepted:false,code,message,receiptId}`，保存原报文为异常，绝不能放行。
- 认证错误 401/403；已认证设备的无效 JSON 也保存为异常接收，返回 `accepted:false`；系统错误 500。发生超时/500，设备用相同记录编号及相同报文重试。

每台设备的 recordId 唯一，相同内容重试只存一次 TestResult；改变内容拒绝并保留接收日志。一次任务只能一个结果，复测必须新建 externalTaskId/recordId，不覆盖旧结果。原始报文保存在 TestResult 和 Receipt；rawFileReference 仅作外部记录引用，不会由服务器自动下载。

模拟器：配置环境变量 MES_URL、MES_DEVICE_KEY；复制上面检查请求为 job.json，加上 items 标准数组；运行 `node simulator/device.mjs job.json FAIL` 或 `PASS`。每次复测更新 externalTaskId。模拟器会自动重试一次以验证幂等。

SignalR 在数据库事务提交后广播 `changed`，工位和大屏重新获取快照；断线重连也刷新快照。广播不包含测试原文或产品信息。
