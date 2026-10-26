# Etax（Eta 助手·后台版）· 源码版

基于上游 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) `v3.3.3`。
以前的做法是把官方 APK 的 dex 反编译、手工插入调用再打包；现在所有接线都写在源码里，
直接从源码编译出 APK，不再依赖 `dexopt2/classes.dex`。

## 改了什么

| 位置 | 作用 |
|---|---|
| `app/src/main/java/com/eta/shadow/` | 影子代码（通知流式输出、后台虚拟屏工具、观察者、引擎自启 `Engine.java`） |
| `etax/engine/` | 内嵌的 root 引擎（dex + 启动脚本），编译时打进 APK assets，装完授 root 自动拉起 |
| 10 个上游 Kotlin 文件，每处带 `// etax` 注释 | 接线点，对应原来 dex 里的 10 处注入，外加任务开始和结束两个钩子 |
| `app/build.gradle.kts`、`AndroidManifest.xml` | 包名 `io.github.mangi.etax`，显示名「Eta 助手·后台版」 |
| `etax/check_hooks.sh` | 构建前检查 12 处接线都在，少一处就让构建失败 |
| `.github/workflows/etax-build.yml` | 在 GitHub Actions 上编译 APK |

## 编译（GitHub Actions）

1. 在 GitHub 上新建一个**私有**仓库，把整个目录推上去，分支名用 `etax-3.3.3`。
2. 仓库 Settings → Secrets and variables → Actions，添加这 4 个 Secret（用你现在的 `etax.jks`，
   这样新包才能直接覆盖安装、不丢数据）：
   - `ETAX_KEYSTORE_BASE64`：`base64 -w0 etax.jks` 的输出
   - `ETAX_STORE_PASSWORD`：keystore 密码
   - `ETAX_KEY_ALIAS`：别名，用 `keytool -list -keystore etax.jks` 查看
   - `ETAX_KEY_PASSWORD`：key 密码
3. 推送后到 Actions 页面打开 **Etax Build**，跑完后在页面底部下载 APK，大约 15–25 分钟。

不配置 Secret 也能编译，只是会用临时证书签名，装之前要先卸载旧的 etax。

**root 引擎（源码内嵌 + 自启）**：引擎**源码**在 `etax/engine/src/`（`VDrive.java`、`McpEndpoint.java`、
`UiaProbe.java`），加上 `build_engine.sh` 和部署脚本（`vd.sh`、`install_vdrive.sh`、`check_pair.sh`）。
CI 用 `javac + D8` 从源码现编出 `vdrive.dex`（不是 Gradle 模块），版本串从 `VDrive.java` 的 `BUILD`
常量抽取写入 `vdrive.version`，一起打进 APK 的 assets。**不再提交二进制 dex，以源码为准。**

装完 APK 后，无障碍服务一连上、或第一条 `screen_*` 工具调用时，`com.eta.shadow.Engine` 会自动用 `su`
把这些文件释放到 `/data/local/tmp/eta/` 并跑 `vd.sh` 把引擎拉起来 —— **装完授一次 root 就能用，不用再手动部署**。

- 引擎需要 **root**：未 root 的手机 `su` 会失败，`screen_*` 会提示去授权（通知栏流式输出那条链路不吃 root，照常可用）。
- 自启幂等：引擎在跑就不动它（`vd.sh status`）；换了新版（dex 变了）会自动重铺并重启；运行中 build 与内置版本不符也会强制重启到内置版。
- 工具链会影响 dex 字节：CI 用现成 JDK/D8，产物与本地逐字节不一定相同，但功能等价、且 APK 自洽（App 首次运行会把手机上旧 dex 换成 APK 内这版）。想字节级复刻需钉死 JDK/r8/android.jar 版本。
- 升级引擎：改 `etax/engine/src/` 源码并把 `VDrive.java` 的 `BUILD` 常量改成新 stamp，CI 自动现编、配对。
- 仍可手动管理：`sh /data/local/tmp/eta/vd.sh`（重启）、`vd.sh stop`、`vd.sh status`、`check_pair.sh`（配对校验）照常能用。

## 跟上游升级

```bash
git fetch origin tag v3.3.5
git checkout -b etax-3.3.5 v3.3.5
git cherry-pick <本分支的两个 etax 提交>
```

