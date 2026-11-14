# Story 3.2: App 健康记录表单

Status: review

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段 > 架构增量 §6.1（预填接口、DTO）、§7 > PRD FR-158 UI、FR-159 UI、FR-160 UI 与 Helper text、FR-161 Type dropdown / Icon、FR-167 UI「Form, date empty」、FR-169 文案表、FR-171（Neutered 提示、后续记录只读）、§4 `health_record_saved` / `health_record_deleted`、AC2、AC5、AC6、AC10、AC13 > UI 稿 B1 ~ B10、C1 ~ C8、D1 ~ D8、E1 ~ E3（整合稿页头「代码落点」：顺序 类型 → 子类型 → 剂次 / 时长 → 日期 → 下次日期 → 备注）。
> 前置：后端 1.1 / 1.2 契约；3.1（页面参数）。
> 范围层：**只 App**。

## Story

As a 宠物主人,
I want 填记录时选预设疫苗、剂次、驱虫类型和时长，系统自动填好下次日期并给我该注意的提示,
so that 记一条记录几秒钟，日期还准。

## Acceptance Criteria

### AC1 — 字段与顺序 `[L0]` `[L2]`（UI 稿页头；B1 ~ D8）
1. `_HealthRecordForm`（底部抽屉，Material 组件）字段顺序：类型「Jenis」→ 子类型 → 剂次 / 时长 → 日期 → 「Tanggal berikutnya」→ 备注。
2. 类型下拉：Vaksin、Obat Cacing、Berahi、Sterilisasi、Kontrol dokter、Lainnya（Kontrol dokter 在 Lainnya 前）。默认类型 = 预设参数，否则 Vaksin（现状 `?? 'VACCINE'`）。
3. **疫苗**：CAT / DOG → 「Nama vaksin」下拉（CAT：F3 (Tricat)、F4 (Tetracat)、Rabies、FeLV、Lainnya；DOG：Puppy DP、Combo DHPPi+L、Rabies、Leptospirosis、Kennel Cough、Canine influenza、Lainnya）；选 Lainnya → 下方自由填写框「Tulis nama vaksin」（≤30）；其他物种（非 CAT/DOG）→ 只有原来的单个「Nama vaksin」文本框。系列预设（F3/F4/FeLV/Puppy DP/Combo/Lepto）显示「Dosis (opsional)」chip 行 1–5（可取消选择）；单剂预设不显示。chip 视觉 36 高 / 圆角 10，点击区 ≥48。
4. **驱虫**：「Jenis obat」下拉必填：Internal (cacing)、Eksternal (kutu, caplak, tungau)、Kutu + cacing、Heartworm（**只 DOG** 显示 Heartworm）；Eksternal 时显示「Durasi obat」chip 1 bulan / 3 bulan / 8 bulan（默认 1 bulan）。
5. **Kontrol dokter**：只有日期、下次日期、备注。**Lainnya**：自定义名（≤20）。**Sterilisasi**：无下次日期字段。
6. 保存时 `vaccine_name` 写预设标签（Lainnya 写自由文本），见架构 §3.1 标签表。

### AC2 — 下次日期与预填 `[L0]` `[L2]`（PRD FR-158、AC2、AC5；架构 §6.1 preview）
1. 「Tanggal berikutnya」在日期字段下方，带清除（×）按钮，日期选择器与事件日期同一 Material picker；可选范围由预填接口 `nextDateMin` ~ `nextDateMax` 给（事件日期 +1 天 ~ +3 年）；事件日期 picker 仍禁未来日期（AC2）。
2. 字段下方提示「Pakai tanggal dari dokter hewanmu, ya.」（EN「Use the date your vet gave you.」）。
3. 任一依赖字段（类型、预设、剂次、驱虫类型、时长、事件日期）变化 → 防抖 300ms 调 `POST /api/v1/pet-profiles/me/health-records/preview?petId=`（新 `ApiPaths` 常量 + `HealthRecordRepository.preview`）；**用户没手动改过**下次日期 → 用 `suggestedNextDate` 覆盖（可为 null = 清空）；手动改过 → 不覆盖。接口失败 → 不动字段、不报错。
4. `nextDateAllowed=false` → 不显示该字段（母猫 / 公的 / 已绝育以外的 Berahi、Sterilisasi；PRD AC5）。`nextDateLocked=true` → 字段灰色只读 + 锁图标 + 下方「Mengikuti hari pertama berahi ini」（EN「Follows the first day of this heat」），显示 `lockedNextDate`。
5. 保存请求：带 `nextDate`（当前字段值）；用户清空过 → `clearNextDate: true`；编辑时同理。
6. **App 不实现任何日期规则**（系列 / 驱虫间隔 / 发情均值全由接口给）。

