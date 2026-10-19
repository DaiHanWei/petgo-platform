# Story 4.2: App——KTP 创建选宠物

Status: review

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`
> 口径优先级：`decision-log.md` ② 节（2026-10-13 I1–I3「先预填，用户可换」、**I2「默认预填 Diary Tab 当前选中的宠物；它若只读，则取最早创建的正常宠物」**）> `多宠物/1-4-0多宠物prd.md`（§3.1「按宠物计算的功能」表身份证 KTP 行：只列正常状态的宠物、卡片本身仍只挂账号）> `architecture-v1.4.0-multi-pet-delta.md`（AD-8「KTP 创建只列正常宠物（卡不挂宠物）」、AD-14、§5）> `多宠物/ui-1-4-0-multi-pet-integrated-v1.html`（I 泳道 I1 ~ I3）（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。跨分支契约以 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-10-18」节为准。
> 前置（同分支、按 sprint 顺序已完成）：**1.1**（`GET /pet-profiles/me?petId=` 按宠物取档案、`GET /pet-profiles/me/list`）、**1.5**（`petListProvider`、`currentPetIdProvider`、`petProfileProvider` 改派生兼容层）、**2.1**（`/me/list` 带 `status`）、**3.3**（选宠物抽屉 `PetPickerSheet`，`features/profile/presentation/widgets/pet_picker_sheet.dart`，单选 / 多选两种模式，3.3 文件已写明 4.2 I1 复用单选模式）、**4.1**（`PetPickerSheet` 单选模式的只读行 / 副标题参数，本 story 不用只读行）。
> ⚠️ 写本文件时（2026-10-18，`dev_1.4.0 @ 8ca69f9f`）上述 provider / 端点在代码里**都还不存在**；T0 先核对实际名字，有出入以代码为准并在 Completion Notes 记一句。
> 范围：**只做 App**。**后端零改动**——建卡接口 `POST /me/id-cards/v2` 的入参本来就是卡面快照（`CreateIdCardRequest`，与档案解耦），卡不挂宠物（1.3.4「卡快照不挂宠物档案」口径），无需传 petId。

## Story

As a 多宠物用户,
I want 创建身份证时选择是哪只宠物并自动预填,
so that 每只宠物都能有自己的身份证。

## Acceptance Criteria

**AC1 — 默认预填哪只（I2）** `[L0]`（decision-log I2 · Epic AC1 / AC2）
1. 进入建卡页时按顺序决定预填宠物：
   - ① Diary Tab 当前宠物（`currentPetIdProvider`）存在且状态 **ACTIVE** → 用它；
   - ② 否则（当前宠物只读、或当前宠物 id 已失效）→ `petListProvider` 中**最早创建**的 ACTIVE 宠物（列表已按 `created_at ASC, id ASC`，取第一只 ACTIVE）；
   - ③ 没有任何宠物 → 维持现状：不预填（草稿为空，必填缺项提示照常），不显示宠物行。
   - 「全部宠物只读」不会发生（默认名额 1 永久有效，decision-log 第一轮拍板），不单独设计；若数据异常出现（列表非空但无 ACTIVE），按 ③ 处理并不显示宠物行。
2. 预填字段与现状完全相同（`id_card_create_page.dart:61-74`）：名字、物种、品种、生日、性别（`idCardGenderFromPetSex`）、头像；档案无值不预选（性别 null、物种 null）；不预填一句话介绍。数据源从「唯一宠物」（`petProfileProvider`）改为**所选宠物的完整档案**（`GET /pet-profiles/me?petId=`，经 1.1 / 1.5 的按 petId 取档案 provider；列表项只有 id / 名字 / 头像 / 物种 / 状态，不够预填）。
3. 迟到预填不覆盖用户编辑：沿用 `_prefilled` 语义（`id_card_create_page.dart:51`、:223）——用户已在「Ubah info」确认过编辑后，首屏的异步预填结果不得再覆盖。

**AC2 — 宠物行（I2 / I3）** `[L0]`
1. 卡面预览上方新增一行（UI 稿 I2）：宠物头像（圆形，取所选宠物头像，无头像用物种 emoji / 现有默认头像组件）+「Untuk {name}」（I-1）+ 右侧「Ganti ›」（I-2）。整行可点（热区 ≥ 44），点击打开换宠物抽屉（AC3）。
2. **只有 1 只 ACTIVE 宠物**（I3）：显示「Untuk {name}」，**无**「Ganti ›」、整行不可点。
3. **0 只宠物**：整行不显示（= 现状）。
4. 宠物行放在 `AspectRatio` 卡面之前（`id_card_create_page.dart:127` 之前），`_busy`（上传 / 创建中）时「Ganti ›」禁用。

**AC3 — 换宠物抽屉（I1）** `[L0]`（AD-8 · PRD「只列正常状态的宠物」· Epic AC1）
1. 用 3.3 的 `PetPickerSheet` **单选模式**（骨架同 4.1 的 H1，单选卡片：选中 `mintTint2` 底 + 紫描边 + `check_circle`），不另写抽屉：标题「Pilih hewan」（I-3）；**只列 ACTIVE 宠物**（只读宠物不出现，不是置灰）；每行头像 + 名字 + 「物种 · 年龄」副标题（与 4.1 同一套格式化）；默认选中当前预填的宠物；吸底「Pakai」。下滑 / 返回 = 不换。
2. 选中另一只并点「Pakai」→ 拉这只的完整档案 → **草稿整体重置**为新宠物的资料（名字 / 物种 / 品种 / 生日 / 性别 / 头像，含此前「Ubah info」改过的字段与「Ganti foto」换过的照片），`_prefilled = true`；宠物行与卡面预览即时更新。拉档案失败 → toast「`idCardCreateError`」（现有键），草稿与宠物行保持原宠物不变。
3. 选的仍是当前那只 → 不重置、不请求。
4. 卡款（`initialStyle`）、卡面布局、水印、缺项提示、「Buat Kartu」流程与 HD 支付页**全部不变**；建卡请求体仍是草稿快照，**不带 petId**。

**AC4 — 文案** `[L0]`
新 arb 键 EN / ID 同加（见 Dev Notes「文案键」），先 `grep` 避免与 4.1 / 3.3 / 2.4 重名；`test/l10n/microcopy_rules_test.dart` 绿；**不整文件 `dart format`**。

**AC5 — 回归** `[L0]` `[L2]`
1. `flutter analyze` 0 issue；`test/profile/id_card_create_page_test.dart`、`id_card_create_v2_path_test.dart`、`id_card_form_fields_test.dart`、`id_card_watermark_test.dart`、`id_card_style_grid_test.dart` 既有用例全绿（它们 override `petProfileProvider` 的写法若因数据源改变而失效，改为 override 新 provider，**断言不改**）。
2. 单宠物账号：建卡页表现与改造前一致（多一行「Untuk {name}」、无「Ganti」）。
3. Android 模拟器演示 I2（多宠预填当前宠物 + Ganti）、I1（抽屉只列正常宠物）、I3。

## Tasks / Subtasks

- [x] **T0 开工核对**（结论写 Completion Notes）
  - [x] 0.1 1.5 的 `petListProvider` 元素字段（`id / name / avatarUrl / petType / birthday / createdAt / status`）、`currentPetIdProvider` 的失效回落逻辑
  - [x] 0.2 1.1 / 1.5 是否已有「按 petId 取完整档案」的 provider（如 `petProfileByIdProvider(petId)`）；没有则在 `profile_repository.dart` 加 `fetchPetProfile(int petId)` + `FutureProvider.family`
  - [x] 0.3 3.3 实际落地的 `PetPickerSheet` API（单选模式、传入列表 / 过滤方式、确认按钮文案参数）
- [x] **T1 预填决策**（AC1）：纯函数 `int? resolveKtpPrefillPetId({required int? currentPetId, required List<PetListItem> pets})`（放 `features/profile/domain/ktp_prefill_pet.dart`，便于单测；T0 更正：列表模型实际名 `PetListItem`，不是 `PetSummary`）；`initState` 改为按它取档案
- [x] **T2 宠物行**（AC2）：`_KtpPetRow`（私有 widget，放本页文件或 `id_card/` 子目录）
- [x] **T3 换宠物抽屉**（AC3）：`PetPickerSheet` 单选模式，传入过滤后的 ACTIVE 列表；选中后重置草稿
- [x] **T4 文案 + 测试**（AC4 / AC5）

## Dev Notes

### 现状（改动前必读，dev_1.4.0 @ 8ca69f9f 核实）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/profile/presentation/id_card_create_page.dart` | 637 行。`_IdCardCreatePageState`：草稿 `_draft` :49、`_avatarUrl` :50、`_prefilled` :51、`_busy` :52；**`initState` :57-75：`ref.read(petProfileProvider.future).then(p → IdCardFormDraft(name, petType, breed, birthday, gender) + avatarUrl)` :61-74**（直接取唯一宠物）；`_preview` :78-101；布局：卡面 `AspectRatio` :127-137 →「Ubah info / Ganti foto」两按钮 :139-161 → 缺项提示 :167-183 → 吸底「Buat Kartu」:184-200；`_editInfo` :207-226（确认后 `_prefilled = true` :223「用户已确认的编辑优先」）；`_editPhoto` :229-243 | `initState` 改为按 AC1 选宠物取档案；卡面前加宠物行；新增换宠物 | 卡款、水印、缺项、建卡与 HD 流程逐字不变 |
| `…/features/profile/data/profile_repository.dart` | `petProfileProvider = FutureProvider<PetProfile?>` :143（1.5 改为派生「当前宠物」兼容层） | 若无按 petId 取档案的 provider 则补（T0.2） | 1.5 的兼容层语义 |
| `…/features/profile/domain/pet_profile.dart` | `PetProfile`：`id` :24、`name` :25、`petType` :32、`avatarUrl` :33、`breed` :34、`birthday` :35、`sex` :39、`createdAt` :41、`weightKg` :47 | 不改（status 由 1.5 / 2.1 加在列表模型上） | — |
| `…/features/profile/presentation/id_card/id_card_form.dart` 等 | `IdCardFormDraft`、`idCardGenderFromPetSex`、`idCardMissingFields` | 复用 | — |
| `petgo-backend/…/profile/dto/CreateIdCardRequest.java` | 卡面快照入参（name / petType / breed / birthday / gender / avatarUrl …），**无 petId**；Javadoc「卡信息与档案解耦，前端默认预填档案值但可改」 | **不改** | 后端零改动 |
| `petgo-backend/…/profile/service/IdCardService.java` | `create` :106-130 只用 `ownerId` + 请求快照分配号与落库，**不读宠物档案**（`getMyIdCard` :176-181 / `generateSerial` :186-196 是旧单卡接口，1.2 负责多宠安全） | **不改** | — |