有冲突时 git 会停下来，等你处理完再继续。处理完后跑一遍 `bash etax/check_hooks.sh`，
确认 12 处接线都还在；CI 也会在编译前自动跑这一项检查。

已经试过 `v3.3.4`：`agent/runtime/` 下 3 个文件有冲突，共 5 处，需要手工合并，检查脚本也正确报了错。


## 后台多任务（3.3.4 扩展）

- 电源键入口提交后立即建立在途记录。App 用同一会话身份恢复已有事件并订阅后续事件；完成归档按 run ID 去重。关闭入口不取消任务，新一次唤醒拥有独立会话。
- `BackgroundScreenTools` 为每次运行绑定独立工具实例，`ScreenLeaseRegistry` 保留最多三个屏幕名额。同一应用等待当前所有者释放；不同应用并行。第四个不同应用需要屏时取消最早取得名额的任务，直到引擎确认旧屏清理后才分配新名额。
- `VDrive` 负责本地传输及有限容量的会话表，`VDriveWorker` 保留原引擎的 Android 操作实现。每个 worker 有独立 Handler、显示对象、输入状态、截图目录和清理状态。请求携带不可复用的 `sessionId`，已释放会话拒绝迟到请求。
- 活跃任务每 30 秒续期；App 进程消失后引擎回收过期会话。正常 `screen_finish(mode=notify)` 可继续停留最多五分钟，后续任务优先回收这些已完成的屏。
- 主屏通过系统实时通知（旧 Android 使用 App 胶囊）查看流式进度及打开聊天；暂停与停止作用于选中任务。通知按活动任务聚合，支持系统允许的 promoted ongoing 显示。新频道为静音 DEFAULT 频道；系统通知权限和 ColorOS 状态栏/流体云设置仍由系统管理。
- 边缘流光复用官方组件，亮屏绘制、息屏移除窗口；后台展示不申请保持屏幕常亮。

引擎协议增加 `sessionId`、`keepalive`、`validate`、`release_session`。无会话标识的 MCP 请求继续使用独立的兼容会话。`release_session` 的成功响应代表清理已确认，不能以网络超时推断已释放。设备动作可能已经发生，重连只恢复记录，不自动重放操作。

相关回归测试：`ScreenLeaseRegistryTest`、`AssistantRunRecoveryTest`，以及 Runtime 会话、恢复、通知和浮层测试。虚拟屏输入焦点、后台运行及 ColorOS 流体云表现需要在真实 Root 设备验证。

### 系统流体云与窗口恢复（2026102602）

- Android 16 QPR1 及以上只使用系统实时通知呈现任务，不再叠加 App 自绘胶囊或完成浮窗；边缘流光独立保留。旧 Android 版本继续使用原胶囊。
- 运行通知仍为同一个 ID `1107`。卡片内容跳转所选任务的聊天；多任务时提供切换、暂停/继续、停止当前任务。每个操作绑定不可复用的 run ID，迟到操作不影响后续任务。
- 流式更新同时刷新前台服务持有的通知快照，避免该快照一直停留在启动时的通用文案。单元测试确认只有一个通知记录；ColorOS 是否额外呈现普通通知须由真机复测，不能仅凭截图断言已消除系统层重复。
- `screen_finish` 只负责屏幕收尾，不再额外发布一条打开目标 App 的通知。最后完成的结果在前台服务释放后替换同 ID 的运行通知；已完成状态不继续申请系统实时活动。
- 点击/长按系统胶囊如何展开由 ColorOS 决定；点击其卡片进入聊天。系统可将同一实时通知同时呈现在状态栏及下拉区域，不能通过删除通知来保留其流体云。
- 应用显式移回主屏，或用户从桌面重新打开已停留在虚拟屏的应用时，清除该任务的窗口边界、应用边界、dp 尺寸和密度覆盖，恢复主屏全屏配置。使用 Root WindowContainerTransaction，不改手机全局分辨率、不强制停止第三方应用。ROM 不支持接口时记录失败并尝试清除像素边界；兼容缩放是否消失须由真机验证。
- 新增 `AgentSystemNotificationTest`，覆盖单一通知、聊天跳转不串任务，以及完成通知与前台服务退出的先后顺序。

系统接口参考：[Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)；[ColorOS 流体云操作](https://www.coloros.com/article/A00000075/)。
