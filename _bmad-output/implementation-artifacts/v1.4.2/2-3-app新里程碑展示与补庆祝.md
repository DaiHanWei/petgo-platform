# Story 2.3: 新里程碑的 App 展示与补庆祝（App，B1–B4）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`决策日志-social-topic.md`（R-4、B1）> PRD §3.2（FR-154，AC1 / AC7 / AC8 / AC10）+ §7 文案 > 架构 D-12（G-2、R-4）> UI 稿 B1–B4 逐屏说明。
> 前置：**2.1**（后端完成 / 通知 / 回报按账号；且 2.1 已在 `milestone_titles.dart`、`milestone_badge_assets.dart` 补好 6 个新编码的标题与语义键——本 story 不再重复加，只验证）、**2.2**（存量补算，联调用）。
> 后继：3.1 / 3.2 会在同一个 `milestone_celebration.dart` 上改按钮组与分享，本 story **只动内容区**（多宠头像行、隐藏 KOLEKSI、文案），不动按钮行为。
> 范围层：**只 App**。

## Story

As a 完成了账号级里程碑的用户,
I want 在合适的时候看到一次庆祝、看到全部宠物一起完成,
so that 不被打断、也不被重复弹窗。

## Acceptance Criteria

**AC1 — 徽章映射（按完整编码）** `[L0]`（epics 2.3 AC1、UI B3 说明）
1. `kMilestoneBadgeKeys`：`C-S19` / `D-S19` / `G-S12` → `first_friend`；`C-S20` / `D-S20` / `G-S13` → `first_save`（2.1 已加，本 story 验证）；**不按后缀判断**（`milestone_badge.dart` / `milestone_badge_assets.dart` 不得出现字符串切分，源码扫描测试已钉）。
2. 素材 `assets/milestone/first_friend.webp`、`first_save.webp` 未到 → `MilestoneBadge` 自动回落（庆祝页紫渐变奖杯 `_badge`、列表页级别色圆、通知中心 goldTint + `military_tech`），**不改代码**；素材到了直接放进 `assets/milestone/` 即生效（manifest 检测）。

**AC2 — 文案（不拼宠物名）** `[L0]`（R-4、PRD §7、UI B1 / B4）
1. `milestone_celebration_copy.dart` 新增 6 条（三物种同文案，**不含 `{name}`**）：
   - C-S19 / D-S19 / G-S12：ID 标题「Teman pertama! 🤝」正文「Kamu mulai mengikuti seseorang — makin seru bareng sesama pecinta anabul.」；EN「First friend! 🤝」/「You followed someone for the first time — more fun with fellow pet lovers.」
   - C-S20 / D-S20 / G-S13：ID「Simpan pertama! 🔖」/「Postingan pertama sudah tersimpan — buka lagi kapan saja.」；EN「First save! 🔖」/「Your first saved post — come back to it anytime.」
2. 通知中心 `_NotificationTile._milestoneCopy`（`notification_center_page.dart`:746-757）：targetRef 属于这 6 个账号级编码时，**无论 `petName` 有没有**都直接返回庆祝文案（标题 + 正文），不走「无宠物名 → 只显示短标题」分支（R-4）。
3. 时间线 `timeline_item_tile.dart`:398 与庆祝页走同一份文案（不含 `{name}`，天然不拼名），无需改。

**AC3 — 不新增即时弹出点；两枚同时待庆祝只弹一条** `[L0]`（PRD §3.2、AC10）
1. 关注按钮（`social/presentation/widgets/follow_button.dart`）、收藏按钮（`collect/presentation/collect_button.dart`）**不加**任何庆祝调用。
2. 两枚账号级编码同时未庆祝 → `resolveCatchup` 只弹一条、`codesToReport` 含两枚（现有 `highestLevelMilestone` 同级取第一个，行为不改）。
3. 同时有未庆祝 M / L → 弹 M / L 那条，这两枚由 KOLEKSI 带过、随同一次回报记为已庆祝（现有规则，`milestone_catchup.dart`:51-68，不改）。

