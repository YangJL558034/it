# Java 版本 Windows 安装器迁移

本目录是新安装器的平台检查部分，**尚未生成 Java 正式安装包**。父目录的 1.0.2 安装器是保留的 .NET 基线。

## 明确范围

- 仅 x64；不提供 x86/32 位安装包，也不做 32 位运行时降级。
- 最低服务器目标为 Windows Server 2016 x64；客户端安装目标为 Windows 10 1607 及更新 x64。
- Windows 7/8、旧 Server、ARM64 不在当前依赖验证范围，不能声称“所有 64 位 Windows 均已兼容”。
- 平台检查通过只表示满足当前架构和版本门槛，不能代替完整安装/升级/恢复测试。

`Test-MesPlatform.ps1` 可在 Windows PowerShell 5.1 执行，读取 `platform-policy.json`。当前 Windows 11 Enterprise x64（build 26300）预检通过；没有 Server 2016 实机验收记录。

## 待接入的正式向导

程序和数据独立目录、端口/管理员/磁盘检查、现有或新装 PostgreSQL、随机密钥和用户设置的管理员密码、x64 Java 运行环境、缓存组件、Windows 服务、指定端口防火墙、Flyway 迁移、数据库/文件/HTTP 健康检查、升级前备份、失败回退和完成页地址。离线组件必须固定版本及 SHA-256，保存许可文件。

当前开发机使用 Java 17 + PostgreSQL 17.11；Server 2016 数据库发行组件按官方支持清单评估 PostgreSQL 15，禁止对旧数据目录直接降级。若依缓存暂用 Garnet 2.2.2 的 net8.0 Windows x64 包进行兼容性验证，它还依赖 .NET 8 运行时；这不是 .NET MES 后台，也尚未确定为正式安装依赖。外部 Redis 连接方案仍保留。

待实测：Server 2016 / 2019 / 2022 / 2025、Windows 10 / 11 的干净安装、离线安装、重启自启、升级保留数据、卸载保留数据、失败恢复。尚无这些系统全部测试通过的结论。

依据：[PostgreSQL Windows 发行支持](https://www.postgresql.org/download/windows/)、[Garnet 官方源码及许可](https://github.com/microsoft/garnet)、[Garnet 配置文档](https://microsoft.github.io/garnet/docs/getting-started/configuration)。