测试位置：`petgo_app/test/profile/id_card_create_page_test.dart`（:95-98、:351-354 用 `petProfileProvider.overrideWith` 造档案——数据源改后改 override 新 provider）、`id_card_create_v2_path_test.dart`、`id_card_form_fields_test.dart`；新测试同目录。

### 设计要点

- **为什么要拉完整档案**：`/me/list` 的列表项只给 id / 名字 / 头像 / 物种 / 状态（1.1 AC），预填还要品种、生日、性别；按 petId 取 `GET /pet-profiles/me?petId=`（AD-3 / P-4），不另开端点。
- **重置而非合并**：换宠物后保留旧宠物的手改字段会造出「A 的名字 + B 的生日」的混合卡（生日决定卡号且建卡后冻结），所以整体重置（UI 稿 I1 批注「建议全部重置为新宠物资料」）。
- **只列正常**：AD-8「KTP 创建只列正常宠物」；只读宠物不在抽屉里出现（不是置灰），默认预填也绕开只读（AC1 ②）。
- **当前宠物只从 `currentPetIdProvider` 读**（AD-14 / §5）；本页不自存 petId，只在页面会话内记住「本次选中的预填宠物 id」用于宠物行显示与抽屉默认选中。不回写 `currentPetIdProvider`（在 KTP 里换宠物不切换 Diary Tab 当前宠物）。

