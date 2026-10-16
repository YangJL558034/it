# 聚星数据平台-制造MES

基于当前中文原型开发的 Vue 3 / TypeScript / Element Plus + ASP.NET Core 10 + EF Core + PostgreSQL 模块化单体。PC、工位、TV 使用真实服务端；生产数据不保存到浏览器。`dist/` 仅保留原型设计，不用于正式部署。

已实现一条产线的主码→电芯→仪器测试→SN→包装闭环，含服务端事务、防重、防跳站、权限、条码规则、复测追溯、SOP、消息、批次导出、实时大屏和数据库/附件备份恢复。

**当前是待现场验收版本。** 通用 HTTP 仪器接口已通过模拟器和真实 PostgreSQL 测试；厂家协议未提供，没有声称接通真实仪器。Windows 安装器、APK 的构建结果和未测项见 [验收记录](docs/验收记录.md)。不要在未完成现场验收前直接替换生产系统。

## 交付入口

- Windows 离线安装包：`artifacts/installer/JuxingMES-Setup-1.0.2-win-x64.exe`，含自包含后台、编译前端、PostgreSQL、VC++ 运行组件、迁移及维护工具。目标电脑无需开发环境、Node.js 或 Python。
- 工位 APK：`artifacts/android/juxing-mes-station-1.0.2-release.apk`。
- TV APK：`artifacts/android/juxing-mes-tv-1.0.2-release.apk`。
- 完整源码：`server/`、`web/`（含 `android/`）、`installer/`、`simulator/`、`scripts/`、`tests/`。
- [Windows 安装说明](docs/Windows安装交付.md)、[使用手册](docs/使用手册.md)、[仪器 API](docs/仪器API.md)、[服务端 API](docs/服务端API.md)、[版本与升级](docs/版本兼容与升级.md)。
- [Android 构建与验收](docs/Android构建与验收.md)、[实际测试记录](docs/验收记录.md)。

首次安装由向导设置 `admin` 密码，没有默认生产密码。后台地址 `/pc`，工位 `/station`，TV `/tv`。工位 APK 首次配置服务器后免登录进入，正式扫码时认证；TV 页面及 APK 无需人员登录，只读展示。扫码达到后台发布的长度后自动提交，无需手动回车。

## 开发与测试

需要 Node.js 22+、.NET SDK 10、PostgreSQL。Windows 可运行：

```powershell
./scripts/bootstrap-dev.ps1
./scripts/start-dev.ps1
# 另一个终端
npm run dev --prefix web
```

开发 API 为 `http://127.0.0.1:5080`，Vite 为 `http://127.0.0.1:5173`。隔离开发 PostgreSQL 端口 55432，数据在 `.local/pgdata-dev`；随机开发管理员凭据在 `.local/development.json`。这些目录不会进入 Git，也不会进入安装包。

```powershell
./scripts/test-all.ps1
# 独立浏览器测试使用 Playwright + 本机 Edge，见 tests/integration/ui.mjs
npm install --prefix .tools/qa @playwright/test
node tests/integration/ui.mjs
./scripts/build-windows.ps1
./scripts/build-android.ps1 -Release
```

`test-all.ps1` 会在隔离测试库创建数据并实际测试恢复，**不得将 MES_URL 指向生产环境**。源码测试结果记录在 `.local/test-results`，截图在 `.local/screenshots`，公开结论在 `docs/验收记录.md`。

Android Release 签名密钥位于 `.local/signing`，不提交 Git。请离线加密备份，后续 APK 升级必须使用同一密钥；丢失后不能以原应用身份更新。Windows EXE 尚无发行方 Authenticode 证书，签名应由部署单位发布流程补齐。

## 设计与关系

[需求与架构](docs/01-需求与架构.md)、[数据库关系](docs/02-数据库与API.md)、[进度](docs/进度.md)。角色权限和工位授权由后台配置；管理员角色保留系统恢复权限。工序路线为已确认的五道工序。首版包装码按一件一码处理。

程序目录与数据目录独立，升级保留数据库、附件、配置和历史，数据库变更仅通过版本化迁移。生产部署不自动试改数据库；安装器在停服务、备份后显式迁移。所有升级必须先在测试环境验证。

## 1.0.2 界面与若依改造评估

本版包含工位持续登录、自动聚焦与重复报警、侧边消息、SOP 指导书库、后台布局及 ECharts TV。工位密码错误不限制次数。具体见 [1.0.2 发布说明](docs/发布说明-1.0.2.md)。

按用户要求先完成 [若依 A/B 改造评估](docs/若依改造评估.md) 与 [原型功能差距核查](docs/原型功能对照.md)：推荐保留现有 .NET/PostgreSQL，仅接入官方若依 Vue 3 PC 前端；本版尚未集成若依，不重写已有 MES 后台。
