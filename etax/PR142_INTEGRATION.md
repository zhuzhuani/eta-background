# Eta 后台版 (Etax) - PR 142 虚拟屏预览与持久任务调度说明

本文档记录了在 `etax-experimental-preview-scheduler` 分支中整合 Mangi-11/Eta 官方 PR #142 核心特性的技术方案、架构调整及使用指南。

## 1. 核心整合特性

### (1) 虚拟屏只读浮动预览与专用预览界面
- **实现文件**：
  - `app/src/main/kotlin/io/github/mangi/eta/agent/display/EtaxPreviewRegistry.kt`
  - `app/src/main/kotlin/io/github/mangi/eta/agent/display/EtaxFloatingPreviewHost.kt`
  - `app/src/main/kotlin/io/github/mangi/eta/ui/screens/tasks/EtaxPreviewScreen.kt`
- **特性说明**：
  - 提供安全、只读的后台虚拟屏画面抓取与展示能力，通过 `previewFrame()` 获取缩略图，避免直接操纵触控或触发心跳重置。
  - 支持悬浮窗实时预览（带折叠、拖动和关闭），并在锁屏或离开页面时自动暂停抓帧，兼顾隐私与省电。

### (2) 无障碍窗口稳定性改进与窗口策略
- **实现文件**：
  - `app/src/main/java/com/eta/shadow/Boot.java`
  - `app/src/test/java/com/eta/shadow/BootObserverPolicyTest.java`
- **特性说明**：
  - 针对高版本 Android（如 Android 14+ / vivo OriginOS）上偶发的无障碍窗口树空载、多显示屏挂载及悬浮窗干扰进行了加固。
  - 引入了 transient empty-tree 证据策略：空树需要经过多次间隔观察确认，而不是立即断开或抛出 `DISPLAY_NOT_FOUND` 错误，大幅提升了息屏和后台自动化任务的连接稳定性。

### (3) 持久定时/事件任务调度引擎
- **实现文件**：
  - `app/src/main/kotlin/io/github/mangi/eta/agent/automation/`
  - `app/src/main/kotlin/io/github/mangi/eta/data/db/AgentTaskDao.kt`
  - `app/src/main/kotlin/io/github/mangi/eta/data/db/AgentTaskEntities.kt`
  - `EtaDatabase.kt` (Migration 23 → 24)
- **特性说明**：
  - 支持 `once`（单次）、`interval`（间隔）、`daily`（每日）以及 **14 种系统事件触发器**（屏幕亮灭、解锁、充电、网络状态、蓝牙、耳机插拔等）。
  - 使用 Room 数据库持久化存储任务与运行队列，结合 `AlarmManager` 和 `JobScheduler` 在后台安全调度。
  - 配合 `AutomationToolPolicy` 严格限制了自动化任务对敏感工具（如递归任务管理、文件覆写、主屏 GUI 强控）的调用权限，防止模型提示词注入引发风险。

---

## 2. 构建与签名保障

- **独立包名**：`io.github.mangi.etax`（与官方原版 Eta 共存，不冲突）。
- **长期固定签名**：
  - 配置了长期 RSA 3072 签名密钥库与 GitHub Actions Secrets。
  - 生成的 APK 签名指纹与本地备份保持一致（SHA-256: `F43EB676...`），支持无缝覆盖升级。

## 3. 分支状态与后续建议

- 当前实验分支已推送到远程：`etax-experimental-preview-scheduler`。
- 如果官方未来继续合并类似特性，由于采用了模块化隔离设计，你可以直接在远程分支通过 Git Rebase 或 Merge 同步 upstream 变更。
