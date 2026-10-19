# Story 1.5: App——宠物列表、Diary Tab 切换、他人主页、建档与删除

Status: review

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`（从地基 Story 0.1 合入之后的 `dev_1.4.0` 切出）
> 口径优先级：`decision-log.md` ② 节 > `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md`（AD-1 ~ AD-17、P-1 ~ P-4、V-1 / V-2）> UI 稿 `多宠物/ui-1-4-0-multi-pet-integrated-v1.html`（定稿，A0 ~ A6、B1 ~ B6、C1 ~ C6、J0 ~ J3）；新增文案以 `多宠物/1-4-0多宠物-新增文案对照表.md` 为准。
> 前置（后端接口，字段名以实际代码为准，开工先核对）：**Story 1.1** `GET /api/v1/pet-profiles/me/list`（`PetListItemResponse`：`id`/`name`/`avatarUrl`/`petType`/`breed`/`birthday`/`createdAt`/`status`/`isDefault`/`tailsonalityBadge`/`diaryCount`）与 `/pet-profiles/me/*` 的可选 `?petId=`；**Story 1.3** `DELETE /pet-profiles/me?petId=`；**Story 1.4** 建档 409 `pet-slot-full`（扩展 `canRent`/`nextLevelSlot`/`petCount`/`slotTotal`/`unitPrice`）/ `pet-limit-reached`、`GET /api/v1/pet-slots/me`（`PetSlotOverview` 最小版：`total`/`cap`/`defaultSlots`/`levelSlots`/`rentalSlots`/`petCount`/`rentable`/`canRent`/`nextLevelSlot`/`unitPrice`/`days`）、`GET /api/v1/users/{userId}/pets`。
> 范围层：**只 App**（`petgo_app/`）。后端零改动。

## Story

As a 多宠物用户,
I want 在「我的」页看到所有宠物、在 Diary Tab 切换、在他人主页看到对方所有宠物,
so that 我能分别记录和浏览每只宠物。

## Acceptance Criteria

**AC1 — 宠物状态单一来源（AD-14、§5「App 状态单一来源」）** `[L0]`
1. 新增 `features/profile/data/pet_list_repository.dart`：`PetListItem` 模型（字段同后端 `PetListItemResponse`，`status` 解析为 `isReadOnly = status == 'READ_ONLY'`，未知值按 ACTIVE）+ `PetListRepository.fetch()`（`GET ApiPaths.petProfileList`）+ `petListProvider`（`FutureProvider<List<PetListItem>>`）。
2. `currentPetIdProvider`（`NotifierProvider<CurrentPetIdNotifier, int?>`，落在 `features/profile/data/current_pet_provider.dart`，与 `effectivePetIdProvider` 同文件）：本机偏好键 **`petgo.currentPetId`**（写法照 `DiaryOnlyNotifier`，`timeline_repository.dart`:167-189：`build` 先返回 null 再异步从 `AppPrefs` 恢复；`select(int petId)` 写入；`clear()` ~~删除~~ 写 0 表示未设〔T0：`AppPrefs` 没有删除键的方法，本 story 不改该类；宠物 id 恒正，0 = 未设〕），读写失败静默降级。
3. `effectivePetIdProvider`（`FutureProvider<int?>`）：取 `petListProvider`；`currentPetId` 在列表里 → 用它；否则（未设、已删、换了账号）→ 列表中 `isDefault == true` 的那只（没有则第一只）；列表为空 → null。**页面一律只读它，不自存 petId**。
4. 原 `petProfileProvider`（`profile_repository.dart`:143）**改为派生兼容层**：`FutureProvider<PetProfile?>`，`await effectivePetIdProvider.future` → null 返回 null，否则 `getMyProfile(petId: id)`。类型与名字不变，现有 19 个使用文件（Dev Notes「petProfileProvider 使用文件」）语义不变；**新代码不再引用它**（文件头注释写明）。
5. `resetUserScopedCaches`（`app.dart`:355）登记：`ref.invalidate(petListProvider)`、`ref.invalidate(effectivePetIdProvider)`、`ref.read(currentPetIdProvider.notifier).clear()`（换账号不得沿用上个账号的宠物 id）。登出路径照旧不失效（避免游客 401 弹框）。

**AC2 — 「我的」页 Hewanku 宠物列表（A0 ~ A3）** `[L0]` `[L2]`（epics 1.5 AC1、UX-DR1）
1. `_PetZone` / `_PetCard`（`me_page.dart`:195 / :275）改读 `petListProvider`：有宠物 → 圆形头像一排（复用 `PetAvatarItem`，头像 56，每只 diary 角标取 `PetListItem.diaryCount`）+ 末格「+」；小节标题「Hewanku」右侧加 `Kelola ›`（`petListManage`，→ 名额管理页占位，见 AC4.4）。点某只 → `currentPetIdProvider.select(id)` + 上报 `pet_switched {from_pet_id, to_pet_id, source: me_page}`（from == to 不报）+ `context.go('/profile')`。
2. A0：`HAS_PET` 但列表为空 → 沿用引导卡 `_PetGuideCard`（`mePetCardCreateTitle`），不改；PLANNING / ENTHUSIAST 不显示 Hewanku（现状）。A2：1 只 → 1 个头像 + 「+」，同样有 `Kelola ›`。
3. A3：`isReadOnly` 的宠物头像灰化 + **左下**锁标（避开右上 diary 角标）；点它同 A1（进 Diary Tab 切到这只）。只读横幅、拦截抽屉不在本 story（2.4）。
4. `PetAvatarItem`（`features/profile/presentation/widgets/pet_avatar_item.dart`）加可选 `readOnly`（默认 false）与 `size`（~~默认 56~~ **默认 64 = 改动前的实际直径**，「我的」页显式传 56，切换器用 40）参数；不改其他调用方的外观。〔T0 2026-10-18：代码里改动前是 `CircleAvatar(radius: 32)` = 64，默认 56 会改变他人主页外观，与「不改其他调用方外观」冲突，按后者取 64；另加 `selected`（当前紫环）/ `compact`（切换器只有头像 + 名字）/ `lockKey` 三个可选参数，默认值均为原外观〕
5. 加载中 / 失败：与现状一致（`loading` / `error` 均不渲染整块，不催建档）。

**AC3 — Diary Tab 宠物切换器（B1 ~ B6）** `[L0]` `[L2]`（epics 1.5 AC2、PRD AC2、UX-DR2）
1. 新组件 `features/profile/presentation/widgets/pet_switcher.dart`：放在 Diary Tab **页面最顶部、标题行「Diary {name}」之上**（`_ArchiveBody` 的 `ListView` 第一项，`growth_archive_page.dart`:442 之前）；头像 40 一排 + 名字，当前宠物紫环（主色取 `colors.dart` 令牌，不写色值），只读宠物灰化 + 锁标；末格「+」走 AC4 添加流程；超出宽度横滑。点另一只 → `select(id)` + 上报 `pet_switched {source: diary_tab}`。
2. 跟随当前宠物：标题行名字、`PetInfoCard`、三列统计、健康记录入口、「Kenali Hewanmu」入口、🏆 里程碑进度、Linimasa / Kalender、时间线全部按 `effectivePetIdProvider`；首页 Discovery **不跟随**（不碰 feed provider）。
3. B2 切换中：切换器立即高亮新宠物、标题行立即换名（名字取 `PetListItem.name`，不等档案请求）；下方数据区显示骨架 + 转圈（**不是**整页居中转圈），切换器保持可点。
4. B3 失败：复用现有时间线失败白卡 `growthLoadFailed`（「Gagal memuat, tarik untuk coba lagi」）+ `growthLoadRetry`（「Coba lagi」），点重试只重取当前宠物的数据；档案本身加载失败沿用现状落空态。
5. B5：只有 1 只 → 显示切换器 = 1 个头像 + 「+」（决策日志 2026-10-13）。B6：没有宠物 → 现有 `_EmptyProfileView`（紧凑建档条 + 推荐网格，`growth_archive_page.dart`:1080）原样，**不显示切换器**。
6. 记住上次查看：退出 App 再进 Diary Tab 停在上次选中的宠物（`petgo.currentPetId`）；该宠物已删除 → 回落默认宠物。
7. Diary 相关 provider 改为按 petId 的 family（见 Dev Notes「provider 改造清单」）；切换宠物 = 换 family 键，旧键缓存保留；发布 / 编辑 / 删除后的既有 `ref.invalidate(xxxProvider)` 对 family 整族失效，写法不变。

**AC4 — 添加宠物与名额拦截（A4 / A4b / A5 / A6）** `[L0]` `[L2]`（epics 1.5 AC3、PRD AC1）
1. 「我的」页与切换器的「+」共用 `features/pet_slot/presentation/add_pet_flow.dart` 的 `startAddPet(context, ref, {required String source})`：先 `GET /pet-slots/me`（App 模型 `PetSlotOverview`，2.4 会扩展同一模型）——`petCount ≥ cap` → **A4b**；`petCount ≥ total` → **A4**；否则 `context.push('/profile/create')`。接口失败 → 直接进建档页（后端 409 兜底）。
2. A4 名额已满抽屉（模型 `PetSlotOverview` / `PetSlotFullInfo` 落在 `features/pet_slot/data/pet_slot_repository.dart`）：`showConfirmSheet`（`shared/widgets/confirm_sheet.dart`:21）——图标 `Icons.pets`、标题 `petSlotFullTitle`、正文 `petSlotFullBody(petCount, slotTotal, price)`（`price` 用 `unitPrice` 按现有金额格式化工具输出「Rp5.000」，**不写死**）、主按钮 `petSlotFullRent`（「Sewa slot」→ 名额管理页占位，2.4 接通 D5 租用抽屉）、次按钮见 Dev Notes「待拍板 1」（本 story 次按钮位放「关闭」语义，用现有通用取消键）。弹出即上报 `pet_slot_full_shown {source: add_pet, action}`：用户点主按钮 `action = rent`、关闭 / 点次按钮 `action = close`（每次弹出报一次，在抽屉关闭时带结果上报）。
3. A4b 已达上限：同一 `showConfirmSheet`，标题 `petSlotLimitTitle`、正文 `petSlotLimitBody`、唯一按钮 `petSlotLimitOk`（「Oke」）。〔T0：`showConfirmSheet` 恒渲染次按钮，「唯一按钮」需给它加可选 `showCancel`（默认 true = 原行为，既有调用零影响），见 Completion Notes 偏差〕5 只时头像排横滑，「+」在最后。
4. 名额管理页占位：路由 **`/me/pet-slots`**（挂 `/me` 前缀，自动进受控路由名单），页面 `features/pet_slot/presentation/pet_slot_page.dart`：AppBar 标题 `petSlotPageTitle`（D-1「Kelola Hewan」），正文只列本人宠物（头像 + 名字，复用 `PetAvatarItem`），**不加任何名额 / 租用文案**——Story 2.4 在同一路由、同一文件里替换为 D1 ~ D15。
5. A5 建档页（`pet_profile_create_page.dart`）：去掉「已有档案直达」守卫（:226-238，有档案就跳走）——改为不跳转、直接渲染表单；提交收到 409：`typeSlug == 'pet-slot-full'` → 弹 A4（用 ProblemDetail 扩展成员）；`'pet-limit-reached'` → 弹 A4b；`'profile-exists'` / 其他 409 → 沿用现有提示（:137）。
6. 建档成功（A6 `profile_created_celebration_page.dart` 不改）：`currentPetIdProvider.select(新 id)`，`ref.invalidate(petListProvider)`；上报 `pet_added {pet_index, slot_source}`：`pet_index` = 建档后的宠物只数；`slot_source` 按建档**前**的 `PetSlotOverview` 推：第 1 只 → `default`；`pet_index ≤ defaultSlots + levelSlots` → `level`；否则 `rent`（取不到摘要时报 `default`）。

**AC5 — 删除单只宠物（C1 ~ C6）** `[L0]` `[L2]`（epics 1.5 AC4、PRD AC3、UX-DR3）
1. 编辑页删除入口（`pet_profile_edit_page.dart`，`petProfileDeleteButton`「Hapus Profil {name}」）不改外观；行为改为删当前编辑的那只：`deleteMyProfile(petId: 编辑中宠物 id)`。
2. C2 确认抽屉（`showConfirmSheet` danger，:135-145）：标题改用改写后的 `petProfileDeleteConfirmTitle(name)`（R-1「Hapus profil {name}?」）；**删的不是最后一只**（`petListProvider` 长度 > 1）→ 正文 `petDeleteConfirmBodyOther(name)`（C-1）；删最后一只 → 沿用原 `petProfileDeleteConfirmBody(name)`。
3. C3 删除中：现状（按钮图标换红色小转圈、禁用），不改。
4. C4 成功（非最后一只）：失效既有 provider 组（:154-165 那一串照留）+ `petListProvider`；当前宠物切到剩余宠物（`currentPetIdProvider.clear()` → `effectivePetIdProvider` 自动回落默认宠物，即最早创建的正常宠物）；`context.go('/profile')` 回 Diary Tab + `showAppToast(petDeleteSuccess(name))`（C-2「Profil {name} dihapus」）。切换器少一格。
5. C5 失败：现状（`petProfileDeleteFailed` Toast 3 秒，留在编辑页），不改。
6. C6 删最后一只：现状（`context.go('/profile')` 落 `_EmptyProfileView`，**无 Toast**，`pet_status` 不改）。

**AC6 — 他人主页宠物列表与访客视图（J0 ~ J3）** `[L0]` `[L2]`（epics 1.5 AC5、PRD AC4、UX-DR10）
1. `public_profile_pet_repository.dart` 加 `fetchAll(int userId)` → `GET ApiPaths.userPublicPets(userId)`（`/users/$userId/pets`，返回数组，空数组 = 没有宠物）；新 `publicProfilePetsProvider = FutureProvider.family<List<PublicProfilePet>, int>(…, isAutoDispose: true)`（与现有单只 provider 同样 autoDispose，防换账号串数据）。
2. `_PetSection`（`public_profile_page.dart`:710）改读列表：现状组件已是横滑 Row（:735-746），只换数据源；空 / 加载 / 失败 → 整块不渲染（现状）；`public_profile_page.dart`:161 的 `ref.invalidate(publicProfilePetProvider(userId))` 改为新 provider。**不显示只读锁标**（访客不暴露主人状态）。
3. J2：6 只时第 5 只露半个、可横滑（现有 `SingleChildScrollView`）。
4. J3：点某只 → 现有 `_open`（:775-780，登录门控 + `pet_card_tapped`）进站内访客视图，数据口径由后端 1.3 按该宠物计算，App 不改 `visitor_archive_view.dart`。
5. 旧 `publicProfilePetProvider` / `fetch` 若无其他引用则删除（grep 确认）。

**AC7 — `/pet-profiles/me/*` 请求带当前宠物** `[L0]`（AD-14「`ApiPaths` 的 `/me/*` 带 petId」）
- `ApiPaths` 加 `static String withPet(String path, int? petId)`（null → 原样；否则追加 `?petId=` / `&petId=`）与 `petProfileList`、`petSlotsMe`、`userPublicPets(int)`。
- Dev Notes「仓库改造清单」里的每个方法加 `int? petId` 参数并用 `withPet`；对应 provider 传 `effectivePetIdProvider` 的值。账号级端点（身份证卡列表 / 详情 / 新建、年龄卡奖励、新手任务、token 寻址的结果 / 快照详情）**不加**。
- 埋点、日志不得带宠物名。

**AC8 — 文案与埋点** `[L0]`
1. 新增 arb 键（**EN / ID 两份都加**，`app_en.arb` / `app_id.arb`，带 `@key` 元数据与占位符类型），见 Dev Notes「新增与改写的文案键」；改完跑 `flutter gen-l10n`（`pubspec.yaml`:161 `generate: true`）。App **不渲染后端显示串**（409 只用 type slug + 数字扩展成员）。
2. 埋点三个事件名照 PRD §4：`pet_added`、`pet_switched`、`pet_slot_full_shown`。仓库命名守卫（`test/analytics/v112_events_test.dart` 的 `allowedPrefixes`，:217）没有 `pet_` 前缀——**按既有扩展路径把 `'pet_'` 作为新模块入表**并写注释（「V1.4.0 multi-pet：模块是『宠物 / 宠物名额』这件事，跨我的页、Diary Tab、建档三处界面，挂到 `me_` / `diary_` 都会误导；新模块入表，非为遗留事件放宽」）；动作词尾 `_added` / `_switched` / `_shown` 已在 `allowedSuffixes` 内。**不删守卫、不放宽正则**。属性只用 PRD 列的键（`pet_index` / `slot_source` / `from_pet_id` / `to_pet_id` / `source` / `action`），值都是枚举或数字，过 `Analytics.scrub` 不被剥。
3. `flutter analyze` 0 issue；相关测试绿；**不对整个文件跑 `dart format`**（存量代码未统一格式化），手工对齐缩进。

**AC9 — 模拟器演示** `[L2]`
- Android 模拟器（本线后端未上任何环境前，需本地起含 1.1 ~ 1.4 的后端，或等用户指令部署 stag 后连 stag）演示并截图：A1（两只 + Kelola）、B1（切换器）、C2（非最后一只新正文）、J2（他人主页 > 4 只横滑）。云端不跑，标「L2 待本地验收」。

## Tasks / Subtasks

- [x] **T0 开工核对**：后端 1.1 / 1.3 / 1.4 实际接口字段名与本文件一致（不一致先改本文件）；对一遍「现状」表 file:line
- [x] **T1 状态层**（AC1）：`PetListItem` / `PetListRepository` / `petListProvider`、`currentPetIdProvider`、`effectivePetIdProvider`、`petProfileProvider` 派生、`resetUserScopedCaches` 登记
- [x] **T2 ApiPaths + 仓库带 petId**（AC7）
- [x] **T3 Diary provider family 化**（AC3.7、provider 改造清单）+ 调用点
- [x] **T4 「我的」页 Hewanku**（AC2）+ `PetAvatarItem` 参数
- [x] **T5 切换器 + Diary Tab 跟随 / 切换中 / 失败**（AC3）
- [x] **T6 添加流程 + A4 / A4b + 占位页 + 建档页守卫**（AC4）
- [x] **T7 删除流程**（AC5）
- [x] **T8 他人主页**（AC6）
- [x] **T9 文案 + 埋点 + 守卫入表**（AC8）
- [x] **T10 测试**（见「验证层级」）；`flutter analyze`；Completion Notes 标「L2 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 8ca69f9f）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/profile/data/profile_repository.dart` | `getMyProfile()` / `update()` / `deleteMyProfile()` 打 `ApiPaths.petProfileMe`（:117 / :122 / :127）；`petProfileProvider` = `FutureProvider<PetProfile?>` 直接 `getMyProfile()`（:142-145） | 三个方法加 `int? petId`；provider 改派生 | 404 → null 归一；`create` 不变 |
| `…/features/profile/data/timeline_repository.dart` | `timelineFirstPageProvider`（:152）、`DiaryOnlyNotifier` 偏好写法（:162-189）、`archiveStatsProvider`（:192）、`calendarMonthProvider`（:197）、`dayDetailProvider`（:203）均非 petId family；访客族与作用域选择器（:214-300） | 作者态四个 provider 改 family；选择器 owner 分支收 petId | 访客族与 `isAutoDispose` 注释所述约束 |
| `…/features/profile/data/milestone_repository.dart`（`milestoneListProvider` :109）/ `health_record_repository.dart`（`healthListProvider` :58） | 非 family | 改 family（键 petId） | — |
| `…/features/profile/presentation/growth_archive_page.dart` | `ref.watch(petProfileProvider)`（:155）；有档案 → `_ArchiveBody`（:200），整页 `CircularProgressIndicator` 加载（:195）；`DiaryHeader`（:447）；`_EmptyProfileView`（:1080）；状态改后三连 invalidate（:267-269） | 顶部插切换器；数据区按当前宠物；切换时只刷数据区 | 页头各模块顺序、`resolveDiaryUserState` 单一判定、访客分支 |
| `…/features/me/presentation/me_page.dart` | `_PetZone`（:195-226）读 `petProfileProvider`；`_PetCard`（:275-320）把单只包成长度 1 列表渲染 `PetAvatarItem`，点头像 `context.go('/profile')` | 改读 `petListProvider`、加「+」与 `Kelola ›` | `mePetCard` 等 ValueKey（测试在用，首只保留原 key，其余加序号） |
| `…/features/profile/presentation/widgets/pet_avatar_item.dart` | 参数 `name/avatarUrl/petType/birthday/tailsonalityBadge/diaryCount/onTap` + 三个 key（:20-45） | 加 `readOnly`、`size` | 现有调用方外观 |
| `…/features/user_profile/presentation/public_profile_page.dart` | `_PetSection`（:710）读 `publicProfilePetProvider(userId)` 单只并包成列表（:718-722）；横滑 Row（:735-746）；`_open` 登录门控 + `pet_card_tapped`（:775-780）；:161 invalidate | 换列表数据源 | 游客可见头像、点进需登录 |
| `…/features/user_profile/data/public_profile_pet_repository.dart` | `fetch(userId)` → `/users/$userId/pet`（:68-74）；provider family（:84） | 加 `fetchAll` + 列表 provider | autoDispose |
| `…/features/profile/presentation/pet_profile_create_page.dart` | 409 识别（:137）；「已有档案直达」守卫（:226-238） | 去守卫；409 分流 A4 / A4b | 表单与校验 |
| `…/features/profile/presentation/pet_profile_edit_page.dart` | 删除确认（:135-145）→ `deleteMyProfile()`（:153）→ 一串 invalidate（:154-165）→ `context.go('/profile')`（:175） | 按 petId 删；新正文；C4 Toast + 切宠 | C3 / C5 现状 |
| `…/shared/widgets/confirm_sheet.dart` | `showConfirmSheet({title, message, confirmLabel, cancelLabel, icon, danger, onConfirm, …})`（:21-34） | 不改，只调用 | 视觉规格 |
| `…/core/storage/prefs.dart` | `AppPrefs`，键前缀 `petgo.`（:8 起） | 不改类，键名 `petgo.currentPetId` 写在 notifier | — |
| `…/core/network/api_paths.dart` | `/pet-profiles/me/*` 常量（:156-306）、`userPublicPet`（:285） | 加 `withPet`、三个新路径 | 既有常量 |
| `…/app.dart` | `resetUserScopedCaches`（:355-388） | 登记新 provider | 登出不失效 |
| `…/core/router/app_router.dart` | `/profile` 是 Diary Tab（:149 / :159）；`/me` 前缀受控（:161）；`/me/addresses`（:705）附近有 `/me/*` 路由；`/profile/create`（:729）、`/profile/edit`（:785） | 加 `/me/pet-slots` | 受控名单逻辑 |
| `petgo_app/test/analytics/v112_events_test.dart` | `allowedPrefixes`（:217）无 `pet_`；`allowedSuffixes` 含 `_added` / `_switched` / `_shown` | 入表 `'pet_'` | 不删、不放宽 |

### provider 改造清单（AC3.7）

| provider | 改为 | 调用点处理 |
|---|---|---|
| `timelineFirstPageProvider`（`timeline_repository.dart`:152） | `FutureProvider.family<TimelinePage, int>`（键 petId，仍 watch `diaryOnlyProvider`） | 11 个文件 17 处：`watch/read` 加 `(petId)`；`invalidate(timelineFirstPageProvider)` 不变（整族失效） |
| `archiveStatsProvider`（:192） | `.family<ArchiveStats, int>` | 15 个文件 21 处（含「我的」页旧 diary 角标——改用 `PetListItem.diaryCount` 后可去掉该处 watch） |
| `calendarMonthProvider`（:197） | `.family<CalendarMonth, ({int petId, int year, int month})>` | 8 个文件 10 处 |
| `dayDetailProvider`（:203） | `.family<DayDetail, ({int petId, DateTime date})>` | 5 个文件 6 处 |
| `milestoneListProvider`（`milestone_repository.dart`:109） | `.family<MilestoneList, int>` | 9 个文件 22 处（含发布页 `publish_compose_page.dart`:439、健康页 `health_list_page.dart`:712） |
| `healthListProvider`（`health_record_repository.dart`:58） | `.family<List<HealthListItem>, int>` | 4 个文件 7 处 |
| 作用域选择器 `archiveStatsProviderFor` / `timelineFirstPageProviderFor` / `calendarMonthProviderFor` / `dayDetailProviderFor`（:270-300） | 作者态分支多收一个 `int ownerPetId` 参数 | 访客分支不变 |

> 计数为 2026-10-18 `grep -rn "\b<provider>\b" lib` 结果；以实际为准，全部改完 `flutter analyze` 必须 0 issue。petId 为 null（无宠物）时页面已走空态分支，不会构造这些 provider。

### 仓库改造清单（AC7，`?petId=` 透传）

| 仓库文件 | 带 petId 的方法 | 不带 |
|---|---|---|
| `features/profile/data/profile_repository.dart` | `getMyProfile`、`update`、`deleteMyProfile` | `create` |
| `features/profile/data/timeline_repository.dart` | 作者态 `getTimeline` / `getCalendar` / `getDay` / `getStats`（`ArchiveScope.me()` 分支） | 访客分支（按 token / petId 路径） |
| `features/profile/data/health_record_repository.dart` | 列表 / 新增 / 编辑 / 删除 | — |
| `features/profile/data/milestone_repository.dart` | 列表、打卡候选、打卡、分享、庆祝上报、名片分享信号 | — |
| `features/profile/data/id_card_repository.dart` | 旧单卡 `GET/POST /me/id-card`、`/me/id-card/hd-download`、身份证分享奖励（上报与展示口径） | 卡列表 / 详情 / 新建 / 单卡高清 / 定价、年龄卡奖励 |
| `features/tailsonality/data/tailsonality_repository.dart` | 提交结果、结果列表、卸下徽章 | 结果详情 / 解锁 / 配型解锁 / 佩戴（按 token） |
| `features/tailsonality/data/tailsonality_share_reward_repository.dart` | 上报 | — |
| `features/pet_passport/data/pet_passport_repository.dart` | 护照页、发起快照、快照列表 | 快照详情（token） |
| `features/pet_passport/data/passport_share_reward_repository.dart` | 上报 | — |
| `features/boarding_pass/data/boarding_pass_repository.dart` | 列表、详情、解锁 | — |

### `petProfileProvider` 使用文件（19 个，改派生后语义不变，逐个过一遍无需改动或只需小改）

`app.dart`、`features/me/presentation/me_page.dart`（本 story 改读列表）、`features/notify/presentation/notification_center_page.dart`、`features/place/presentation/place_checkin_button.dart`（打卡选择器在 3.3，本 story 维持当前宠物）、`features/profile/presentation/{age_card_page, archive_prompt_dialog, growth_archive_page, id_card_create_page, pet_card_page, pet_insights_page, pet_profile_create_page, pet_profile_edit_page}.dart`、`features/tailsonality/presentation/{share/result_share_card, tailsonality_match_page, tailsonality_quiz_page, tailsonality_result_page, tailsonality_results_page, tailsonality_retake}.dart`、`features/triage/presentation/triage_red_result.dart`。

### 新增与改写的文案键（AC8，取自文案对照表；现有 `pet*` 键前缀为 `petCard*` / `petInfo*` / `petInsights*` / `petProfile*` / `petRecommend*` / `petStatus*` / `petType*`，本表新键用 `petList*` / `petSlot*` / `petDelete*`，均不冲突；`growth*` 属 Diary 成长档案与 ① 等级线，本线不用）

| 键 | ID | EN | 占位符 | 来源 |
|---|---|---|---|---|
| `petListManage` | Kelola | Manage | — | A-1 |
| `petSlotFullTitle` | Slot hewan penuh | Pet slots are full | — | A-2 |
| `petSlotFullBody` | Kamu sudah punya {petCount} hewan dari {slotTotal} slot. Tambah slot dengan naik level, atau sewa slot {price} / 30 hari. | You have {petCount} pets in {slotTotal} slots. Get more slots by leveling up, or rent a slot for {price} / 30 days. | `petCount` int、`slotTotal` int、`price` String | A-3（表中两个 `{n}` 拆成两个具名占位符） |
| `petSlotFullRent` | Sewa slot | Rent a slot | — | A-4 |
| `petSlotLimitTitle` | Maksimal 5 hewan | Up to 5 pets | — | A-5 |
| `petSlotLimitBody` | Satu akun bisa punya maksimal 5 hewan. | One account can have up to 5 pets. | — | A-6 |
| `petSlotLimitOk` | Oke | OK | — | A-7 |
| `petSlotPageTitle` | Kelola Hewan | Manage Pets | — | D-1（占位页标题；2.4 沿用，不重复加） |
| `petDeleteConfirmBodyOther` | Diary, milestone, dan catatan kesehatan {name} akan dihapus permanen. Postinganmu tetap ada. Hewan lain tidak terpengaruh, dan slot {name} bisa dipakai lagi. | {name}'s Diary, milestones and health records will be deleted permanently. Your posts stay. Other pets aren't affected, and {name}'s slot can be used again. | `name` String | C-1 |
| `petDeleteSuccess` | Profil {name} dihapus | {name}'s profile deleted | `name` String | C-2 |
| `petProfileDeleteConfirmTitle`（**改写已有键**） | Hapus profil {name}? | Delete {name}'s profile? | 新增 `name` String | R-1（原「Hapus profil ini?」/「Delete this profile?」；唯一调用点 `pet_profile_edit_page.dart`:137，getter 变方法，同步改） |

- 沿用现有：`growthLoadFailed` / `growthLoadRetry`（B3）、`mePetsSection`「Hewanku」、`mePetCardCreateTitle`（A0）、`petProfileDeleteConfirmBody`（C6 最后一只）、`petProfileDeleteButton`、`petProfileDeleteFailed`（C5）、确认抽屉通用取消键（照 `pet_profile_edit_page.dart`:135-145 现有写法取用）。
- 「Lihat Level Saya」是 ① 的键 `growthViewMyLevel`（① Story 2.3 新增），**本线不重复加**，见「待拍板 1」。
- B-1 ~ B-3（只读横幅）、D-2 起（名额管理页）、E / F / G / H / I / K 组文案不在本 story。

### 架构约束

- AD-14：`petListProvider` + `currentPetIdProvider`（`AppPrefs` 键 `petgo.currentPetId`，失效回落默认宠物）；`petProfileProvider` 派生兼容层；Diary provider 按 petId family；`ApiPaths` 带 petId。
- §5：当前宠物只从 `currentPetIdProvider` / `effectivePetIdProvider` 读，页面不自存 petId；切换宠物时 petId family 的 provider 换键重取。
- NFR7：不渲染后端显示串（409 只用 slug + 数字扩展）；新增文案只取对照表。
- 记忆约定：主题色以 `colors.dart` 为准（当前淡紫 `#845EC9`，常量名 `mint*` 是遗留别名），不写色值；`setState(() => _future = …)` 箭头返回 Future 的陷阱不要再写。

### 本 story 不做

- 名额管理页 D1 ~ D15、租用 / 续租支付、只读横幅（B4 横幅、去铅笔）、统一拦截抽屉 E1、通知 K1 → **2.4**（本 story 只做头像锁标 / 灰化与占位页；2.4 的「切换器锁标」由本 story 提前完成，2.4 只核对）。
- 发布页 / 打卡选宠物、详情页关联宠物胶囊 → **3.3**；问诊选宠物 → **4.1**；KTP 选宠物 → **4.2**；升级庆祝永久名额提示 → **4.4**。
- 后端任何改动。
- **不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同），视觉验收走 L2 模拟器。

### 待拍板（实现按默认做法，不阻塞）

1. **A4 次按钮「Lihat Level Saya」**：UI 稿要求跳 ① 等级页（`/me/growth`、键 `growthViewMyLevel`），二者都在 ① 分支、`feat/1.4.0-multi-pet` 上不存在。默认做法：本 story **不渲染**该按钮（抽屉只有「Sewa slot」+ 通用关闭），在 `add_pet_flow.dart` 写固定注释 `// TODO(v1.4.0-multi-pet: ① 合入后加次按钮 growthViewMyLevel → context.push('/me/growth')，pet_slot_full_shown.action = upgrade)`，由 ① → ② 合并对齐时补上；正文里的「Tambah slot dengan naik level」照常显示，「升级」这条路以文字提示存在。若要求本分支就有按钮，需另定临时键名并接受合并时去重。
2. **`/me/list` 与 `/pet-slots/me` 两个请求**：「+」点击时才取 `/pet-slots/me`，不预取；弱网下点「+」会有一次短等待（按钮显示转圈）。

### 测试

**验证层级**
- **L0（云端可跑：`flutter analyze` + `flutter test`，无需模拟器）**：
  - `test/profile/current_pet_provider_test.dart`（新增）：偏好恢复；id 不在列表 → 默认宠物；`isDefault` 缺失 → 第一只；空列表 → null；`clear()` 后回落；读写偏好抛异常不崩。
  - `test/profile/pet_list_repository_test.dart`（新增，fake Dio）：解析字段；`status` 未知值按 ACTIVE；`withPet` 拼接（无 query / 已有 query）。
  - `test/profile/pet_switcher_test.dart`（新增，widget）：两只 → 两个头像 +「+」、当前紫环；点另一只调用 `select` 并经 `Analytics.debugCaptureSink` 断言 `pet_switched {from_pet_id, to_pet_id, source: diary_tab}`；只读 → 锁标；1 只 → 1 个头像 +「+」。
  - `test/profile/growth_archive_test.dart` / `growth_archive_state_branch_test.dart` 补：切换器在标题行之上；B2 切换时标题行立即换名、数据区出骨架、切换器仍可点；B3 失败卡与重试只重取当前宠物；B6 无宠物不渲染切换器；记住上次查看。
  - `test/me/me_page_test.dart` 补：两只头像 +「+」+「Kelola」；点头像 `select` + `go('/profile')` + `pet_switched {source: me_page}`；只读锁标在左下；A0 引导卡不变。
  - `test/pet_slot/add_pet_flow_test.dart`（新增）：`petCount ≥ cap` → A4b（「Oke」）；`petCount ≥ total` → A4 正文数字与价格格式「Rp5.000」、`pet_slot_full_shown` 的 action（rent / close）；未满 → 进 `/profile/create`；摘要接口失败 → 进建档页。
  - `test/profile/pet_profile_create_test.dart` 补：有档案不再跳走；409 `pet-slot-full` → A4、`pet-limit-reached` → A4b、`profile-exists` → 原提示；成功后 `select(新 id)` + `pet_added {pet_index, slot_source}`。
  - `test/profile/pet_profile_edit_test.dart` 补：非最后一只 → 标题带名字 + 新正文；最后一只 → 原正文；成功（非最后）→ Toast「Profil {name} dihapus」、回 Diary Tab、当前宠物回落；最后一只 → 无 Toast；`deleteMyProfile` 带编辑中宠物的 petId。
  - `test/user_profile/public_profile_pet_test.dart` 补：列表 6 只全部渲染、可横滑；空数组整块不渲染；无锁标；点进走登录门控。
  - `test/analytics/v112_events_test.dart`：`'pet_'` 入表后全部守卫绿；`test/analytics/v140_events_inventory_test.dart` 不需改（它是旧 Toko 清单）。
  - `test/l10n/*` 既有 arb 对齐测试绿（EN / ID 键集一致、占位符一致）。
- **L2（真机 / 模拟器视觉，云端不跑）**：AC9 四屏截图 + 视觉还原度对 UI 稿逐屏打分（标准 ≥ 95%）。本地验收时注意：内部包用 debug 签名、连真后端测试，提交 / 部署等用户明确指令。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 1.5]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#§0.3 第 9 条 · AD-3 · AD-14 · §5（App 状态单一来源 / 埋点 / 文案）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.1 切换当前宠物 / 「我的」页与他人主页 / 建档与删除 · AC1 / AC2 / AC3 / AC4 · §4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html#A0 ~ A6 · B1 ~ B6 · C1 ~ C6 · J0 ~ J3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md#A / C / D-1 / R-1]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② 多宠物（B5 单宠也显示切换器 / A1 Kelola 入口 / C2 新正文 / C6 = B6）]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/1-4-名额计算与解除单宠物限制.md#AC2 / AC5 / AC6]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- 基线（改动前，`feat/1.4.0-multi-pet` @ a87304b4）：`flutter analyze` → No issues found；`flutter test` → 3411 通过 / 1 跳过 / 0 失败。
- 改动后：`flutter analyze` → No issues found（0 新增）；`flutter test` 全量 → 3477 通过 / 1 跳过 / 0 失败（+66 条新增用例）。
- code review 修复后：`flutter analyze` → No issues found；`flutter test` 全量 → **3493 通过 / 1 跳过 / 0 失败**（再 +16 条）。

### Completion Notes List

**L1/L2 待本地验收**：AC9 模拟器四屏（A1 两只 + Kelola、B1 切换器、C2 非最后一只新正文、J2 他人主页 > 4 只横滑）截图与 UI 稿逐屏还原度打分（≥ 95%）云端不跑，需本地连含 1.1 ~ 1.4 的后端（或 stag）验收。本 story 只 App，**后端零改动**，无 L1 测试；无 Flyway 迁移。

**T0 核对结果**（后端 1.1 ~ 1.4 已提交代码 vs 本文件）：
- `PetListItemResponse` 字段 `id/name/avatarUrl/petType/breed/birthday/createdAt/status/isDefault/tailsonalityBadge/diaryCount` 与本文件一致（`isDefault` 用 `@JsonProperty("isDefault")`，`status` 本线恒 `ACTIVE`，2.1 起读列）。
- `PetSlotOverview` 字段 `total/cap/defaultSlots/levelSlots/rentalSlots/petCount/rentable/canRent/nextLevelSlot/unitPrice/days` 一致；409 扩展成员 `canRent/nextLevelSlot/petCount/slotTotal/unitPrice` 一致；ProblemDetail type 为 `https://petgo/errors/pet-slot-full` / `pet-limit-reached` / `profile-exists`，App 按末段 slug 判。
- `/pet-profiles/me/*`、`/me/health-records`、`/me/milestones/*`、`/me/tailsonality/results|badge`、`/me/passport(/snapshots)`、`/me/boarding-passes/*`、`/me/id-card(/hd-download)`、`/me/card-shares`、`/me/id-cards/{cardId}/share-rewards`、`/me/id-cards/share-reward`、`/me/{tailsonality,passport}/share-rewards` 均接受可选 `?petId=`；`GET /users/{userId}/pets` 游客 permitAll；`DELETE /pet-profiles/me?petId=` 只删该只。均与本文件一致，未改后端契约。
- 本文件与代码不一致处已先改正文（见 AC1.2 / AC2.4 / AC4.2 / AC4.3 的〔T0〕标注）。

**实现要点**
- 状态层：`pet_list_repository.dart`（`PetListItem` + `PetListRepository` + `petListProvider`，`retry: retryExcept4xx`）；`current_pet_provider.dart`（`currentPetIdProvider` / `resolveEffectivePetId` 纯函数 / `effectivePetIdProvider` / `requestPetIdProvider` / `readRequestPetId` / `peekEffectivePetId`）。`petProfileProvider` 改派生层（文件头注释写明新代码不再引用）。`resetUserScopedCaches` 登记 `petListProvider` / `effectivePetIdProvider` / `currentPetIdProvider.clear()` / `petSlotOverviewProvider`。
- Diary family 化：`timelineFirstPageProvider(int)` / `archiveStatsProvider(int)` / `calendarMonthProvider(({petId, year, month}))` / `dayDetailProvider(({petId, date}))` / `milestoneListProvider(int)` / `healthListProvider(int)`；作用域选择器作者态多收 `ownerPetId`；既有整族 `invalidate` 写法不变。Diary 页用档案 id 作 family 键；健康 / 里程碑 / 某天详情页读 `effectivePetIdProvider`。
- 仓库带 `?petId=`（`ApiPaths.withPet`）：profile（get/update/delete）、timeline 作者态四方法、health 四方法、milestone 六方法、id-card 旧单卡 + 分享奖励（上报 / 展示口径）、tailsonality（提交 / 结果列表 / 卸下徽章）+ 分享奖励、passport（护照 / 发起快照 / 快照列表）+ 分享奖励、boarding pass（列表 / 详情 / 解锁）。账号级端点未加。
- 「我的」页 Hewanku 改读列表 + 「+」+ `Kelola ›`（→ `/me/pet-slots` 占位页）；Diary 切换器（B1 ~ B6）；`startAddPet` + A4 / A4b + 占位页；建档页去「已有档案直达」守卫 + 409 分流 + `pet_added`；编辑页按 petId 删 / 改 + C2 新正文 + C4 Toast + 当前宠物回落；他人主页换 `publicProfilePetsProvider`（旧 `publicProfilePetProvider` / `fetch` / `ApiPaths.userPublicPet` 无其他引用，已删）。
- 埋点：`pet_switched {from_pet_id, to_pet_id, source}`（me_page / diary_tab，from == to 不报）、`pet_added {pet_index, slot_source}`、`pet_slot_full_shown {source: add_pet, action: rent|close}`；`v112_events_test.dart` 的 `allowedPrefixes` 按既有扩展路径入表 `'pet_'`（注释照本文件），未删守卫、未放宽正则。属性均为 id / 枚举 / 数字，不带宠物名。
- 文案：新增 10 键 + 改写 `petProfileDeleteConfirmTitle(name)`，EN / ID 两份（仓库只有这两个 arb），已 `flutter gen-l10n`。

**L0 测试（均已跑、全绿）**
- 新增：`test/profile/current_pet_provider_test.dart`（14）、`test/profile/pet_list_repository_test.dart`（7）、`test/profile/pet_switcher_test.dart`（6）、`test/pet_slot/add_pet_flow_test.dart`（9）。
- 补：`test/profile/growth_archive_state_branch_test.dart`（+7：B1 切换器在标题行之上 / B2 立即高亮 + 换名 + 骨架 + 切换器仍可点 / B3 失败卡 + 重试只重取当前宠物 / B5 / B6 / 记住上次查看 / 已删回落）、`test/me/me_page_test.dart`（+7：A1 / A2 / A0 / A3 锁标左下 / 点头像 select + go + pet_switched / from == to 不报 / Kelola）、`test/profile/pet_profile_create_test.dart`（+6：不再跳走 / 409 pet-slot-full → A4 / pet-limit-reached → A4b / profile-exists → 原提示 / select + pet_added / slot_source 三档）、`test/profile/pet_profile_edit_test.dart`（+5：C2 两种正文 / C4 Toast + 回落 + petId / C6 无 Toast / PATCH 带 petId）、`test/user_profile/public_profile_pet_test.dart`（+5：6 只全渲染横滑 / 空数组不渲染 / 无锁标 / 点第 3 只进访客视图 / 游客登录门控）。
- `test/analytics/v112_events_test.dart`、`test/l10n/*` 全绿；全量 3477 / 1 skip / 0 fail。
- 存量测试改动**只改调用形状 / 桩**（family `overrideWith((ref, _) …)`、假仓库方法补 `{int? petId}`、`ArchiveCalendar(ownerPetId: 1, …)`、补 `effectivePetIdProvider.overrideWithValue(const AsyncData(1))` / `petListProvider` 桩、`publicProfilePetsProvider` 桩），断言语义未改、未删、未 skip。`me_page_test` 的 diary 角标 12 改由列表项 `diaryCount: 12` 提供（原由统计栏桩提供），断言不变。

**与本文件文字的偏差**
1. `PetAvatarItem.size` 默认取 64（改动前实际直径），不是 56；「我的」页显式传 56。另加 `selected` / `compact` / `lockKey` 可选参数（默认 = 原外观）。
2. `showConfirmSheet` 加可选 `showCancel`（默认 true）—— A4b「唯一按钮」无此参数做不出来；本文件写「不改，只调用」。
3. `CurrentPetIdNotifier.clear()` 写 0（未设）而非删键（`AppPrefs` 无删除方法且本 story 不改该类）。
4. A4 次按钮位用 `commonClose`（「Tutup / Close」，现有通用键），对应「关闭」语义；本文件写「现有通用取消键」。
5. `petProfileProvider` 派生层：宠物列表取不到（网络 / 接口失败）时退回旧口径 `getMyProfile()`（不带 petId = 后端默认宠物），避免列表失败时把有宠用户误判成「无档案」。列表为空才返回 null。
6. 新增 `requestPetIdProvider` 供「带 petId 的请求」使用（列表失败 → 用户选过的 `currentPetId`，没选过 → null = 默认宠物，见 code review #5）；命令式调用点用 `readRequestPetId`（当前宠物未被任何界面建立时不额外发列表请求，直接用 `currentPetId`），里程碑庆祝回报用 `peekEffectivePetId`（必须同步发出；未解析 → `currentPetId`）。发布页取当前宠物允许发一次列表请求（冷启动直进发布页）。
7. 建档 409 / 「+」入口的抽屉都**不 await**（否则提交按钮 / 「+」在抽屉开着时一直转圈）；`pet_slot_full_shown` 仍在抽屉关闭时带结果上报。
8. 建档前名额摘要经路由 `extra` 带进建档页（`PetProfileCreatePage.slotOverview`），用于推 `slot_source`；直接进建档页（引导卡等）时 `slot_source = default`、`pet_index` 用已加载列表长度 + 1。
9. 编辑页删除：`auth.hasPetProfile` 只在删最后一只时置 false（原来删档恒置 false，多宠下会把仍有宠物的用户误标）；保存成功额外 `invalidate(petListProvider)`（名字 / 头像在列表里也显示）。
10. `ArchiveCalendar` 新增必填 `ownerPetId`；`DayDetailPage` 作者态读 `watchEffectivePetId`（无宠物 → 空日文案，列表失败 → 原失败态，换号重取中 → 转圈）。
11. （code review #3）AC4.5「去掉已有档案直达守卫」**只对「+」添加流程生效**：`startAddPet` 进建档页带 `?add=1`（`PetProfileCreatePage.addFlow`）；不带该参数的存量入口（深链生命周期 push、游客页 / 示例页、Toko 建档按钮、Diary 失败兜底、「我的」页 / Diary 空态引导卡等）保留改版前的「已有档案直达」（已有宠物 → Diary Tab）。
12. （code review #1）当前宠物偏好按账号绑定：新增偏好键 `petgo.currentPetOwner`；`resetUserScopedCaches(ref, userId:)` 调 `CurrentPetIdNotifier.bindUser` —— 同一账号（冷启动恢复）保留、不同账号清除；AC1.5 原写的「`clear()`」改为按账号判。
13. （code review #4）`petListProvider` 跟随当前账号（watch 账号 id，游客 → 空列表不发请求）；`resetUserScopedCaches` 不再手动 invalidate 它（保留「换号 = isReloading」信号）。

### code review 结果（2026-10-18，均已修复）

1. **CONFIRMED · 冷启动清掉「记住上次查看」**：`resetUserScopedCaches` 冷启动恢复会话也会跑，原先一律 `clear()`。→ 改为按账号绑定（`bindUser`，偏好存主人 id）：同账号保留、换号（含登出后换人登录）清除；进程内直接换号同步清。测试：`test/profile/current_pet_provider_test.dart`（bindUser 组 5 条）、`test/app/reset_user_scoped_pet_test.dart`（经 `resetUserScopedCaches` 的冷启动保留 / 换号清除 / A → B 立即清 3 条）。
2. **CONFIRMED · 「我的」页 diary 角标 / 性格小标不刷新**：凡失效 `archiveStatsProvider` 的 9 个文件与 Tailsonality 解锁 / 佩戴 / 卸下 3 处，同时 `invalidate(petListProvider)`。测试：`test/me/pet_list_refresh_guard_test.dart`（源码扫描守卫 + 正则自检，豁免 `app.dart` / quiz 重试并写明理由）、`me_page_test` 补「列表失效后角标 / 小标刷新」。
3. **CONFIRMED · 单宠存量入口丢了「已有档案直达」**：见偏差 11（`?add=1`）。测试：`pet_profile_create_test` 补「?add=1 有档案也渲染表单 / 不带参数 + 有宠物 → 直达 Diary / 不带参数 + 无宠物 → 表单」，`add_pet_flow_test` 断言「+」带 `?add=1`。
4. **PLAUSIBLE · 换号后 Diary 可能渲染上个账号的宠物**：`petListProvider` 跟随账号（偏差 13）；Diary 页在列表 `isReloading` 期间整页转圈（`diaryAccountSwitching`），档案不在本账号列表里时不渲染；健康 / 里程碑 / 某天详情页改用 `watchEffectivePetId`（换号重取中视为未定，不拿旧 petId 发请求）。测试：`growth_archive_state_branch_test` 补「换账号 → 重取期间不渲染上个账号宠物、不发按宠物请求，之后显示新账号宠物」。
5. **PLAUSIBLE · 列表取不到时写操作悄悄落到默认宠物**：`requestPetIdProvider` / `readRequestPetId` / `peekEffectivePetId` 在未解析时改用用户选过的 `currentPetId`（后端校验归属，非本人 → 404），没选过才不带（默认宠物，与旧版同口径）。读操作同口径（读到的是用户选的那只；那只已删且列表也取不到时会落到该页失败态）。测试：`current_pet_provider_test` 补「列表失败 + 选过 → 带它」。

### 待确认

1. **A4 次按钮「Lihat Level Saya」**：按「待拍板 1」默认做法未渲染，`add_pet_flow.dart` 留固定 TODO（① 合入后加 `growthViewMyLevel` → `/me/growth`，`action = upgrade`）。
2. **当前宠物档案加载失败（B3 之外）**：沿用现状落空态（`_EmptyProfileView`，不含切换器）；此时用户可从「我的」页点别的宠物切走，Diary 页本身没有切换入口。是否需要在失败态顶部也放切换器，请产品确认。
3. **`PetAvatarItem` 默认尺寸**：UI 稿 A1 注「头像 56（原 64，2026-10-15 缩小）」，但现网代码他人主页仍是 64。本 story 只让「我的」页用 56、他人主页保持 64（不改其他调用方外观）；他人主页是否也要缩到 56，请确认。
5. **`?add=1` 的取舍**（code review #3）：存量入口一律保留「已有档案直达」，所以多宠用户只能从「我的」页 / Diary 切换器的「+」新增宠物；空态引导卡等入口对已有宠物的用户仍直达 Diary。若产品希望别的入口也能直接加第二只，需逐个入口改成走 `startAddPet`。
6. **列表取不到时读操作带 `currentPetId`**（code review #5）：选过的那只若已被删除（且列表也取不到），护照 / 身份证 / 性格结果等页会落失败态而不是默认宠物；如需读操作仍回落默认宠物，请确认。
4. **删除默认宠物后的短暂闪现**：删掉的是默认宠物时，列表重取完成前（约一次请求时长）切换器 / 页面可能仍按旧列表算一帧，随后切到剩余宠物；L2 本地验收时留意。

### File List

新增：
- `petgo_app/lib/features/profile/data/pet_list_repository.dart`
- `petgo_app/lib/features/profile/data/current_pet_provider.dart`
- `petgo_app/lib/features/profile/presentation/widgets/pet_switcher.dart`
- `petgo_app/lib/features/pet_slot/data/pet_slot_repository.dart`
- `petgo_app/lib/features/pet_slot/presentation/add_pet_flow.dart`
- `petgo_app/lib/features/pet_slot/presentation/pet_slot_page.dart`
- `petgo_app/test/profile/current_pet_provider_test.dart`
- `petgo_app/test/profile/pet_list_repository_test.dart`
- `petgo_app/test/profile/pet_switcher_test.dart`
- `petgo_app/test/pet_slot/add_pet_flow_test.dart`
- `petgo_app/test/app/reset_user_scoped_pet_test.dart`（code review #1）
- `petgo_app/test/me/pet_list_refresh_guard_test.dart`（code review #2）

修改（lib）：
- `petgo_app/lib/app.dart`
- `petgo_app/lib/core/network/api_paths.dart`
- `petgo_app/lib/core/router/app_router.dart`
- `petgo_app/lib/shared/widgets/confirm_sheet.dart`
- `petgo_app/lib/l10n/app_en.arb`、`petgo_app/lib/l10n/app_id.arb`
- `petgo_app/lib/features/me/presentation/me_page.dart`
- `petgo_app/lib/features/profile/data/{profile_repository,timeline_repository,milestone_repository,health_record_repository,id_card_repository,milestone_celebration_reporter}.dart`
- `petgo_app/lib/features/profile/domain/milestone_share.dart`
- `petgo_app/lib/features/profile/presentation/{growth_archive_page,day_detail_page,health_list_page,milestone_list_page,milestone_instant_celebration,pet_card_page,pet_insights_page,id_card_detail_page,pet_profile_create_page,pet_profile_edit_page}.dart`
- `petgo_app/lib/features/profile/presentation/widgets/{archive_calendar,pet_avatar_item}.dart`
- `petgo_app/lib/features/tailsonality/data/{tailsonality_repository,tailsonality_share_reward_repository,tailsonality_providers}.dart`
- `petgo_app/lib/features/tailsonality/presentation/{tailsonality_quiz_page,tailsonality_results_page}.dart`、`.../share/{result_share_card,match_share_card}.dart`
- `petgo_app/lib/features/pet_passport/data/{pet_passport_repository,passport_share_reward_repository}.dart`、`.../presentation/pet_passport_page.dart`、`.../presentation/share/passport_share_card.dart`
- `petgo_app/lib/features/boarding_pass/data/boarding_pass_repository.dart`、`.../presentation/boarding_pass_detail_page.dart`、`.../presentation/share/boarding_pass_share_card.dart`
- `petgo_app/lib/features/content/presentation/publish_compose_page.dart`
- `petgo_app/lib/features/user_profile/data/public_profile_pet_repository.dart`、`.../presentation/public_profile_page.dart`
- （code review #2 追加失效宠物列表）`petgo_app/lib/shared/widgets/app_shell.dart`、`petgo_app/lib/features/consult/presentation/consult_conversation_page.dart`、`petgo_app/lib/features/content/presentation/content_detail_page.dart`、`petgo_app/lib/features/triage/presentation/triage_result_view.dart`、`petgo_app/lib/features/tailsonality/presentation/{tailsonality_result_page,tailsonality_match_page}.dart`

修改（test，只改调用形状 / 桩 + 新增用例）：
- `petgo_app/test/analytics/v112_events_test.dart`（`'pet_'` 入表）
- `petgo_app/test/me/me_page_test.dart`、`petgo_app/test/profile/{growth_archive_state_branch,pet_profile_create,pet_profile_edit}_test.dart`、`petgo_app/test/user_profile/public_profile_pet_test.dart`（新增用例）
- 调用形状 / 桩：`test/boarding_pass/{boarding_pass,boarding_pass_share}_test.dart`、`test/content/{publish_compose,publish_checkin_link}_test.dart`、`test/keepsake/fake_share_reward_repos.dart`、`test/mention/mention_entry_test.dart`、`test/pet_passport/passport_snapshot_test.dart`、`test/place/place_checkin_test.dart`、`test/profile/{archive_prompt_dialog,calendar_cell_priority,calendar_week_start,diary_near_empty_state,diary_no_profile_guide,growth_archive_r2,growth_archive,health_focus_from_timeline,health_list_page,id_card_detail_page,id_card_page,id_card_share_reward_hint,id_card_watermark,milestone_checkin,milestone_instant_celebration,milestone_list,milestone_local_celebrated,milestone_share,newbie_card,pet_card_share_analytics,pet_insights,timeline_five_class_render,timeline_pagination,timeline_place_checkin,visitor_deeplink_routing}_test.dart`、`test/profile/milestone_instant_fakes.dart`、`test/shop/consult_shop_boundary_test.dart`、`test/social/{comment_profile_entry,follow_list_entry}_test.dart`、`test/tailsonality/{tailsonality_badge,tailsonality_entry_coachmark,tailsonality_match_unlock,tailsonality_quiz,tailsonality_unlock}_test.dart`、`test/user_profile/{public_profile_blocked_view,public_profile_follow,public_profile_page,public_profile_posts,public_profile_self_view}_test.dart`

### Change Log

- 2026-10-18：实现 Story 1.5（App 多宠物：宠物列表 / Diary 切换器 / 他人主页列表 / 建档名额拦截 / 单只删除；`/me/*` 带 `?petId=`；Diary provider 按 petId 分族；三个埋点 + `'pet_'` 前缀入表；10 个新文案键 + R-1 改写）。L0 绿（analyze 0 issue；test 3477 / 1 skip / 0 fail），Status → review；L2 待本地验收。
- 2026-10-18：code review 修复 5 项（当前宠物按账号绑定、宠物列表随发布 / 佩戴刷新、存量建档入口保留直达、换号期间不渲染上个账号宠物、未解析时请求带用户选过的宠物）。analyze 0 issue；test 3493 / 1 skip / 0 fail。
