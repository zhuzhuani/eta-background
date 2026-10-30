# 原作者同步与后台定制分支维护

## 仓库及分支

- 上游：[Mangi-11/Eta](https://github.com/Mangi-11/Eta)，官方源码。
- 直接 Fork：[zhuzhuani/Eta](https://github.com/zhuzhuani/Eta)。`main` 跟踪官方；`etax-main` 是后台定制版。
- 迁移前备份：[zhuzhuani/eta-background](https://github.com/zhuzhuani/eta-background)，保留原历史和构建记录，不继续作为主要开发入口。

此分支保留 Lmin2419 的 Root 虚拟屏、双任务、通知和几何处理，也保留息屏实验开关、通用执行报告、原会话回执及归档恢复修复。非官方发行版，继续保留上游及后台扩展归属和许可证。

迁移基线：定制版 `67dffac`，官方 `3019c43`，共同祖先 `b322d98`。采用 merge 保留两边历史，不执行覆盖式快照替换或无共同历史强并。上游的部分子智能体和通知能力已经提前存在于定制版，所以按行为核对而不是重新引入单任务通知架构。

## 下载与安装

**后台版必须选择 `etax-main` 的 Etax Build 产物。** `main` 是原作者版本，包名、签名变量和构建流程不等同于本定制版。

定制版保持包名 `io.github.mangi.etax`；此次 versionCode 增加为 `2026103001`，之后发布只增不减。沿用同一长期签名，新的四个 ETAX Secrets 已从本机既有备份加密配置，密钥未放入 Git。

长期证书 SHA-256（公开指纹，不是密钥）：

```text
F43EB676A6027BB30E62F204D6E1C5ED7C054C2F8D1176343E529508B25602A3
```

已安装同长期签名版时，兼容的数据迁移和非递减版本允许覆盖升级；旧临时签名版仍不能直接覆盖，不要因此卸载或清数据。换 GitHub 仓库不要求更换 Android 包名或证书。

## 本机远程命名

现有工作目录继续使用，不重命名以免破坏工具路径：

- `origin`：旧备份仓库。
- `fork`：新的 `zhuzhuani/Eta`。
- `upstream`：官方 `Mangi-11/Eta`。

如果重新 clone 新仓库，通常 `origin` 指新 Fork。下面示例使用现有命名，执行前先确认 `git remote -v`，不要机械照搬目标名。

## 同步官方 main

保持干净工作区；先检查官方是否存在重写历史，不强制推送：

```bash
git fetch upstream main
git push fork upstream/main:main
```

仅当新仓库 `main` 没有本地定制提交且能快进时使用。网页 Sync fork 也只用于官方跟踪分支；不要使用 Discard commits 覆盖 `etax-main`。

## 合并到定制分支

```bash
git switch etax-main
git fetch upstream main
git switch -c etax-sync-next
git merge --no-ff upstream/main
```

解决冲突、保留功能并运行测试后：

```bash
git push fork etax-sync-next
```

该 `etax-*` 分支触发 Etax Build。通过后用 PR 合并到 `etax-main`，或经维护者确认后快进。集成分支名示例复用前先确认不存在；不要为更新强制清理用户工作。

重点检查：

1. `applicationId`、模块类路径、引擎客户端包名及证书保持一致。
2. 两任务／两虚拟屏租约、同应用排队、清理 ACK、不盲重放输入。
3. 子智能体继承取消／暂停／恢复，不能借用父任务后台屏幕。
4. 息屏实验默认关闭；不自动解锁或增加永久唤醒锁。
5. 报告先持久化再通知，点击精确 runId，技术日志分离，原会话 ACK 顺序。
6. 数据库／存储迁移、通知回复、冷启动、并发归档导入。
7. versionCode 递增，Secrets 仍指向同一签名备份。

## 自动化边界

不自动把上游提交推到可安装定制版。GitHub fork 关系方便发现和获取更新，不保证无冲突；必须由集成分支 CI 验证。当前提供清晰的手工 merge/PR 流程，未设置定时自动合并或自动发布 APK。

Etax Build 仅 contents:read，通过 push 的 etax-* 分支执行测试、Lint、Release、签名及包名／内嵌引擎校验。自定义 workflow 位于非默认分支时 GitHub 页面手动入口可能不可见，推送本分支仍能触发；不要为了显示入口把定制代码塞进官方 main。