### 文案键（App，`app_id.arb` / `app_en.arb`）

| 键 | ID | EN | 来源 |
|---|---|---|---|
| `petPickerForName`（`{name}`） | Untuk {name} | For {name} | I-1（对照表只列前缀「Untuk / For」，带占位符拼整句，避免代码里拼接） |
| `petPickerChange` | Ganti | Change | I-2 |
| `petPickerChooseTitle` | Pilih hewan | Choose a pet | I-3（对照表写「沿用现有」，**实际 arb 里不存在**，见「需拍板」） |
| `petPickerUse` | Pakai | Use | UI 稿 I1 按钮；对照表未列（现有 `vetAssistAdopt`「Pakai」是兽医助手专用，不借用） |

复用：失败 toast `idCardCreateError`；物种名 `petTypeCat` / `petTypeDog`。`grep` 现状：`petPicker*` 前缀 0 处；若 4.1 / 3.3 已建同义键（如抽屉标题、确认按钮）以先到者为准。`publishForLabel`「Untuk:」带冒号、属发布页，不借用。

### 架构约束

- AD-8：KTP 创建只列正常宠物，卡不挂宠物；AD-14：当前宠物单一来源；§5：新代码不再引用 `petProfileProvider`。
- App 不渲染后端显示串；不改共享 CHECK；无迁移；无埋点（PRD §4 无 KTP 选宠物事件）。