### AC3 — 提示与安全文案 `[L0]` `[L2]`（PRD FR-159、FR-160 Helper text、FR-171；UI B4、B7、C4 ~ C7、D3）
1. 按接口 `hints` 渲染，样式 = 金色浅底 + ⓘ 图标的注意条（与下次日期的普通提示区分）；`PERMETHRIN` 用**警示框**样式（同 3.1 的警示框），放在「Jenis obat」正下方。文案（ID / EN 均已定稿，PRD FR-169 表）：
   - `VACCINE_PREGNANT`：「Tanya dokter hewan sebelum vaksinasi」/「Ask your vet before vaccinating.」
   - `RABIES_YEARLY`：「Rabies wajib tiap tahun. Simpan buku vaksinnya.」/「Rabies is yearly. Keep your vaccine book.」
   - `DEWORM_UNDER_8_WEEKS`：「Belum disarankan di bawah usia 8 minggu」/「Not recommended under 8 weeks yet.」（只提示、不挡保存）
   - `DEWORM_PREGNANT`：「Tanya dokter hewan sebelum memberi obat」/「Ask your vet before giving.」
   - `PERMETHRIN`：「Obat kutu anjing dengan permethrin mematikan bagi kucing. Jauhkan kucing dari anjing selama 3 hari.」/「Dog flea products with permethrin are deadly to cats. Keep your cat away from your dog for 3 days.」
   - `HEARTWORM_TEST`：「Tanya dokter hewan soal tes heartworm」/「Ask your vet for a heartworm test first.」
   - `HEAT_NEUTERED`：「Berahi pasca steril? Tanya dokter hewan」/「Heat after neutering? Ask your vet.」

### AC4 — 必填项字段级报错 `[L0]`（PRD FR-158 Required fields、AC10、FR-167 UI；UI C1、C8、E2、E3）
1. 保存按钮一直可点；点了有缺项 → 对应字段变红 + 下方报错，不提交：驱虫类型「Pilih jenis obat dulu」/「Choose a treatment type first」；日期（从建议提醒打开、`dateEmpty=1` 时日期为空，占位「Pilih tanggal」现有键）「Pilih tanggal dulu」/「Choose a date first」；自定义名称原 SnackBar 改为同样的字段报错（文案沿用现有键）。
2. 编辑存量无类型驱虫（`dewormType == null`）：表单类型字段空、必须先选才能保存（哪怕只改备注；UI C8）。后端 400 时也映射成该字段报错（ProblemDetail 不直出 `detail`）。

### AC5 — 预填打开 `[L0]`（3.1 传入的 prefill；PRD FR-167 Where each reminder lands）
1. `prefill`（type、preset、dose、deworm、custom、dateEmpty）→ 表单初值；`dateEmpty=false` 时日期 = 今天；有预设时立刻调一次预填接口。
2. 编辑已有记录：按记录字段回填（`vaccinePreset` 为 OTHER 或空 → 显示 Lainnya + 原文；匹配到的预设 → 选中该预设，PRD FR-168 Display and editing）。

### AC6 — 未保存标记 `[L0]`（PRD FR-167 Fallbacks；3.4 使用）
1. 新增 `healthFormDirtyProvider`（`StateProvider<bool>` 或 Notifier，放 `features/profile/domain/`）：表单打开且任一字段与初值不同 → true；保存成功 / 关闭抽屉 → false。

