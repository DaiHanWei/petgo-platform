# Story 3.5: app运营跳转物种类型页

Status: ready-for-dev

> 版本 v1.5.0 · 主题 pet-species（会话 ④）· 分支 `feat/1.5.0-pet-species`
> 口径优先级：`decision-log.md` > PRD §3.5「点开跳哪里」/ §6「上线推送」> `architecture-v1.5.0-pet-species-delta.md`（D-15）> App UI 稿 E1；`CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」（`OpsAppPage.PET_SPECIES` 后端已由地基加，App 值与跳转归本线）。
> 前置：3.1（`PetListItem.species`、统一显示函数）、3.3（编辑页可改类型）。
> 范围层：**只 App**。后端枚举、后台下拉标签地基已做；后台说明文案归 2.1。
> 迁移：无。

## Story

As a 收到「新的宠物类型上线了」推送的用户,
I want 点开就落到能改宠物类型的页面,
so that 不用自己找入口，顺手就给小家伙换上新身份。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` stag 后台发一条运营通知真点（本地）· `[L2]` 模拟器视觉 + 旧版包兼容（本地）。

### AC1 — 枚举与动作（纯函数层）`[L0]`
1. `lib/features/ops/domain/ops_target.dart` 的 `OpsAppPage` **末尾**加 `petSpecies('PET_SPECIES')`；改类注释「只这 5 个」为当前实际值说明。⚠️ 超值礼包线 ② 会在同一位置加 `starterPack('STARTER_PACK')`：合并冲突时**两值都留、`starterPack` 在前**（与后端 `OpsAppPage` 顺序一致）。
2. `lib/features/ops/domain/ops_target_action.dart` 新增 `final class OpsPetSpeciesAction extends OpsTargetAction`（`requiresLogin = true`，实现 `==` / `hashCode` / `toString`，照 `OpsOpenPublishAction`）；`resolveOpsTarget` 的 `appPage` 分支加 `OpsAppPage.petSpecies => const OpsPetSpeciesAction()`。
3. `test/ops/ops_target_test.dart` 补：`PET_SPECIES` 解析为 `OpsAppPage.petSpecies`、动作为 `OpsPetSpeciesAction`；`pet_species`（小写）→ null / `OpsNoAction`（区分大小写，现有规则）。

### AC2 — 执行层分流 `[L0][L1]`（PRD 3.5、UI E1）
在 `lib/features/ops/presentation/open_ops_target.dart` 的 `switch` 加 `case OpsPetSpeciesAction()`，经现有 `_runGated`（游客先强弹登录，登录后续跑）：
1. **遵守本文件的 ref 调用契约**：入口处同步读出所需对象（宠物列表读口——`petListProvider` 背后的 repository 或等价读口——以及 `currentPetIdProvider.notifier`），之后不再碰 `ref`；异步取列表后用 `rootNavigatorKey.currentContext` 导航。
2. 取「我的宠物」列表（`GET /api/v1/pet-profiles/me/list`，强制刷新，不用可能过期的缓存）：
   - 0 只 → `push('/profile/create')`（建档页，PRD 3.5 AC3）。
   - 1 只 → `currentPetIdProvider.notifier.select(id)` → `push('/profile/edit')`。
   - 多只 → `showPetPickerSheet(multiSelect: false)`：标题沿用「Pilih hewan」，说明新增「Pilih hewan yang mau diperbarui jenisnya」（新词条 `petSpeciesPushPickHint`，EN「Choose which pet to update」）；行副标题是组件原有的「类型 · 年龄」（3.1 已改读细分类型）；**`species == 'OTHER'` 的排最前**（只看细分类型仍为 OTHER 的，CIVET 等已细分的不算），其余按列表接口原顺序（创建顺序）；点一行 → 同 1 只的处理；关闭抽屉 → 什么都不做。
   - 只读宠物照常列出，进编辑页由现有只读态处理（待拍板 Q-7 默认）。