**AC4 — 里程碑列表（B3）** `[L0]`
S 组末尾显示「Teman pertama」「Simpan pertama」两枚（后端 sortOrder 34 / 35 · 通用 19 / 20），分组标题「Level S — SMALL」与顺序照现有代码（`Text(_levelTitle)` 不转大写）；Header 分母与 S 组计数来自后端（猫 / 狗 35、S 组 /20；其他 20、S 组 /13），App 不写死。

**AC5 — 补庆祝按账号（回报后不再弹）** `[L0]`（PRD AC10）
1. Given 有未庆祝的账号级里程碑，When 进**任一** ACTIVE 宠物的里程碑列表，Then `_maybeCatchUp`（`milestone_list_page.dart`:103-133）补弹一次（`MilestoneCelebrationPath.catchup`）。
2. 回报（`_reportCelebrated` → `reportMilestoneCelebrated`）完成后，若 `codesToReport` 含账号级编码 → **失效整族 `milestoneListProvider`**（及成长档案未庆祝角标所在 provider），因为后端已把其他宠物的同一条也置为已庆祝，本地缓存不刷就会在切宠物后误弹。
3. 回报在途期间切到另一只宠物的列表也不弹：`locallyCelebratedMilestonesProvider` 的 code 集合（不带 petId 的那份）已含这些 code，`_maybeCatchUp` 会扣除（现有逻辑，补一个测试钉住）。

**AC6 — 多宠庆祝页（B2）/ 单宠（B1）** `[L0]` `[L2]`
1. 账号级编码 **且** 账号 ACTIVE 宠物 ≥ 2 只 → B2：
   - 正文与日期胶囊之间新增**宠物行**：全部 ACTIVE 宠物（按建档顺序，最多 5 只）头像 40 + 名字 11（单行省略），头像间距 12，头像 2px `splashInk` 描边 + 白 25% 外圈；正文 → 宠物行 20、宠物行 → 日期胶囊 12；
   - 日期胶囊只写日期（无物种 emoji、无名字）；
   - **不显示 KOLEKSI**（`_collection` 不渲染）。
2. 账号级编码但只有 1 只 ACTIVE 宠物 → 与 B1 相同（物种 emoji + 名字 · 日期胶囊 + KOLEKSI），文案仍是不含名字的那条。
3. 非账号级编码 → 现有行为一字不改（回归）。

**AC7 — 点通知进列表补弹（B4）** `[L0]`（G-2）
1. When 点通知中心那条账号级 `MILESTONE_SM_NODE`，Then 进**当前选中宠物**的里程碑列表并补弹；当前宠物非 ACTIVE（`PetListItem.status == 'READ_ONLY'`）→ 先把当前宠物切到账号下第一只 ACTIVE（`currentPetIdProvider.notifier.select(id)`），再进列表。
2. 点击埋点 `app_notification_item_tapped` 照旧（`notif_type = milestone_sm`、`level = S`）。

**AC8 — 文案与还原度** `[L2]`
文案按 PRD §7；B1–B4 对 UI 稿逐屏还原度 **≥ 95%**（本地 Android 模拟器截图 + 对稿打分，记入 Completion Notes）。

## Tasks / Subtasks

- [ ] **T1 账号级编码判定（App 侧唯一实现）**（AC2、AC5、AC6、AC7；R-4）
  - [ ] 1.1 `lib/features/profile/domain/milestone_account_codes.dart`（新）：`const Set<String> kAccountMilestoneCodes = {'C-S19','D-S19','G-S12','C-S20','D-S20','G-S13'};` + `bool isAccountMilestone(String code)`。显式列举、精确匹配，不切后缀；注释指向后端 `AccountMilestoneFlag`（2.1）。
  - [ ] 1.2 加一条跨库一致性测试（可选，推荐）：后端 `AccountMilestoneFlagTest` 读本文件比对集合，或 App 测试断言集合 == `kMilestoneBadgeKeys` 中值为 `first_friend` / `first_save` 的 key。
