# Story 3.2: app物种类型品种选择抽屉

Status: review

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md`（11-13：宫格、品种显示全文、类型搜不到引导 Lainnya、建档页红字改「每 90 天」）> PRD §3.1 / §3.2 / §4 > `architecture-v1.5.0-pet-species-delta.md`（D-18、§8）> App UI 稿 A1 ~ A5、B1 ~ B7（`宠物物种扩展/拆页-App/`，页头文案表为准）。
> 前置：3.1（清单、匹配函数、统一显示函数、l10n 类型名）、1.1（建档接口接 `species`）。
> 范围层：**只 App**：新抽屉组件 + 建档页接入。编辑页的改类型流程归 3.3（复用本组件）；身份证建卡归 3.4（复用本组件）。
> 迁移：无。

## Story

As a 建档的用户,
I want 在一个能搜索的抽屉里选类型和品种,
so that 15 种类型、上百个品种也能很快找到，找不到还能直接用自己输入的。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连 stag 后端真建档（本地）· `[L2]` Android 模拟器视觉、还原度 ≥ 95%（本地）。

### AC1 — 抽屉外壳 `lib/features/profile/presentation/widgets/species_picker_sheet.dart`（新）`[L0][L2]`
1. 底部抽屉，头部照 `PetPickerSheet`：拖拽条 36×4 + 标题 18 / w700；标题下常驻搜索框（高 44，左 `search` 图标、右清除 `cancel` 图标热区 44，占位文案见 AC5）。
2. 键盘弹起时抽屉整体上移、列表区变矮（`MediaQuery.viewInsets` 内边距 + 可滚动内容区）；抽屉最大高度 = 屏高 90%。
3. 选中态沿用身份证下拉 `_option` 的样式（浅紫底 + 紫字 + 右侧勾）；颜色 / 字号 / 间距只用 `AppColors` / 现有 token（UI 稿页头「token 核对」：emoji 18、行高 48、搜索框 44）。
4. 叠在另一个抽屉上弹出时（3.4 用）加遮罩、返回键先关上层。

### AC2 — 类型抽屉 `showPetSpeciesPicker(context, {PetSpeciesCode? current, required String scene})` → `Future<PetSpeciesPick?>` `[L0][L2]`（UI A2 ~ A4）
1. 标题「Pilih jenis」（已有 `petProfileTypePick`）。
2. 无输入：**3 列 × 5 行宫格**，顺序 = `kSpeciesTypes`（「Lainnya」固定最后一格）；格高 80，emoji 30 + 类型名 13；当前项打勾（若 current 是「其他」组物种 → 「Lainnya」格打勾）；点一格即选中、收起；抽屉高度随内容、一屏放下不滚动。
3. 有输入：切成列表（每行 emoji + 名，命中段加粗，`highlightRanges`）；同时匹配 ID 名与 EN 名（英语界面输「rabbit」能找到）。输入「ka」→ Ikan、Kadal、Katak（A3）。
4. 无结果（A4）：浅紫圆底 `search_off` 图形 + 标题「Jenis "{q}" belum ada」+ 说明「Pilih Lainnya, lalu cari di daftar ras.」+ 一行「🐾 Lainnya」（点了即选 OTHER、收起）。**类型不提供手填**。
5. 返回 `PetSpeciesPick(code, viaSearch)`；点外部 / 下拉关闭 → null。

### AC3 — 品种抽屉 `showPetBreedPicker(context, {required PetSpeciesCode type, String? current, required String scene})` → `Future<BreedPick?>` `[L0][L2]`（UI B1 ~ B6）
1. 标题「Pilih ras」（已有 `petProfileBreedPick`）；搜索框**一直有**（不论列表长短）。
2. 非「其他」类型（B1）：按 `breedsFor(type)` 顺序列出，显示全文；当前已选项打勾。
3. 「其他」类型（B5）：按 12 组分组列表——组标题（`titleId` 大写小字）→ 该组品种 → 「<组> lainnya」（`petSpeciesGroupOther`）；选中 → `BreedPick(label 或 titleId, species = 该组 code)`。
4. 搜索（B2 / B6）：用 `speciesQueryMatches`（label + 别名）；「其他」里命中组标题 → 整组显示（含「<组> lainnya」）；输入「poo」（狗）→ Pudel / Poodle、Maltipoo、Shih-poo。
5. 底部固定一行（不随列表滚走）：无输入「✎ Tulis sendiri」（点了聚焦搜索框、弹键盘）；有输入「✎ Pakai "{q}"」（q 截到 60 字；点了即用，`BreedPick(q, species = resolveCustomBreed(type, q))`，`isCustom = true`）。图标放浅紫圆底（UI 稿）。
6. 无结果（B3）：列表区只显示「Belum ada di daftar」，底部「Pakai "{q}"」照常。
7. 已选的是手填值（B4，`!isPresetBreed(type, current)`，含存量 Thai Bangkaew、「Kelinci lokal」这类）：打开时搜索框预填该值、**键盘不自动弹出**、「Pakai」行打勾。
8. **取代**原「Lainnya」→ `AlertDialog` 手填（删掉 `BreedField._editCustom` 一类代码）。
9. 「其他」组内换物种（如 Musang Pandan → Small-clawed Otter）在这里直接选，**不弹确认**（不算改类型，PRD 3.1；UI B6 说明）。

### AC4 — 字段与建档页接入 `[L0][L1][L2]`（UI A1、A5、B7）
1. `SpeciesField`（`pet_form_fields.dart`）：入参由 `String? petType` 改为 `String? species`（wire）+ `ValueChanged<PetSpeciesPick> onChanged`；现有 `locked` / `lockedHint` 参数保留（3.3 冷却锁定态用）；显示 `petSpeciesView(species: …).emoji + label`；点开 `showPetSpeciesPicker`。保留旧构造参数名的兼容（若别的线调用了 `SpeciesField(petType: …)`，加 `@Deprecated` 的命名参数转调，Story 4.2 清理）。
2. `BreedField`：入参 `String? species` + `TextEditingController controller`；未选类型显示「Pilih jenis dulu」不可点（原逻辑）；点开 `showPetBreedPicker`；选中写回 controller，并把 `BreedPick.species` 回传给父页（「其他」组选具体物种时要更新页面的 `_species`）。
3. `pet_profile_create_page.dart`：
   - 状态 `_petType`（String）改为 `_species`（`PetSpeciesCode?`）；`_canSubmit` 判 `_species != null`。
   - 类型标签旁红字词条 `petProfileTypeImmutableHint` **改文案**为「(bisa diubah 90 hari sekali)」/「(can be changed once every 90 days)」（key 不改，避免别的线引用失效）。
   - 换类型（pickerType 变）→ 清空品种（原逻辑）；「其他」内选了某组品种 → `_species` = 该组 code，不清品种。
   - 头像占位 emoji 跟 `_species` 变（A5：🐰）。
   - 提交 `profileRepository.create(species: _species.wire, petType: _species.groupWire, …)`——`ProfileRepository.create` 加 `String? species` 参数并写进请求体（`petType` 仍传大类，兼容回滚到旧后端）。
   - 既有埋点 `pet_profile_create_submitted` 的 `species` 属性**保持大类**（AppsFlyer 归因口径，不改）。
4. 不改建档页其余 8 个模块的顺序与样式（首日线 ③ 也在改这一页的流程；本 story 只动类型框、品种框、红字、头像占位）。

### AC5 — 文案（`app_id.arb` / `app_en.arb`）`[L0]`
照 UI 稿页头文案表：`petSpeciesSearchHint`（Cari jenis hewan / Search pet type）、`petBreedSearchHint`（Cari ras / Search breed）、`petSpeciesNoResultTitle`（Jenis "{q}" belum ada / No "{q}" type yet）、`petSpeciesNoResultBody`（Pilih Lainnya, lalu cari di daftar ras. / Pick Other, then search the breed list.）、`petBreedNoResult`（Belum ada di daftar / Not on the list yet）、`petBreedWriteOwn`（Tulis sendiri / Write my own）、`petBreedUseCustom`（Pakai "{q}" / Use "{q}"）；`petProfileTypeImmutableHint` 改文案（AC4.3）。`flutter gen-l10n`。

### AC6 — 埋点（PRD §4）`[L0]`
1. 类型选中：`Analytics.capture('pet_type_selected', {'species': wire, 'scene': scene, 'via_search': bool})`。
2. 品种选中：`pet_breed_selected`，`{'species': 选中后的细分类型 wire, 'scene', 'is_custom', 'via_search'}`。
3. 品种搜索无结果：`pet_breed_search_no_result`，`{'species', 'query'}`（query 截 60 字）；输入停顿 800 ms 后结果仍为空才报，同一次打开抽屉同一 query 只报一次。
4. `scene` ∈ `create` / `edit` / `id_card`（由调用方传）。事件名写字面量并加进 `core/analytics/analytics.dart` 白名单；`pet_` 前缀已在 `test/analytics/v112_events_test.dart` 登记（核对，不删守卫、不放宽正则）。

### AC7 — 测试 `[L0]`
1. Widget 测试 `species_picker_sheet_test.dart`：宫格 15 格顺序、Lainnya 最后；输入「ka」切列表 3 项；输入「musang」类型抽屉空态 + 点 Lainnya 返回 OTHER；品种抽屉兔 19 项 + 底部「Tulis sendiri」；输入「poo」（狗）3 项；输入清单外词 → 只剩「Pakai "该词"」、点了返回该词且 `isCustom`（PRD 3.2 AC2）；「其他」分组 12 组且每组末项「… lainnya」（PRD 3.1 AC2）；搜「musang」命中整组；已选手填值预填搜索框且未聚焦；选「Musang Pandan」返回 species = CIVET（PRD 3.1 AC3 的 App 侧）。
2. `pet_profile_create_page` 测试：选兔 → 品种框可点；提交请求体含 `species: RABBIT`、`petType: OTHER`；换类型清空品种。
3. 不写 golden（云端 Linux 与 macOS 字体渲染不同），视觉留 L2。

### AC8 — 本地验收 `[L1][L2]`
- L1：连 stag 后端建一只「Lainnya → Musang Pandan」，后端 `pet_species = CIVET`、`pet_type = OTHER`；宠物页显示类型「Lainnya」、品种「Musang Pandan」（PRD 3.1 AC3）。
- L2：A1 ~ A5、B1 ~ B7 逐屏截图与拆页对照，还原度 ≥ 95%。

## Tasks / Subtasks

- [x] **T0 开工核对**：对照 3.1 实际提交的名字（`PetSpeciesCode`、`speciesQueryMatches`、`resolveCustomBreed`、`petSpeciesView`）；`pet_form_fields.dart` / 建档页行号
- [x] **T1** 外壳（AC1）
- [x] **T2** 类型抽屉（AC2）
- [x] **T3** 品种抽屉（AC3）
- [x] **T4** `SpeciesField` / `BreedField` / 建档页 / `ProfileRepository.create`（AC4）
- [x] **T5** 文案（AC5）、埋点（AC6）
- [x] **T6** 测试（AC7）；`flutter analyze` + `flutter test`；Completion Notes 标「L1 / L2 待本地验收」

## Dev Notes

### 现状（2026-11-14 核 `dev_1.5.0` @ 9680309cf，前缀 `A/` = `petgo_app/lib/`）

| 文件 | 现状 | 本 story 改什么 |
|---|---|---|
| `A/features/profile/presentation/widgets/pet_form_fields.dart` | `SpeciesField`（:16-80）3 行 `ListTile` 的 `showModalBottomSheet`；`BreedField`（:85-213）清单 + 「Lainnya」`AlertDialog` 手填（:171-）；`petTypeDisplay`（:8） | 两个字段改用新抽屉；删 ListTile / AlertDialog |
| `A/features/profile/presentation/pet_profile_create_page.dart` | `_petType`（:62）；红字（:336-353）；`SpeciesField` / `BreedField`（:356-372）；头像占位（:473）；`_submit`（:106-）`create(petType: …)` | AC4.3 |
| `A/features/profile/data/profile_repository.dart` | `create(...)`（:12-90）请求体 `petType` | 加 `species` |
| `A/features/profile/presentation/widgets/pet_picker_sheet.dart` | 抽屉头部样式参照 | 只参照 |
| `A/features/profile/presentation/id_card/id_card_pet_type_fields.dart` | `_option` 选中态样式 | 只参照（3.4 删整个下拉） |

### 设计要点
- 三处（建档、编辑、身份证）**只有一个组件**；匹配只走 3.1 的函数。
- 「Pakai」取代手填弹窗后，手填值和清单值一样直接写 `breed`，后端仍是自由文本 `VARCHAR(60)`（截 60 字）。
- 「其他」里选具体物种会改 `species`（CIVET 等），但页面上类型框仍显示「Lainnya」——这是 PRD 定义（类型 = 宫格上的那一项）。

### 不做
- 编辑页改类型确认 / 冷却（3.3）；身份证表单（3.4）。

## Dev Agent Record

### Agent Model Used

Claude（云端 L0 会话，2026-11-14）

### Completion Notes List

- **L1/L2 待本地验收**：AC8（连 stag 建「Lainnya → Musang Pandan」核 `pet_species = CIVET`；A1 ~ A5、B1 ~ B7 逐屏截图还原度）留本地。
- L0：`flutter analyze` 相对基线零新增；`flutter test` 全量 4971 例通过（3 例存量 skip）。新增 `test/features/profile/species_picker_sheet_test.dart`（宫格 15 格顺序、ka 列表、musang 空态 → OTHER、兔 19 项 + Tulis sendiri、poo 3 项、清单外 Pakai + isCustom + 无结果埋点一次、「其他」12 组每组末项 lainnya、搜 musang 命中整组、选 Musang Pandan → CIVET、手填值预填且不聚焦）；`pet_profile_create_test.dart` 新增两例（兔：品种抽屉、换类型清空、请求体 `species=RABBIT` / `petType=OTHER`；「其他」→ Musang Pandan → `species=CIVET`、类型框仍 🐾 Lainnya）。
- 按设计更新的既有测试：建档 / 档案提示 / 发帖流程测试里点类型的 key 从 `speciesOption_*`（旧 ListTile）改为宫格 `speciesCell_*`；编辑页「类型只读」测试断言 `species`（旧后端无 species 时传大类线上值）；`test/analytics/v112_events_test.dart` 动作词尾表登记 `_no_result`（PRD §4 定死事件名 `pet_breed_search_no_result`，扩表、不放宽正则；`pet_` 前缀已登记）；5 个 `ProfileRepository` 测试替身的 `create` 补 `String? species`。
- T0：3.1 的名字（`PetSpeciesCode`、`speciesQueryMatches`、`resolveCustomBreed`、`petSpeciesView`）与本文件一致。
- 实现说明：
  - `SpeciesField` / `BreedField` 新入参 `species`（wire）；旧 `petType` 入参保留并标 `@Deprecated`（别的线旧调用点照常编译，按大类显示）；`BreedField` 另加 `onPicked`（`BreedPick.species` 回传），`onChanged` 仍是 `VoidCallback`（不破旧调用点）。删掉 3 行 ListTile 版类型弹层与「Lainnya」`AlertDialog` 手填。
  - 建档页：`_petType` → `_species`（`PetSpeciesCode?`）；提交 `species: _species.wire` + `petType: groupWire`；`pet_profile_create_submitted` 的 `species` 属性仍报大类；红字 `petProfileTypeImmutableHint` 只改文案（key 不变）。
  - `ProfileRepository.create` 加 `String? species`（请求体 `'species': ?species`）。
  - App 端 `Analytics` 没有事件名白名单集合（守卫是 `v112_events_test` 从源码提取字面量 + 前缀 / 词尾表），故「进白名单」落实为词尾表登记；事件名均为字面量。
- 复审（code-review medium）已修：#1 / #2 两个测试里旧 key；#3 在「其他」里已细分（如 CIVET）后重选 Lainnya 会把细分类型冲回 OTHER 而品种仍是 Musang Pandan → 现保留细分类型；#7 手填截断按字形（不切半个 emoji）；#8 抽屉键盘避让改用共享 `KeyboardInset`。
- 待确认：
  - 复审 #4：编辑页品种框未接 `onPicked`（「其他」组内经品种换物种的提交属 3.3）。
  - 复审 #6：`pet_breed_search_no_result` 的 `query` 属性按 PRD §4 原样上报用户输入的品种词（截 60 字；`Analytics.scrub` 仍会剥 PII 键名）。
  - 复审 #10：建档页红字已改为「每 90 天可改一次」，编辑页真正可改由 3.3 落地（同分支下一条）。

### File List

- petgo_app/lib/features/profile/presentation/widgets/species_picker_sheet.dart（新）
- petgo_app/lib/features/profile/presentation/widgets/pet_form_fields.dart
- petgo_app/lib/features/profile/presentation/pet_profile_create_page.dart
- petgo_app/lib/features/profile/presentation/pet_profile_edit_page.dart（类型框 / 品种框入参）
- petgo_app/lib/features/profile/data/profile_repository.dart
- petgo_app/lib/l10n/app_{en,id}.arb
- petgo_app/test/features/profile/species_picker_sheet_test.dart（新）
- petgo_app/test/profile/{pet_profile_create_test,pet_profile_edit_test,archive_prompt_dialog_test}.dart、test/content/publish_compose_test.dart、test/mention/mention_entry_test.dart、test/analytics/v112_events_test.dart
