# Windows 安装、升级与维护

安装包：`artifacts/installer/JuxingMES-Setup-1.0.1-win-x64.exe`。当前为待现场验收候选版本：已编译，干净 Windows 安装、系统重启与卸载验收尚未执行。目标 Windows x64，建议 Windows 11 或 Windows Server 2019+，管理员运行。包含 ASP.NET Core 10 自包含后台、编译前端、PostgreSQL 17.11、Microsoft VC++ x64 运行组件、EF 迁移和维护脚本。目标电脑无需联网下载、Node.js、Python、SDK 或手工执行 SQL。

## 首次安装

1. 双击安装器，接受系统管理员提权；程序目录默认 `C:\Program Files\FactoryMES`。
2. 选择独立业务数据根目录，默认 `C:\ProgramData\FactoryMES`；生产建议专用数据盘。数据库、配置和附件不得放进程序目录。
3. 配置 SOP、附件、日志和备份四个独立目录，不能相互嵌套。预检至少 3 GB 可用空间，实际容量按附件量和备份周期另行规划。
4. HTTP 端口默认 8080，检查占用。选择内置 PostgreSQL（默认 55433、仅监听 127.0.0.1、随机密码），或连接已有 PostgreSQL；只接受新库或空库，含业务表即拒绝，不覆盖其它数据库。
5. 设置 admin 密码，至少 12 位且含字母和数字，无固定生产密码。选择是否为指定 MES 端口开放专用/域网络防火墙规则。
6. 点击安装，执行数据库初始化、版本化迁移、注册 `FactoryMES` 服务；新建模式同时注册 `FactoryMES-PostgreSQL`，设置开机自启。
7. 数据库、文件目录读写和 HTTP 健康检查通过后，完成页显示本机和局域网地址，数据目录 `访问地址.txt` 同步保存。

后台 `/pc`，工位 `/station`，TV `/tv`。后台配置步骤见 [使用手册](使用手册.md)。默认面向受控工厂局域网；其它网络部署需配置 TLS。

运行配置在 `数据目录\configuration\appsettings.Local.json`，保存连接、存储目录及端口，由 ACL 保护。安装错误查看日志目录 `installation.log` 与 Windows 服务事件。配置和 Android 签名密钥另做受控加密备份，不能提交 Git。

## 升级与回退

修改先在测试环境验证，再发布升级包。选择原程序目录及原数据目录，保留原连接、端口、附件目录与管理员密码；升级不迁移目录、不允许降级。

安装器停止应用，保存 `数据目录\upgrades\时间-编号` 中的数据库 dump、旧程序、旧配置、SOP、附件，成功后才替换程序、显式执行迁移、启动及健康检查。迁移/健康失败尝试恢复这套备份；若日志出现 `ROLLBACK FAILED`，保持停机，由维护人员整套恢复匹配版本并验证。不能单独用旧程序运行新数据库。

后台在线恢复必须先预检摘要、查看全库/SOP/附件范围，并输入准确确认文字。恢复前自动备份当前状态；过程中禁止过站及仪器上传；完成后撤销所有会话。异常中断保留维护标记，不自行放开生产。

服务不可用时，可由维护人员运行随包工具：

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\FactoryMES\maintenance\recover-windows.ps1" `
  -ConfigFile "D:\MESData\configuration\appsettings.Local.json" `
  -Archive "D:\MESData\backups\待恢复备份.zip" `
  -ProgramDir "C:\Program Files\FactoryMES" -Confirmation RESTORE
```

该工具校验路径、摘要和迁移版本，停止服务，备份当前状态后恢复，再迁移、核对附件、撤销会话。失败自动尝试回滚，不能回滚则保持维护标记与服务停止。离线脚本已做语法检查，完整 Windows 服务恢复路径仍待实测；在线恢复已在隔离 PostgreSQL 实际执行。浏览器 JSON 不是数据库备份。

卸载移除程序、相关服务和防火墙规则，保留业务数据。保留目录不能当空库首次安装；重装或目录迁移应由维护人员走恢复流程。

## 构建与待验收项目

构建机需要 Node.js、.NET SDK 10、Inno Setup 6、PostgreSQL binaries 和有效签名的 VC++ 离线组件；`bootstrap-dev.ps1` 下载开发组件，Inno 单独安装。目标机器没有这些开发依赖。

```powershell
./scripts/build-windows.ps1 -Dotnet .tools/dotnet/dotnet.exe -Iscc .tools/inno/ISCC.exe -PostgresRuntime .tools/pg-runtime/pgsql
```

安装器尚无发行方 Authenticode 签名，正式发布由部署单位签名。干净虚拟机必须继续验证：断外网下一步安装、已有空库/非空库保护、中文和非默认磁盘路径、重启自启、另一台设备局域网访问、指定防火墙端口、升级和故障回滚、在线/离线恢复、卸载保留数据。不能把构建通过当作这些实测已通过。