- [ ] **T2 文案**（AC2）
  - [ ] 2.1 `milestone_celebration_copy.dart`：在 `C-S18`（:127 块）/ `D-S18`（:327 块）/ `G-S11`（:485 块）之后各加两条 `MilestoneCelebrationCopy`（见 AC2.1）。该文件是里程碑文案的既有事实源（Dart 表、en / id 两语，**不是 ARB**）——沿用既有约定，不迁 ARB。
  - [ ] 2.2 `notification_center_page.dart` `_milestoneCopy`：`if (isAccountMilestone(code)) return localizedMilestoneCelebration(code, locale, '');`（放在 `name == null` 判断之前）。
- [ ] **T3 多宠庆祝内容区**（AC6；UI B2）
  - [ ] 3.1 `showMilestoneCelebration`（`milestone_celebration.dart`:38-67）新增可选参数 `List<CelebrationPet> accountPets = const []`（`CelebrationPet(name, avatarUrl, petType)`，放 `profile/domain/`）；`_MilestoneCelebrationView` 据 `isAccountMilestone(item.code) && accountPets.length >= 2` 切 B2 布局。
  - [ ] 3.2 宠物来源：在弹出前从 `petListProvider` 取（`status != 'READ_ONLY'`，按列表顺序 = 建档顺序，取前 5）。为避免 6 处调用方各写一遍，提供 `Future<List<CelebrationPet>> readAccountCelebrationPets(ProviderContainer c)`（经 `ProviderScope.containerOf(context, listen: false)` 取 container；读失败 → 空表 → 退化为 B1）。本 story 只在**补庆祝**（`_maybeCatchUp`）与**重温**（`_showCelebration`，`milestone_list_page.dart`:684-701）两处传入——账号级编码只会经这两条路径弹出（AC3：无即时弹出点）。
  - [ ] 3.3 宠物行组件：头像用现有 `PetSpeciesAvatar`（`profile/presentation/widgets/pet_species_avatar.dart`，无头像时物种默认图）；名字 `fontSize: 11`、`maxLines: 1`、`overflow: ellipsis`、白 70%；行 `Wrap` / `Row` 居中，间距 `AppSpacing.md`（12）。
  - [ ] 3.4 B2 隐藏 KOLEKSI：`if (widget.collection.isNotEmpty && !isMultiPetAccount)`；日期胶囊去物种 emoji 与名字，只留 `_formatLongDate`。
  - [ ] 3.5 **只改内容 Column**（:171-268 的中段），不碰按钮区与 `_onSharePressed`——3.2 会整体重构布局为吸底按钮组。
- [ ] **T4 回报后失效**（AC5.2）
  - [ ] 4.1 `milestone_list_page.dart` `_reportCelebrated`（:135 起）或 `reportMilestoneCelebrated(onDone:)`：`codesToReport.any(isAccountMilestone)` → `onDone` 里 `container.invalidate(milestoneListProvider)`（整族）+ 失效档案头未庆祝角标所在 provider（grep `uncelebrated` 定位）。⚠️ 用 provider 自己的 Ref / container，不要用已销毁的 WidgetRef（`milestone_celebration_reporter.dart`:22-28 注释同一教训）。
- [ ] **T5 通知点击（G-2）**（AC7）
  - [ ] 5.1 `notification_center_page.dart` `_onTap`（:297 起）：`item.type == 'MILESTONE_SM_NODE' && isAccountMilestone(item.targetRef ?? '')` → 读 `petListProvider`（已在本页 watch，:600 附近 `pets`）与 `effectivePetIdProvider`；当前有效宠物为 READ_ONLY → `await ref.read(currentPetIdProvider.notifier).select(firstActive.id)`；然后照常 `NotificationDeepLink.open` → `MILESTONE_NODE` → `DeepLinkRoutes.milestoneList`。其余类型不变。
  - [ ] 5.2 系统推送直跳不涉及（MILESTONE_SM_NODE 不推送）。