### 本 story 不做

- 不改建卡接口与卡面；不给身份证加宠物关联；不改「Pilih Kartu」选款式网格与身份证列表页。
- 不改 HD 购买、分享奖励（1.2 / P-3 已处理分享奖励口径）。

### 测试

- **L0（云端必须全绿）**
  - `test/profile/ktp_prefill_pet_test.dart`（新，纯函数）：当前宠物 ACTIVE → 它；当前宠物只读 → 最早创建的 ACTIVE；当前宠物 id 不在列表 → 最早 ACTIVE；空列表 → null；列表无 ACTIVE → null。
  - `test/profile/id_card_create_page_test.dart`（补）：3 只（A 正常、B 正常当前、C 只读）→ 预填 B、宠物行「Untuk B · Ganti」；当前 = C → 预填 A；点「Ganti」抽屉只列 A、B，默认选中 B；选 A「Pakai」→ 名字 / 生日 / 头像换成 A，且此前「Ubah info」改过的名字被重置；拉 A 档案失败 → toast、仍是 B；只 1 只正常 → 无「Ganti」、行不可点；0 只 → 无宠物行（= 现状）；迟到预填不覆盖已确认编辑（既有用例保持）。
  - 既有 `id_card_*` 测试全绿；`microcopy_rules_test.dart` 绿。
- **L1**：无（纯 App，无后端改动）。
- **L2**：Android 模拟器连 stag，多宠账号演示 I2 / I1 / I3；建出的卡在身份证列表可见、卡面为所选宠物资料。

#### 验证层级

| 层级 | 内容 | 环境 |
|---|---|---|
| L0 | `flutter analyze`；`ktp_prefill_pet_test`、`id_card_create_page_test`、`id_card_create_v2_path_test`、`id_card_form_fields_test`、`id_card_watermark_test`、`microcopy_rules_test` | 云端可跑 |
| L1 | — | — |
| L2 | 模拟器 I1 ~ I3 | 本地 Android 模拟器 + stag |

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 4.2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#AD-8 / AD-14 / §5]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.1 按宠物计算的功能（身份证 KTP）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html#lane-I（I1 ~ I3）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md#I]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② 多宠物（I1–I3 / I2）]

## 需拍板 / 与文档不符（默认按建议执行）

1. **「Pilih hewan」并不存在**：对照表 I-3 写「沿用现有『Pilih hewan』」，但 `app_id.arb` 里没有这条（最接近的是 `petProfileTypePick`「Pilih jenis」）。建议新建 `petPickerChooseTitle`（ID「Pilih hewan」/ EN「Choose a pet」），EN 为提议值。
2. **「Pakai」按钮不在对照表**：UI 稿 I1 有吸底「Pakai」，对照表未登记。建议新建 `petPickerUse`（ID「Pakai」/ EN「Use」）。
3. **换宠物是否提示「已改的内容会被重置」**：UI 稿批注写「建议全部重置，第三步出提示」，但定稿与对照表都没有这条提示文案。建议**直接重置、不弹提示**（重置结果在卡面预览上一眼可见）；若要提示需补文案。
4. **I3 宠物行显示与否**：UI 稿 I3 批注「也可整行不显示、与现状一致——第三步定」，定稿画的是显示「Untuk {name}」无「Ganti」，按定稿执行。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- `flutter analyze` → No issues found
- `flutter test`（全量）→ 3678 passed / 1 skipped（基线 4.1 后 3661 / 1，+17）；code review 修复后 3680 / 1

