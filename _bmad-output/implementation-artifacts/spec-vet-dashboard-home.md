---
title: '兽医工作台首页 dashboard 还原（P1·共享深色顶栏 + 统计卡）'
type: 'feature'
created: '2026-06-17'
status: 'done'
baseline_commit: 'aae69aa53ebc3f43cb4de1205644329c29858c9d'
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/fidelity-audit.md'
  - '{project-root}/_bmad-output/pages/vet-dashboard.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** app 兽医端第一 tab（`VetInboxPage` 待接单）只有标准 `AppBar` + 裸列表；原型 V-01 dashboard（`vet-dashboard.html`）规定为：深色顶栏 `#2B2540`（问候 + 医生名 + 在线切换）+ 今日 3 统计卡（队列/完成/评分）+「ANTRIAN SEKARANG (n)」当前队列分区。且 vet_me 的在线态用本地 `_online`、与顶栏切换会双源漂移。

**Approach:** ① 建可复用 `VetTopBar` 深色顶栏 widget（供后续 vet 屏复用）；② 建共享 `vetOnlineStatusProvider`（读 `readOnlineStatus`/写 `setOnline`，乐观更新+失败回滚）作单一事实源，顶栏与 vet_me 共用；③ `VetInboxPage` 换上 `VetTopBar`、补 3 统计卡与分区头。统计值只用真实可得：队列=待接单数、完成=今日 history 数、评分**无端点 → 占位「—」**（不造假）。队列卡视觉增强留下一单元。

## Boundaries & Constraints

**Always:** 以 `pages/vet-dashboard.html` 为视觉唯一标准；颜色走 `AppColors.vet*` token（`vetTopBar #2B2540`/`vetPrimary #5BCBBB`/`vetSurface #EFF7F4`），**禁硬编码 hex**（CLAUDE.md）；在线切换乐观更新 + 失败回滚 + 复用现有 `setOnline`；统计值只展示真实数据源，缺源用占位「—」。

**Ask First:** 若需新增后端端点（完成数/评分统计）才能填卡——本单元不加端点，缺的用占位；若 vet_me 重构在线态影响其心跳/生命周期逻辑超出「换数据源」范围。

**Never:** 不做队列卡视觉增强（AI 摘要框/Lewati·Detail 双按钮/宠物 meta，留下一单元）；不做 3 态状态弹窗（Online/Sibuk/Offline，⚫deferred，本步只布尔在线）；不加 MQ/缓存/新依赖；不改用户侧；不造 rating 假值。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 进 dashboard | role=VET，已登录 | 深色顶栏显示「Selamat pagi, {displayName}」+ 在线开关；3 统计卡：队列=列表数、完成=今日数、评分「—」 | me()/列表失败 → 顶栏名占位、统计「—」、列表区错误态 |
| 切在线开关 | 点 toggle | 乐观翻转 + 调 `setOnline`；成功保持，失败回滚并提示 | 失败 SnackBar + 回滚 |
| 队列为空 | waitingList 空 | 统计「队列 0」+ 分区头「ANTRIAN SEKARANG (0)」+ 现有空态 | N/A |
| vet_me 在线态 | 在另一屏切换 | 与顶栏同源（共享 provider），不漂移 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- 待接单/dashboard 首屏；换顶栏 + 加统计卡 + 分区头
- `petgo_app/lib/features/vet/presentation/vet_me_page.dart` -- 现持本地 `_online`；改用共享 provider 消除双源
- `petgo_app/lib/features/vet/data/vet_repository.dart` -- 已有 `readOnlineStatus`/`setOnline`/`me`/`waitingList`/`history`，复用
- `petgo_app/lib/core/theme/colors.dart` -- vet token（已在上一单元落地）
- `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- 新文案键（问候/统计卡标题/评分占位）双语
- `petgo_app/test/vet/` -- 新增 widget 测试

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/features/vet/domain/vet_online_status.dart`（新建）-- `Notifier<bool>`（手写风格同 auth_state）：`build` 自动 `readOnlineStatus` 置态、`toggle` 乐观+`setOnline`+IM 联动+失败回滚 rethrow，作在线态单一事实源 -- 消除顶栏与 vet_me 双源（路径置于 domain/，符合项目约定）
- [x] `petgo_app/lib/features/vet/presentation/widgets/vet_top_bar.dart`（新建）-- 可复用深色顶栏：`vetTopBar #2B2540` 底 + 时段问候 + 医生名 + 在线开关（接 provider，错误回滚提示）；参数化 `greetingName`/`title`/`showOnlineToggle`，供后续 vet 屏复用 -- 共享 chrome
- [x] `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- 用 `VetTopBar` 替换 `AppBar`；加 3 统计卡（队列=`waitingList` 数、完成=`history` 数、评分占位「—」）+「ANTRIAN SEKARANG (n)」分区头（含刷新）；保留 `_InboxCard` 列表与空态 -- 还原 dashboard 骨架
- [x] `petgo_app/lib/features/vet/presentation/vet_me_page.dart` -- 在线态本地 `_online` → 共享 provider；心跳改由 `ref.listen(provider)` 跟随、IM 移交 provider，保留生命周期/登出 -- 单一事实源（行为保持：初始在线只起心跳不登 IM）
- [x] `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- 新键：统计卡三标题(Queue/Done/Rating·Antrian/Selesai/Rating)、分区头(带 count)；问候复用现有 `greetingMorning/...`；`flutter gen-l10n` -- 双语
- [x] `petgo_app/test/vet/vet_dashboard_test.dart`（新建，4 例）-- 测：顶栏医生名+开关、3 统计卡数值（队列/完成/占位—）、空队列分区头(0)+空态、切开关乐观更新写 setOnline -- L0 绿（vet 18 + 全量 317 通过）

