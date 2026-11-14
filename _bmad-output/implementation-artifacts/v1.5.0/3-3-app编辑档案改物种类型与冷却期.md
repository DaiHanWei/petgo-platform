# Story 3.3: app编辑档案改物种类型与冷却期

Status: ready-for-dev

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md` > PRD §3.3.1 / §3.3.2 / §6 > `architecture-v1.5.0-pet-species-delta.md`（D-8、D-9）> App UI 稿 C1 ~ C8（`宠物物种扩展/拆页-App/`）。
> 前置：1.2（PATCH 接 `species`、409 `pet-species-cooldown` + `changeableFrom`）、3.1（`PetProfile.species` / `speciesChangeableFrom`）、3.2（`SpeciesField` / `BreedField` / 抽屉）。
> 范围层：**只 App**：`pet_profile_edit_page.dart` 的类型框、品种框与保存流程。编辑页其余模块（健康线 ① 新增的字段等）不动。
> 迁移：无。

## Story

As a 建档时选错了类型的用户,
I want 在编辑页改类型，并清楚知道改完多久能再改,
so that 我敢改，也不会因为误触浪费 90 天的机会。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连 stag 后端真改（本地）· `[L2]` Android 模拟器视觉（本地）。

### AC1 — 类型框两种状态 `[L0][L2]`（UI C1 / C6）
1. 可改（`profile.speciesChangeableFrom == null`）：类型框与其他字段一样白底 + 下拉箭头，点开 `showPetSpeciesPicker(current: species, scene: 'edit')`（C2：宫格，当前项打勾）。删掉现有 `SpeciesField(locked: true, lockedHint: l10n.petTypeLockedHint)` 的恒锁写法（`ValueKey('petProfileEditTypeReadonly')` 改名为 `petProfileEditTypeField`，同步改测试）。
2. 冷却中（`speciesChangeableFrom != null`）：沿用原锁定样式（灰底 #F7F7F7 + 灰字 + 红锁 + 框内右侧红字 11.5），文案「Bisa diubah lagi {date}」替换「Tidak bisa diubah」；`{date}` 用界面语言的「日 月缩写」格式（ID「12 Feb」、EN「Feb 12」，`DateFormat.MMMd(localeName)`；若超值礼包线已提供「7 天后可再买」日期格式工具则复用它，否则先用 `MMMd`，Story 4.2 统一）。**品种仍可改**。
3. `petTypeLockedHint` 词条保留（其他地方可能引用），新增 `petTypeChangeableFrom`（`Bisa diubah lagi {date}` / `Can change again {date}`）。

### AC2 — 改类型交互 `[L0]`（UI C2 ~ C4、PRD 3.3.2）
1. 选了**不同**的类型（比较 `pickerType`）→ 弹 `showConfirmSheet`（C3）：neutral 紫、`Icons.swap_horiz_rounded`、标题「Ubah jenis hewan?」、正文「Ras akan dikosongkan. Setelah diubah, jenis hewan baru bisa diubah lagi 90 hari kemudian.」、按钮「Ubah」/「Batal」（`commonCancel`）、**不传 `onConfirm`**（只改本地表单）。
2. 「Ubah」→ 本地 `_species` = 新值、品种 controller 清空（C4，品种框显示「Pilih ras」）；「Batal」→ 原值不变。
3. 选了**相同**类型 → 直接收起，不弹确认、不算修改。
4. 「其他」组内经品种抽屉换物种（如 Musang Pandan → Small-clawed Otter）→ 不弹确认、不清品种（只是改品种，PRD 3.1）。
5. 未点「Simpan」就返回 → 什么都不存（编辑页本来就只在保存时提交）；再进页面类型与品种为原值（PRD 3.3 AC3）。

### AC3 — 保存 `[L0][L1]`（UI C5 / C7 / C8）
1. `ProfileRepository.update` 加 `String? species` 参数；编辑页仅当 `_species != 原值` 时传 `species`；改了类型且品种为空时传 `breed: ''`（显式清空；后端在 species 变而 breed 为 null 时也会清，双保险）。
2. 成功 → 与现状同：失效 `petProfileProvider`、`petListProvider`，`context.go('/profile')`（C5 回到 Diary，档案卡显示新类型）。
3. 409 `pet-species-cooldown`（C7，例如另一台设备刚改过）：`ProblemDetail.fromDioException` 取扩展成员 `changeableFrom`；`showAppToast`「Jenis hewan baru bisa diubah lagi {date}」（新词条 `petTypeCooldownToast`，EN「Pet type can be changed again on {date}」）；本地类型与品种**恢复为进页时的原值**，类型框切成 C6 锁定样式（用返回的日期）；**其他字段的修改保留在表单里**，用户可再点「Simpan」（后端已整次回滚，见 1.2 Q-2）。
4. 409 `pet-read-only` 仍走现有 `handlePetReadOnly`；网络失败（C8）沿用现有 `petProfileSaveFailed` toast，表单不变。
5. 处理顺序写在 `_submit` 的 catch 里，判 `pd.type` 结尾 `pet-species-cooldown`（与现有 problem type 判定写法一致）。

### AC4 — 文案 `[L0]`
`app_id.arb` / `app_en.arb`：`petTypeChangeConfirmTitle`（Ubah jenis hewan? / Change pet type?）、`petTypeChangeConfirmBody`（见 AC2.1 / EN「The breed will be cleared. After this, the pet type can only be changed again in 90 days.」）、`petTypeChangeConfirmYes`（Ubah / Change）、`petTypeChangeableFrom`、`petTypeCooldownToast`。`flutter gen-l10n`。

### AC5 — 测试 `[L0]`
1. Widget 测试（mock repository）：
   - 可改态点类型 → 宫格；选不同类型 → 确认抽屉；「Ubah」→ 品种清空；「Batal」→ 不变；选相同 → 不弹。
   - 冷却态 → 类型框锁定、显示「Bisa diubah lagi 12 Feb」（固定 locale `id`、日期 2027-02-12），点击无反应；品种框可点。
   - 保存：改了类型 → 请求含 `species` 与 `breed: ''`；没改类型 → 请求不含 `species`。
   - 409 冷却 → toast 文案含日期、类型品种回滚、类型框锁定、名字等其他输入保留。
2. 现有编辑页测试中按「类型恒锁」写的断言，按新规则更新（不删测试）。

### AC6 — 本地验收 `[L1][L2]`
- L1（stag）：新建宠物首次改类型成功；随即再进编辑页 → 锁定且日期 = 今天 + 90 天（WIB）（PRD 3.3 AC1）；用 curl 直接 PATCH 改类型 → 409 + `changeableFrom`（PRD 3.3 AC2）；猫 → 兔后里程碑列表变为「其他」清单、已得徽章的完成记录仍在（PRD 3.3 AC5，与 1.2 联调）。
- L2：C1 ~ C8 逐屏截图对照，还原度 ≥ 95%。

## Tasks / Subtasks

- [ ] **T0 开工核对**：1.2 的 409 type 与扩展成员名、3.1 / 3.2 的组件签名；编辑页行号（健康线可能已改这页——本线在功能分支上开发时看到的是地基版本，以实际为准）
- [ ] **T1** 类型框两态（AC1）
- [ ] **T2** 确认抽屉与本地状态（AC2）
- [ ] **T3** `ProfileRepository.update` + `_submit` 错误分支（AC3）
- [ ] **T4** 文案（AC4）、测试（AC5）
- [ ] **T5** `flutter analyze` + `flutter test`；Completion Notes 标「L1 / L2 待本地验收」

## Dev Notes

### 现状（2026-11-14 核 `dev_1.5.0` @ 9680309cf，前缀 `A/` = `petgo_app/lib/`）

| 文件 | 现状 | 本 story 改什么 |
|---|---|---|
| `A/features/profile/presentation/pet_profile_edit_page.dart` | `_petType`（:51，「编辑页置灰只读、不随 PATCH 提交」）；`_prefill`（:72-85）；`_submit`（:115-149）catch 只认 `pet-read-only`；类型框 `SpeciesField(locked: true, lockedHint: petTypeLockedHint)`（:371-378）；`BreedField(petType: _petType)`（:390-395）；头像占位（:337） | 改为 `_species` + `_originalSpecies`；类型框两态；确认；保存带 species；409 冷却分支 |
| `A/features/profile/data/profile_repository.dart` | `update(...)`（:101-122）「null = 不改」 | 加 `species` |
| `A/shared/widgets/confirm_sheet.dart` | `showConfirmSheet(...)` | 只用 |
| `A/shared/widgets/app_toast.dart` | `showAppToast` | 只用 |

### 设计要点
- 冷却以服务端为准（PRD「App 置灰只是提示」）：锁定态来自接口的 `speciesChangeableFrom`，保存被拒时以 409 返回的日期刷新锁定。
- 编辑页属于健康提醒线 ① 的 UI 稿范围（他们在这页加字段）；本线只碰类型框、品种框、头像占位与 `_submit` 的 catch 分支，合并时两边各自保留。
- 编辑的是「当前宠物」（`petProfileProvider`）；3.5 的推送落地先切当前宠物再 push 本页。

## Dev Agent Record

### Agent Model Used

### Completion Notes List

### File List