### Completion Notes List

**L1/L2 待本地验收**（纯 App story，无 L1；L2 = Android 模拟器连 stag 演示 I1 / I2 / I3，建出的卡在列表可见、卡面为所选宠物资料）。

**T0 核对结论（以代码为准）**
- 0.1 列表模型实际是 `PetListItem`（`features/profile/data/pet_list_repository.dart`），字段 `id / name / avatarUrl / petType / breed / birthday / createdAt / status / isDefault / …`，`isReadOnly` = `status == READ_ONLY`（未知状态按 ACTIVE）；故事里的 `PetSummary` 不存在，T1 签名已改成 `List<PetListItem>`。`currentPetIdProvider` 只存「用户选过的那只」（可能失效 / null），回落在 `resolveEffectivePetId`（在列表里 → 它；否则 `isDefault`；再否则第一只）。后端 `isDefault` = `DefaultPetPolicy.pick` = 最早创建的 ACTIVE，所以「当前宠物 id 失效 / 没选过」回落到的就是最早 ACTIVE，与 AC1 ② 一致。
- 0.2 **没有**按 petId 取完整档案的 provider；仓库已有 `ProfileRepository.getMyProfile({int? petId})`（`GET /pet-profiles/me?petId=`，404 → null），所以没加 `fetchPetProfile`，只在 `profile_repository.dart` 末尾加了 `petProfileByIdProvider = FutureProvider.autoDispose.family<PetProfile?, int>`（`retry` 关掉：一次性取数，失败由页面提示；autoDispose 见下方 code review #3）。
- 0.3 `showPetPickerSheet` 单选 + 吸底确认模式在 4.1 已补好（`multiSelect: false` + `confirmLabel` + `initialSelectedId`），直接复用，**未改 `pet_picker_sheet.dart`**；调用方传已过滤的 ACTIVE 列表，不用 `lockedPetIds` / `disabledPetIds`。

**实现要点**
- `IdCardCreatePage.initState` → `_initPrefill()`：`petListProvider` → `resolveKtpPrefillPetId(currentPetId: currentPetIdProvider, pets)` → `petProfileByIdProvider(id)` 预填；预填字段与改造前逐字相同（抽成 `_applyProfile`）。迟到预填仍受 `_prefilled` 约束，并且只在宠物行仍是那只时才写入。本页不再引用 `petProfileProvider`（§5）。
- 宠物行 `_KtpPetRow` 放在卡面 `AspectRatio` 之前：圆形头像（无头像用 `Icons.pets` 占位，同 `PetCardRow`）+「Untuk {name}」+「Ganti ›」；≥ 2 只 ACTIVE 时整行 `InkWell`（最小高 48）；1 只时无「Ganti」、不可点；0 只 / 无 ACTIVE 不显示。
- 换宠物：选了另一只 → `ref.refresh(petProfileByIdProvider(id).future)` → 草稿整体重置（`IdCardFormDraft` 重建 + 头像换成新宠物的，`_prefilled = true`）；失败或 404 → toast `idCardCreateError`，草稿与宠物行不变；选的仍是当前那只 → 不请求。不回写 `currentPetIdProvider`。建卡请求体、卡款、水印、缺项提示、HD 流程未改动。
- 文案：新增 `petPickerForName`（Untuk {name} / For {name}）、`petPickerChange`（Ganti / Change）、`petPickerChooseTitle`（Pilih hewan / Choose a pet）、`petPickerUse`（Pakai / Use），EN / ID 两份 arb 同加（仓库只有这两个 locale），已 `flutter gen-l10n`。没有新埋点（PRD §4 无此事件）。

