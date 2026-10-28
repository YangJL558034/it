# 若依 MES 通用 HTTP 接口与兼容约定

本接口用于设备模拟联调，未接通真实厂家仪器。厂家 `HQAPI/MES/` 子路径、HTTP 方法、字段和返回格式仍待文档；不注册猜测的厂家路径。

## 服务与凭证

基础地址为 Java MES 服务地址。管理页面开发端口 5174 不是独立的 MES 后台；正式 JAR 可在同一端口提供 `/admin/`、`/station`、`/tv`、`/api/health`。

后台登记设备时返回一次独立 `deviceKey`，请求通过 `X-Device-Key` 提交，服务端只存摘要。设备关联第三道测试工位、产线、程序版本及参数标准。停用设备、轮换凭证立即影响后续上传，不能使用管理员账号代替设备认证。

通用路径为 `POST /api/instruments/generic-v1/{operation}`，JSON 请求。操作为 `user`、`orders`、`check`、`results`、`heartbeat`。所有操作需要 `deviceCode` 和 UUID `stationId`。工单列表、心跳不需要主码。

工艺检查：

```json
{
  "deviceCode": "ATE-01",
  "stationId": "由后台工位资料提供的 UUID",
  "batchId": "当前生产批次 UUID",
  "taskId": "设备唯一任务编号，每次复测使用新编号",
  "mainCode": "实际产品主码",
  "programVersion": "STD-01"
}
```

检查主码规则、主码过站、电芯绑定、产线与批次所属工单、开放状态和前序记录。已进入 SN/包装的产品不得自动复测；产品已有未完成任务时不能创建另一任务。任务编号重复且关联相同只返回原任务。

结果上传包含上述设备/工位/主码/批次/任务/版本，另外提交：

```json
{
  "recordId": "设备唯一上传记录编号",
  "testedAt": "2026-10-16T04:00:00Z",
  "verdict": "PASS",
  "items": [
    {"name": "voltage", "value": 3.5, "unit": "V", "lowerLimit": 3, "upperLimit": 4, "verdict": "PASS"}
  ],
  "rawData": "可附加原始仪器记录",
  "rawFileReference": "可附加原始文件引用"
}
```

参数必须完整、无重复，项目、单位和上下限匹配任务创建时的标准快照；时间在任务时间减五分钟至服务器时间加五分钟之间。数值与项目结论、总结果必须一致。相同设备与 `recordId` 重试只保存一次，改变内容的重试拒绝；复测新增任务和结果。FAIL 不过测试工序，后段始终检查最新测试任务和结论。结果提交后广播更新通知，工位端与 TV 重新取得真实数据。

成功为 `{"accepted":true,"data":{...}}`，业务拒绝为 `{"accepted":false,"message":"明确原因"}`；通用结果拒绝使用 HTTP 422。客户端也应检查 JSON 中的业务 `code`（若依全局认证错误可能为 HTTP 200 加 `code:401/403`）。已认证的无效 JSON、关联错误、重复冲突均保留接收日志和原始内容，不生成可放行记录。

## 旧版通用接口

保留源码中已有的 `/api/instrument/v1/check`、`/results`、`/heartbeat`、`/user` 和 GET `/work-orders`。旧检查使用 `externalTaskId`、`workOrderId`、`stationCode`；旧上传使用检查返回的 UUID `taskId`，参数上下限字段为 `lower`、`upper`。兼容层转换后进入同一个服务端校验，不另建业务规则或用户系统。

工位/TV 1.1.x 使用 Java API 2；旧 1.0.x APK 仍为原 .NET 接口客户端，需安装新 APK。新 APK 会明确拒绝错误的后台类型。厂家协议适配器只负责字段转换，工序与结果校验由公共领域服务执行。

## 模拟器

开发机安装 Python 后，设置环境变量 `MES_DEVICE_KEY`，准备符合配置的请求 JSON：

```powershell
python simulators/ruoyi-http-instrument.py --server http://127.0.0.1:5081 --operation check --payload check.json
python simulators/ruoyi-http-instrument.py --server http://127.0.0.1:5081 --operation results --payload result.json
```

这只是开发用模拟器；MES 服务器部署不依赖 Python。不得把模拟器测试作为真实仪器验收。
