# Eta 后台版 — Eta Background Edition

[简体中文](README.md) · [English](README_EN.md)

A derivative branch of **[Eta](https://github.com/Mangi-11/Eta), created by [蛮吉 / Mangi-11](https://github.com/Mangi-11)**, based on upstream v3.3.5. This repository maintains background virtual-display automation and related task, notification and lifecycle extensions. It is an independent distribution. The internal project name remains `Etax`; its Android package is `io.github.mangi.etax`.

## What this branch adds

- Foreground tools for the current page; virtual displays for explicitly requested background work or continuation.
- Root-backed virtual displays with per-task screen ownership, observations and input state.
- Two executing tasks and at most two active or retained displays; bounded queuing and serialized access to the same app.
- Live conversation recovery after leaving the power-button assistant sheet.
- Separate native task notifications, streaming progress, pause/stop controls and in-place result updates. Compatible systems can show them as Live Updates / Fluid Cloud.
- Inline notification replies to continue the original conversation, plus a separate action to open the ETA conversation.
- Persisted execution reports: summary notifications open the exact run detail, with Markdown, attachments and separate diagnostic logs; idempotent receipts reach the original conversation.
- Shared display geometry validation, landscape capture, screenshot-coordinate mapping and structured rejection diagnostics.
- Assistant sheet transitions, optional edge lighting in Appearance settings and conversation reading-position restoration.

Upstream v3.3.5 Subagents, Secondary Model selection, progress details, tool summaries and Debian recovery are included. Subagents cannot borrow their parent's background display tools. Screen-off pauses execution by default; an opt-in experimental continuation setting is available without auto-unlock or a permanent wake lock. Doze and ROM restrictions still apply. Earlier three-display policies remain superseded.

## Requirements and limitations

Android 13+, ARM64. Background virtual displays require Root and the accessibility service. Configure your own model provider. Device feedback primarily comes from OPPO Find X9 Ultra / ColorOS 17; notification presentation and cross-display behavior depend on the ROM.

Retained and active displays share the two-display capacity. Retained pages can be reclaimed for new work. Android memory pressure, cross-display configuration changes or the target app itself may still recreate an activity; seamless game handoff is not guaranteed. ETA does not force an app restart merely to remove letterboxing.

## Source, builds and history

This repository directly forks Mangi-11/Eta. `main` tracks the official source; [`etax-main`](https://github.com/zhuzhuani/Eta/tree/etax-main) contains the complete customized background edition. The [previous repository](https://github.com/zhuzhuani/eta-background) is retained as a backup. See [upstream maintenance](etax/UPSTREAM_SYNC.md).

See the [version history and source map](etax/README.md) and [build/signing instructions](.github/RELEASING.md) (Chinese). Download APK artifacts from successful [Etax Build runs](https://github.com/zhuzhuani/Eta/actions/workflows/etax-build.yml). Artifacts require a GitHub login and expire; maintainers can publish permanent downloads through this repository's Releases. Upstream Eta APKs do not contain this branch's additions.

Application source lives in `app/`, background bridges in `app/src/main/java/com/eta/shadow/`, the Root engine in `etax/engine/`, and shared geometry in `etax/shared/`. Regression tests in `app/src/test/` remain part of the build.

## Attribution and license

Credit to **蛮吉 (Mangi-11)** and the [Eta contributors](https://github.com/Mangi-11/Eta/graphs/contributors). Upstream: [Mangi-11/Eta](https://github.com/Mangi-11/Eta). Original background extensions: [Lmin2419](https://github.com/Lmin2419). This customized branch: [zhuzhuani/Eta](https://github.com/zhuzhuani/Eta/tree/etax-main).

This project retains **[PolyForm Noncommercial 1.0.0](LICENSE)** and the upstream copyright notice. It is source-available under a noncommercial license, not MIT/Apache or OSI-defined open source. Third-party components retain their own licenses.

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).
