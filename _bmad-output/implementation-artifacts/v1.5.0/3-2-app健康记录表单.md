# Story 3.2: App 健康记录表单

Status: ready-for-dev

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

- [ ] T1 字段与顺序、各类型分支、Kontrol dokter（AC1）
- [ ] T2 预填接口仓储方法 + 防抖 + 手改标记 + 只读 / 隐藏逻辑（AC2）
- [ ] T3 提示 / 警示（AC3）
- [ ] T4 字段级报错（AC4）
- [ ] T5 prefill 与编辑回填（AC5）
- [ ] T6 `healthFormDirtyProvider`（AC6）
- [ ] T7 图标与 arb（AC7），`flutter gen-l10n`
- [ ] T8 埋点（AC8）
- [ ] T9 widget 测试：猫 / 狗 / 其他物种预设列表（AC6）、剂次 chip 只在系列预设出现、驱虫必填报错（AC10）、Heartworm 只狗、Eksternal 时长 chip、预填覆盖与手改不覆盖（假仓储）、发情只读、Sterilisasi 无下次日期、dateEmpty 报错、编辑 Legacy 驱虫必须选类型
- [ ] T10 `flutter analyze` + `flutter test`；Completion Notes 标「L2 待本地验收」

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

### File List
