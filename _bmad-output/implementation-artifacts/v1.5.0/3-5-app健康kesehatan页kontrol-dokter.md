# Story 3.5: App Kesehatan 页 Kontrol dokter

Status: review

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段 > 架构增量 §6.3 > PRD FR-161 UI（Adding / Kesehatan reloads / listed in "Konsultasi saya"）、AC69 > UI 稿 A10（整合稿页头「代码落点」第 2 行）。
> 前置：后端 2.5（`/consult/history` 的 `VET_VISIT` 条目）；App 3.1（`add=VET_VISIT`、`focus`）、3.2（`entry=consult_page`）。
> 范围层：**只 App**。

## Story

As a 宠物主人,
I want 在 Kesehatan 页直接添加 Kontrol dokter，并在「Konsultasi saya」里看到所有宠物的 Kontrol dokter 和问诊,
so that 线下看兽医的记录也和线上问诊放在一起。

## Acceptance Criteria

### AC1 — 「+ Kontrol dokter」按钮 `[L0]` `[L2]`（PRD AC69）
1. `triage_page.dart`「Konsultasi saya」标题行右侧加 `TextButton`「+ Kontrol dokter」（复用类型名 arb，EN「+ Vet visit」）；仅「已登录且至少 1 只宠物」显示（游客 / 无宠物不显示）。
2. 点击：≥2 只宠物 → 先 `pickPetForConsult(context, ref, purpose: …)`（与「Cek Gejala」同一个选择器；取消 → 什么都不做）；然后切到该宠物（`currentPetIdProvider.select`，换宠物时报 `pet_switched`，`source` 沿用该页现有值或新增 `consult_page`）→ `await context.push('/profile/health?add=VET_VISIT&from=consult')`。只有 1 只 → 不弹选择器。
3. 返回 Kesehatan 页后 `setState(_load)` 重新拉「Konsultasi saya」。

### AC2 — VET_VISIT 条目 `[L0]` `[L2]`
1. `ConsultHistoryItem` 追加 `recordId`、`petId`、`petName`、`eventDate`、`note`、`nextDate`、`overdue`、`recordStatus`；`type == 'VET_VISIT'` 时 `_HistoryTile` 渲染新样式：红色听诊器头像（同 3.2 图标表 `VET_VISIT` 行）、标题「Kontrol dokter · {nama}」（EN「Vet visit · {name}」，新 arb 键带占位）、右侧日期「15 Jan 2027」（记录卡格式，不用相对时间）、备注最多 2 行、与记录卡相同的状态胶囊（复用 3.1 的胶囊 widget）。
2. 点击：切到 `petId`（同上）→ `await context.push('/profile/health?focus=<recordId>')`；返回后重新 `_load`。
3. 现有 AI / VET 条目渲染与点击不变；`fromJson` 对未知 `type` 不崩（保持现有默认）。
4. 空态「Belum ada riwayat konsultasi」只在 AI / VET / VET_VISIT 都没有时显示。

### AC3 — 测试 `[L0]`
1. widget 测试：游客 / 无宠物不显示按钮；1 只宠物直接 push、不弹选择器；2 只先弹选择器；VET_VISIT tile 标题 / 日期 / 胶囊；点 tile 切宠物并 push focus；返回触发重新加载（用假仓储计数）。

### AC4 — 视觉 `[L2]`
1. 模拟器对照 A10，还原度 ≥95%（4.1 统一打分）。

## Tasks / Subtasks

