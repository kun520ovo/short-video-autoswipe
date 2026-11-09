# 短视频挂机

![哈基伟应用图标](design/icon/app-icon-preview.png)

Android 本地自动滑屏工具：填写手机上的应用名称，设置上滑次数与随机停留时间，通过悬浮窗暂停、继续或关闭任务。

当前源码版本为 **0.1.0**（`versionCode = 1`），支持 **Android 10（API 29）及以上的 ARM64 设备**，界面为简体中文。包名为 `com.shortvideo.autoswipe`，目标 API 为 37。版本与打包配置见 [app/build.gradle.kts](app/build.gradle.kts)。

## 当前功能

- 按名称匹配手机中已安装且可启动的应用；存在多个匹配结果时手动选择，不固定目标包名。
- 打开目标应用后固定等待 10 秒，再开始按停留时间循环上滑。
- 每轮停留时长在设定范围内按毫秒随机抽取；上下限相同时使用固定时长。
- 单次上滑手势时长随机为 1–100 毫秒，手势位置根据屏幕可用区域计算，并避开本应用悬浮窗。
- 可选随机双击点赞；每轮按设定概率决定是否在停留期间执行双击。
- 黑色半透明、白字悬浮窗，每次新任务默认在屏幕左侧中部展开，可在本次任务中拖动、收起和展开；收起／展开状态不保存。窗口显示运行状态、完成次数、本轮停留时间和最新运行提示。
- 主设置页跟随系统切换浅色或深色模式，采用绿色强调色；应用图标为“哈基伟”，支持自适应及单色主题图标。

## 权限与使用

1. 安装应用，在主设置页手动开启无障碍服务和“显示在其他应用上层”。授权后返回应用，授权本身不会启动任务。
2. 填写应用名称，确认匹配结果，设置上滑次数、停留时间和可选的点赞概率。
3. 点击“开始挂机”，应用会打开所选目标。在 10 秒启动等待期间进入需要操作的页面；等待结束后，每轮先停留再上滑。
4. 通过主设置页或悬浮窗控制同一个任务。使用时保持屏幕点亮、解锁，并让需要操作的页面位于前台。

| 操作或状态   | 行为                                    |
| ------- | ------------------------------------- |
| 暂停      | 暂停后续手势，保留本次进度和剩余等待时间                  |
| 运行中切换应用 | 当前操作的应用离开前台后暂停，切回后自动继续                |
| 继续      | 在当前前台应用中继续同一个任务；可以切换操作对象，不会自动打开原应用    |
| 关闭      | 结束任务、清空本次进度并关闭悬浮窗                     |
| 达到次数上限  | 停止上滑，保留悬浮窗显示完成结果；可点击“关闭”，或回到主设置页开始新任务 |

手动暂停、熄屏、锁屏、权限关闭或服务中断后，需要恢复相应条件并手动继续。点击“继续”时，应先切到要操作的应用，再使用悬浮窗按钮。

应用只检查前台应用和可用操作区域，不识别视频画面、页面文字、输入框或弹窗。上滑次数和点赞次数分别按系统确认完成的上滑、双击手势统计，不读取目标应用的实际点赞状态。已经提交给系统的短手势可能在暂停或关闭后完成，后续操作会取消。

## 编程语言与构建工具

| 类别           | 名称与版本                          | 用途                     |
| ------------ | ------------------------------ | ---------------------- |
| 应用编程语言       | Kotlin 2.4.21                  | 编写 Android 应用与构建配置     |
| 界面框架         | Jetpack Compose，BOM 2026.09.00 | 绘制主页面与悬浮窗内容            |
| 设置存储         | DataStore Preferences 1.3.0    | 异步保存本机设置               |
| 异步任务         | Kotlin Coroutines 1.11.0       | 执行等待、手势调度及状态更新         |
| 构建工具         | Gradle 9.8.2                   | 管理编译、依赖和打包任务           |
| Android 构建插件 | AGP 9.5.1                      | 编译和打包 Android 应用       |
| Java 开发工具    | JDK 27，Actions 使用 Temurin      | 运行构建工具；应用字节码目标为 JVM 17 |
| Android 编译平台 | SDK Platform 37.2              | 提供 Android 系统接口        |
| Android 打包工具 | Build Tools 37.0.0             | 处理资源、签名及安装包            |
| 单元测试         | JUnit 6.1.3                    | 检查任务控制、手势及参数逻辑         |
| 工作流脚本        | Bash、Python 3                  | 自动构建及临时签名配置            |

应用源码使用 Kotlin，主页面与悬浮窗内容使用 Jetpack Compose 构建。悬浮窗由 ComposeView 和 WindowManager 承载，滑动与双击等手势由 AccessibilityService 配合协程执行。

依赖版本集中在 [gradle/libs.versions.toml](gradle/libs.versions.toml)。发布版开启 R8 代码压缩、混淆和资源裁剪，压缩 DEX，仅保留 ARM64 原生库及简体中文资源。

## 构建

上传仓库时，将“软件源码”目录中的内容作为仓库根目录，保留 `app/`、`.github/`、`gradle/` 和构建脚本。`app/build.gradle.kts` 定义版本号、依赖和打包规则，必须一同上传。SDK、本机配置、构建缓存和签名密钥不属于源码；忽略规则见 [.gitignore](.gitignore)。

### 本地构建

准备 JDK 27、Android SDK Platform `android-37.2` 和 Build Tools `37.0.0`，接受 SDK 许可。将 `JAVA_HOME` 指向 JDK，通过 `ANDROID_HOME` 或本机 `local.properties` 中的 `sdk.dir` 指定 SDK 位置。

