---
title: '档案里程碑流 i18n 收口（⑤）'
type: 'chore'
created: '2026-06-16'
baseline_commit: 'f416f293653532051ab8df8368980217e4113c5c'
status: 'done'
context:
  - '{project-root}/_bmad-output/core-pages-UX.md'
  - '{project-root}/_bmad-output/pages/namecard.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** ⑤ 档案里程碑流全部屏已实现且对齐薄荷绿（档案/日历/建档/编辑/里程碑列表/抽屉/图鉴/三级庆祝/名片均完整；里程碑标题已按 `code` 在 `milestone_titles.dart` 本地化）。剩 **7 处硬编码印尼语**：时间线「Momen Bahagia」徽章、档案卡「Hari bareng」、H5 名片页 5 处——其中含**又一处 `'PETGO'` 改名漏网**，且出现在**对外分享的 H5 名片卡**上（品牌泄漏）。

**Approach:** 迁入 arb（新增 7 键）；H5 `'PETGO · KARTU ANABUL'` 顺带改 TailTopia。demo 占位数据（默认品种 `'Kucing Oren'`、H5 月份缩写数组）不迁。无新建、无行为/后端改动。

## Boundaries & Constraints

**Always:** 新增键 en+id 成对 ≤1 emoji；保持 mint+cream；里程碑标题继续走 `milestone_titles.dart`（不动）。

**Ask First:** 无。

**Never:** 不动后端 / 主题 / 功能；不迁 demo 占位数据（`'Kucing Oren'` 默认品种、H5 月份缩写数组——后者属日期格式化，留 demo 不重构）；不碰保留的 petgo 标识（仅改用户可见的 H5 字标 `'PETGO'`）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 印尼语环境 | locale=id | 时间线徽章/档案卡/H5 文案显示印尼语，取自 arb | N/A |
| 英语环境 | locale=en | 同位置显示英文 | N/A |
| H5 名片头 | 任意 | 显示 `TailTopia · KARTU ANABUL`（不再 `PETGO`） | N/A |

</frozen-after-approval>

## Code Map

- `lib/features/profile/presentation/widgets/timeline_tiles.dart` -- `HappyMomentTile.build`(L89) L117 `'Momen Bahagia'`→`happyMomentLabel`（build 加 l10n；import 已有）
- `lib/features/profile/presentation/widgets/pet_info_card.dart` -- L83 `'Hari bareng'`→`daysTogetherLabel`（已有 l10n L26）
- `lib/features/profile/presentation/pet_card_page.dart` -- H5 5 处文案→arb（加 `app_localizations` import + `l10n`；`'PETGO'`→TailTopia）；demo 不动
- `lib/l10n/app_en.arb` / `app_id.arb` -- 新增 7 键

## Tasks & Acceptance

**Execution:**
- [x] `lib/features/profile/presentation/widgets/timeline_tiles.dart` -- `HappyMomentTile.build` 加 `final l10n = AppLocalizations.of(context)`，`'Momen Bahagia'`→`l10n.happyMomentLabel`
- [x] `lib/features/profile/presentation/widgets/pet_info_card.dart` -- `'Hari bareng'`→`l10n.daysTogetherLabel`
- [x] `lib/features/profile/presentation/pet_card_page.dart` -- 加 `import app_localizations` + `final l10n = AppLocalizations.of(context)`；`'PETGO · KARTU ANABUL'`→`l10n.petCardHeader`、`'Momen terbaru'`→`l10n.petCardRecentMoments`、`'5 terakhir'`→`l10n.petCardRecentCount`、下载引导(L195)→`l10n.petCardDownloadPrompt(name)`、`'Unduh TailTopia'`(L223)→`l10n.petCardDownloadCta`；`'Kucing Oren'` 默认品种与月份缩写数组保持 demo 不动
- [x] `lib/l10n/app_en.arb` + `app_id.arb` -- 增 7 键：`happyMomentLabel`、`daysTogetherLabel`、`petCardHeader`、`petCardRecentMoments`、`petCardRecentCount`、`petCardDownloadPrompt`（带 `{name}` placeholder 元数据于 en）、`petCardDownloadCta`；en/id 对齐；`flutter gen-l10n`

**Acceptance Criteria:**
- Given locale=id, when 看时间线/档案卡/H5 名片, then 对应文案为印尼语且来自 arb；locale=en 为英文
- Given 任意 locale, when 渲染 H5 名片头, then 显示 `TailTopia · KARTU ANABUL`（`grep` 源码无 `'PETGO'`）
- Given 本地 `flutter gen-l10n`/`analyze`/`test`, when 执行, then L0 全绿（含 microcopy J2/J3）

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 无错误
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: 全绿（313 + 不回归）
- `cd petgo_app && grep -nE "'Momen Bahagia'|'Hari bareng'|PETGO · KARTU|'Momen terbaru'|'Unduh TailTopia'" lib/features/profile/presentation/widgets/timeline_tiles.dart lib/features/profile/presentation/widgets/pet_info_card.dart lib/features/profile/presentation/pet_card_page.dart` -- expected: 无输出

## Suggested Review Order

- 入口：新增 7 键（en 模板，`petCardDownloadPrompt` 带 `{name}`）
  [`app_en.arb:531`](../../petgo_app/lib/l10n/app_en.arb#L531)
- H5 名片页 5 处文案走 l10n + 修 'PETGO'→TailTopia（对外分享品牌泄漏）
  [`pet_card_page.dart:94`](../../petgo_app/lib/features/profile/presentation/pet_card_page.dart#L94)
- 时间线快乐时刻徽章 + 档案卡陪伴天数走 l10n
  [`timeline_tiles.dart`](../../petgo_app/lib/features/profile/presentation/widgets/timeline_tiles.dart)
  [`pet_info_card.dart`](../../petgo_app/lib/features/profile/presentation/widgets/pet_info_card.dart)