### AC7 — 图标与名称 `[L0]`（PRD FR-161 Icon）
1. `health_record_icons.dart` 加 `'VET_VISIT'` 行：听诊器、`AppColors.coral`（UI 稿用的是 Material Symbols `stethoscope`）。先查当前 Flutter SDK 的 `Icons` 有没有 `stethoscope`：有就用；没有就把 Material Symbols 的 stethoscope 轮廓（Apache-2.0）存成单色 `assets/icons/stethoscope.svg`，走该表已有的 `svgAsset` 机制（`icon` 字段填 `Icons.medical_services_outlined` 兜底）。Diary 时间线、日历、列表三处共用这一张表（文件头注释）。
2. 类型名 arb：`healthTypeVetVisit` 「Kontrol dokter」/「Vet visit」；预设标签、「Dosis ke-{n}」/「Dose {n}」、「Jenis obat」/「Treatment type」、四个驱虫选项、「Durasi obat」/「Duration」+ 三个时长、「Tanggal berikutnya」/「Next date」、「Berikutnya: {date}」/「Next: {date}」、「Melewati jadwal」/「Past due」、「Tulis nama vaksin」/「Enter vaccine name」——全部按 PRD FR-169 表 ID / EN 原文。

### AC8 — 埋点 `[L0]`（PRD §4）
1. 保存成功 → `health_record_saved {record_id, pet_id, species, type, deworm_type, vaccine_preset, dose, has_next_date, next_date_prefilled, next_date_edited, is_edit, entry, source, notification_id?, notification_kind?}`：`entry` = `consult_page`（从 Kesehatan 页「+ Kontrol dokter」进来，3.5 传 `from=consult`）否则 `health_page`；`source` = `health_reminder`（3.4 的 30 分钟归因命中）/ `milestone`（现有 `?add=` 里程碑入口）/ `manual`。
2. 删除 → `health_record_deleted {record_id, pet_id, type, source, record_created_at}`（`source` 为原记录的来源：本地无从得知时报 `manual`，Completion Notes 记一句）。
3. 不报宠物名、备注、疫苗原文。

## Tasks / Subtasks

