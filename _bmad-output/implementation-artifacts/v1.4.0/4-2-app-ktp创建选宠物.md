# Story 4.2: App——KTP 创建选宠物

Status: ready-for-dev

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

- [ ] **T0 开工核对**（结论写 Completion Notes）
  - [ ] 0.1 1.5 的 `petListProvider` 元素字段（`id / name / avatarUrl / petType / birthday / createdAt / status`）、`currentPetIdProvider` 的失效回落逻辑
  - [ ] 0.2 1.1 / 1.5 是否已有「按 petId 取完整档案」的 provider（如 `petProfileByIdProvider(petId)`）；没有则在 `profile_repository.dart` 加 `fetchPetProfile(int petId)` + `FutureProvider.family`
  - [ ] 0.3 3.3 实际落地的 `PetPickerSheet` API（单选模式、传入列表 / 过滤方式、确认按钮文案参数）
- [ ] **T1 预填决策**（AC1）：纯函数 `int? resolveKtpPrefillPetId({required int? currentPetId, required List<PetSummary> pets})`（放 `features/profile/domain/`，便于单测）；`initState` 改为按它取档案
- [ ] **T2 宠物行**（AC2）：`_KtpPetRow`（私有 widget，放本页文件或 `id_card/` 子目录）
- [ ] **T3 换宠物抽屉**（AC3）：`PetPickerSheet` 单选模式，传入过滤后的 ACTIVE 列表；选中后重置草稿
- [ ] **T4 文案 + 测试**（AC4 / AC5）

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

### Debug Log References

### Completion Notes List

### File List

### Change Log