3. 拉列表失败 → 静默（与本文件其他分支同口径：运营跳转失败不打扰用户）。
4. 兽医账号 → 现有兜底直接 return。
5. 运营弹窗（`ops_popup_*`）与运营通知点击走的都是 `openOpsTarget`，**两处自动生效**，不另写。

### AC3 — 埋点 `[L0]`
不新增事件。现有 `ops_notification_opened`（按推送活动区分组）照常上报；核对点击运营通知时它仍在 `openOpsTarget` 之前发出，不因新分支漏报。

### AC4 — 测试 `[L0]`
`test/ops/open_ops_target_test.dart` 补（mock 列表读口与导航）：
1. 0 只 → 导航到 `/profile/create`。
2. 1 只 → `select(id)` 被调、导航到 `/profile/edit`。
3. 2 只（猫 + OTHER）→ 弹选宠物抽屉，第一行是 OTHER 那只（PRD 3.5 AC2）；点它 → `select(该 id)` + `/profile/edit`。
4. 3 只（猫、CIVET、OTHER）→ 只有 OTHER 那只被提前，CIVET 保持原位。
5. 游客 → 走强弹登录（`showHardDialog` 被调），不直接 push。
6. 列表接口抛错 → 不导航、不抛出。

### AC5 — 本地验收 `[L1][L2]`
- L1：stag 后台新建系统通知，跳转选「App 内页面 → 宠物类型」，指定自己的测试账号；分别用 0 / 1 / 2 只宠物的账号点开，落点符合 AC2（PRD 3.5 AC2 / AC3）。
- L2：E1 抽屉截图对照（还原度 ≥ 95%）；**1.4.2 正式版包**点同一条推送 → 正常打开 App、不闪退、不跳转（PRD 3.5 AC4，现有「不认识的跳转一律不跳」逻辑）。

## Tasks / Subtasks

- [ ] **T0 开工核对**：`ops_target.dart` 仍是 5 值（若 ② 已合进 dev 且本分支看得到 `starterPack`，按 AC1.1 处理）；`open_ops_target.dart` 的 ref 契约注释未变
- [ ] **T1** 枚举 + 动作 + 纯函数测试（AC1）
- [ ] **T2** 执行层分流（AC2）+ 词条
- [ ] **T3** 测试（AC4）；`flutter analyze` + `flutter test`；Completion Notes 标「L1 / L2 待本地验收」

## Dev Notes

### 现状（2026-11-14 核 `dev_1.5.0` @ 9680309cf，前缀 `A/` = `petgo_app/lib/`）

| 文件 | 现状 | 本 story 改什么 |
|---|---|---|
| `A/features/ops/domain/ops_target.dart` | `OpsAppPage` 5 值（:31-50），`fromWire` 不认识 → null | 加 `petSpecies` |
| `A/features/ops/domain/ops_target_action.dart` | sealed `OpsTargetAction` 5 种；`resolveOpsTarget`（:97-）`appPage` 分支（:111-120） | 加动作与映射 |
| `A/features/ops/presentation/open_ops_target.dart` | `openOpsTarget`（:36-80）入口同步读 provider 的契约；`_runGated`（:85-104） | 加分支 |
| `A/features/profile/data/current_pet_provider.dart` | `currentPetIdProvider` + `select(int)`（:82） | 只用 |
| `A/features/profile/presentation/widgets/pet_picker_sheet.dart` | `showPetPickerSheet(multiSelect: …)`（:22） | 只用；如需「说明」行而组件没有参数，加一个可选 `description` 参数（只追加） |
| 路由 `A/core/router/app_router.dart` | `/profile/create`（:818）、`/profile/edit`（:888） | 只用 |

### 设计要点
- 旧版 App 安全：后端发的是 `PET_SPECIES`，旧 App `fromWire` 返回 null → `OpsNoAction` → 不跳转（现有逻辑）。
- 「其他」排最前只看细分类型 `OTHER`：推送 A 组就是这批人，选中他们最需要改的那只。

## Dev Agent Record

### Agent Model Used

### Completion Notes List

### File List
