# 通用任务执行结果：实现范围与验证

> 当前是待 Android 集成构建／真机验收的实现，不是系统级可靠定时调度器。不要为安装测试包卸载现版、清数据或更换现版签名。

## 根因与改造

基线是 Fork `main` 提交 `66813f00d4cec9ea80fc2606671858e7efdc067e`（2026-10-29 核对 GitHub API 与本地一致）。

- 后台命令完成回调原来只有描述、退出码，直接发送打开终端的 Intent；所有通知复用同一 PendingIntent 身份。通知 tag 不是 Intent 的执行身份。
- Agent Runtime 另有结果交付 outbox 与外部入口 archive，但 ACK／导入后会删除，不能充当通用任务历史。原完成通知有会话／目标应用入口，仍没有某次执行的持久报告。
- 没有找到通用持久化任务调度器。shell 等待、定期巡检与设备时钟工具不是可靠的 Agent 定时任务调度。

新链路：生产执行结果 → 私有目录持久化不可变执行记录 → 摘要通知 → `TaskRunDetail(runId)`。点击不重新执行任务，不唤醒模型，也不自动重放工具。

关键实现：

- [TaskRunResult.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/tasks/TaskRunResult.kt)：执行与可选任务定义 ID 分离，状态／摘要／报告／附件／诊断引用。
- [TaskRunResultStore.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/tasks/TaskRunResultStore.kt)：每次执行一个 SHA-256 命名 JSON；文件锁、fsync 与原子移动；重复相同结果幂等，冲突拒绝覆盖。历史独立于 ACK 队列。
- [TaskCommandResultReader.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/tasks/TaskCommandResultReader.kt)：显式结构化协议，未提供报告时保守显示未确认，不从 stdout 推测成功。
- [TerminalRuntime.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/terminal/TerminalRuntime.kt)：命令完成先保存再通知；重复回调复用已保存结果。
- [AgentTaskResultDelivery.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentTaskResultDelivery.kt)：保存 Agent 最终正文，不保存思考／工具 stdout 为业务报告；`RunResult.ok` 仅代表本轮完成，状态保守为 Unconfirmed。
- [TaskRunDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tasks/TaskRunDetailScreen.kt)：摘要、时间、Markdown、附件与独立技术日志入口。

## 后台命令如何交付实际结果

`terminal_job start` 会给命令提供 `ETA_TASK_RESULT_PATH` 环境变量。它是本次执行的固定结果文件，不能由模型指定任意本地路径。start 返回 Android 宿主 `result_path`，Linux 命令内部使用环境变量指向的路径。

命令在退出前写入 UTF-8 JSON（整个文件不超过 64 KiB）：

```json
{
  "status": "Partial",
  "summary": "已完成 2 项核查，另 1 项因数据源不可用未完成。",
  "report": "## 已确认\n\n|项目|结果|\n|---|---|\n|A|已核实|\n\n## 数据缺口\n\nB 数据源不可用，不能给出结论。",
  "attachments": [
    {"name": "result.txt", "mimeType": "text/plain", "base64": "aGVsbG8="},
    {"name": "数据来源", "mimeType": "text/html", "url": "https://example.org/"}
  ]
}
```

状态为 `Succeeded`、`Partial`、`Failed`、`Cancelled`、`Unconfirmed`。成功／部分成功是执行方的明确报告，不是系统独立核验；非零退出、超时或中断不会被成功声明覆盖，用户停止优先为取消。无报告、格式错误、附件无效时保守回退，技术日志仍可查看。摘要不自动抓取 stdout，不编造业务结果。

可选 `task_id` 仅是用户指定的关联标识，不提供权限。原会话 ID 从 Runtime 入口传给命令控制器，不接受模型指定另一个会话作为权威归属。

## 内容类型与隐私

- 原生 Compose Markdown：段落、列表、代码、表格与允许的链接；不使用 WebView，不运行 HTML／JavaScript。远程 Markdown 图片不自动拉取。
- 附件支持 HTTPS 引用，用户明确点击后用外部应用打开；远程资源可能失效，不承诺内容永久有效。
- base64 小附件每项解码最多 40 KiB，受 64 KiB 整体报告预算限制；复制成私有、内容寻址的持久文件。PNG/JPEG/WebP 可缩采样预览，其余类型展示元数据并可通过系统文档选择器导出。
- 私有附件 API 单个文件上限 16 MiB，只供可信调用方从批准目录导入；命令不能让系统读取任意路径。附件丢失或校验失败只影响附件，不隐藏整份报告。
- 通知保持 PRIVATE，公开锁屏版本只显示通用结束提示；不自动打开通知权限、声音或绕过勿扰。已允许显示敏感内容的系统设置仍由用户控制。
- 通知 URI 为 `eta-task://run/<runId>`，不同执行身份不同。冷启动、onNewIntent 与保存／恢复通过现有 launch request 链路传 ID；已消费请求不重放。

