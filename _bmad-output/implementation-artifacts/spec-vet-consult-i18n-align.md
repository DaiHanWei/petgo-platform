---
title: '兽医问诊流 i18n 收口 + 测试修复 + 首次问诊推送接线（④）'
type: 'chore'
created: '2026-06-16'
baseline_commit: 'f571d1dd3d4e2d979c865d2dd1d9ae206077a290'
status: 'done'
context:
  - '{project-root}/_bmad-output/core-pages-UX.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** ④ 兽医问诊流的屏与功能大多已对齐（入口/等待/超时弹层/对话/评分/中断态/兽医工作台全套）。剩三类小缺口：① IM 输入框 hint(`'Tulis pesan...'`) 与兽医历史终态标签(`'Terputus'/'Selesai'`) 硬编码印尼语；② `vet_chat_test.dart` 2 条用例断言旧种子串（大小写/已不存在的 `'foto anabul'`），baseline 即失败；③ 「首次问诊完成」推送时机 `maybeRequestAfterFirstConsult` **从未在生产代码接线**（① 评审遗留债）。

**Approach:** 输入框 hint 迁新键 `imInputHint`；历史终态**复用**既有 `terminalInterrupted`/`terminalClosed`；修 `vet_chat_test` 断言对齐当前真实种子；在 `consult_conversation_page` 会话转 CLOSED 时调既有 `PushPermissionGate.maybeRequestAfterFirstConsult`（接 ① 的 P-09 sheet，gate 自守仅一次）。种子对话属 demo 占位内容、不迁。

## Boundaries & Constraints

**Always:** 新增键 en+id 成对 ≤1 emoji；历史终态复用既有键（DRY）；推送沿用 `PushPermissionGate` 既有语义（绝不首启、双时机取最早、仅一次、拒绝不再弹）；涉及断言的测试用 `locale('id')`+delegates，arb id 逐字一致。

**Ask First:** 无。

**Never:** 不动后端契约 / 主题 / 安全护栏（红态锁、零 CTA、超时弹层、中断态逻辑均不碰）；**不建 P-25 存档确认页 / V-08 诊断表单**（P-25 后端 `/archive-decisions` 虽有但本条延后；V-08 无后端契约——均记 deferred）；种子对话 demo 文案不迁；不碰保留的 petgo 标识。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 输入框文案 | locale=id / en | hint 显示 `Tulis pesan...` / `Write a message...`（来自 arb） | N/A |
| 兽医历史终态 | terminalState=INTERRUPTED / 其他 | 标签 `Terputus`(`terminalInterrupted`) / `Selesai`(`terminalClosed`) | N/A |
| 首次问诊完成 | 会话转 CLOSED & `pushPermissionAsked=false` | 触发 `maybeRequestAfterFirstConsult` → P-09 前置 sheet → 系统权限 | 闸门异常静默、不阻断问诊完成 |
| 已问过 / 再次 | `pushPermissionAsked=true` 或本页已触发 | 不重复弹（gate 自守 + 本页 `_firstConsultPushTried` 守） | N/A |

</frozen-after-approval>

## Code Map

- `lib/features/consult/presentation/im_chat_placeholder.dart` -- `_InputBar` 输入框 hint(L277)→`l10n.imInputHint`（`_InputBar.build` 加 l10n）；种子对话(L53-76) demo 不动
- `lib/features/vet/presentation/vet_history_page.dart` -- `_HistoryTile` 终态标签(L~92)→复用 `terminalInterrupted/terminalClosed`（其 build 加 l10n）
- `lib/features/consult/presentation/consult_conversation_page.dart` -- `_tick` CLOSED 分支(L85) + `_openRating`(L98-102) 转 CLOSED 后触发推送；新增 `_firstConsultPushTried` 守 + helper；import `push_permission_providers`
- `lib/l10n/app_en.arb` / `app_id.arb` -- 新增 `imInputHint`
- `test/consult/vet_chat_test.dart` -- 修 2 条断言对齐当前种子 + `host()` 补 l10n delegates/locale('id') + 发送后刷足帧