- [ ] **T6 测试（widget / unit，覆盖稿中每种状态）**（全部 AC）
  - [ ] 6.1 `test/profile/milestone_badge_test.dart`：新 6 条映射与回落（`MilestoneBadgeAssets.debugOverride` 空集 → 回落；含 `assets/milestone/first_friend.webp` → 出图）。
  - [ ] 6.2 `test/profile/milestone_celebration_test.dart`：B1（账号级 + 1 宠：有 emoji 胶囊、有 KOLEKSI、标题「Teman pertama! 🤝」无宠物名）、B2（账号级 + 3 宠：3 个头像 + 名字、无 KOLEKSI、胶囊只有日期）、5 宠上限、名字超长省略、非账号级回归（id / en 各一次）。
  - [ ] 6.3 `test/profile/milestone_catchup_test.dart` / `milestone_list_test.dart`：两枚同时未庆祝只弹一次且报两枚；有未庆祝 M → 弹 M、报三枚；回报含账号级 → 整族失效（用 fake repository 计数 fetch 次数）；切宠物后本地已庆祝集合挡住二次补弹。
  - [ ] 6.4 `test/notify/…`（新建或扩 `notification_center_page_test.dart`）：B4 文案无宠物名（有 petName / 无 petName 两种）；点击当前宠物为 READ_ONLY → 先 select 第一只 ACTIVE 再跳 `/profile/milestones`。
  - [ ] 6.5 `test/profile/milestone_v134_copy_test.dart` 风格加一条：6 个新编码 id / en 文案均不含 `{name}`。
  - [ ] 6.6 现有测试全绿（`flutter test`）。
- [ ] **T7 L2 验收**（AC8）：本地 Android 模拟器 + stag 后端（不连生产）造数：单宠账号 / 3 宠账号第一次关注 → 通知中心 B4 → 点开补弹 B1 / B2 → 关闭后切另一只宠物列表不再弹；截图对稿打分 ≥ 95%。

## Dev Notes

### 现状（核 `dev_1.4.2` @ c496e3c3）

- **庆祝页** `profile/presentation/widgets/milestone_celebration.dart`：`showMilestoneCelebration(context, item, {petName, path, onShare, onSeeAll, collection})`（:38-67）→ `showGeneralDialog` 全屏；内容区 Column（:171-268）：徽章 120 + 级别芯片 → 26 → 解锁标头 → 8 → 标题 26/1.2 → 10 → 正文 13/1.6 → 20 → 宠物·日期胶囊（物种 emoji 按 code 首字母，:280-284）→ 24 → KOLEKSI（`_collection`，:351-399）→ 28 → 分享按钮 → 14 →「Lihat semua」。文案取 `localizedMilestoneCelebration(code, locale, petName)`（:137）。
- **补庆祝** `milestone_list_page.dart` `_maybeCatchUp`（:103-133）：`resolveCatchup` 扣除 `justCelebrated` + `locallyCelebratedMilestonesProvider` → 帧后 `markMilestonesCelebrating` → `showMilestoneCelebration(path: catchup)` → `_reportCelebrated(codesToReport)`。`_catchupDone` 闸防重复。
- **重温** `_showCelebration`（:684-701）：点已完成徽章，不回报。
- **通知中心** `notify/presentation/notification_center_page.dart`：`petName` 取 `resolveEffectivePetId(pets, null)` 那只（:600-603）传给每个 tile；`_milestoneCopy`（:746-757）有宠物名 → 庆祝文案，无 → 短标题；图标 :821（MILESTONE_SM_NODE）；点击 `_onTap`（:297）→ `NotificationDeepLink.open` → `MILESTONE_NODE` → `/profile/milestones`（`deep_link_routes.dart`:79-80）。里程碑列表页按 `effectivePetIdProvider` 取当前宠物（`resolveEffectivePetId` 不看 ACTIVE / READ_ONLY，`current_pet_provider.dart`:111-118）。
- **文案表**：`milestone_celebration_copy.dart`（标题 + 正文，en / id，`{name}` 占位）、`milestone_titles.dart`（短标题，与后端 `titleId` 跨库比对）——这是里程碑文案的既有约定（Dart 常量表而非 ARB），CLAUDE.md「新增文案写 ARB」对里程碑不适用，沿用既有表；其余新 UI 字符串（本 story 无）才走 ARB。
- **宠物数据**：`petListProvider`（`profile/data/pet_list_repository.dart`:100）→ `PetListItem(id, name, avatarUrl, petType, …, status, isDefault)`；后端 `PetListService.listMine` 按 `petResolver.listOwned` 顺序（建档顺序，实现时核一次）。

