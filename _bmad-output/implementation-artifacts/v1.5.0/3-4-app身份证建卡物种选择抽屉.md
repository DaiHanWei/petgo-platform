# Story 3.4: app身份证建卡物种选择抽屉

Status: ready-for-dev

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md`（11-13：「统一一下，身份证也弹宫格抽屉」，`IdCardSpeciesDropdown / IdCardBreedDropdown` 删掉）> PRD §3.2（AC3）/ §3.3.3（卡是快照）> `architecture-v1.5.0-pet-species-delta.md`（D-13、D-18）> App UI 稿 D1 ~ D4。
> 前置：1.3（建卡接 `species`、卡返回 `species`、`IdCardDataResponse.species` 预填）、3.1（卡面物种 `cardNameId`）、3.2（抽屉组件）。
> 范围层：**只 App**：`id_card_create_page.dart` 的「Ubah info」抽屉类型 / 品种两项与建卡提交。
> 迁移：无。

## Story

As a 给宠物做身份证卡的用户,
I want 建卡时也用和档案页同一个抽屉选类型和品种,
so that 兔子能选「Kelinci」、品种能搜，卡上印的就是「KELINCI」。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连 stag 建卡（本地）· `[L2]` Android 模拟器视觉（本地）。

### AC1 — 删掉下拉组件 `[L0]`
1. 删除 `lib/features/profile/presentation/id_card/id_card_pet_type_fields.dart` 里的 `IdCardSpeciesDropdown`、`IdCardBreedDropdown` 及只为它们服务的私有件（`_menu`、`_DropdownAnchor`、`_option`）；若文件因此变空则删文件，并删掉对应测试 / 改为测新抽屉。
2. 全仓库 `grep -rn "IdCardSpeciesDropdown\|IdCardBreedDropdown" petgo_app/` 为 0。
3. 原来下拉展开时的紫色描边 + 向上箭头一起消失（UI D1 说明）。

### AC2 — 「Ubah info」里改用同一抽屉 `[L0][L2]`（UI D1 ~ D4）
1. 类型框：外观同档案页 `SpeciesField`（显示 emoji + 名；未选「Pilih jenis」；必填未选时现有红框 + `idCardPetTypeRequired` 错误文案保留）；点了**叠在「Ubah info」抽屉上**弹 `showPetSpeciesPicker(scene: 'id_card')`（宫格，D1；输入切列表，D2）。选中即收起回到「Ubah info」。
2. 品种框：同 `BreedField`；KTP / 居住证卡款必填（现有规则不变）；点了叠弹 `showPetBreedPicker(scene: 'id_card')`（D3；「其他」分组 D4）。搜索无结果、「Pakai」、已选手填值等状态与 3.2 完全相同（不重复实现）。
3. 换类型（pickerType 变）→ 清空品种（原逻辑）；「其他」内选具体物种 → 记下该物种 code。**建卡这里换类型不弹 90 天确认**（卡是独立快照，不改档案）。
4. 抽屉叠层：返回键 / 点遮罩先关上层选择抽屉，再关「Ubah info」。

### AC3 — 状态与提交 `[L0][L1]`（D-13）
1. `IdCardFormData`（`id_card_form.dart`）加 `String? species`；`petType` 由 `species` 推出大类（`PetSpeciesCode.groupWire`），**不再由用户直接选**。必填判定（`IdCardFormField.petType => d.petType != null`）改为 `d.species != null || d.petType != null`。
2. 预填（`id_card_create_page.dart:147` 一带）：从档案 / `IdCardDataResponse` 取 `species`（无则按 `petType`）。
3. 提交（`IdCardRequest` / `id_card.dart:273` 序列化）：带 `species` 与 `petType`（大类）。
4. 卡号预览 `speciesCodeFor(petType)`（`ktp_fields.dart:112`）与护照号预览**继续用大类**——兔子预览物种段 `00`，与后端一致。
5. 卡面物种文字（KTP `spesies`、SIM / 居住证 `NewCardDefaults.spesies`、学生卡 `_species`，3.1 已改为接 species）：预览用表单的 `species`，已建卡用卡快照 `species`（旧卡 null → 按 `petType`，显示不变）。

### AC4 — 测试 `[L0]`
1. Widget 测试：「Ubah info」点类型 → 弹出的是新抽屉（宫格 15 格，用 3.2 抽屉的 Key 断言），不是下拉浮层；搜索结果与档案页一致（同一组件，PRD 3.2 AC3）；选「Kelinci」→ 品种框可点、品种列表为兔子清单；提交请求体含 `species: RABBIT`、`petType: OTHER`。
2. 卡面：兔子 KTP 预览物种行为「KELINCI」、卡号预览物种段「00」；旧卡（无 species、petType=CAT）显示「KUCING」。
3. 原下拉组件的测试删除或改写，不 skip。

### AC5 — 本地验收 `[L1][L2]`
- L1（stag）：兔子建 KTP 卡成功，`GET /me/id-cards` 返回 `species = RABBIT`、卡号物种段 `00`；之后把宠物改成仓鼠，这张卡仍显示 KELINCI（快照，PRD 3.3.3）。
- L2：D1 ~ D4 截图对照，还原度 ≥ 95%。

## Tasks / Subtasks

- [ ] **T0 开工核对**：1.3 的建卡字段名、3.2 的抽屉签名；`id_card_create_page.dart` 行号（:553-590 状态、:699-730 类型 / 品种两项）
- [ ] **T1** 删下拉（AC1）
- [ ] **T2** 换抽屉（AC2）
- [ ] **T3** 表单数据 / 预填 / 提交 / 卡面（AC3）
- [ ] **T4** 测试（AC4）；`flutter analyze` + `flutter test`；Completion Notes 标「L1 / L2 待本地验收」

## Dev Notes

### 现状（2026-11-14 核 `dev_1.5.0` @ 9680309cf，前缀 `A/` = `petgo_app/lib/`）

| 文件 | 现状 | 本 story 改什么 |
|---|---|---|
| `A/features/profile/presentation/id_card/id_card_pet_type_fields.dart` | 333 行，`IdCardSpeciesDropdown`（:125）、`IdCardBreedDropdown`（:193）、`_menu` / `_DropdownAnchor` / `_option` | 删 |
| `A/features/profile/presentation/id_card_create_page.dart` | 预填 `petType: p.petType`（:147）；卡号 / 护照预览（:203-215）；「Ubah info」状态 `_petType`（:553-590）；类型 / 品种两项（:699-730） | 换抽屉、加 species |
| `A/features/profile/presentation/id_card/id_card_form.dart` | `IdCardFormData.petType`；必填判定（:63-99） | 加 species |
| `A/features/profile/domain/id_card.dart` | 卡模型 `petType`（:45,141,249）；请求序列化（:273） | 加 species |
| `A/features/profile/presentation/id_card/ktp_fields.dart` | `spesies`（:75，3.1 已改）、`speciesCodeFor`（:112，不改） | 只用 |

### 设计要点
- 全 App 只剩一个类型 / 品种选择组件（UI 稿页头结论）。
- 卡是快照：建卡时的类型印在卡上，之后改档案不影响；卡号物种码按大类（PRD「卡号里的物种码不变」）。

## Dev Agent Record

### Agent Model Used

### Completion Notes List

### File List
