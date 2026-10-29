# Eta 后台版构建与分发

## 从源码构建

使用 JDK 25、仓库自带的 Gradle Wrapper、Android SDK `platforms;android-37.0` 和 `build-tools;35.0.0`，并设置 `ANDROID_HOME`。CI 工具版本见 [setup-build](actions/setup-build/action.yml)。

```bash
bash etax/check_hooks.sh
bash etax/embed_engine.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
```

`embed_engine.sh` 从 `etax/engine/src` 和 `etax/shared/src` 编译 Root 引擎，读取源码 BUILD 常量，并将 dex、版本与启动脚本放入 APK assets。这些是生成文件，不提交到 Git。直接运行 Gradle 前必须先执行该步骤；签名构建和 PR 检查共用同一个脚本。

本地自定义工具链可设置 `ANDROID_JAR`、`R8_JAR`、`JAVA`、`JAVAC`。引擎默认编译目录为 `etax/engine/build/`，已忽略。升级引擎源代码时同步更新 `VDrive.java` 中的 BUILD 标识。

## GitHub Actions

- **Etax Build**：推送到 `main`、`etax-*` 分支或手动运行时触发。接线与引擎编译通过后，依次运行全量单元测试、lint、Release 构建、签名及包名检查，最后上传 APK，保留 30 天。
- **PR Check**：检查面向 `main` 的 PR，编译相同的内嵌引擎，运行单元测试、lint 和 Debug 构建；不读取发布签名 Secrets。成功后上传 Debug APK，APK 与失败报告均保留 7 天。外部分支首次运行的审批取决于仓库 Actions 设置。

单元测试先于 lint 执行，减少 CPU 竞争对 UI 时序测试的影响。只有检查通过的构建才进入签名打包。可在仓库分支规则中将 `Lint / Test / Debug Build` 设为必需检查。

## 自行构建与开启息屏执行

1. 将修改后的源码推送到自己的 GitHub 仓库默认分支；Fork 后如 Actions 被禁用，先在 Actions 页面启用。
2. 打开 **Actions → Etax Build → Run workflow**，选择包含修改的分支。无需签名 Secrets 也会生成可安装的临时签名 Release APK。
3. 等待工作流成功，在本次运行页面的 Artifacts 下载 APK。需要登录 GitHub；这不是自动发布到 Releases。
4. 安装后打开 **设置 → 实验性功能 → 息屏继续执行（实验性）**，手动开启。默认关闭，保持原有息屏暂停行为。

开启后，运行中的任务不再因收到息屏广播而自动暂停，息屏时新建任务也不再自动暂停。已暂停的任务不会自动恢复；请先解锁并手动继续，或重新发起任务。关闭开关不打断当前运行，从下一次息屏事件或息屏下新建任务开始恢复原有自动暂停行为。

此功能只移除应用自身的自动暂停策略，不绕过锁屏、不自动解锁或唤醒屏幕，也不保证普通前台 UI 工具能在息屏状态下操作。后台虚拟屏幕仍需要项目已有的 Root 引擎正常工作。系统 Doze、厂商省电策略可能限制 CPU、网络和目标应用；耗电也可能增加，请先用非敏感任务在真机上测试。手动暂停及停止保持有效。

也可手动运行 **PR Check** 下载 Debug APK。Debug 和 Release 使用相同包名，不同签名不能覆盖安装或并存；新 runner 的 Debug 签名也不保证跨次一致。卸载前先备份可导出的设置及数据。

## 签名

在 `Settings → Secrets and variables → Actions` 配置以下四项；不要提交证书或密码：

| Secret | 内容 |
| --- | --- |
| `ETAX_KEYSTORE_BASE64` | 发布 keystore 的 Base64 文本 |
| `ETAX_STORE_PASSWORD` | keystore 密码 |
| `ETAX_KEY_ALIAS` | 签名别名 |
| `ETAX_KEY_PASSWORD` | 对应私钥密码 |

配置了 keystore 但缺少其他项会直接报错。全部未配置时会生成临时证书，仅供自行测试；临时证书每次不同，不能用来覆盖既有安装。维护者分发必须沿用原发布证书，避免用户需要卸载及丢失数据。

CI 将上述 Secrets 映射为 Gradle 读取的 `ETA_RELEASE_STORE_FILE`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`。本地 Release 构建也可提供这四个环境变量后运行 `./gradlew :app:assembleRelease`。

## 版本、历史与下载

`app/build.gradle.kts` 的 `versionCode` 使用 `yyyyMMdd` 加两位序号，只能递增。`versionName` 表示当前上游基础版本；后台版变更由 versionCode、源码提交与 [更新日志](../etax/README.md) 区分。CI 文件名如 `Etax-v3.3.5+etax.<短提交号>.apk`。

历史源码通过分支和提交保留，无需复制整套源码目录。正式分发前更新版本日志，在真机检查通知内回复、双任务、微信截图坐标、横屏游戏及前台接管，再从成功的 Etax Build 取得签名 APK。

Actions 附件需要登录且到期失效。长期下载由维护者在本仓库 Releases 创建版本、选择经过验证的提交并上传 APK，附上版本说明与 SHA-256。当前流程不自动创建或发布 Release；推送标签也不会自动触发构建。发布源码及 APK 时保留 [LICENSE](../LICENSE) 和上游 Required Notice。
