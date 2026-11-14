# Story 3.3: App 宠物档案健康状态字段

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段 > 架构增量 §0 Z-9、§6.2 > PRD FR-162（全部，含 UI）、FR-169 文案表、§4 `health_profile_field_updated`、AC15 > UI 稿 F1 ~ F5（整合稿页头「代码落点」：新字段在 Berat badan 和 BIO 之间）。
> 前置：后端 1.1（`catInHome`、`pregnantNursing`、`pregnantAutoOffDate`、`neuterStatus`）。
> 范围层：**只 App**。⚠️ ④ 物种线也会改 `pet_profile_edit_page.dart`（在本线之后合并），本 story 只在「Berat badan」与「Bio」之间插入一个独立 widget，尽量不动其他段落，降低冲突。

## Story

As a 宠物主人,
I want 在宠物档案里填绝育状态、家里有没有猫、是否怀孕 / 哺乳,
so that 健康提醒按实际情况发，并在需要时提醒我注意安全。

## Acceptance Criteria

### AC1 — 分组与显示条件 `[L0]` `[L2]`（PRD FR-162 表与 UI、AC15）
1. 新 widget `PetHealthStatusSection`（`features/profile/presentation/widgets/pet_health_status_section.dart`），插在 `pet_profile_edit_page.dart` 的 Berat badan 与 Bio 之间。标题「Status Kesehatan」，说明行「Digunakan untuk notifikasi catatan kesehatan」（EN「Health status」/「Used for health record notifications」）。
2. 三项，均为**等分切换**（样式同 Diary 的 Linimasa / Kalender 切换，不是下拉）：
   - 「STATUS STERIL」：Sudah steril / Belum steril / Tidak yakin（EN NEUTER STATUS：Neutered / Not neutered / Not sure）→ `NEUTERED / INTACT / UNKNOWN`；`neuterStatus=null` 时**都不选中**。CAT、DOG 显示。
   - 「ADA KUCING DI RUMAH?」：Ya / Tidak（EN CAT AT HOME?：Yes / No）→ `catInHome`；只 DOG 显示；null 时都不选中。
   - 「HAMIL / MENYUSUI」：Ya / Tidak（EN PREGNANT / NURSING）→ `pregnantNursing`；只在 sex = FEMALE **或 sex 未填**时显示；为 Ya 且接口给了 `pregnantAutoOffDate` → 下方一行「Otomatis nonaktif pada {tanggal}.」（EN「Turns off automatically on {date}.」，日期 `d MMM y` 本地化）。
3. 物种非 CAT/DOG（含 ④ 线新增物种）→ 整个分组不显示（UI F5）。sex 在本页改成 MALE 时怀孕项立即隐藏（提交时不带该字段）。
4. widget 测试覆盖 AC15（母猫：绝育 + 怀孕；公狗：绝育 + 家里有猫；母狗：三项全有）、F4（性别未填：显示怀孕项）、F5（OTHER：不显示）。

### AC2 — 保存 `[L0]`
1. `PetProfileUpdateRequest`（App 端模型 / 仓储）追加 `neuterStatus`（若尚未接入）、`catInHome`、`pregnantNursing`；只发**变化了**的字段（部分更新语义，null = 不改）。
2. 保存成功后对每个变化字段报 `health_profile_field_updated {pet_id, field, value}`：`field` ∈ `neuter_status` / `cat_in_home` / `pregnant_nursing`，`value` 为枚举小写或 `true/false`。
3. 保存后刷新档案 provider，使自动关闭日期行立即出现。

### AC3 — 文案 `[L0]`
1. 新 arb 键 ID / EN 两份同步（PRD FR-169 表原文），`flutter gen-l10n`。

### AC4 — 视觉 `[L2]`
1. 模拟器对照 F1 ~ F5，还原度 ≥95%（4.1 统一打分）。

## Tasks / Subtasks

- [ ] T1 `PetHealthStatusSection` + 显示条件（AC1）
- [ ] T2 插入编辑页、保存与埋点（AC2）
- [ ] T3 arb（AC3）
- [ ] T4 widget 测试；`flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

- 文件：`features/profile/presentation/pet_profile_edit_page.dart`（602 行；`_sex` 状态在第 55 行附近，保存在第 131 行附近）、档案模型 / 仓储（`features/profile/data/`、`domain/`）。
- 现有编辑页没有设置 `neuterStatus` 的 UI（PRD §2 属实），本 story 是第一次接入。
- 不整文件 `dart format`。

### 验证层级
- L0：`flutter analyze`；`test/profile/pet_health_status_section_test.dart`
- L2：模拟器 F1 ~ F5

## Dev Agent Record

### Completion Notes

### File List