**2.4 E1 与本 story 的冲突（已按保守口径处理）**
- 2.4 在「Pilih Kartu」点卡款（`id_card_page._onStyleTap`）接了 `guardPetWritable`：Diary 当前宠物只读 → 弹 E1、进不了建卡页。这样 AC1 ②「当前宠物只读 → 预填最早创建的正常宠物」永远走不到，也和 AD-8「KTP 创建只列正常宠物（卡不挂宠物）」矛盾。
- 处理：入口改为 `_guardKtpEntry()`：当前宠物只读**且**已加载的列表里一只 ACTIVE 都没有 → 仍弹 E1（PRD「只读宠物不能买新付费项目」在这种情况仍然成立：没有能建卡的正常宠物）；只要还有正常宠物 → 放行，建卡页自动预填正常宠物，只读宠物不出现在抽屉里。列表没加载（不知道）→ 放行（同 `guardPetWritable` 的口径）。建卡页本身对「列表非空但无 ACTIVE」仍按 AC1 ③ 处理（深链等绕过入口的情况）。卡不挂宠物，后端建卡接口无宠物维度，没有 409 兜底问题。

**L0 已跑**
- `flutter analyze`：0 issue。
- `flutter test` 全量：**3678 passed / 1 skipped**（+17）。新增 / 补充：`test/profile/ktp_prefill_pet_test.dart`（新，6：ACTIVE 当前 / 只读当前 / id 失效 / 未选过 / 空列表 / 无 ACTIVE）；`test/profile/id_card_create_page_test.dart`（+9：I2 当前 ACTIVE 预填 B + 行「For Bobo · Change」；当前只读 → 预填 A、不取只读档案；I1 抽屉只列 A / B、默认选中 B、选 A「Use」→ 此前改过的名字被重置、提交快照为 A；拉档案失败 → toast 仍是 B；选同一只不请求 + 下滑关闭不换；I3 只 1 只正常 → 无 Change、行不可点；单宠物账号「For Oyen」预填不变；0 只 → 无行、不取档案、缺项提示；无 ACTIVE → 按 0 只）；`test/profile/id_card_style_grid_test.dart`（+2：当前只读另有正常宠物 → 进建卡页不弹 E1；全部只读 → 仍弹 E1）。`microcopy_rules_test`、埋点守卫测试全绿。
- 既有 `id_card_create_page_test` 14 例：只把 `petProfileProvider.overrideWith` 换成 `petListProvider`（缺省单宠物 id 1）+ `currentPetIdProvider` + `petProfileByIdProvider` 的 override，**断言未改**。`id_card_create_v2_path_test` / `id_card_form_fields_test` / `id_card_watermark_test` 不 pump 建卡页（源码 / 纯组件断言），无需改，全绿。
- 后端零改动，没跑 mvn；无迁移，flyway-guard 不涉及。

**L1 已写未跑**：无（纯 App）。

**与正文的出入**
- `PetSummary` → 实际 `PetListItem`（T1 签名已改）；没有新增 `fetchPetProfile`（复用已有 `getMyProfile(petId:)`），只加 family provider。
- 换宠物取档案期间（`_switching`）「Ganti」和「Buat Kartu」都暂时禁用（防止把旧宠物的草稿提交出去）；「Buat Kartu」样式不变，只是不可点。正文 AC2.4 只写了 `_busy` 时禁用「Ganti」。
- 宠物列表接口失败时：不显示宠物行，退回旧口径 `getMyProfile()`（不带 petId = 后端默认宠物）预填，与 `petProfileProvider` 失败回退同口径，没有引用 `petProfileProvider`。
- 2.4 建卡入口 E1 改为「没有任何正常宠物才拦」，见上一节。

**待确认**
1. `petPickerChooseTitle` 的 ID「Pilih hewan」/ EN「Choose a pet」、`petPickerUse` 的 ID「Pakai」/ EN「Use」是按「需拍板」1、2 的建议新建的（对照表没有这两条 / 写了「沿用现有」但仓库里没有），EN 值需运营补审。
2. 换宠物直接重置、不弹「已改内容会被重置」提示（按「需拍板」3 的建议）。
3. 2.4 建卡入口 E1 的放宽（当前宠物只读但有正常宠物 → 放行）需产品确认；若坚持「当前宠物只读就拦」，AC1 ② 将无法触达。