## 原会话写回与未完成范围

Agent 本轮结果继续沿用已有 outbox／ACK 写回原会话。新报告独立保存，ACK 不删除报告。

后台命令保存可信来源会话关联，并通过独立持久 outbox 在 App 进入／恢复、完成事件或原运行结束后写入原会话：稳定消息 ID 避免重复，保存会话后 ACK；正在生成、编辑或删除的会话暂缓，已删除会话不重建。回执仅用于显示，不覆盖已推进的模型上下文／草稿，也不自动续跑模型。回执包含摘要和最多 6000 字符报告，附件与完整正文仍从完成通知进入详情。旧命令不会凭空生成历史业务报告，需新提交并遵守协议。

当前没有任务历史列表／清理 UI、跨设备同步、任务定义管理或可靠周期调度。结果目录暂不自动过期，避免通知突然指向最近结果；需要后续受控保留策略以避免长期积累。

## 调度与执行保障边界

- 前台服务与监控线程尽力维持任务；不是 CPU 唤醒锁，也不保证 Doze 中网络可用。
- App 再打开后可按记录认领／核对存活命令。监控不运行时，完成通知和超时处理可能延迟。
- 手机重启不恢复 shell 执行；强制停止、系统回收、Root 失效可能中断任务。当前不重新执行有副作用任务。
- 无网络、重试、精确闹钟权限、重启后触发与周期幂等，需要独立调度子项目；本改造不声称准时执行。
- 通知回调仍是尽力／至少一次，固定 tag 避免重复卡片；崩溃或用户关闭通知后不保证恰好一次可见。没有新建持久通知投递队列。

## 已执行与待执行验证

已在本机用 JDK 21、缓存 Kotlin 1.9.24 编译器、独立 JSON/JUnit 依赖执行核心模型／存储／结果协议测试，使用 Android Context 和环境枚举的最小替身。这不是 APK 编译：

```text
JUnit version 4.13.2
OK (27 tests)
PASS: durable reopen, immutable retry, execution isolation, report survives missing attachment,
      conservative command outcomes, unsafe links
```

覆盖重建读取、不同执行隔离、不可变重试、写失败、损坏／截断／错误身份、并发、私有附件拷贝与源文件删除、附件篡改降级、无报告与退出码保守处理、取消／失败和安全附件协议，以及会话 outbox 重建、重复入队、所有者冲突、腐败与 ACK 隔离。实际恢复调用共用的策略测试覆盖忙碌后解除、错误归属／执行 ID、已删除会话、保存失败保留队列与成功后 ACK 顺序；这些仍不是完整 Activity／SQLite 恢复集成测试。

新增或更新了通知、冷／热启动请求、请求消费重建、路由序列化、Agent 结果适配等 Android 测试，但**尚未执行完整 Gradle／Robolectric／Lint／APK 构建**：本机只有 JDK 21、Android 33–35，所需 JDK 25、Android 37 与完整 Gradle 9.7.1 不齐。子任务尝试 `--offline` 时 Wrapper 仍尝试获取分发，已取消，不能当测试通过。

待 CI 与真机检查：保存失败时不发结果就绪通知、冷启动点两条不同执行通知、同任务不同日期、进程重建、表格与图片附件、导出、锁屏隐藏、Root／日志失效、通知权限关闭、磁盘满、取消／部分失败、长报告与清理降级。不能把未执行的测试代码当作已验证行为。

## 构建与安装

使用现有 Etax Build Actions：JDK 25、Android 37、内嵌引擎、单元测试、Lint、Release 构建与签名。修改仍在本地，未经确认不要自动提交或推送。

当前手机上已安装的 `Etax-tempkey.apk` 来自之前 CI 的一次性临时签名。后续重新构建生成另一个临时证书，不能保证覆盖安装；那个临时私钥若没有保存，无法凭同包名重建相同签名。不要因此卸载现版／清数据。

可选方案：先配置长期固定签名供未来升级，或经用户同意使用独立测试包名并行安装；独立包名也需核对模块／引擎包名接线，不能只改 applicationId 就声称兼容。现阶段先在 CI 验证，再确定不破坏现有安装的测试方案。
