# Story 1.7: 建卡页去掉样式 Tab，按卡种填表

Status: review

> 版本 v1.3.4 · 主题 ktp · 分支 `feat/1.3.4-ktp-revamp`
> 口径优先级：`决策日志-ktp.md` > `PRD-v1.3.4-ktp.md` > `architecture-v1.3.4-ktp-delta.md` > `ui-v1.3.4-ktp.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 前置：Story 1.1（后端收 5 种卡种 + `cardVariant`、物种 / 性别必填、KTP / KITAS 品种必填、建卡响应带 `numberReused`）、Story 1.2（`IdCardStyle` 注册表含 `formFields` 与卡面构建函数）、Story 1.3（SIM / 居住证卡面）、Story 1.5（网格点格进建卡页并带上卡款）。
> **范围边界**：本 story 只改建卡页本身与「Ubah info」表单。建卡成功后弹付费抽屉、关闭 / 付费后 `context.pop()` 回列表的**现有行为不动**——跳转改造在 Story 1.8。

## Story

As a 用户,
I want 建卡时只填这张卡上会印出来的信息,
so that 填表更快、不被无关字段干扰。

## Acceptance Criteria

**AC1 — 建卡页去掉样式 Tab，按所选卡款渲染** `[L0]` `[L2]`（FR-142C.1 / C.2，UX-DR6，UI 稿 C1）
1. `IdCardCreatePage` 接收所选卡款 `IdCardStyle style`（由 Story 1.5 的跳转带入，取值方式以 1.5 落地为准）；删除 `_styleIndex`、`_styleSwitcher`、`_styleTab`（及 key `idCardStyleTab_$i`）与 `_cardTypeWire`。页面不再有任何切换卡款的入口。
2. 卡面预览由注册表构建：画布尺寸、圆角、卡面 widget 一律取自 `style`（Story 1.2 的构建函数），**页面内不再写 `switch` 判卡种**（AD-6）。预览恒叠 `IdCardWatermark`（现状保留）。8 款各自打开时预览是对应卡面。
3. AppBar 标题 `idCardCreateTitle`、「Ubah info」（key `idCardEditInfo`）、「Ganti foto」（key `idCardEditPhoto`）、「Buat Kartu」（key `idCardCreateSubmit`）文案、样式、位置不变；「Ganti foto」逻辑（`_editPhoto`）不变。
4. 未带卡款或卡款无法识别时回落 `IdCardStyle.ktp`，不崩溃（防御；正常入口只有网格）。

**AC2 — 预填（档案有值才预选）** `[L0]`（FR-142C.2 / D.7）
1. 进入页面按当前宠物档案预填：名字、物种（`petType`）、品种、生日、性别、头像。**不再预填一句话介绍**。
2. 性别：档案 `sex` 为 `MALE` / `FEMALE` → 预选对应 chip；档案未填（null）或其他值 → **不预选**（会话性别为 null，三个 chip 都不选）。`idCardGenderFromPetSex`（`ktp_fields.dart:104`）的回落值由 `'UNKNOWN'` 改为 `null`，返回类型改为 `String?`。
3. 物种：档案有 `petType` → 预选；无 → 不预选，物种框显示 `petProfileTypePick`（「Pilih jenis」）。

**AC3 — 「Ubah info」按卡款显示字段** `[L0]` `[L2]`（FR-142D.1 / D.2 / D.4 / D.5，UX-DR7，UI 稿 D1 ~ D5）
1. 抽屉字段集取自 `style.formFields`，顺序固定为：名字 → 物种 → 品种 → 性别 → 生日 → 出生城市 → 地址 → 职业 → 婚姻状态 → 学校 → 院系（不在字段集内的跳过）。五套字段与 PRD §3.4 表一致：

   | 卡款 | 字段（* = 必填） | 项数 |
   |---|---|:-:|
   | KTP | 名字* · 物种* · 品种* · 性别* · 生日* · 出生城市 · 地址 · 职业 · 婚姻状态 | 9 |
   | Paspor | 名字* · 物种* · 性别* · 生日* · 出生城市 | 5 |
   | Kartu Izin Tinggal | 名字* · 物种* · 品种* · 性别* · 生日* | 5 |
   | SIM | 名字* · 物种* · 性别* · 生日* · 出生城市 · 地址 · 职业 | 7 |
   | Pelajar（Bintang / Malam / Ceria / Klasik 共用） | 名字* · 物种* · 性别* · 生日* · 学校 · 院系 | 6 |

   若 Story 1.2 落地的 `formFields` 与上表不一致，以本表（PRD）为准修正注册表。
2. **删除「一句话介绍」**：去掉 `petProfileBioLabel` 标签与 key `idCardEditIntro` 输入框；`_intro` 状态、`_InfoDraft.intro` 一并删除。
3. 必填字段标签带红色 `*`（沿用现有 `_requiredLabel`）；选填字段沿用现有带「(OPSIONAL)」的 l10n 标签与 hint（hint 即卡面趣味默认值：`KtpDefaults.tempatKota` / `alamat` / `pekerjaan` / `statusPerkawinan`、学校 `TAILTOPIA ACADEMY`、院系 `FAKULTAS KEBAHAGIAAN`），输入长度上限不变（20 / 48 / 30 / 20 / 20 / 20）。
4. 抽屉形态保持 `showModalBottomSheet(isScrollControlled: true)`；结构改为「顶部把手 + 字段区（抽屉内滚动）+ 底部吸底「Selesai」（key `idCardEditDone`）」，长表单（KTP 9 项）在小屏上字段区滚动、「Selesai」始终可见；键盘弹起时「Selesai」与当前输入框不被遮挡（沿用 `viewInsets.bottom` 处理）。
5. 点「Selesai」回建卡页、卡面即时刷新（现状行为）。
6. 点「Ubah info」上报 `id_card_form_opened{card_type}`，`card_type` 取 `style.cardType` 的 wire 值（`KTP` / `PASSPORT` / `KITAS` / `SIM` / `STUDENT`）。

**AC4 — 身份证专用物种 / 品种下拉** `[L0]` `[L2]`（FR-142D.3，NFR5，UI 稿 D7）
1. 新建身份证模块专用组件（建议 `petgo_app/lib/features/profile/presentation/id_card/id_card_pet_type_fields.dart`，含物种下拉与品种下拉两个 widget），**`widgets/pet_form_fields.dart` 零改动**，宠物档案创建 / 编辑页的 `SpeciesField` / `BreedField` 行为不变。
2. 物种：点输入框（key `idCardSpeciesField`）在**框正下方展开**选项列表（不弹底部抽屉）：`Kucing` / `Anjing` / `Lainnya`（取 `petTypeCat` / `petTypeDog` / `petTypeOther`，对应 wire `CAT` / `DOG` / `OTHER`；选项 key `idCardSpeciesOption_<wire>`）；当前项右侧打勾；选中即收起。改物种时清空品种（沿用现状 `id_card_create_page.dart:561-564`）。
3. 品种：点输入框（key `idCardBreedField`）在框正下方展开当前物种的品种清单（`breedsForPetType`，`domain/pet_breeds.dart`），列表区最高约 5 项、超出在菜单内滚动；当前项打勾；末项为 `petProfileBreedOther`（「Lainnya」，key `idCardBreedOption_OTHER`），点它在原位展开为输入框（key `idCardBreedCustomInput`，`maxLength: 60`，hint `petProfileBreedHint`），输入值即品种。品种选项 key `idCardBreedOption_<品种名>`。
4. 未选物种时品种框禁用、显示 `petProfileBreedNeedType`（「Pilih jenis dulu」）。物种为 `OTHER`（无清单）时，品种框直接是输入框（与现状 `BreedField` 对 `OTHER` 直接手填的语义一致）。
5. 预填品种不在清单内（档案自定义品种）时：品种框显示该值，展开后「Lainnya」打勾且输入框预填该值。
6. 同一时刻最多一个下拉展开；点另一个字段 / 选完 / 再点框本身 → 收起。

**AC5 — 必填校验与「Buat Kartu」置灰** `[L0]` `[L2]`（FR-142C.5 / D.6，UX-DR6，UI 稿 C4 / D6）
1. 必填项 = 名字（trim 后非空）/ 物种 / 性别 / 生日；`style.cardType` 为 `KTP` 或 `KITAS` 时另加品种（trim 后非空）。判定写成纯函数（建议 `idCardMissingFields(IdCardStyle, draft) → List<IdCardFormField>`，按 AC3 字段顺序返回），表单与建卡页共用，可单测。
2. 表单内红字（字段下方，字号 / 颜色沿用现有 `idCardBirthdayRequired` 的 11 号 `AppColors.danger`）：生日缺 → `idCardBirthdayRequired`（现状保留）；物种缺 → 新 key `idCardPetTypeRequired`；性别缺 → 新 key `idCardGenderRequired`。物种框缺值时描边变红（UI 稿 D6 `bad` 态）。
3. 「Selesai」**不拦截、始终可点**（现状）。
4. 建卡页：任一必填缺失 → 「Buat Kartu」置灰（`onPressed: null`），按钮上方一行红字（key `idCardCreateMissingHint`，字号 / 颜色沿用现有 `:188-194` 的生日提示）：新 key `idCardCreateMissing`，带占位符 `{fields}`，按缺失项（AC3 字段顺序）拼接各项名称（缺几项拼几项）。拼接词与各项名称见 Dev Notes「新增文案」。删除现有 `if (_birthday == null)` 单独显示 `idCardBirthdayRequired` 的分支（`:188-194`），由该组合提示取代。
5. 全部必填齐 → 按钮可点、红字消失。

**AC6 — 提交：请求带卡种与配色，不带 intro** `[L0]` `[L1]`（FR-142C.4 / D.4，AD-4）
1. App `CreateIdCardRequest`（`domain/id_card.dart:213`）新增 `String? cardVariant`，`toJson` 非 null 时写 `'cardVariant'`；删除 `intro` 字段及其 `toJson` 分支（App 内唯一调用点是建卡页）。
2. `_create` 发送：`cardType = style.cardType`、`cardVariant = style.cardVariant`（学生卡为 `BINTANG` / `MALAM` / `CERIA` / `KLASIK`，其他卡 null）；`gender` 为会话选中值（AC5 保证非 null）；**只提交 `style.formFields` 内的字段值**，字段集外的字段一律传 null（如 Paspor 不传 `breed` / `address`，学生卡不传 `birthCity`；见「待确认」第 4 条）；`avatarUrl` 照传。
3. 提交中：主按钮内白色转圈，「Ubah info」「Ganti foto」不可点（现状 `_busy` 逻辑保留，UI 稿 C2）。
4. 失败：预览保持不变，SnackBar `idCardCreateError`，按钮恢复可点（现状保留，UI 稿 C3）。
5. 成功：上报 `id_card_created{card_type, card_variant, number_reused}`——`card_type` / `card_variant` 取 `style`（非学生卡不带 `card_variant` 键，与 Story 1.5 `id_card_style_tapped` 同口径），`number_reused` 取后端建卡响应的 `numberReused`（Epic 2 前恒 false；缺键按 false）。然后**沿用现状**：`ref.invalidate(idCardListProvider)` → `_openPayment(card)` → `context.pop()`。
6. App 读取 `numberReused`：`IdCard` 增加 `final bool numberReused`（`fromJson` 取 `json['numberReused'] as bool? ?? false`；列表 / 详情响应无此键 → false）。不改 `IdCardRepository.createCard` 签名（避免波及各测试 fake）。

**AC7 — 埋点登记** `[L0]`
1. `test/analytics/v112_events_test.dart` 的 `allowedSuffixes` 增加 `'_open'`、`'_create'`，各附注释「名字由 PRD §4 定死（V1.3.4 ktp `id_card_form_opened` / `id_card_created`），扩表、不改名」；前缀 `id_card_` 已在表内。若 Story 1.5 / 1.6 已先加过同名后缀则不重复。
2. 属性键 `card_type` / `card_variant` / `number_reused` 均 snake_case。

**AC8 — 测试与回归** `[L0]`
1. 新增 `petgo_app/test/profile/id_card_create_page_test.dart`（建卡页当前零覆盖），至少覆盖：
   - 无样式 Tab（`idCardStyleTab_0` 不存在）；以 `ktp` / `passport` / `studentBintang` 进入，预览分别是对应卡面 widget；
   - 五套表单各自的字段 key 出现 / 不出现（如 Paspor 无 `idCardBreedField`、无 `idCardEditAddress`；学生卡有 `idCardEditSchool` 无 `idCardEditBirthCity`；任何卡款都无 `idCardEditIntro`）；
   - 档案无 `sex` → 三个 `idCardGender_*` 都未选中、表单出现 `idCardGenderRequired` 文案、建卡页按钮置灰并显示 `idCardCreateMissingHint`；档案 `sex = FEMALE` → `idCardGender_FEMALE` 选中；
   - 物种下拉：点 `idCardSpeciesField` → 三个选项在页面内出现（不是新路由 / 新 bottom sheet）、当前项有勾；选 `DOG` 后品种被清空；
   - 品种下拉：点「Lainnya」出现 `idCardBreedCustomInput`，输入后「Selesai」，预览品种更新；
   - KTP / KITAS 品种为空 → 按钮置灰；Paspor 品种为空 → 不影响；
   - 提交：fake repo 捕获 `CreateIdCardRequest`，断言 `cardType` / `cardVariant`（学生卡四款各一）/ 无 `intro` 键 / 字段集外字段为 null；`Analytics.debugCaptureSink` 断言 `id_card_form_opened`、`id_card_created`（含 `number_reused`）；
   - 提交失败 → SnackBar `idCardCreateError`、预览仍在。
2. `ktp_fields_test.dart:152-158` 按 AC2.2 更新（`idCardGenderFromPetSex(null)` / 未知值 → null）。
3. `missing fields` 纯函数单测：五套字段集 × 各必填缺失组合。
4. 回归不改动即通过：`pet_profile_create_test`、`pet_profile_edit_test`、`archive_prompt_dialog_test`、`publish_compose_test`（均点 `petProfileSpeciesField` 走档案组件的底部抽屉）、`id_card_page_test`、`ktp_unlock_analytics_test`、`v112_events_test`。
5. `flutter analyze` 0 issue，`flutter test` 全绿；`git diff` 中 `widgets/pet_form_fields.dart` 无改动。

**AC9 — 联调与视觉** `[L1]` `[L2]`
1. `[L1]` 连本分支后端（已含 Story 1.1）：8 款各建一张成功，后端落库 `card_type` / `card_variant` 正确、`intro` 为 NULL、字段集外字段为 NULL；`number_reused` 埋点为 false。
2. `[L2]` 模拟器：8 款进入建卡页均无 Tab、卡面正确；KTP 表单在小屏抽屉内滚动、「Selesai」吸底；物种 / 品种下拉在框下展开、约 5 项内滚、「Lainnya」原位展开输入框；缺项时 C4 红字与置灰；宠物档案创建 / 编辑页的物种 / 品种仍弹底部抽屉（未受影响）。

## Tasks / Subtasks

- [x] Task 1：建卡页接收卡款、去 Tab（AC1）
  - [x] 1.1 `IdCardCreatePage` 增 `style` 参数；对接 Story 1.5 的路由传参（`app_router.dart:836`），缺省回落 `IdCardStyle.ktp`
  - [x] 1.2 删 `_styleIndex` / `_styleSwitcher` / `_styleTab` / `_cardTypeWire`；预览改由注册表构建
- [x] Task 2：预填与会话状态（AC2）
  - [x] 2.1 `_gender` 改 `String?`；`idCardGenderFromPetSex` 回落改 null；删 `_intro`
  - [x] 2.2 更新 `ktp_fields_test` 对应用例
- [x] Task 3：按卡款字段集的表单抽屉（AC3、AC5.2 / 5.3）
  - [x] 3.1 `_EditInfoSheet` 按 `style.formFields` 渲染；删「一句话介绍」；必填 `*`
  - [x] 3.2 抽屉结构改「字段区滚动 + Selesai 吸底」
  - [x] 3.3 物种 / 性别 / 生日字段下红字；物种框红描边
  - [x] 3.4 `id_card_form_opened` 埋点
- [x] Task 4：身份证专用物种 / 品种下拉（AC4）
  - [x] 4.1 新组件（框下内联展开、打勾、约 5 项内滚、「Lainnya」原位输入、OTHER 直接输入、同时只开一个）
  - [x] 4.2 确认 `pet_form_fields.dart` 零改动
- [x] Task 5：必填判定与建卡页置灰提示（AC5）
  - [x] 5.1 `idCardMissingFields` 纯函数 + 单测
  - [x] 5.2 建卡页组合红字 `idCardCreateMissing`，替换现有生日单独提示
  - [x] 5.3 l10n 新 key（id / en，见 Dev Notes），`flutter gen-l10n`
- [x] Task 6：提交（AC6、AC7）
  - [x] 6.1 `CreateIdCardRequest` 加 `cardVariant`、删 `intro`；`IdCard.numberReused`
  - [x] 6.2 `_create` 按字段集取值、带 `cardType` / `cardVariant`；成功埋点 `id_card_created`；付费与 `context.pop()` 保持现状
  - [x] 6.3 `v112_events_test` 增 `_open` / `_create` 后缀
- [x] Task 7：测试（AC8）
  - [x] 7.1 新建 `test/profile/id_card_create_page_test.dart`
  - [x] 7.2 `flutter analyze` / `flutter test` 全绿
- [x] Task 8：联调与视觉验收（AC9，L1 / L2 本地）

## Dev Notes

### 现状（改动前必读）

**建卡页 `petgo_app/lib/features/profile/presentation/id_card_create_page.dart`**
- 会话状态 `:39-56`：`_gender` 是非空 `String`，默认 `'UNKNOWN'`（`:43`）；`_intro`（`:44`）；`_styleIndex` 0=KTP / 1=Paspor / 2=Pelajar（`:55`）；`_busy`（`:56`）。所有编辑**仅会话级、不落库**，点「Buat Kartu」才 POST。
- 预填 `:59-76`：`ref.read(petProfileProvider.future)` 后填 name / petType / breed / birthday / gender（`idCardGenderFromPetSex(p.sex)`，档案无性别 → `UNKNOWN`）/ intro / avatarUrl。
- `_preview` `:79-99`：组 `IdCardData`，`cardNo` 用 `buildPreviewCardNo`、`passportNo` 用 `buildPreviewPassportNo`（`ktp_fields.dart:119` / `:133`，WIB 年份），含 `intro`。
- `_cardTypeWire` `:102-106`：`_styleIndex` → `KTP` / `PASSPORT` / `STUDENT`。
- `build` `:113-129` 用 `switch (_styleIndex)` 取画布 / 圆角 / 卡面（`PassportCardFront` / `StudentCardFront` / `KtpCardFront`）；`:136` 渲染 `_styleSwitcher`（`:221-259`，key `idCardStyleTab_$i`，文案 `idCardStyleKtp/Paspor/Pelajar`）。
- 生日缺失时按钮上方红字 `idCardBirthdayRequired`（`:188-194`）；按钮禁用条件 `name 为空 || birthday == null || _busy`（`:204`）——**现状不校验物种、性别、品种**。
- `_editInfo` `:262-301`：`showModalBottomSheet<_InfoDraft>(isScrollControlled: true)`，回传草稿整体覆盖会话状态。
- `_create` `:321-355`：POST（`intro` `:333`、`cardType: _cardTypeWire` `:339`）→ `invalidate(idCardListProvider)` → `_openPayment(card)`（`:358-419`，`KtpUnlockAnalytics` entry=create）→ `context.pop()`（`:354`）。失败 SnackBar `idCardCreateError`。**付费 / 跳转本 story 不动（Story 1.8）**。
- `_InfoDraft` `:428-458`；`_EditInfoSheet` `:461-730`：整张表单包在一个 `SingleChildScrollView` 里，「Selesai」在滚动内容末尾（不吸底）。字段顺序：名字（key `idCardEditName`，max 60）→ `SpeciesField`（`:559`，改物种清空品种）→ `BreedField`（`:568`）→ 性别 chip（`:572-583`，key `idCardGender_$wire`）→ 生日（`_requiredLabel` + key `idCardEditBirthday`，`:586-614`，缺失红字 `:610-614`）→ 一句话介绍（key `idCardEditIntro`，`:616-624`）→ 出生城市 / 地址 / 职业 / 婚姻（`:627-642`）→ 学校 / 院系（`:643-651`）→ Selesai（key `idCardEditDone`，`:653-676`）。**所有卡种显示同一套全部字段。**

**共享组件 `petgo_app/lib/features/profile/presentation/widgets/pet_form_fields.dart`**（本 story 不改）
- `SpeciesField`（`:16-78`，key `petProfileSpeciesField`）点击 `showModalBottomSheet` 列 `CAT/DOG/OTHER`（`:59-77`，显示带 emoji 的 `petTypeDisplay`）。
- `BreedField`（`:83-198`）点击 `showModalBottomSheet` 列品种 + 「Lainnya」（`:139-159`），「Lainnya」再弹 `AlertDialog` 手填（`:170-197`，max 60）；物种 `OTHER` 无清单 → 直接弹手填；未选物种禁用并显示 `petProfileBreedNeedType`。
- 使用方：`pet_profile_create_page.dart:300 / :311`、`pet_profile_edit_page.dart:330 / :343`、以及本页 `:559 / :568`。档案页测试（`pet_profile_create_test`、`pet_profile_edit_test`、`archive_prompt_dialog_test`、`publish_compose_test`）依赖其 key 与抽屉行为——NFR5 要求不动。
- 品种清单 `domain/pet_breeds.dart`：`kCatBreeds`（22 项）、`kDogBreeds`（35 项）、`breedsForPetType`（`OTHER` / null → 空清单）；「Lainnya」不在清单内，由 UI 追加。

**其他**
- `ktp_fields.dart`：`KtpDefaults`（`:64-100`，趣味默认值即表单 hint）；`KtpDefaults.jenisKelaminFor(null)` → `'JANTAN'`（`:88-99`，用于旧卡零变化，**不要改**）；`idCardGenderFromPetSex`（`:104-108`）仅建卡页使用 + `ktp_fields_test.dart:152-158`。
- `domain/id_card.dart`：`CreateIdCardRequest` `:213-271`（`toJson` 对 null 字段省略键）；`IdCard.fromJson` `:159-182`；`effectiveCardType` `:185`。
- 路由 `core/router/app_router.dart:836`：`/profile/id-cards/create` → `const IdCardCreatePage()`（Story 1.5 会改为带卡款）。
- l10n：`idCardStyleKtp/Paspor/Pelajar`（`app_id.arb:1539-1541`）**仍被 `id_card_page.dart:124-126` 使用**，本 story 只删建卡页对它们的引用，不删 key（若 1.5 / 1.6 落地后已无引用，可顺手删除并 grep 确认）。
- 埋点：`v112_events_test.dart` 前缀表已有 `id_card_`（`:277`），后缀表（`:330` 起）**没有** `_open` / `_create`，不加则 AC7 用例会红。`Analytics.debugCaptureSink`（`core/analytics/analytics.dart:159`）可在 L0 断言事件。

### 本 story 改什么 / 必须保持什么

- **改**：去 Tab；卡款由入口决定；表单按字段集；身份证专用下拉；必填扩到物种 / 性别（+ KTP / KITAS 品种）；请求带 `cardVariant`、不带 `intro`；两个新埋点。
- **保持**：会话级不落库；预览恒带水印；「Ganti foto」；趣味默认值与 hint；生日选择器（`showDatePicker`，近 40 年）；`_busy` 转圈与禁用；失败 SnackBar；付费抽屉与 `context.pop()`（Story 1.8 再改）；`KtpUnlockAnalytics` 现有事件（卡种属性由 1.6 / 1.8 补）。

### 架构约束

- AD-6：卡款相关的判断一律从 `IdCardStyle` 取（`cardType`、`cardVariant`、`formFields`、卡面构建），不在页面写 `switch`。
- AD-9：表单由注册表字段集驱动同一个底部抽屉组件；物种 / 品种下拉是身份证模块专用新组件；共享 `SpeciesField` / `BreedField` 不动。
- AD-4（后端，Story 1.1 已落）：`petType` / `gender` 必填、KTP / KITAS `breed` 必填、`intro` 不写入。App 侧校验与之一致，后端仍是最终防线（422 时走 `idCardCreateError`）。
- AD-12：`id_card_form_opened{card_type}`、`id_card_created{card_type, card_variant, number_reused}`。
- 下拉实现建议：在表单 Column 内**内联展开**（框下插入带圆角描边的列表容器，`ConstrainedBox(maxHeight ≈ 5 × 行高)` + `ListView`），随抽屉一起滚动；不建议用 `Overlay` / `DropdownMenu`（在可滚动的 modal sheet 里定位与裁剪易出问题）。最终视觉以 UI 稿 D7 为准。
- 新 l10n key 一律 `idCard` 前缀；id / en 两份 arb 同步，改完 `flutter gen-l10n`。

### 新增文案（l10n）

| key | id | en | 来源 |
|---|---|---|---|
| `idCardPetTypeRequired` | Jenis hewan wajib diisi | Please choose a species | PRD §3.4 / D-11 |
| `idCardGenderRequired` | Jenis kelamin wajib dipilih | Please choose a gender | PRD §3.4 / D-11 |
| `idCardCreateMissing`（占位 `{fields}`） | Lengkapi {fields} dulu | Fill in {fields} first | UI 稿 C4 / D-11 |
| `idCardMissingName` | nama hewan | pet name | **待确认** |
| `idCardMissingPetType` | jenis hewan | species | UI 稿 C4 示例 / en 待确认 |
| `idCardMissingBreed` | ras | breed | **待确认** |
| `idCardMissingGender` | jenis kelamin | gender | UI 稿 C4 示例 / en 待确认 |
| `idCardMissingBirthday` | tanggal lahir | date of birth | **待确认** |
| `idCardMissingAnd` | dan | and | UI 稿 C4 示例（两项用「dan」）；三项以上拼法待确认 |

拼接建议：1 项「A」；2 项「A dan B」；3 项及以上「A, B dan C」（en「A, B and C」）。示例：缺物种 + 性别 → 「Lengkapi jenis hewan dan jenis kelamin dulu」（与 UI 稿 C4 一致）。

### 本 story 不做

- 建卡成功后的付费抽屉落点（关闭 / 成功 → 新卡详情）与共享付费函数接入（Story 1.8）。
- 网格、Tab、列表（Story 1.5 / 1.6）；卡面组件与编号格式化（Story 1.2 / 1.3）。
- 后端任何改动（Story 1.1 已完成白名单与必填）；编号沿用（Epic 2）。
- 宠物档案表单的物种 / 品种组件。

### 测试

- 新 widget 测试 `petgo_app/test/profile/id_card_create_page_test.dart`：参照 `test/profile/id_card_page_test.dart` 的 `_FakeIdCardRepo` 写法（实现 `IdCardRepository` 全部方法，`createCard` 记录请求、可配置抛错 / 返回 `numberReused`）；override `idCardRepositoryProvider`、`petProfileProvider`（返回带 / 不带 `sex`、`petType` 的 `PetProfile`）；提交成功后会弹付费抽屉，需同时 override `pawCoinProvider` / `idCardHdPriceProvider`（或 fake `hdPrice()` 同步返回）并在测试里关闭抽屉。用 `locale: Locale('en')` 或 `'id'` 断言文案。
- 纯函数单测：`idCardMissingFields`（可放 `test/profile/id_card_form_fields_test.dart`）。
- 更新：`test/profile/ktp_fields_test.dart:152-158`、`test/analytics/v112_events_test.dart` 后缀表。
- L1：本地连本分支后端，`DB_NAME=<本分支库>`（见记忆「后端测试要连本分支的库」）；L2：模拟器（见记忆「本地开发环境坐标」「模拟器与 flutter run 的坑」）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-ktp.md#Story 1.7 / 新增文案 / UX-DR6 / UX-DR7 / NFR5]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-ktp.md#3.3 FR-142C / #3.4 FR-142D（字段表）/ #4 数据埋点]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-ktp-delta.md#AD-4 / AD-6 / AD-9 / AD-12 / §7]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md#D-9 / D-11]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-ktp.html#C1 ~ C4 / D1 ~ D7]

## 待确认项裁定（2026-10-12，权威，覆盖本文件上文的对应写法）

> 依据 `_bmad-output/planning-artifacts/v1.3.4/决策日志-ktp.md` D-13 ~ D-19 与技术裁定 T-1 ~ T-22。Completion Notes 里原列的「待确认」均已按此关闭。

- D-13：事件改名为 `id_card_form_opened` / `id_card_created`，**不放宽**命名白名单
- D-17：KTP / 居住证品种必填（与本 story 写法一致）
- T-14：缺项提示统一「Lengkapi {项} dulu」，项名见决策日志
- T-15：表单红字只按生日 / 物种 / 性别
- T-16：只提交本卡款表单字段

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，dev-story 流程手工执行）

### Debug Log References

- 建卡成功后付费抽屉打开期间按钮转圈持续动画（现状，Story 1.8 前保留），测试里点「Buat Kartu」后不能 `pumpAndSettle`，按固定时长推进。

### Completion Notes List

- **L1/L2 待本地验收**（Task 8 / AC9）：L1 连本分支后端 8 款各建一张，核 `card_type` / `card_variant` 落库、`intro` 与字段集外字段为 NULL、`number_reused` 埋点为 false；L2 模拟器看 8 款无 Tab、KTP 长表单抽屉内滚动「Selesai」吸底、物种 / 品种框下展开约 5 项内滚、「Lainnya」原位输入、C4 红字与置灰，以及档案创建 / 编辑页物种品种仍弹底部抽屉。
- L0 已绿：`flutter analyze` 无问题；`flutter test` 全量 2812 通过（新增 `id_card_create_page_test`、`id_card_form_fields_test`，更新 `ktp_fields_test`）；`widgets/pet_form_fields.dart` 无改动。
- 与 story 文字的出入：
  - AC1.1 写参数名 `style`；Story 1.5 已落地对外契约 `IdCardCreatePage(initialStyle:)` + `locationFor` / `styleFromQuery`，按规则不改前序契约，沿用 `initialStyle`（页面内 `_style` 只读，不再可切换）。
  - AC6.1「新增 `cardVariant`」已在 Story 1.5 完成，本 story 只删 `intro`。
  - AC7：D-13 改名后的 `id_card_form_opened` / `id_card_created` 本就符合词尾白名单（`_opened` / `_created` 已在表内），未扩表。
  - `idCardStylePelajar` 已无引用（建卡页三 Tab 删除、列表取注册表），从两份 ARB 删除。
- 按文末裁定：D-17 KTP / 居住证品种必填（标签带 `*`）；T-14 缺项提示「Lengkapi {项} dulu」/「Fill in {fields} first」，项名与拼接见 `id_card_form.dart`；T-15 表单内红字只有生日 / 物种 / 性别；T-16 只提交本卡款字段集内的值（字段集外一律 null）。
- 性别未选时预览卡面沿用现有映射（`KtpDefaults.jenisKelaminFor(null)` → JANTAN，为旧卡零变化不改），编号占位按性别加码 0（原待确认 5，保持现状）。
- 必填判定纯函数 `idCardMissingFields` / `idCardFieldRequired` / `idCardMissingText` 在 `presentation/id_card/id_card_form.dart`，建卡页与表单共用；草稿类型 `IdCardFormDraft` 取代 `_InfoDraft`。
- 物种 / 品种下拉为身份证专用组件 `id_card_pet_type_fields.dart`（框下内联展开、当前项打勾、品种菜单 5 行高内滚、「Lainnya」原位输入、OTHER 直接输入、预填自定义品种进入手填态），同时只展开一个（父级统一管理，点其他字段收起）。
- code-review（medium）6 条：采纳 5 条（迟到的档案预填不覆盖已确认编辑、生日 picker 初始日期夹到范围内、手填品种框获得焦点收起下拉、补测 FEMALE 预选、缺项名不回落枚举名）；不改 1 条——品种必填规则写在 `idCardFieldRequired` 单一纯函数内（story AC5.1 指定），未挪进注册表。

- 以下原「待确认 1 ~ 6」已按文末 2026-10-12 裁定（D-13 / D-17 / T-14 / T-15 / T-16）关闭，实现即按裁定口径。
- 待确认 1：**KTP / 居住证品种必填**——PRD §3.4 表与 FR-142C.5 写必填，且称「为现状口径不变」，但现状 App 建卡页并不校验品种（`id_card_create_page.dart:204` 只看名字与生日），UI 稿 D1 / D3 的「RAS」标签也没有 `*`。本 story 按 PRD 做成必填并加 `*`；物种选「Lainnya」（`OTHER`，无品种清单）时用户须手填品种才能建 KTP / 居住证。
- 待确认 2：**缺项提示文案**——各项名称（nama hewan / ras / tanggal lahir 及其英文）与三项以上的拼接方式文档未给，Dev Notes 中为拟稿；另 UI 稿 C1 说明写「缺生日时按钮上方显示 `idCardBirthdayRequired`（现状保留）」，而 C4 / epics AC 为统一的「Lengkapi {缺的项} dulu」。本 story 按 epics 统一用组合提示（只缺生日时为「Lengkapi tanggal lahir dulu」）。
- 待确认 3：**名字 / 品种缺失时表单内是否红字**——FR-142D.6 只列生日 / 物种 / 性别三条红字；名字、品种缺失本 story 只在建卡页组合提示里体现，表单内不加红字。
- 待确认 4：**字段集外的值是否提交**——PRD「表单只采集卡面上印出来的字段」。档案预填的品种等值在 Paspor / SIM / 学生卡表单里不出现，本 story 按「只提交字段集内的值、其余传 null」处理（落库为 NULL）；若希望照旧把档案品种等存进快照，需改 AC6.2。
- 待确认 5：**性别未选时预览卡面的性别位**——会话性别为 null 时，现有映射 `KtpDefaults.jenisKelaminFor(null)` 显示「JANTAN」（为旧卡零变化而设，不宜改），预览会在用户未选性别时显示 JANTAN；编号占位按性别加码 0 计算。文档未规定预览口径，本 story 保持现有映射不改。
- 待确认 6：卡款入参缺失 / 非法时回落 KTP（AC1.4）为防御性处理，正常入口只有网格，无产品可见影响。
- L1 / L2 待本地验收。

### File List
