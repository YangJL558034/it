# 服务端 API 1.0

基础地址为安装时配置的 HTTP(S) 服务地址。JSON 使用 camelCase，关系键为 UUID，时间为带时区 ISO8601。设备专用接口见 [仪器 API](仪器API.md)。以下是首版完整功能索引；请求定义同时保存在对应模块的 C# record、端点及集成测试中。

用户请求通过 `Authorization: Bearer <token>` 认证。令牌只在登录响应中返回，数据库保存摘要；退出、停用账户、修改角色/密码和恢复数据库后撤销会话。工位使用服务端授权，不相信客户端传入的身份或工序。

## 登录与配置

| 方法 / 路径 | 请求与行为 |
|---|---|
| GET /api/health | 数据库、文件目录读写、版本及支持客户端；恢复中 status=maintenance |
| POST /api/auth/login | `{account,password}` → `{authenticated,token,user}`；失败读取 authenticated/message，不把 HTTP 200 当登录成功 |
| GET /api/auth/me | 当前身份 |
| POST /api/auth/logout | 撤销当前会话 |
| GET /api/catalog | 当前账号可读的产线、工序、工位、产品、工单、批次 |
| GET/POST /api/admin/{lines,stations,products,orders,batches} | 查询或新增主数据 |
| PUT/DELETE /api/admin/{类型}/{id} | 修改/删除；已有引用及关键关联受保护 |
| GET/POST /api/admin/users，PUT /api/admin/users/{id} | 用户、角色、停用和密码管理 |
| GET/POST /api/admin/grants，DELETE /api/admin/grants/{id} | 用户—工位授权 |
| GET/POST /api/admin/permissions，DELETE /api/admin/permissions/{id} | 角色权限；管理员权限不可撤销 |
| GET/POST /api/admin/rules | 查询、发布条码规则新版本 |
| GET/PUT /api/admin/settings | 工厂名称、备份间隔与保留天数 |
| GET /api/admin/audit | 最近 500 条操作审计，敏感字段脱敏 |

主数据字段：Line `{code,name,workshop}`；Station `{code,name,lineId,processStepId}`；Product `{code,name}`；WorkOrder `{code,lineId,productId,plan,status:"Open"或"Closed"}`；Batch `{code,workOrderId,plan}`。条码规则字段以 `MasterData/Endpoints.cs` 和后台编辑器为准；发布创建新版本，历史过站继续引用旧规则。

## 工位与追溯

| 方法 / 路径 | 请求与行为 |
|---|---|
| GET /api/station/options | 授权工位及对应工单、批次 |
| POST /api/station/scan | `{requestId,stationId,batchId,code,sopVersionId}`；每次物理扫描新 requestId，同一扫描网络重试使用原 ID 和原内容 |
| POST /api/station/reset | `{stationId}`；清除当前用户该工位等待状态 |
| POST /api/station/heartbeat | `{stationId}` |
| GET /api/station/summary?stationId=…&batchId=… | 工位完成数量、待扫码状态与记录 |
| GET /api/trace?code=… | 任意主码、电芯码、SN 或包装码的完整追溯 |
| GET /api/tests?stationId=…&batchId=… | 实际仪器结果，工位端仅自动展示 |

已发布 SOP 存在时，扫码必须带播放器实际加载的版本 ID。工艺状态由服务器维护；同一码重复不增加计数。冲突、跨工单、前序缺失及最新有效 FAIL 均拒绝。错误格式 `{code,message}`，通常 401 未登录、403 越权、409 业务冲突、422 参数错误、503 恢复维护。

## SOP、消息、设备

| 方法 / 路径 | 请求与行为 |
|---|---|
| GET/POST /api/sop/versions | 创建 `{processStepId,title}` |
| POST /api/sop/versions/{id}/files | multipart/form-data 多个 files；服务器保存实际文件 |
| PUT /api/sop/versions/{id}/files | 全部文件数组 `[{id,seconds}]`，数组顺序为播放顺序 |
| POST /api/sop/versions/{id}/review 或 /publish | 审核/发布；创建人与审核人不同 |
| GET /api/sop/current/{processId} | 工序当前已发布版本及文件 |
| GET /api/sop/files/{id} | 带身份读取，支持 Range |
| GET/POST /api/messages | 发布 `{title,body,important,lineId}`，lineId 可空 |
| POST /api/messages/{id}/ack；GET /api/messages/{id}/acks | 本人确认与后台确认列表 |
| GET /api/admin/protocols | 通用协议及未启用的厂家协议状态 |
| GET/POST /api/admin/devices | 登记与工位绑定，受控程序/项目标准；密钥只展示一次 |
| POST /api/admin/devices/{id}/rotate 或 /disable | 更换密钥/停用 |
| GET /api/admin/receipts | 最近接收日志及异常原文 |

## 报表、大屏与备份

报表范围 `filter={workOrderId,batchId,mainCode?,cellCode?,snCode?,packCode?,verdict?}`，两个 ID 必须具体且相互匹配。条码为包含匹配。verdict 为历史测试筛选产品；测试导出仍保留所选产品的全部 PASS/FAIL/复测项目，避免隐藏失败历史。

| 方法 / 路径 | 请求与行为 |
|---|---|
| POST /api/reports/search | filter，返回 total 和前 200 条预览 |
| POST /api/exports | `{kind:"products"或"passages"或"tests",filter}` → 202 后台任务 |
| GET /api/exports；GET /api/exports/{id}/download | 状态与 CSV 下载，限定任务本人或管理员 |
| GET /api/tv?lineId=…&workshop=… | 只读真实五工序、工单、产量、合格率、在线工位、异常、测试 |
| GET/POST /api/backups | 日志/创建真实数据库及文件备份任务 |
| GET /api/backups/{id}/download | 下载 ZIP，内含 pg_dump、文件、配置摘要和校验清单 |
| POST /api/backups/{id}/preview | 校验、展示恢复范围，返回限时一次性确认票据 |
| POST /api/backups/{id}/restore | `{ticket,confirmation:"RESTORE <备份UUID>"}`；确切字段以后台恢复页和 Operations/Endpoints.cs 为准 |
| GET /api/maintenance | 恢复状态；期间写入返回 503 |

SignalR `/hubs/mes` 使用同一用户认证；事务提交后 `changed` 事件只表示有更新，客户端重新读取各自获权的快照。断线重连和轮询均可补齐；不可把广播事件当作过站成功凭据。

兼容表及升级约束见 [版本兼容与升级](版本兼容与升级.md)。禁止客户端直接连接 PostgreSQL。