正式密钥文件、密钥库密码、别名和私钥密码由你自行准备和设置。在仓库根目录创建本机 `signing.properties`，按密钥库的实际信息填写，由 Gradle 读取：

```properties
KEYSTORE_FILE=your-release-key.jks
KEYSTORE_PASSWORD=your-store-password
KEY_ALIAS=your-key-alias
KEY_PASSWORD=your-key-password
```

密钥文件路径相对仓库根目录，也可使用绝对路径；Windows 路径使用正斜杠。密钥文件和 `signing.properties` 已由 `.gitignore` 排除，应仅保留在本机。

本地使用常规签名配置：明确指定密钥和别名，分别读取两个密码，不自动生成密钥、选择别名或改写密码。请使用自己的正式签名密钥；发布流程不会回退到默认 Debug 签名。APK 只启用 v3 签名；v1、v2、v4 关闭。

Windows 在仓库根目录执行：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Linux 或 macOS 将 `.\gradlew.bat` 替换为 `bash ./gradlew`，并保持 `gradlew` 脚本使用 LF 换行。Wrapper 会下载指定版本的 Gradle，无需另行安装 Gradle。

签名发布包位于 `app/build/outputs/apk/release/短视频挂机-<版本号>.apk`，当前版本的文件名为 `短视频挂机-0.1.0.apk`。许可证在构建时自动打入 APK，可直接分享这个安装包。

### GitHub Actions 与 Releases

[Build and Release 工作流](.github/workflows/build.yml)只构建签名 Release APK。单元测试、Lint、签名、ZIP 对齐和内置许可证检查全部通过后，自动发布到 GitHub Releases。

仓库 **Settings → Secrets and variables → Actions** 只需配置一项 Secret：

| 名称 | 内容 |
| --- | --- |
| `SIGNING_CONFIG` | 将密钥库 Base64、库密码、别名和私钥密码合并填写在同一段 JSON 中 |

只减少仓库需要创建的 Secret 条目，不减少或代填你的签名信息。Secret 名称填 `SIGNING_CONFIG`，值按下面的格式填写；所有值由你提供，真实配置不提交到源码：

```json
{
  "KEYSTORE": "你的正式密钥库文件的完整 Base64 内容",
  "KEYSTORE_PASSWORD": "你设置的密钥库密码",
  "KEY_ALIAS": "你的签名密钥别名",
  "KEY_PASSWORD": "你设置的私钥密码"
}
```

`KEYSTORE` 填文件的 Base64 内容，其余三项与本地 `signing.properties` 对应。工作流只将你提供的信息还原为临时密钥文件和常规签名配置，屏蔽日志中的敏感字段；Gradle 按指定别名和密码签名，任务结束后清理临时文件。首次配置后，后续发布复用这一项 Secret。

发布有两种触发方式：

1. **手动运行**：进入 **Actions → Build and Release → Run workflow**，选择要发布的分支并运行。工作流根据版本号创建 Release 和对应标签，例如 `0.1.0` 对应 `v0.1.0`。
2. **推送版本标签**：推送与 `versionName` 一致的 `v*` 标签，例如 `v0.1.0`。标签与实际 APK 版本不一致时会停止发布。

成功后，在 **Releases → v0.1.0 → Assets** 中直接下载 `短视频挂机-0.1.0.apk`。工作流上传的附件只有这一个 APK，不生成额外的 APK 压缩包或报告归档；GitHub 自动提供的源码 ZIP、tar.gz 下载链接仍会显示。

普通分支推送和 Pull Request 不触发发布。发布新版本时沿用同一签名密钥，在 `app/build.gradle.kts` 中递增 `versionCode`、更新 `versionName`。已有同名 Release，或同名标签指向其他提交时，发布会失败，不覆盖旧版本。

调整 SDK 或 Build Tools 版本时，同时更新工作流中的 `ANDROID_PLATFORM`、`BUILD_TOOLS_VERSION`。

## 项目结构

| 文件或目录                                   | 中文说明                          |
| --------------------------------------- | ----------------------------- |
| app/                                    | 手机软件的主体                       |
| app/src/main/java/                      | Kotlin 功能代码，包括设置页、悬浮窗和自动滑屏    |
| app/src/main/res/                       | 手机软件使用的图标、文字等资源               |
| app/src/test/                           | 检查功能逻辑的测试代码                   |
| app/build.gradle.kts                    | 应用版本、SDK 要求、依赖、APK 命名和签名规则    |
| design/icon/                            | 哈基伟图标的 SVG 原稿和 PNG 图片         |
| gradle/                                 | 构建工具版本、依赖版本和 Wrapper 文件       |
| gradlew、gradlew.bat                     | 构建启动入口；Windows 使用 gradlew.bat |
| build.gradle.kts、settings.gradle.kts    | 项目的构建配置                       |
| gradle.properties                       | 构建参数，包含 Windows 中文目录设置        |
| .github/workflows/build.yml             | Release 构建、检查及 GitHub Releases 发布         |
| .gitignore | 本机配置、签名材料和构建产物的忽略规则 |
| README.md                               | 本说明文件                         |
| LICENSE                                 | 本项目的 Apache-2.0 开源许可证         |
| app/src/main/resources/META-INF/androidx/NOTICE.txt | 组件说明，随 APK 打包 |

## 许可证

代码与项目自有图标采用 [Apache-2.0](LICENSE)。组件说明见 [NOTICE.txt](app/src/main/resources/META-INF/androidx/NOTICE.txt)。许可证随依赖保留在 APK 原始的 `META-INF/androidx/` 路径：相同的 Apache 许可条款复用一份，Protobuf 许可证单独保留。组件说明也随 APK 打包，分发时只需提供 APK。