#### code review 结果（2026-10-19，按复审意见修复；仍未提交）

- **#1（CONFIRMED）换宠物与「Ubah info」/「Ganti foto」竞态**：换宠物取档案期间（`_switching`）「Ubah info」「Ganti foto」也禁用（原来只禁「Ganti」「Buat Kartu」）。另外 `_editInfo` / `_editPhoto` 打开时记下 `_pet?.id`，抽屉结果 / 上传结果回来时宠物已换（或仍在换）→ 丢弃，不写回草稿 / 头像，避免「For A」配 B 的名字 / 生日。测试 +1：取 A 档案挂起期间四个入口都禁用、宠物行仍是 B；落定后入口恢复、提交快照为 A。说明：UI 上「Ganti」在 `_busy`（上传中）时本来就禁用、编辑抽屉是模态，所以「打开时记下的宠物 id」这层防御从界面上触发不到，没有单独的 widget 测试，靠代码路径保证。
- **#2（PLAUSIBLE）单宠物用户每次进页多一次请求**：首屏预填的若就是 Diary 当前宠物（`resolveEffectivePetId(pets, currentPetIdProvider) == id`）→ 沿用改造前的 `petProfileProvider.future`（已加载就直接用缓存，新鲜度与改造前一致；返回的不是这只或抛错 → 再按 petId 取）。只有别的宠物（当前宠物只读时预填的最早 ACTIVE）、以及换宠物时才按 petId 取。这里**按复审要求**在新代码里引用了 `petProfileProvider`（偏离 §5「新代码不再引用」，理由是保持单宠物体验不回退）。测试：I2 用例改为断言「当前宠物走兼容层、按 petId 取 0 次」；+1 用例：退出再进建卡页不重复请求。
- **#3（PLAUSIBLE）换账号后残留上个账号的宠物资料**：`petProfileByIdProvider` 改为 `FutureProvider.autoDispose.family`；页面用 `_fetchPetProfile` 取数时 `listenManual` 保活、取完 `close` → 立即释放（下次取自然是最新的，不再需要 `ref.refresh`）。没有注册到 `resetUserScopedCaches`（autoDispose 后无需）。测试：换宠物取完后 `container.exists(petProfileByIdProvider(1)) == false`。
- 测试 harness：`_pump` 额外 override `petProfileProvider`（口径同生产：`effectivePetIdProvider` → 取那只），新增 `gates`、`currentProfileCalls` 参数；「迟到预填不覆盖已确认编辑」既有用例的 gate 改回挂在 `petProfileProvider` 上（单宠物 = 当前宠物路径），断言未改。
- **复审后 L0**：`flutter analyze` 0 issue；`flutter test` 全量 **3680 passed / 1 skipped**（+2）。L2 仍待本地验收。

### File List

- `petgo_app/lib/features/profile/domain/ktp_prefill_pet.dart`（新）
- `petgo_app/lib/features/profile/data/profile_repository.dart`（加 `petProfileByIdProvider`）
- `petgo_app/lib/features/profile/presentation/id_card_create_page.dart`
- `petgo_app/lib/features/profile/presentation/id_card_page.dart`（建卡入口 E1 口径）
- `petgo_app/lib/l10n/app_en.arb`、`petgo_app/lib/l10n/app_id.arb`
- `petgo_app/test/profile/ktp_prefill_pet_test.dart`（新）
- `petgo_app/test/profile/id_card_create_page_test.dart`
- `petgo_app/test/profile/id_card_style_grid_test.dart`

### Change Log

- 2026-10-19：code review 修复 #1（换宠物期间禁用 Ubah info / Ganti foto + 丢弃过期结果）、#2（当前宠物沿用兼容层缓存）、#3（`petProfileByIdProvider` autoDispose）；全量 3680 / 1。
- 2026-10-18：实现 Story 4.2（KTP 建卡选宠物：预填决策、宠物行、换宠物抽屉、4 条新文案）；建卡入口 E1 按 AD-8 改为「无正常宠物才拦」；L0 全绿，状态 → review。