- [x] T1 按钮与选择器流程（AC1）
- [x] T2 模型与 tile（AC2）
- [x] T3 测试（AC3）；`flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

- 文件：`features/triage/presentation/triage_page.dart`（563 行；`_load` 第 50 行、`pickPetForConsult` 第 185 行、`_HistoryTile` 第 400 行）、`features/consult/domain/consult_history_item.dart`、`l10n/*.arb`。
- 现有 `setState(() => _future = call())` 箭头返回 Future 在 debug 下静默不刷新——写成块体：`setState(() { _load(); });`（项目踩过的坑）。
- 不整文件 `dart format`。

### 验证层级
- L0：`flutter analyze`；`test/triage/triage_page_vet_visit_test.dart`
- L2：模拟器 A10

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC1 / AC2 的 `[L2]` 部分与 AC4（模拟器对照 UI 稿 A10，还原度 ≥95%）未做，留 4.1 统一打分；真后端联调（`/consult/history` 在 `X-App-Version` ≥ 1.5.0 时下发 `VET_VISIT`）留 4.1。本 story 无 L1 项。
- L0：`flutter analyze` 无 error / warning（仅工具链 3.50 的全仓 deprecation info 与既有 rendering import info）；新增 `test/triage/triage_page_vet_visit_test.dart` 9 例（游客 / 无宠物不显示按钮、1 只直接 push 不弹选择器 + 返回重拉、2 只先弹选择器并切宠物报 `pet_switched{consult_page}`、选择器取消什么都不做、VET_VISIT 条目标题 / 日期 / 备注 2 行 / 胶囊、只有 Kontrol dokter 不是空态、点条目切宠物 + push focus + 返回重拉、未知 type 不崩、三类全无才空态）；`flutter test` 全量 5022 例绿。
- 「+ Kontrol dokter」：文案 = `'+ ' + healthTypeVetVisit`（story 要求复用类型名 arb，不另起键）；显示条件 = 已登录（该段本就只在登录态渲染）且 `petListProvider` 非空。选择器复用 `pickPetForConsult`，`purpose` 选 `vetConsult`（标题「Konsultasi untuk siapa?」/ 说明「Dokter akan melihat profil hewan ini.」）——**待确认**：Kontrol dokter 是线下看诊记录，这两句是线上咨询的口吻，PRD / UI 稿未给专用文案，暂复用、未新造文案。
- 切宠物：`effectivePetIdProvider` 当前值不同才 `currentPetIdProvider.select` + `pet_switched {from_pet_id, to_pet_id, source: consult_page}`（不带宠物名；`consult_page` 为新增来源值，story 允许）。返回后重拉用块体 `setState(() { _load(); })`（story Dev Notes 点名的坑）。
- VET_VISIT 条目：新 `_VetVisitTile`（AI / VET 的 `_HistoryTile` 一行未改）；头像 = 3.2 图标表 `VET_VISIT` 行（听诊器 SVG + coral，coralTint 圆底）；标题新 arb 键 `consultHistoryVetVisitTitle`（「Kontrol dokter · {name}」/「Vet visit · {name}」）；日期用记录卡同一格式 `formatDayMonthYear(eventDate)`；状态胶囊复用 3.1 的胶囊——为此把 3.1 页面里的私有 `_statusPill` 抽成公共 widget `widgets/health_status_pill.dart`（`HealthStatusPill`，健康记录页改为用它，测试 key 不变）。
- `ConsultHistoryItem` 追加 8 字段 + `isVetVisit`；`fromJson` 对未知 `type` 保持原默认行为。空态条件不变（`items.isEmpty`，三类都没有才显示）。
- 未运行 `bmad-dev-story` / `bmad-code-review` 技能文件（仓库内不存在），按 story 手动执行；code review 由本会话逐项核对 diff，无 CONFIRMED 问题。

### File List

- `petgo_app/lib/features/triage/presentation/triage_page.dart`
- `petgo_app/lib/features/consult/domain/consult_history_item.dart`
- `petgo_app/lib/features/profile/presentation/widgets/health_status_pill.dart`（新）
- `petgo_app/lib/features/profile/presentation/health_list_page.dart`
- `petgo_app/lib/l10n/app_id.arb`、`petgo_app/lib/l10n/app_en.arb`
- `petgo_app/test/triage/triage_page_vet_visit_test.dart`（新）
