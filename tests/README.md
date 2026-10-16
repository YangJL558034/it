# 测试入口

正式系统测试在 `integration/`：phase1–5、hardening、ui。`scripts/test-all.ps1` 按阶段串行执行，phase5 会实际还原隔离开发数据库；只允许本机 5080 端口，不允许生产数据。真实 PostgreSQL 连接和凭据由 `scripts/start-dev.ps1` 在 `.local` 创建。

`installer.ps1` 执行已发布安装辅助程序的只读预检；`published.ps1` 直接启动自包含 EXE，在本机 5081 使用同一隔离数据库验证页面及登录，然后停止自己的进程。不注册 Windows 服务、不修改防火墙。

原有 `verify-*.cjs` 验证 `dist/` 浏览器原型，仅保留设计历史，不计入正式系统测试，不作为权限、数据库或备份验证证据。

`node tests/integration/station-entry.mjs` 使用实际浏览器和隔离数据库验证免登录进入工位、首次扫码认证、取消/错误密码不放行、授权选择/SOP 加载后提交一次、退出再认证，以及 PC 仍须登录。运行前构建前端并更新开发服务的 wwwroot。

`node tests/integration/terminal-updates.mjs` 验证 1.0.1 匿名 TV/通知只读边界、品牌和导航隔离、按长度自动提交及规则实时更新。该脚本临时发布主码长度规则并在 finally 恢复原值，必须单独串行执行；不要与扫码回归或现场生产并行运行。
