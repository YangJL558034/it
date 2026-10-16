# Android 工位与 TV

完整工程在 `web/android`，Vue 代码在 `web/src`。Capacitor 7，JDK 21，Android SDK 36，Gradle 8.14.3，最低 SDK 24（Android 7.0）。station 包名 `com.factory.mes.station`，TV 包名 `com.factory.mes.tv`，各自 flavor/入口、横屏及屏幕常亮；TV 声明 Leanback 入口。设备还须有 Chrome / Android System WebView 60 或更新版本；Android 7 系统自带的 WebView 52 不能运行 Capacitor 7，应用会显示更新网页组件的提示，不再留在白屏。

开发构建：`./scripts/build-android.ps1`。首次签名运行 `./scripts/create-android-signing.ps1`，再 `./scripts/build-android.ps1 -Release`。密钥生成脚本拒绝覆盖。签名通过环境变量注入，不写入源码；`.local/signing` 中密钥及密码由 ACL 保护，不提交 Git，须受控加密备份并移交维护负责人。更新保持包名、签名一致，递增 versionCode。本版 1.0.3 的 versionCode=4，可覆盖之前 versionCode=1/2/3；本版仍连接 1.0.2 API，先将服务器升级到 1.0.2，再安装新版 APK。

产物 `artifacts/android/juxing-mes-station-1.0.3-release.apk` 和 `juxing-mes-tv-1.0.3-release.apk` 已构建并验证签名；不代表实体设备验收。Gradle wrapper、Manifest、MainActivity、资源和构建脚本均保留在 Git。

构建网络受限时显式设置 `MES_ANDROID_MAVEN_MIRROR` 和 `MES_MAVEN_CENTRAL_MIRROR`；本次使用阿里云 Google Maven/Central 镜像，默认仍为官方仓库。安装 APK 不依赖这些仓库。

两端软件名称均为“聚星数据平台-制造MES”。首次配置服务器地址并验证连接，工位无需登录即可进入，正式扫码时认证；TV 无需人员登录。服务器配置、最近有效工位选择、加密终端凭据和 SOP 缓存在本地，禁止离线过站。扫码枪使用键盘模式，按后台当前条码规则的长度自动提交；兼容 Enter/Tab 结尾，不重复提交，单框连扫。测试工位自动收结果。TV 使用专用匿名只读数据接口和通知通道，没有人员会话到期问题。

1.0.3 白屏修复：原版在 Android 11 的 WebView 83 上重现白屏，启动异常为 `Iterator is not defined`。原因是 PDF.js 6 在工位和 TV 首屏被静态加载，其现代 JavaScript 全局对象不受该 WebView 支持。现将 PDF.js 改为浏览器按需加载，Android PDF 采用系统 PdfRenderer；首次 WebView 加载即按 flavor 进入工位或 TV。加入兼容构建和可见的启动错误页。工位和 TV 均已在 Android 11 模拟器安装、启动并显示；工位连接测试服务器后进入免登录扫码页，TV 连接后进入真实数据看板。Android 7 默认 WebView 52 的两种 APK 都显示升级提示。以上是模拟器验证，没有替代实体设备验收。

待实测：Android 7 + 更新后的 WebView 60 以上组合、实体 Android 11 设备、扫码枪、断网与 SOP 缓存、视频及 PDF、TV 遥控器、1080p/4K、重启和长时间运行。实体设备应先更新系统 WebView/Chrome，再安装 APK；不需要清除应用数据或卸载旧版。
