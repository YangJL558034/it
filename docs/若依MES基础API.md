# 若依 MES 基础 API

本阶段 Java API 运行于开发端口 5081，官方 Vue 3 通过 `/dev-api` 代理调用。所有 MES 接口均要求若依 `Authorization: Bearer <token>`，不接受原 .NET 会话。

响应沿用若依 `{code,msg,data}`；列表为 `{code,msg,rows,total}`。若依业务失败可能仍使用 HTTP 200，客户端必须检查响应 `code`。常见业务码：403 权限或范围拒绝、409 重复/引用/版本冲突、422 参数及数量规则、404 不支持的资料类型。

| 方法与路径 | 用途 | 服务端权限 |
|---|---|---|
| GET `/mes/modules` | 当前用户可见的功能、迁移状态及授权范围内主数据数量 | 登录；逐模块过滤权限 |
| GET `/mes/master/{resource}` | 分页及编码/名称筛选 | `mes:{resource}:list` |
| GET `/mes/master/{resource}/options` | 当前表单所需的授权关联编号/标签 | 同上；受部门范围约束 |
| POST `/mes/master/{resource}` | 新增基础资料 | `mes:{resource}:add` |
| PUT `/mes/master/{resource}/{uuid}` | 修改基础资料 | `mes:{resource}:edit` |
| DELETE `/mes/master/{resource}/{uuid}?version=N` | 删除未使用资料 | `mes:{resource}:remove` |
| GET `/mes/master/processes/list` | 五道工序及原编号 | `mes:processes:list` |

资源为 `lines`、`stations`、`products`、`orders`、`batches`，不能指定任意表名。分页参数为 `pageNum`、`pageSize`（最多 200）与 `keyword`。

| 资源 | 新增/修改请求字段 |
|---|---|
| lines | code、name、workshop、deptId |
| products | code、name、deptId |
| stations | code、name、lineId、processStepId |
| orders | code、lineId、productId、plan、status（Open / Closed） |
| batches | code、workOrderId、plan |

修改必须额外提交返回记录的 `version`。编号为 UUID，部门编号为若依整数编号；关联不接受名称字符串。工位、工单、批次的部门由产线/父工单继承，客户端不得指定。额外字段会被拒绝。计划必须为正整数；新增工单/批次及关键关系还要经过业务校验。

目前没有提供兼容原 APK 的 Java `/api/station`、SignalR、仪器或 SOP 业务。迁移状态为 `migrating` 的功能没有可用写入接口，不能用基础资料 CRUD 代替扫码状态机。
