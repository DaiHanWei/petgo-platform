---
title: '兽医待接单卡片增强（P1 第2屏）'
type: 'feature'
created: '2026-06-17'
status: 'done'
baseline_commit: '3ab64c1eeb26f6a2c11fc9a666c68cc33c7d9988'
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/pages/vet-queue.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 工作台首页队列卡（`_InboxCard`）仍是裸行（AI 等级文字 + 症状 + 图数 + chevron）。原型 `vet-queue.html` 规定富卡片：三优先级视觉（绿/黄/**红**）、等待时间、RINGKASAN AI 摘要框、Lewati(跳过)/Lihat Detail 双按钮、RED 紧急强调横幅。

**Approach:** 重做 `_InboxCard`：按 `aiDangerLevel` 上色（GREEN/YELLOW/RED，复用 `triageGreen/Yellow/Red` token）+ 等级标签徽章；等待时间从 `waitingElapsedSeconds` 渲染；RINGKASAN AI 框（`symptomPreview` + 「N 张照片/无照片」，`vetSurface` 底）；Lewati（客户端本地移除该卡）+ Lihat Detail（→ 既有 `/vet/request/:id`）双按钮；RED 加 ⚠️ 紧急横幅 + 强调按钮。DIRECT 无 AI 框只显「Direct request」。mock 待接单加一条 RED 供模拟器验收。

## Boundaries & Constraints

**Always:** 以 `vet-queue.html` 为视觉标准；颜色走 `AppColors` token（`triageGreen/triageYellow/triageRed/vetSurface/vetPrimary`），**禁硬编码 hex**（CLAUDE.md）；新增文案双语进 `app_en.arb`+`app_id.arb`；Lihat Detail 复用既有 `/vet/request/:id`（不新增路由/不碰接单逻辑）。

**Ask First:** 若要展示宠物名/种类/年龄/主人——`VetInboxItem` 无这些字段且后端无契约，本步**不加**；如需，须先扩后端 contract（升级 story）。若 Lewati 需要后端「跳过」语义（当前仅客户端本地移除）。

**Never:** 不造宠物 meta 假数据（无字段就 omit + 记 deferred）；不改抢单/接单/详情页逻辑（仅列表卡视觉 + 客户端跳过）；不加 MQ/缓存/新依赖；不改用户侧。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| YELLOW 卡 | aiDangerLevel=YELLOW | 黄色条/徽章「Perlu Konsul」级标签 + 时间 + RINGKASAN AI 框(症状+N foto) + Lewati/Detail | N/A |
| RED 卡 | aiDangerLevel=RED | 红色强调 + ⚠️ 紧急横幅 + 红强调「Tangani Sekarang/Detail」按钮 | N/A |
| GREEN 卡 | aiDangerLevel=GREEN | 绿色条/徽章 + AI 框 | N/A |
| DIRECT 卡 | source=DIRECT, aiDangerLevel=null | 无 AI 框，只显「Direct request」+ 时间 + Detail | N/A |
| 点 Lewati | 任意卡 | 该卡从列表本地移除（不调后端）；队列计数同步减 | N/A |
| 点 Lihat Detail | 任意卡 | push `/vet/request/:id`（既有），返回刷新 | N/A |
| 无图 | imageCount=0 | AI 框显「tanpa foto / no photo」 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- `_InboxCard` 重做 + `_VetInboxPageState` 加本地跳过集合 `_skipped`
- `petgo_app/lib/features/vet/domain/vet_inbox_item.dart` -- 字段只读，确认无宠物 meta（不改）
- `petgo_app/lib/core/theme/colors.dart` -- `triageGreen/Yellow/Red`、`vetSurface` 已有，复用
- `petgo_app/lib/core/mock/mock_backend.dart` -- 待接单列表加一条 RED 项
- `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- 新文案键（RED 标签/时间/RINGKASAN AI/foto 数/Lewati/紧急横幅/Detail）
- `petgo_app/test/vet/vet_inbox_test.dart` -- 更新覆盖新卡结构 + Lewati 跳过；Detail 改点按钮

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- 新键 9 个（vetAiContextLevelRed/vetQueueWaitingMinutes/WaitingJustNow/AiSummaryTitle/PhotosAttached/NoPhoto/Skip/ViewDetail/UrgentBanner）；`flutter gen-l10n` -- 双语文案
- [x] `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- 重做 `_InboxCard`：等级徽章(三色 triage*)+时间+RINGKASAN AI 框(vetSurface)+Lewati/Detail 双按钮+RED 紧急横幅(triageRed)+红边框；DIRECT 简版；`_VetInboxPageState` 加 `_skipped` Set 过滤、`_skip` 本地移除、`_reload` 清空跳过；队列/分区计数随过滤 -- 还原富卡片
- [x] `petgo_app/lib/core/mock/mock_backend.dart` -- 待接单加 sessionId 8100 RED 项（巧克力中毒，3 图）供模拟器看红态 -- 红态可视
- [x] `petgo_app/test/vet/vet_inbox_test.dart` -- 更新：AC5 测等级徽章+AI SUMMARY 框+照片数、Detail 改点 `vetDetail_` 按钮；新增 RED 紧急横幅测、Lewati 跳过卡消失测、DIRECT 无 AI 框测 -- L0 绿（全量 319 通过）
- [x] 视觉验收 -- 待 `flutter run --dart-define=DEV_ROUTE=/vet/workbench --dart-define=DEV_VET=true` 截图确认绿/黄/红/DIRECT 四态（present 步骤执行）-- 本地 L2

**Acceptance Criteria:**
- Given aiDangerLevel∈{GREEN,YELLOW,RED}，when 渲染卡，then 对应 `triage*` 色 + 等级标签 + RINGKASAN AI 框（症状 + 照片数/无照片）
- Given RED 卡，when 渲染，then 额外显 ⚠️ 紧急横幅 + 红强调按钮
- Given DIRECT 卡，when 渲染，then 无 AI 框，仅「Direct request」+ 时间 + Detail
- Given 点 Lewati，when 返回，then 该卡从列表消失且不发起后端调用
- Given 点 Lihat Detail，when 返回，then 进入 `/vet/request/:id`
- Given `flutter analyze` + `flutter test`，when 执行，then 全绿

## Design Notes

卡片结构（参原型）：顶行 等级徽章(色) + 等待时间(右, textTertiary)；中部 RINGKASAN AI 框（`vetSurface` 底圆角，标题 `vetQueueAiSummaryTitle` 小字 + 症状 body + 照片数 caption）；底行 Lewati(文本/次按钮) + Lihat Detail(主按钮 `vetPrimary`)。RED：卡顶加 `triageRed` 紧急横幅 + Detail 按钮换 `triageRed`。等待时间：`waitingElapsedSeconds<60` → JustNow，否则 `~/60` 分钟。

宠物名/种类/年龄/主人：原型有、`VetInboxItem` 无字段 → 本步 omit，记 deferred（需后端补 contract）。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: All tests passed

## Suggested Review Order

**富卡片（设计入口）**

- `_InboxCard` 重做：等级徽章(三色) + 等待时间 + RINGKASAN AI 框 + 双按钮 + RED 紧急横幅
  [`vet_inbox_page.dart:198`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L198)

- RED 紧急横幅（triageRed 满宽条）
  [`vet_inbox_page.dart:255`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L255)

- RINGKASAN AI 摘要框（vetSurface 底 + 症状 + 照片数/无照片）
  [`vet_inbox_page.dart:299`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L299)

**客户端跳过（Lewati）**

- `_skip` 本地移除 + `_refresh` 显式清跳过（详情返回保留，审查 patch）
  [`vet_inbox_page.dart:44`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L44)

**支撑**

- mock RED 待接单项 8100（含 waiting 池补登，审查 patch 防预览自踢）
  [`mock_backend.dart:47`](../../petgo_app/lib/core/mock/mock_backend.dart#L47)

- 新双语键（RED/时间/RINGKASAN AI/foto/Lewati/紧急）
  [`app_en.arb`](../../petgo_app/lib/l10n/app_en.arb) · [`app_id.arb`](../../petgo_app/lib/l10n/app_id.arb)

- inbox 测试（等级/AI 框/RED 横幅/Lewati 跳过/DIRECT）
  [`vet_inbox_test.dart`](../../petgo_app/test/vet/vet_inbox_test.dart)