### 拍板点（按推荐实现，Completion Notes 复述）

1. **宠物列表的取法**：弹出前经 container 读 `petListProvider`，不在庆祝 widget 内 watch——庆祝层是 root navigator 上的 dialog，宿主可能销毁（与 3.2 的「依赖在弹出时取好」一致）。
2. **G-2 切当前宠物**：直接 `select` 第一只 ACTIVE（会持久化为用户的当前宠物）。替代方案是给列表页传 `petId` extra，但列表页全链路按 `effectivePetIdProvider`，改动面大；切当前宠物与用户在档案页手动切换等价，可接受。
3. **只读宠物**：账号级里程碑不在只读宠物上完成（2.1），所以只读宠物列表里这两枚是锁定态，正确。

### UI 数值出处（不量截图，照说明）

- B1：标题 26/1.2、正文 13/1.6、段间距 26/8/10/20/24/28/14、胶囊 padding 14×7、KOLEKSI 14/16/16——`milestone_celebration.dart` 现值，**不改**。
- B2：头像 40、名字 11（micro）、头像间距 12（`AppSpacing.md`）、正文→宠物行 20、宠物行→日期胶囊 12、头像 2px `AppColors.splashInk` 描边 + `Colors.white.withValues(alpha: 0.25)` 外圈。
- B3：徽章 64 + 标题 11 / 行高 1.15（`milestone_list_page.dart`:660-661），无视觉改动。
- B4：标题 13.5 / w700（`notification_center_page.dart`:1288），无视觉改动。

### 禁止事项与踩坑

- 不按后缀判断账号级编码（G-S12 ≠ G-S19，B1 更正就是这个坑）。
- 不整文件 `dart format`（存量未格式化，会带出几百行无关 diff）；手工对齐，只跑 `flutter analyze`。
- 本 story 无 ARB 改动；若实现中新增任何用户可见字符串，必须写 `app_id.arb` / `app_en.arb` 后 `flutter gen-l10n`，不得硬编码，不改生成的 `app_localizations_*.dart`。
- 不在关注 / 收藏处弹庆祝。
- `setState(() => _future = …)` 箭头返回 Future 在 debug 下静默不刷新（memory 陷阱），写刷新逻辑用块体。

### 测试

- 云端只跑 **L0**：`flutter analyze`（零警告）、`flutter test`。**L2（模拟器视觉、还原度、联调 stag）待本地验收**，Completion Notes 写明「L1/L2 待本地验收」，不得标 done。
- 埋点：本 story 不新增事件（`milestone_celebration_shown` 已覆盖新编码）。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md#R-4、B1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.2、#7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#D-12]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#B1、B2、B3、B4]
- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 2.3]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/2-1-账号级里程碑后端.md]

## Dev Agent Record

### Agent Model Used

（实现时填写）

### Debug Log References

### Completion Notes List

- create-story：上下文分析完成，已生成完整开发指引（2026-11-04）。
- 实现完成时必须写明：「云端只跑 L0；L1/L2 待本地验收」。

### File List

## 待确认 / 发现的规划冲突

1. **epics 2.3 AC1 与 2.1 的边界**：徽章映射与短标题因后端跨库测试已在 2.1 加入，2.3 只验证；不是规划冲突，属落位调整。
2. **UI B4 说明**写「不写宠物名靠 C-S19 / C-S20 文案本身不含 `{name}` 来保证」，但现码在 `petName` 为空时走短标题分支，正文为空；R-4 要求走无 `{name}` 的标题 / 正文——本 story 按 R-4 统一走庆祝文案（无论有无宠物名）。
3. **G-2 实现方式**（切当前宠物 vs 传 petId）规划未定，按拍板点 2。
4. **「Bagikan ke Komunitas」在 B1 / B2 的行为**属 3.1 / 3.2；本 story 交付后到 3.2 之前，账号级庆祝页的主按钮仍是旧的站外分享（行为与线上一致）。