**Acceptance Criteria:**
- Given role=VET 进 `/vet/workbench` 首 tab，when 渲染，then 见 `#2B2540` 深色顶栏（问候+医生名+在线开关）+ 3 统计卡 + 「ANTRIAN SEKARANG (n)」分区头
- Given 点在线开关且 `setOnline` 失败，when 返回，then 开关回滚原态 + 错误提示
- Given vet_me 与 dashboard 顶栏，when 任一处切在线，then 另一处反映同一状态（共享 provider）
- Given 无评分端点，when 渲染评分卡，then 显示占位「—」而非假值
- Given `flutter analyze` + `flutter test`，when 执行，then 全绿

## Design Notes

顶栏布局参考原型：深底 `vetTopBar`，左「Selamat pagi,」小字 + 医生名粗体，右在线开关（开=`vetPrimary` 薄荷）。统计卡 3 列等宽，白卡 `surface` + `vetSurface` 点缀，数字大 + 标题小。分区头「ANTRIAN SEKARANG (n)」n=队列数。队列卡本单元不改（下一单元增强）。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: All tests passed

## Suggested Review Order

**在线态单一事实源（设计意图入口）**

- provider toggle：乐观更新 + setOnline + IM 联动 + 回滚 + 在途锁（审查 patch 防并发）
  [`vet_online_status.dart:34`](../../petgo_app/lib/features/vet/domain/vet_online_status.dart#L34)

- vet_me 改消费 provider：心跳由 `ref.listen` 跟随、保留生命周期
  [`vet_me_page.dart:115`](../../petgo_app/lib/features/vet/presentation/vet_me_page.dart#L115)

**共享深色顶栏**

- `VetTopBar`：`#2B2540` 底 + 问候/医生名 + 在线开关，参数化供后续 vet 屏复用
  [`vet_top_bar.dart:15`](../../petgo_app/lib/features/vet/presentation/widgets/vet_top_bar.dart#L15)

**dashboard 骨架**

- VetInboxPage 换顶栏 + 统计卡 + 分区头
  [`vet_inbox_page.dart:69`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L69)

- 3 统计卡：队列/完成（真实）+ 评分占位「—」（无端点不造假）
  [`vet_inbox_page.dart:127`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L127)

**支撑**

- 双语键（统计卡标题 + 分区头）
  [`app_en.arb`](../../petgo_app/lib/l10n/app_en.arb) · [`app_id.arb`](../../petgo_app/lib/l10n/app_id.arb)

- dashboard widget 测试（4 例）
  [`vet_dashboard_test.dart`](../../petgo_app/test/vet/vet_dashboard_test.dart)
