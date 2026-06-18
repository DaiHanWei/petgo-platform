---
title: '兽医待接单卡片补到 1:1（宠物身份块 + 样式对齐）'
type: 'feature'
created: '2026-06-17'
status: 'done'
baseline_commit: 'a16f7bd002f2bf5981603619cd5e0dc77a993fb4'
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/pages/vet-queue.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 第2屏待接单卡片约 70% 保真：核心结构（三色/AI 框/时间/双按钮/红态）在，但原型最显眼的**宠物身份块（种类 emoji 头像 + 宠物名 + 「种类·性别·年龄·@主人」meta）缺失**；且 AI 摘要框统一薄荷底（原型按等级配色：黄卡琥珀 `#FEF3DE`）、等级措辞与原型不符、缺照片图标。

**Approach:**（决策：Mock 先做满、后端随后补）给 `VetInboxItem` 加 **nullable** 宠物字段 + `fromJson` 解析（缺失=null → 优雅降级为当前形态，不破真后端）；`mock_backend` 待接单各项填 demo 值；卡片渲染身份块（emoji 头像/名/meta，i18n）。同时纯前端对齐：AI 框按等级配色、等级徽章用原型措辞（新增 queue 专用键）、照片数前加图标。

## Boundaries & Constraints

**Always:** 以 `vet-queue.html` 为视觉标准；新字段一律 **nullable**，`petName==null` 时**不渲染身份块**（真后端未接 = 当前形态，零破坏）；颜色走 `AppColors` token（含 `withValues` 派生 tint，禁裸 hex）；新文案双语进 `app_en.arb`+`app_id.arb`；species/sex/age 由 code/数值经 i18n 本地化（不存显示串）。

**Ask First:** 若发现真后端 `VetInboxItem` 已下发同名字段需对齐命名；若身份块 AA 对比度需新增深色 token。

**Never:** 不动接单/详情/路由/抢单逻辑；不改用户侧；不加 MQ/缓存/新依赖；不把宠物 meta 拼进 symptomPreview（结构化字段独立）；不删现有 `vetAiContextLevel*` 键（别处在用）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 全字段齐 | petName/species/sex/ageMonths/owner 非空 | 头像(种类 emoji)+名(heading)+meta「种类·性别·年龄·@主人」 | N/A |
| 字段缺失（真后端） | petName=null | 不渲染身份块，卡片=当前形态（等级/AI框/按钮） | 优雅降级 |
| 年龄<12 月 | petAgeMonths=8 | 「8 bln / 8 mo」 | N/A |
| 年龄≥12 月 | petAgeMonths=12 | 「1 thn / 1 yr」 | N/A |
| 种类 CAT/DOG | petSpecies | 🐱 / 🐶 头像 + 本地化种类名 | 未知种类 → 🐾 |
| YELLOW/GREEN/RED 框 | aiDangerLevel | AI 框底分别 goldTint / 绿派生 tint / coralTint | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/features/vet/domain/vet_inbox_item.dart` -- 加 nullable petName/petSpecies/petSex/petAgeMonths/ownerHandle + fromJson
- `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- `_InboxCard` 加身份块 + AI 框按等级配色 + 照片图标 + queue 等级徽章措辞；加 `_speciesEmoji`/`_ageLabel`/`_metaLine` 辅助
- `petgo_app/lib/core/mock/mock_backend.dart` -- 待接单 4 项填宠物字段（按原型：Benji/Anjing/Jantan、Oyen/Kucing… 等）
- `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- species/sex/age/queue 等级标签键
- `petgo_app/test/vet/vet_inbox_test.dart` -- 身份块渲染 + petName=null 降级

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/features/vet/domain/vet_inbox_item.dart` -- 加 `String? petName/petSpecies/petSex/ownerHandle`、`int? petAgeMonths` + `fromJson` 解析（全 nullable）-- 承载身份数据
- [x] `petgo_app/lib/l10n/app_en.arb` + `app_id.arb` -- 新键：`vetSpeciesCat/Dog`、`vetSexMale/Female`、`vetAgeYears({count})`、`vetAgeMonths({count})`、`vetQueueLevelGreen/Yellow/Red`(Normal/Perlu Konsul/Darurat)；`flutter gen-l10n` -- 双语本地化
- [x] `petgo_app/lib/core/mock/mock_backend.dart` -- 待接单 4 项加宠物字段 demo 值（原型映射：8100 Benji/DOG/MALE/60mo/@bagas，8101 Oyen/CAT/MALE/24mo/@rani，8102 Bruno/DOG/…，8103 DIRECT 也给宠物）-- 模拟器看满血卡
- [x] `petgo_app/lib/features/vet/presentation/vet_inbox_page.dart` -- `_InboxCard`：顶部身份块（种类 emoji 头像 + petName heading + meta 行，`petName==null` 跳过）；等级徽章改用 `vetQueueLevel*`；AI 框底按等级 tint（goldTint/绿派生/coralTint）；「N foto」前加 `Icons.photo_outlined` -- 还原 1:1
- [x] `petgo_app/test/vet/vet_inbox_test.dart` -- 加：身份块渲染（名/meta）、`petName=null` 降级不显身份块、AI 框/等级措辞 -- 守 L0
- [x] 视觉验收 -- `flutter run --dart-define=DEV_ROUTE=/vet/workbench --dart-define=DEV_VET=true` 截图四态 -- 本地 L2

**Acceptance Criteria:**
- Given 卡片含全宠物字段，when 渲染，then 显示种类 emoji 头像 + 宠物名 + 「种类·性别·年龄·@主人」meta 行
- Given `petName==null`，when 渲染，then 不显身份块，卡片回退当前形态（无报错）
- Given petAgeMonths 8 / 12，when 渲染，then 分别「8 mo」/「1 yr」（en）、「8 bln」/「1 thn」（id）
- Given aiDangerLevel YELLOW，when 渲染 AI 框，then 底色为琥珀 tint（非薄荷）
- Given `flutter analyze` + `flutter test`，when 执行，then 全绿

## Design Notes

身份块布局（原型）：行首圆形头像（种类 emoji，浅底）+ 右侧两行（粗体名 / secondary meta）；等级徽章与时间在身份块下方一行。meta 行用 ` · ` 连接本地化 species/sex/age + `@{ownerHandle}`。年龄：`months>=12 ? vetAgeYears(months~/12) : vetAgeMonths(months)`。AI 框标题色用等级 accent 或 ink（取 AA 安全者）。species emoji：CAT→🐱 / DOG→🐶 / 其他→🐾。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: All tests passed

## Suggested Review Order

- 宠物身份字段（nullable，后端未下发=null 降级）
  [`vet_inbox_item.dart:25`](../../petgo_app/lib/features/vet/domain/vet_inbox_item.dart#L25)

- 身份块渲染（emoji 头像 + 名 + meta；petName==null 跳过；DIRECT 中性头像；空 meta 不渲染——审查 patch）
  [`vet_inbox_page.dart:307`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L307)

- `_metaLine` 本地化拼接（species/sex/age/@owner，缺项跳过无悬挂分隔符）
  [`vet_inbox_page.dart:244`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L244)

- AI 框按等级配色（黄琥珀/红珊瑚/绿派生）
  [`vet_inbox_page.dart:265`](../../petgo_app/lib/features/vet/presentation/vet_inbox_page.dart#L265)

- mock 待接单 4 项宠物 demo 值 + 新双语键 + 测试（身份块/降级）
  [`mock_backend.dart`](../../petgo_app/lib/core/mock/mock_backend.dart) · [`vet_inbox_test.dart`](../../petgo_app/test/vet/vet_inbox_test.dart)
