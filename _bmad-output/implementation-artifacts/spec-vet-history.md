---
title: '兽医端第六屏：历史页 1:1 还原（vet-history.html）'
type: 'feature'
created: '2026-06-18'
status: 'done'
baseline_commit: 'a58fb9b'
context:
  - '{project-root}/_bmad-output/pages/vet-history.html'
  - '{project-root}/_bmad-output/fidelity-audit.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 兽医「历史」Tab `vet_history_page.dart` 当前是标准 AppBar + 朴素卡（petName/date/summary/terminal 徽章/星）。审计 🟢 但缺原型 `vet-history.html` 的深色顶栏 #2B2540 + 今日总数、4 个筛选 Chip（Semua / ⭐4-5 / 🟡 Perlu Konsul / 🔴 Darurat）、以及更丰富的记录卡（种类头像 + 「名·@主人」+ 评价引用 + 等级徽章 + Selesai 徽章 + Lihat）。

**Approach:** 按原型 1:1 重排呈现层：深顶栏「Riwayat Konsultasi」+ 今日总数 + 横向筛选 Chip（客户端纯前端过滤，不调后端）+ 升级记录卡。给 `VetHistoryEntry` 补 nullable `dangerLevel/ownerHandle/petSpecies/reviewText`（Mock 先做满、后端随后补，同会话/队列决策），缺失则卡片对应段优雅降级。**只改呈现 + 前端过滤，不改 `history()` 端点/契约必填字段/排序语义。**

## Boundaries & Constraints

**Always:** 颜色走 `AppColors` token（禁裸 hex）；文案走 arb（en+id 双语）；新字段全 nullable 且**可选有默认**（不破坏现有 `VetHistoryEntry(...)` 构造点，如 dashboard 测试）；过滤在客户端对已拉列表做，空结果显空态；INTERRUPTED 项无评分/等级时段优雅降级。

**Ask First:** 改 `history()` 端点签名、必填契约字段、列表排序；引入服务端筛选参数。

**Never:** 不引后端新必填字段（仅前端模型 + mock 加 nullable）；不引新依赖；筛选无数据来源时不臆造（仅依据已落库的 stars/dangerLevel 过滤；INTERRUPTED 不计入等级筛选）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 默认 Semua | history() 返回 N 项 | 顶栏总数 N + 全部卡渲染（种类头像/名·@主人/summary/星/等级徽章/Selesai\|Interrupted/Lihat） | 加载中转圈；失败/空 → 空态 |
| ⭐ 4-5 bintang | 选该 Chip | 仅 `stars != null && stars >= 4` 项；选中 Chip 薄荷高亮 | 无匹配 → 空态文案 |
| 🟡 Perlu Konsul / 🔴 Darurat | 选对应 Chip | 仅 `dangerLevel==YELLOW`/`==RED` 项 | 无匹配 → 空态 |
| 字段缺失 | dangerLevel/ownerHandle/reviewText==null | 对应徽章/「@主人」/评价引用块跳过，不崩 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/presentation/vet_history_page.dart` -- 目标页：深顶栏 + 总数 + 筛选 Chip 行 + 客户端过滤 + 升级 `_HistoryCard`
- `petgo_app/lib/features/vet/domain/vet_workbench_lists.dart` -- `VetHistoryEntry` 补 nullable `dangerLevel/ownerHandle/petSpecies/reviewText` + fromJson（可选，默认 null）
- `petgo_app/lib/core/mock/mock_backend.dart` -- history 列表补等级/主人/种类/评价示例（做满，含 1 条 RED + 1 条 YELLOW + 1 条 GREEN + 1 条 INTERRUPTED）
- `petgo_app/lib/core/theme/colors.dart` -- token：vetTopBar、vetPrimary、vetSurface、goldTint、coralTint、triage*、vetPrimaryDeep
- `petgo_app/lib/l10n/app_en.arb` / `app_id.arb` -- 新增筛选/标签键（复用 terminalClosed/terminalInterrupted、vetChatToolUnavailable 无关）

## Tasks & Acceptance

**Execution:**
- [x] `vet_workbench_lists.dart` -- `VetHistoryEntry` 加 `this.dangerLevel/this.ownerHandle/this.petSpecies/this.reviewText`（均可选默认 null）+ fromJson 解析 -- 喂卡片/筛选
- [x] `mock_backend.dart` -- history 4 条做满：Cookie(GREEN/@hana/CAT/5★+评价)、Luna(YELLOW/@rio/DOG/4★+评价)、Benji(RED/@bagas/DOG/5★+评价)、Tofu(INTERRUPTED 无等级无评分) -- 让 4 筛选 + 卡片元素可见
- [x] `app_en.arb`/`app_id.arb` -- 新增：`vetHistoryTitle`(Riwayat Konsultasi/Consultation history)、`vetHistoryTotalToday`({count}，Total: N hari ini/Total: N today)、`vetHistoryFilterAll`(Semua/All)、`vetHistoryFilterTopRated`(⭐ 4-5)、`vetHistoryFilterYellow`(🟡 Perlu Konsul)、`vetHistoryFilterRed`(🔴 Darurat)、`vetHistoryFilterEmpty`(无匹配文案)、`vetHistoryView`(Lihat →/View →) -- 原型文案
- [x] `vet_history_page.dart` -- 重写：深顶栏「Riwayat Konsultasi」+ 今日总数；筛选 Chip 行（4 Chip，选中薄荷高亮，`_filter` state 客户端过滤）；升级 `_HistoryCard`（种类头像圈 + 「名 · @主人」+ summary + 右上 ⭐星+日期 + 评价引用块(reviewText 有则显) + 等级徽章(goldTint/coralTint/vetSurface 按级) + Selesai/Interrupted 徽章 + Lihat）；空/加载/空匹配态 -- 1:1 还原

**Acceptance Criteria:**
- Given history 4 条，when 进入，then 顶栏深色 + 总数 4 + 默认 Semua 全显，卡片含头像/名·@主人/星/等级徽章/Selesai。
- Given 选「🔴 Darurat」，when 过滤，then 仅 RED 项（Benji）显示、该 Chip 薄荷高亮。
- Given 选「⭐ 4-5」，when 过滤，then 仅 stars≥4 项显示。
- Given 选某 Chip 无匹配，then 显空匹配文案，不崩。
- Given `flutter analyze` + `flutter test test/vet/`，then 全绿（现有 VetHistoryEntry 构造点不受影响）。

## Spec Change Log

## Design Notes

**等级徽章配色（复用全局 triage tint 映射）：** RED→coralTint 底/triageRed 字、YELLOW→goldTint 底/gold 深字、GREEN→vetSurface 底/vetPrimaryDeep 字（同会话/详情页）；「Selesai」=vetSurface/vetPrimaryDeep、「Interrupted」=muted tint。筛选 Chip 选中=vetPrimary 实底白字，未选=surface + border。

**INTERRUPTED 项：** 无评分、无等级 → 不进等级/星筛选，Semua 下仍显示，徽章只显 Interrupted。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues
- `cd petgo_app && flutter test test/vet/` -- expected: All tests pass
- `cd petgo_app && flutter gen-l10n` -- expected: 新键被 codegen 识别

**Manual checks:**
- 模拟器 `DEV_VET_TAB=2`（历史 Tab）对照 `vet-history.html`：深顶栏总数、4 筛选 Chip 切换、记录卡头像/主人/评价/等级徽章/Lihat。