- [x] T1 字段与顺序、各类型分支、Kontrol dokter（AC1）
- [x] T2 预填接口仓储方法 + 防抖 + 手改标记 + 只读 / 隐藏逻辑（AC2）
- [x] T3 提示 / 警示（AC3）
- [x] T4 字段级报错（AC4）
- [x] T5 prefill 与编辑回填（AC5）
- [x] T6 `healthFormDirtyProvider`（AC6）
- [x] T7 图标与 arb（AC7），`flutter gen-l10n`
- [x] T8 埋点（AC8）
- [x] T9 widget 测试：猫 / 狗 / 其他物种预设列表（AC6）、剂次 chip 只在系列预设出现、驱虫必填报错（AC10）、Heartworm 只狗、Eksternal 时长 chip、预填覆盖与手改不覆盖（假仓储）、发情只读、Sterilisasi 无下次日期、dateEmpty 报错、编辑 Legacy 驱虫必须选类型
- [x] T10 `flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

## Dev Notes

- 文件：`features/profile/presentation/health_list_page.dart` 内的 `_HealthRecordForm`（约 611 行起；体量大时拆到 `features/profile/presentation/widgets/health_record_form.dart`）、`features/profile/data/health_record_repository.dart`、`features/profile/domain/health_list_item.dart`（`HealthRecordDraft` 加字段）、`core/network/api_paths.dart`、`features/profile/domain/health_record_icons.dart`、`l10n/app_id.arb` / `app_en.arb`。
- 底部抽屉键盘避让：`showModalBottomSheet(isScrollControlled: true)` + 内容套 `KeyboardInset`（`petgo_app/CLAUDE.md` 输入 / 键盘标准）。
- 物种只认 CAT / DOG，其余（含 ④ 线将新增的物种）走 OTHER 分支。
- 不整文件 `dart format`。

### 验证层级
- L0：`flutter analyze`；`test/profile/health_record_form_test.dart`
- L2：模拟器对照 B1 ~ E3 逐屏、印尼语日期选择器按钮文字（UI 稿「真机确认」项）

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC1 / AC2 / AC3 的 `[L2]` 部分（模拟器对照 UI 稿 B1 ~ E3 逐屏、chip 36 高 / 圆角 10 的观感、印尼语日期选择器按钮文字「真机确认」项）未做；真后端联调（预填接口、`X-App-Version` ≥ 1.5.0 才生效的新字段）留 4.1。本 story 无 L1 项。
- L0：`flutter analyze` 无 error / warning（仅工具链 3.50 的全仓 deprecation info 与 `health_list_page.dart` 既有的 rendering import info）；`flutter test` 全量 4983 例绿；新增 `test/profile/health_record_form_test.dart` 28 例（猫 / 狗 / 其他物种预设、剂次 chip 只在系列预设、Lainnya 自由框、Heartworm 只狗、Eksternal 时长 chip 默认 1 个月、Kontrol dokter 只有日期 / 下次日期 / 备注、Sterilisasi 无下次日期、字段顺序、预填覆盖 vs 手改不覆盖 + `clearNextDate`、预填失败静默、`nextDateAllowed=false` 隐藏、发情只读锁定、7 个提示 + PERMETHRIN 警示框位置 + 未知 code 忽略、驱虫必填 / dateEmpty / 自定义名字段报错、存量无类型驱虫编辑必须先选、prefill 初值 + 立即预填、编辑回填 OTHER / 预设、脏标记、两个埋点）。
- 表单从 `health_list_page.dart` 拆到 `presentation/widgets/health_record_form.dart`（公开类 `HealthRecordForm`）；里程碑庆祝 / 轮询逻辑原样搬过去。键盘避让改用 `KeyboardInset`（原手写 `viewInsets` padding）。
- **App 不实现日期规则**：下次日期的建议、可选范围、显示 / 只读、提示全来自预填接口。接口还没回来时只做两个非规则的界面兜底：Sterilisasi 恒不显示；Berahi 先不显示（避免闪一下又消失）；下次日期 picker 范围接口缺省时取「事件日 +1 天 ~ 2100」，越界交后端校验。
- 编辑已有记录：已存的下次日期视为用户值，打开时**不被预填覆盖**（保守口径：怕把兽医给的日期冲掉）；用户清空后再改依赖字段也不覆盖。**待确认**：若产品希望编辑时改事件日期也跟着重算，需另定。
- 疫苗名：选预设时 `vaccineName` 写预设标签（arb 两语同文，等于架构 §3.1 标签表；后端缺省时也会自补同一串），Lainnya / 其他物种写自由文本。
- **字段报错**：驱虫类型、日期（`dateEmpty=1`，占位复用 `petProfileBirthdayPick`）、自定义名（原 SnackBar 改为字段 `errorText`，文案沿用 `healthCustomNameRequired`）。后端 4xx 且驱虫未分型 → 映射为驱虫字段报错，不直出 `detail`。
- **AC7 图标**：当前 SDK 的 `Icons` 没有 `stethoscope` → 存 Material Symbols「stethoscope」outlined 24px（Apache-2.0，google/material-design-icons）为 `assets/icons/stethoscope.svg`，`svgAsset` 机制。**偏离 story**：`icon` 兜底没用 story 建议的 `medical_services_outlined`——它已是日历「当天多条记录」通用标记 `kHealthRecordGenericIcon`，`calendar_cell_priority_test` 明确禁止撞车；改用 `monitor_heart_outlined`。
- 类型名映射收敛到既有的 `healthRecordLabel`（图标表旁，加 VET_VISIT）；3.1 的 `healthTypeLabel` 改为转调它，全项目一份。
- **埋点**：枚举值一律小写蛇形（cloud-rules 埋点约定，优先于 story / PRD 表里的大写写法）——`species` cat / dog / other、`type` vaccine / vet_visit…、`deworm_type`、`vaccine_preset`。同时把 3.1 已提交的 `health_category_filtered.category` 由大写改为小写（`vaccine` / `deworm` / …；PRD §4 写的是大写 code，此处按 cloud-rules 小写，**待确认**看板口径），3.1 测试同步。
  - `health_record_saved`：`next_date_prefilled` = 本次打开出现过预填；`next_date_edited` = 出现过预填且用户亲手选过 / 清过；`entry` 只有从 `?from=consult` 自动弹出的那一张是 `consult_page`；`source` = 带 `nid` → `health_reminder`（`notification_id` / `notification_kind` 与 30 分钟归因归 3.4 的 `HealthReminderAttribution`，本条未报），`?add=` 且非 consult → `milestone`，其余 `manual`。路由新增 `?from=consult` 解析（3.5 传）。
  - `health_record_deleted`：列表接口不下发记录来源与创建时刻 → `source` 恒报 `manual`、不报 `record_created_at`（story AC8.2 允许，在此记录）。
  - `test/analytics/v112_events_test.dart` 动作词表加 `_deleted`（名字由 PRD §4 定死，扩表不改名）。
- `healthFormDirtyProvider`（`domain/health_form_dirty.dart`，Riverpod 3 `Notifier<bool>`）：字段快照与初值比较；保存成功 / 抽屉关闭（dispose）置 false；容器已销毁时忽略。
- 仓储：`create` 改返回新记录 id（`Future<int?>`，取响应 `id`），新增 `preview`；`ApiPaths.healthRecordPreview`。4 个测试假仓储同步实现（`health_list_page_test`、`health_focus_from_timeline_test`、`consult_shop_boundary_test`、`pet_readonly_block_test`）。
- 新 arb 键（FR-169 表原文）：`healthVaccinePresetOther`、`healthVaccineOtherName`、`healthDoseOptional`、`healthDewormTypeField`、`healthDurationField`、`healthDurationMonths`（EN 单复数）、`healthNextDateField`、`healthNextDateHelper`、`healthNextDateLockedHelper`、`healthErrorDewormType`、`healthErrorDate`、6 个 `healthHint*`；PERMETHRIN 复用 3.1 的 `healthSafetyPermethrin`。下次日期清除按钮 tooltip 复用 `healthDelete`（「Hapus」）。
- 3.1 测试按 3.2 新规则调整：AC67 改类型为驱虫时需先选驱虫类型（驱虫必填）。
- 未运行 `bmad-dev-story` / `bmad-code-review` 技能文件（仓库内不存在），按 story 手动执行；code review 由本会话逐项核对 diff：修 3 处（长选项让下拉溢出 → `isExpanded`；`entry=consult_page` 原先会扩散到同页后续打开的表单；`?add=VET_VISIT&from=consult` 原先被记成 `milestone`）。

### File List

- `petgo_app/lib/features/profile/presentation/widgets/health_record_form.dart`（新）
- `petgo_app/lib/features/profile/presentation/health_list_page.dart`
- `petgo_app/lib/features/profile/domain/health_form_dirty.dart`（新）
- `petgo_app/lib/features/profile/domain/health_list_item.dart`
- `petgo_app/lib/features/profile/domain/health_record_icons.dart`
- `petgo_app/lib/features/profile/data/health_record_repository.dart`
- `petgo_app/lib/core/network/api_paths.dart`
- `petgo_app/lib/core/router/app_router.dart`
- `petgo_app/assets/icons/stethoscope.svg`（新）
- `petgo_app/lib/l10n/app_id.arb`、`petgo_app/lib/l10n/app_en.arb`
- `petgo_app/test/profile/health_record_form_test.dart`（新）
- `petgo_app/test/profile/health_list_page_test.dart`
- `petgo_app/test/profile/health_focus_from_timeline_test.dart`
- `petgo_app/test/shop/consult_shop_boundary_test.dart`
- `petgo_app/test/pet_slot/pet_readonly_block_test.dart`
- `petgo_app/test/analytics/v112_events_test.dart`
