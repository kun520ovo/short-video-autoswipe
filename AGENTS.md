# AGENTS.md

本文件适用于所在目录及子目录，构建命令均在本目录执行。这里记录当前开发约定；任务明确要求调整相关行为时，同步更新实现、测试和文档。

## 项目与代码职责

- 本项目是单 `app` 模块的 Kotlin / Jetpack Compose Android 应用，功能与使用流程见 `README.md`。
- 下述代码包目录位于 `app/src/main/java/com/shortvideo/autoswipe/`，单元测试位于 `app/src/test/java/com/shortvideo/autoswipe/domain/`。
- `domain/` 保存任务状态机、前台判断与手势规划，保持不依赖 Android 框架；系统能力通过 `AutomationPort` 接入。
- `accessibility/` 执行系统手势和窗口检查，`platform/` 负责服务桥接、权限与屏幕几何；系统相关实现放在这些目录。
- `ui/` 和 `overlay/` 共用 `AppContainer.engine` 控制任务，保持单一状态来源；设置持久化由 `data/SettingsRepository.kt` 负责。
- 默认值与参数校验集中在 `model/TaskModels.kt`；修改配置结构时，联动核对 `FormDraft`、`SettingsRepository` 和对应测试，保留已有 DataStore 键的兼容性或提供迁移。

## 任务行为与并发

- 开启权限本身不启动任务。打开目标应用后固定等待 10 秒，之后每轮先停留再上滑；暂停保留进度与剩余等待时间。
- 任务命令和结果更新在引擎的 `dispatcher` 上串行执行。取消 worker 前先使 `generation` 失效，保留异步结果的 token 检查，避免旧回调影响新任务或重新打开已关闭的悬浮窗。
- 等待时长使用注入的 `MonotonicClock`；随机数、时钟与 dispatcher 保持可注入，便于使用虚拟时间验证逻辑。
- 前台应用或可操作区域变化导致的暂停，在确认当前活动目标恢复后可以自动继续；手动暂停、熄屏、锁屏、权限关闭或服务中断需要手动继续。
- 前台信息未知不能唤醒已暂停任务；不要把读不到页面内容直接视为切换了应用。相关规则见 `ForegroundAppTracker.kt` 和 `AutomationEngineTest.kt`。
- 手动继续沿用当前任务进度，操作当前前台应用，不重新启动原应用或改写已保存的启动设置。
- 仅按 `GestureOutcome.Completed` 统计上滑和双击次数；暂停后不重放已部分提交的双击。停止清空本次任务并关闭悬浮窗，正常完成保留结果与悬浮窗。

## 构建与验证

- 当前本地开发仅使用 Windows PowerShell，通过项目自带的 `gradlew.bat` 执行构建与验证。依赖版本以 `gradle/libs.versions.toml` 为准，Gradle 版本见 `gradle/wrapper/gradle-wrapper.properties`。
- AGP 9 已提供 Kotlin 集成。升级 Kotlin 时，同时对齐根 `build.gradle.kts` 中的插件 classpath 与版本目录中的 Kotlin 版本；不要重复应用 `org.jetbrains.kotlin.android`。
- 构建 JDK 与 SDK 配置参考 `.github/workflows/build.yml`；应用 SDK、JVM 字节码目标、版本与打包规则以 `app/build.gradle.kts` 为准。构建运行的 JDK 版本和应用字节码目标是两个独立设置。
- 使用本机配置的 `JAVA_HOME` 和 Android SDK。保留 `gradle.properties` 中的 UTF-8 与 Windows 中文路径设置。

在本目录下，Windows PowerShell 的 Gradle 检查命令为：

```powershell
.\gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon --console=plain --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process"
```

上述 Gradle 任务与 CI 保持一致。当前 CI 仅由 `v*` 标签推送或手动触发，普通分支推送和 PR 不会自动执行检查。

局部逻辑改动可先运行对应测试，例如：

```powershell
.\gradlew.bat :app:testReleaseUnitTest --tests 'com.shortvideo.autoswipe.domain.AutomationEngineTest'
```

- 修复任务逻辑缺陷时，在现有 JUnit Jupiter 测试中补充复现用例；协程测试使用 `kotlinx-coroutines-test` 的虚拟时间。
- 交付代码、资源或构建配置改动前，执行上述完整检查；纯文档改动核对内容、路径和格式即可。
- 权限、悬浮窗和实际手势的改动还需真机或模拟器验证；没有设备时，明确记录未验证项。
- 本地缺少 `signing.properties` 时，Gradle 可以生成带 `-unsigned` 后缀的 Release APK；CI 的签名步骤需要配置好的 `SIGNING_CONFIG` Secret。按当前任务要求区分未签名构建与可交付的签名包。
- 发布新版本时递增 `versionCode`，使标签与 `v<versionName>` 一致；沿用现有应用签名，避免更换密钥导致已安装版本无法覆盖升级。发布产物还需通过 CI 的签名、对齐和许可证校验。

## 数据与仓库文件

- 保持本地处理：不添加网络权限、统计或广告 SDK；无障碍检查只读取必要的前台包名、窗口边界和屏幕区域，不采集或保存页面文字、输入内容与节点树。
- 配置保存在应用私有 DataStore 中；任务进度、计数、自动恢复意图和最新日志只存在内存，不开启系统备份或迁移。
- 遵循 `.gitignore`，不提交本机配置、签名材料、APK 或构建产物；不把签名值写入代码、日志或文档。
- Release 排除 `libdatastore_shared_counter.so` 的前提是使用单进程 DataStore；调整存储模式、依赖或打包排除项时，联动核对 `app/build.gradle.kts`、`app/src/main/resources/META-INF/androidx/NOTICE.txt` 和 CI 的许可证校验。
- 功能、使用流程或构建方式变化时，同步更新 `README.md`；涉及图标或展示素材时，核对 `design/icon/`、应用资源和 `docs/` 中相应文件的一致性。
