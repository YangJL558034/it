# Android 工位与 TV

完整工程在 `web/android`，Vue 代码在 `web/src`。Capacitor 7，JDK 21，Android SDK 36，Gradle 8.14.3，最低 SDK 23。station 包名 `com.factory.mes.station`，TV 包名 `com.factory.mes.tv`，各自 flavor/入口、横屏及屏幕常亮；TV 声明 Leanback 入口。

开发构建：`./scripts/build-android.ps1`。首次签名运行 `./scripts/create-android-signing.ps1`，再 `./scripts/build-android.ps1 -Release`。密钥生成脚本拒绝覆盖。签名通过环境变量注入，不写入源码；`.local/signing` 中密钥及密码由 ACL 保护，不提交 Git，须受控加密备份并移交维护负责人。更新保持包名、签名一致，递增 versionCode。

产物 `artifacts/android/mes-station-1.0.0-release.apk` 和 `mes-tv-1.0.0-release.apk` 已构建并验证签名；不代表实体设备验收。Gradle wrapper、Manifest、MainActivity、资源和构建脚本均保留在 Git。

构建网络受限时显式设置 `MES_ANDROID_MAVEN_MIRROR` 和 `MES_MAVEN_CENTRAL_MIRROR`；本次使用阿里云 Google Maven/Central 镜像，默认仍为官方仓库。安装 APK 不依赖这些仓库。

首次配置服务器地址、验证连接、登录。只有服务器配置和 SOP 缓存在本地，禁止离线过站。扫码枪使用键盘模式并附加 Enter/Tab，单框连扫。测试工位自动收结果。TV 会话有效期 30 天，到期重新登录。

待实测：首次安装/覆盖升级、横屏、实际扫码枪、断网与 SOP 缓存、视频解码/PDF、遥控器、1080p/4K、重启和长时间运行。当前 `adb devices` 没有实体终端，环境无可运行 Android 模拟器，不能将这些项目标为通过。
