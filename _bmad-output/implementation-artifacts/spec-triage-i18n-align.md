---
title: 'AI 分诊流 i18n 收口（③）'
type: 'chore'
created: '2026-06-16'
baseline_commit: '51ff837b471cac5cf67237d1be642e7d7adf3cd1'
status: 'done'
context:
  - '{project-root}/_bmad-output/core-pages-UX.md'
  - '{project-root}/_bmad-output/pages/konsultasi.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** ③ AI 分诊流的功能与安全护栏（红态 5 秒锁 + 零 CTA/零导航、黄态条件倒计时协议、三态结果卡、免责前置、上传 ≤3 图/≤2000 字）均已完整实现，但问诊 Tab hub `triage_page.dart`（换肤重做）的**页头 + 两入口卡共 11 处 UI 文案硬编码印尼语**，违反「用户可见文案走 arb」护栏。

**Approach:** 把这 11 处迁入 arb（en+id）。兽医在线徽章参数化 `{count}`。demo 兽医名/专科（生产由 API 拉）保持不迁。功能/安全护栏/字数上限一律不动。

## Boundaries & Constraints

**Always:** 新增键 en+id 成对、每条 ≤1 emoji；保持 mint+cream + 三色语义；症状字数上限保持 **2000**（与后端 `@Size` Bean Validation 一致，权威——原型标的「500」是 mockup 不准，不据此改）；涉及断言的测试用 `locale: Locale('id')` + l10n delegates，arb id 值与现文案逐字一致以保断言不变。

**Ask First:** 无（纯收口，决策已定）。

**Never:** 不动分诊三态 / 红态 5 秒锁 / 零 CTA 零导航等安全攸关逻辑；不改后端契约 / 字数；不动 demo 兽医数据；不做严重度胶囊「浅底深字」改造（既有 deferred a11y 项，留 story 7-4）；不删孤儿旧键（`triageEntryVetTitle/AiDesc/VetDesc/FreeBadge`，本条仅记一笔）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 印尼语环境 | locale=id | 问诊 Tab 页头 + 两入口卡 + 在线区头全为印尼语，取自 arb（源码无这 11 串） | N/A |
| 英语环境 | locale=en | 同位置显示英文 | N/A |
| 在线兽医徽章 | count=N（demo=2） | 显示「{count} dokter online」/「{count} vets online」 | N/A |

</frozen-after-approval>

## Code Map

- `lib/features/triage/presentation/triage_page.dart` -- 页头标题(L91)/副标(L97) + AI 卡 标题/徽章/描述/CTA(L110-114) + 兽医卡 标题/徽章/描述/CTA(L129-132) + 在线区头(L143)；`build` 已有 `l10n`(L73)，header Row(L84) 需去 `const`
- `lib/l10n/app_en.arb` / `app_id.arb` -- 新增 11 键
- `test/triage/triage_page_widget_test.dart` / `test/auth/story_1_5_gating_test.dart` / `test/content/beranda_home_test.dart` -- 确保 pump 带 `locale('id')` + delegates（断言文案不变）

## Tasks & Acceptance

**Execution:**
- [x] `lib/features/triage/presentation/triage_page.dart` -- 11 处 chrome 文案走 `l10n`：页头 Row 去 `const`，标题→`triageHeroTitle`、副标→`triageHeroSubtitle`；AI 卡 标题→`triageAiCardTitle`/徽章→`triageAiSpeedBadge`/描述→`triageAiCardDesc`/CTA→`triageAiCardCta`；兽医卡 标题→`triageVetCardTitle`/徽章→`triageVetOnlineBadge(2)`/描述→`triageVetCardDesc`/CTA→`triageVetCardCta`；在线区头→`triageOnlineVetsHeader`（去 const）；demo 兽医名/专科不动
- [x] `lib/l10n/app_en.arb` + `app_id.arb` -- 增 11 键（`triageVetOnlineBadge` 带 `{count}` placeholder 元数据于 en 模板）；id 值逐字保留现文案；保持 en/id 键完全对齐；`flutter gen-l10n`
- [x] `test/triage/triage_page_widget_test.dart` + `test/auth/story_1_5_gating_test.dart` -- pump 的 MaterialApp 补 `locale: const Locale('id')` + `AppLocalizations` delegates（若缺），保持现断言；`beranda_home_test` 已带 locale id（②），确认仍绿

**Acceptance Criteria:**
- Given locale=id, when 打开问诊 Tab, then 页头与两入口卡全印尼语且来自 arb；`grep` 源码无这 11 串
- Given locale=en, when 打开问诊 Tab, then 对应文案显示英文
- Given 任意, when 渲染兽医卡, then 徽章显示「{count} dokter online」（count 由 widget 传入，demo=2）
- Given 本地 `flutter gen-l10n`/`analyze`/`test`, when 执行, then L0 全绿（含 microcopy J2/J3 + 三个相关 widget 测试）

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 无错误
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test test/triage test/auth/story_1_5_gating_test.dart test/content/beranda_home_test.dart test/l10n` -- expected: 全绿
- `cd petgo_app && grep -nE "Konsultasi Kilat|Tanya AI|Chat Dokter Hewan|Dokter sedang online|Mulai triase|Mulai konsultasi|dokter online|≤ 15 detik" lib/features/triage/presentation/triage_page.dart` -- expected: 无输出（chrome 硬编码已清；demo 兽医名/专科不在此列）

## Suggested Review Order

- 入口：新增 11 键（en 模板，`triageVetOnlineBadge` 带 `{count}`）——迁移目标全貌
  [`app_en.arb:512`](../../petgo_app/lib/l10n/app_en.arb#L512)
- 问诊 hub 页头 + 两入口卡 + 在线区头全走 l10n（header Row 去 const）
  [`triage_page.dart:91`](../../petgo_app/lib/features/triage/presentation/triage_page.dart#L91)
- 测试固定 `locale('id')`（triage widget）/ 改断言英文标题（story_1_5 pump 真实 App 默认 en）
  [`triage_page_widget_test.dart:14`](../../petgo_app/test/triage/triage_page_widget_test.dart#L14)
