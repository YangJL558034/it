# Windows 离线安装交付要求

安装器：Inno Setup，管理员模式，x64 Windows 10/11 或 Windows Server 2019+。后台 self-contained win-x64，前端静态编译并随后台部署。打包 PostgreSQL binaries，不在目标电脑要求 Node、Python、SDK 或执行 SQL。

向导：检查 OS/管理员/可用空间/端口→程序路径→独立数据路径→服务端口→新建 PostgreSQL 或已有连接→管理员密码→SOP/附件/日志/备份目录→防火墙选项→执行初始化迁移→注册 PostgreSQL（仅新建）及 FactoryMES 服务→健康检查→显示 localhost、LAN、/pc、/station、/tv。

必须保护：已有数据库含表时拒绝作为首次安装目标；不覆盖已有数据目录。凭据只写受 ACL 保护的运行配置；安装日志不写明文密码。卸载保留数据，升级前备份、检查版本、执行迁移；生产 APK 签名由部署方密钥提供。

验收分层：源码/编译验证、安装器生成、隔离数据库验证、干净 Windows 虚拟机实际下一步安装、服务开机自启/防火墙/卸载保留数据实测。未执行层级不得标成通过。
