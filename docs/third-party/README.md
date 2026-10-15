# 第三方组件记录

PostgreSQL 随附许可原文从实际使用的 EDB PostgreSQL 17.11 Windows binaries 压缩包提取，随离线安装器一并交付。没有打包 pgAdmin 或 StackBuilder。

前端及 Android 依赖版本见 `web/package-lock.json`、`web/android/variables.gradle` 和 Gradle 文件；后端依赖版本见两个 csproj。相关项目包括 Vue、Element Plus、Capacitor、SignalR、PDF.js、ASP.NET Core、EF Core、Npgsql 与 AndroidX。源码保留第三方文件原有版权注释。Microsoft VC++ 运行组件使用微软原始签名安装程序，构建时验证 Authenticode，未修改二进制。

发布方应保留分发组件的许可文本，更新依赖后同步核对清单；本文件不替代上游许可原文。