## Tasks & Acceptance

**Execution:**
- [x] `lib/features/consult/presentation/im_chat_placeholder.dart` -- `_InputBar.build` 加 `final l10n = AppLocalizations.of(context)`，`hintText` → `l10n.imInputHint`；import app_localizations；种子对话不动
- [x] `lib/features/vet/presentation/vet_history_page.dart` -- `_HistoryTile.build` 加 l10n，`'Terputus'/'Selesai'` → `interrupted ? l10n.terminalInterrupted : l10n.terminalClosed`
- [x] `lib/features/consult/presentation/consult_conversation_page.dart` -- 加 `bool _firstConsultPushTried=false` + `Future<void> _maybeTriggerFirstConsultPush()`（守 + `await ref.read(pushPermissionGateProvider.future)` → `maybeRequestAfterFirstConsult(firstConsultDone:true)`，try/catch 静默）；在 `_tick` 检测 `CLOSED` 与 `_openRating` 转 CLOSED 后各调一次；import `../../notify/data/push_permission_providers.dart`
- [x] `lib/l10n/app_en.arb` + `app_id.arb` -- 增 `imInputHint`（id `"Tulis pesan..."` / en `"Write a message..."`）；en/id 对齐；`flutter gen-l10n`
- [x] `test/consult/vet_chat_test.dart` -- `host()` 补 `locale('id')` + `AppLocalizations` delegates；L31 `'puasakan makanan'`→`'Puasakan makanan'`、L32 `find.text('foto anabul')`→`find.text('Oyen tadi malam')`（照片 caption）；发送后 `pump` 足够帧让新气泡进视口；2 条用例转绿

**Acceptance Criteria:**
- Given locale=id, when 打开对话, then 输入框 hint 为 `Tulis pesan...`（来自 arb）；locale=en 为 `Write a message...`
- Given 兽医历史项, when 渲染, then 终态标签走 `terminalInterrupted`/`terminalClosed`（源码无硬编码 `'Terputus'/'Selesai'`）
- Given 首次问诊会话转 CLOSED 且未问过权限, when 落地, then 触发推送闸门（P-09 sheet）；`pushPermissionAsked=true` 时不再弹
- Given `flutter analyze`/`test`, when 执行, then L0 全绿——含 `vet_chat_test` 2 条由红转绿、microcopy J2/J3

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 无错误
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test test/consult test/vet test/l10n` -- expected: 全绿（vet_chat_test 2 条转绿）
- `cd petgo_app && grep -nE "'Tulis pesan|'Terputus'|'Selesai'" lib/features/consult/presentation/im_chat_placeholder.dart lib/features/vet/presentation/vet_history_page.dart` -- expected: 无输出

**Manual checks (L2 本地，云端跳过):**
- 模拟器：完成一次问诊（首次）→ 弹 P-09 通知权限前置 sheet；id/en 输入框 hint 正确

## Suggested Review Order

- 入口（唯一行为改动）：首次问诊完成→推送闸门接线（守 + gate 自守 + try/catch；CLOSED 两路径）
  [`consult_conversation_page.dart:88`](../../petgo_app/lib/features/consult/presentation/consult_conversation_page.dart#L88)
- IM 输入框 hint 走 arb
  [`im_chat_placeholder.dart`](../../petgo_app/lib/features/consult/presentation/im_chat_placeholder.dart)
- 兽医历史终态复用 terminalInterrupted/Closed
  [`vet_history_page.dart`](../../petgo_app/lib/features/vet/presentation/vet_history_page.dart)
- 测试修复：vet_chat 2 条转绿（断言对齐种子 + 视口加高 + host 补 l10n）
  [`vet_chat_test.dart`](../../petgo_app/test/consult/vet_chat_test.dart)
