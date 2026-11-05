# Story 3.2: 庆祝页重构与两个分享按钮（App，D1–D3）

Status: review

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`
> 口径优先级：`决策日志-social-topic.md` > PRD §3.6（FR-156「现状」「交互」「站外分享保留为次入口」「缺陷修复要求」、AC1–AC8）+ §4 + §7 > 架构 §1.2 第 7 条、D-21 > UI 稿 D1 / D2 / D3（及 B1 / B2 的按钮组）逐屏说明。
> 前置：**2.3**（多宠内容区、`readAccountCelebrationPets`）、**3.1**（`MilestoneShareCard`、`captureMilestoneShareCard`、`BragPostEntry.prepare/openPrepared`、`milestoneComposePreset`、`BragPostSource.milestoneCelebration`）。本 story 把零件接到庆祝页上，并统一改 6 处弹出点。
> 范围层：**只 App**。

## Story

As a 在庆祝页想分享的用户,
I want 点按钮必有反应、能发到社区也能分享到站外,
so that 不会「点了没反应」。

## Acceptance Criteria

**AC1 — 按钮组吸底（D1，所有级别、所有弹出点统一）** `[L0]` `[L2]`
1. 布局由「SafeArea › Center › SingleChildScrollView › Column（含按钮）」改为「SafeArea › Column[ Expanded(Center › SingleChildScrollView(padding 28×32) › 内容 Column), 吸底栏 ]」，外加一层 `IgnorePointer` 上沿渐隐：吸底栏上方 32 高、`AppColors.splashInk` 0 → 100% 竖向渐变，叠在滚动区底部、不占布局。
2. 吸底栏（不随内容滚动，底色 `splashInk`）= 上内边距 16 + 主按钮「📤 Bagikan ke Komunitas」（`milestoneCelebrateShare`，`FilledButton` 52 高 / 字 14·w700 / 圆角 14，现有样式搬移）+ 12 + 描边次按钮「Bagikan ke luar」（同高 52 / 圆角 14 / 字 14·w700，1.5 宽白 35% 描边，白字）+ 14 +「Lihat semua milestone →」（现有 `GestureDetector`，上下 4 内边距）+ 底部安全区。
3. 内容放得下时在滚动区内居中（同现 `Center`），放不下（L 级三句正文 + KOLEKSI 两排）从顶部开始、可滚，按钮始终在首屏。
4. ✕ 保留 `Positioned(top: 4, right: 4)` + `IconButton`（48×48 热区），左右滑关闭保留；彩纸保留。原有模块 10 项（B1）/ 9 项（B2）全部保留，间距照代码值、不压缩。

**AC2 — 依赖在弹出时取好，6 处弹出点统一改造** `[L0]`（PRD「缺陷修复要求」、架构 §1.2 第 7 条、D-21）
1. `showMilestoneCelebration` 在弹出**之前**用 `ProviderScope.containerOf(context, listen: false)` 取 `ProviderContainer`，按钮里一切读 provider（分享服务、里程碑仓库、当前宠物、宠物列表、本地庆祝标记）都走这个 container，**不再接收、不再持有宿主的 `WidgetRef`**；删除 `onShare` 参数。
2. 6 处生产弹出点全部改（见 Dev Notes 表）：删掉 `onShare: () => shareMilestoneWithLink(ref, …)` 闭包，改传 `petId`（这条里程碑所属宠物）与 2.3 的 `accountPets`；debug 钩子 `milestone_list_page.dart`:79 同步改签名。
3. 宿主页已销毁 / 已被盖住（如「去发布」后宿主发布 sheet 正在关闭）时两个按钮仍有效。

**AC3 — 点击立即加载态，连点只触发一次（D2）** `[L0]`（PRD AC7）
1. 点主按钮：主按钮保持紫底、文字换成 20 白色转圈（宽高不变）；次按钮与链接降到 40% / 50% 不透明度且不可点；✕ 仍可点（关闭即放弃）。
2. 点次按钮：次按钮自己转圈，主按钮与链接同样置灰不可点。
3. 处理中再次点击任何一个按钮都不再触发（状态位 `_busy`，同一时刻只允许一个动作）。

**AC4 — 「Bagikan ke Komunitas」（D2 → D4 / D5；失败 D3）** `[L0]`（PRD AC1 / AC2 / AC4 / AC5 / AC8）
1. 点击当下上报 `brag_post_tapped {source: 'milestone_celebration', code, level}`（`BragPostEntry.reportTap(..., extra:)`，3.1）。
2. 顺序：截里程碑卡（3.1 `captureMilestoneShareCard`，卡在庆祝层弹出时即屏外挂载，图片提前加载）→ `BragPostEntry.prepare` 转 JPEG → **关闭庆祝页** → 打开发布页（`BragPostEntry.openPrepared`，`preset = growthMoment`、`petIds` / `text` 取 3.1 `milestoneComposePreset`、`source = milestoneCelebration`）。
3. 截图返回 null 或 JPEG 转换失败（D3）：轻提示 `shareCardExportError`「Gagal membuat kartu. Coba lagi ya.」，`showAppToast(..., extraBottom: 96)`（底距 34 + 90 + 96 = 220，落在吸底栏上沿约 208 之上 12，不压按钮；不用 `top: true`，会压住 ✕）；**不关庆祝页、不打开发布页**；按钮恢复可点，可重试；上报 `milestone_share_failed {code, stage: 'render_card'}`。
4. 打开发布页抛异常（root context 不可用等）→ 上报 `stage: 'open_compose'`，静默结束（庆祝页此时已关）。
5. 发布页里用户取消（✕ / 下滑）→ 回到原页面，**不再弹回庆祝页**；发布成功走现有发布成功流程。
6. 陪伴满 100 / 365 天（C/D/G-L2、L3）同样走本流程，卡面为对应徽章与标题（PRD AC8）。

**AC5 — 「Bagikan ke luar」（站外分享，接手原主按钮）** `[L0]`（PRD AC6、FR-156.3 / .4）
1. 沿用 `shareMilestoneWithLink`：建分享链接（H5 `/m/{shareToken}`）→ 系统分享面板（文案 `milestoneShareText` + 链接）→ 面板返回后关闭庆祝页（沿用「await 后 `_close()`」）。
2. 建链失败（断网 / 后端错）→ 退化为只分享文案，上报 `milestone_share_failed {code, stage: 'create_link'}`，**照常拉起分享面板**。
3. **任何异常都不能吞掉分享面板**：`shareMilestoneWithLink` 里 `ref.read(shareServiceProvider)(message)`（`milestone_share.dart`:53，现在在 try 之外）改为在 try 保护内调用；分享服务本身抛错 → 上报 `stage: 'open_sheet'`、按钮恢复、不崩。
4. iOS：调用分享服务时传 `sharePositionOrigin` = 「Bagikan ke luar」按钮的全局矩形（iPad 不传会崩，iPhone 不传可能不弹；同 bug 20260707 / `share_service.dart` 头注释）。

**AC6 — 埋点** `[L0]`（PRD §4 FR-156）
`milestone_share_failed {code, stage}`，`stage ∈ render_card / open_compose / create_link / open_sheet`；`milestone_share_created`（站外建链成功）不变；`milestone_celebration_shown` 不变。事件名写字面量 `Analytics.capture('milestone_share_failed', …)`（`v112_events_test.dart` 按字面量提取；`milestone_` 前缀已登记）。

**AC7 — 端到端与还原度** `[L2]`（PRD AC1 / AC2 / AC3 / AC8）
6 处弹出点（含发帖「去发布」后、健康记录、打卡 picker、即时庆祝、补庆祝、重温）与陪伴满 100 / 365 天，点「Bagikan ke Komunitas」都能打开预填发布页；发布后帖子出现在 Feed、该宠物成长档案、追更该宠物的用户的关注流（Epic 1）；D1 / D2 / D3 与 B1 / B2 按钮组还原度 ≥ 95%。

## Tasks / Subtasks

- [x] **T1 庆祝页布局重构**（AC1；UI D1）
  - [x] 1.1 `lib/features/profile/presentation/widgets/milestone_celebration.dart` `_celebration`（:134-277）：按 AC1 改结构；按钮区（:240-267）整体搬到吸底栏；内容 Column 保留 2.3 改过的多宠分支。约 30–40 行改动（UI 估算）。
  - [x] 1.2 新 ARB key `milestoneCelebrateShareOutside`：ID「Bagikan ke luar」/ EN「Share to other apps」（PRD §7）；`flutter gen-l10n`。新 key 写两份 ARB，不改生成文件。
  - [x] 1.3 新增 widget key：`milestoneShareOutside`（次按钮）、`milestoneShareBusy`（转圈）；保留 `milestoneShare` / `milestoneCelebrateSeeAll` / `milestoneCelebrationClose` / `milestoneCelebration` 既有 key（老测试依赖）。
- [x] **T2 `showMilestoneCelebration` 签名与 container**（AC2；D-21）
  - [x] 2.1 新签名：`Future<void> showMilestoneCelebration(BuildContext context, MilestoneItem item, {required String petName, required MilestoneCelebrationPath path, int? petId, List<MilestoneItem> collection = const [], List<CelebrationPet> accountPets = const [], VoidCallback? onSeeAll, Future<void> Function()? beforeCompose})`——删 `onShare`。
  - [x] 2.2 弹出前：`final container = ProviderScope.containerOf(context, listen: false);`、`final l10n = AppLocalizations.of(context)`、`final locale = Localizations.localeOf(context)`、分享文案 `l10n.milestoneShareText(localizedMilestoneTitle(item.code, locale))` 都先取好传进 `_MilestoneCelebrationView`（照 `milestone_instant_celebration.dart`:129-136「在 show 之前取好」）。
  - [x] 2.3 「发到社区」的打开发布页放在 **dialog 关闭之后、`showMilestoneCelebration` 返回之前**：`final r = await showGeneralDialog<_CelebrationExit>(…)`；`r` 是 `compose(jpeg, preset)` → `await beforeCompose?.call()` → `final ctx = rootNavigatorKey.currentContext`（`core/router/app_router.dart`:136）→ `await BragPostEntry.openPrepared(ctx, …)`（**await 到发布页关闭**）→ 返回。这样调用方 `await showMilestoneCelebration` 之后的收尾（回报、pop、跳转）一定发生在发布页关闭之后，不会把刚打开的发布页 pop 掉。
- [x] **T3 两个按钮的行为**（AC3 ~ AC6）
  - [x] 3.1 `_MilestoneCelebrationViewState` 加 `_busy`（`null / community / outside`）；`_onCommunityPressed`：`reportTap` → `setState(busy=community)` → `captureMilestoneShareCard(_cardKey)` → `BragPostEntry.prepare(context, png, toastExtraBottom: 96)` → 失败：`Analytics.capture('milestone_share_failed', {'code': code, 'stage': 'render_card'})` + 复位；成功：`Navigator.of(context).pop(_CelebrationExit.compose(...))`。
  - [x] 3.2 卡片屏外挂载：`Stack` 里加 `Positioned(left: -2000, …)` + `IgnorePointer` + `CardFrame(boundaryKey: _cardKey, canvas: CardCanvas.square, child: MilestoneShareCard(item, pets, completedAt))`；`pets` = 账号级 → `accountPets`，否则当前宠物一只（名字 / 头像 / 物种从 `container.read(petListProvider)` 中按 `petId` 取；取不到用 `petName` + 物种默认头像）。`completedAt` 为 null（重温旧数据）时用 `DateTime.now()`。
  - [x] 3.3 `_onOutsidePressed`：`setState(busy=outside)` → 取按钮 `RenderBox` 全局矩形 → `await shareMilestoneWithLink(container, …, sharePositionOrigin: rect)` → `_close()`。
  - [x] 3.4 「Lihat semua」`_seeAll` 不变（先 `_close()` 再 `onSeeAll`），busy 时不可点。
- [x] **T4 `milestone_share.dart` 修复**（AC5；架构 §1.2 第 7 条）
  - [x] 4.1 `shareMilestoneWithLink(WidgetRef ref, …)`（`domain/milestone_share.dart`:19-54）改为 `shareMilestoneWithLink(ProviderContainer c, {…, Rect? sharePositionOrigin, void Function(String stage)? onFailed})`：
    - 建链段（:35-51）保持 try：失败 → `onFailed?.call('create_link')`、退化纯文案；
    - **把 :53 的 `ref.read(shareServiceProvider)(message)` 移进第二个 try**：`try { await c.read(shareServiceProvider)(message, sharePositionOrigin: sharePositionOrigin); } catch (_) { onFailed?.call('open_sheet'); }`——修「最后一步在 try 外，宿主 ref 已销毁即抛、面板不出、庆祝页也不关」的根因；
    - `readRequestPetId(ref)`（:38）换成 container 版：在 `current_pet_provider.dart` 增 `Future<int?> readRequestPetIdFrom(ProviderContainer c)`（`c.exists(effectivePetIdProvider) ? c.read(requestPetIdProvider.future) : c.read(currentPetIdProvider)`，语义同 :146-149）。
  - [x] 4.2 grep 确认 `shareMilestoneWithLink` 再无 `WidgetRef` 调用方。
- [x] **T5 6 处弹出点**（AC2；见 Dev Notes 表）
  - [x] 5.1 逐处删 `onShare` 闭包、补 `petId`；补庆祝 / 重温两处传 2.3 的 `accountPets`；即时庆祝那处原有的「show 前取 notifier」保留。
  - [x] 5.2 🔴 **「去发布」宿主（`publish_compose_page.dart`:547）必须传 `beforeCompose`**：发布控制器 `publishControllerProvider` 是**全局单例 `Provider.autoDispose`**（`publish_compose_page.dart`:54，不是 family）——旧发布 sheet 还挂着时再开一个发布页，两页会共用同一个 controller，新页的预填会写进上一条帖子的草稿。所以该宿主的 `beforeCompose` 要先做完它原本在庆祝后做的收尾：`reportMilestoneCelebrated`（改用 container）→ `Navigator.of(context).pop()` 关旧 sheet → `router?.pushReplacement(DeepLinkRoutes.milestoneList, extra: {done.code})`；`showMilestoneCelebration` 返回后宿主若已 `!mounted` 直接 return，不再重复 pop / 跳转。其余 5 处宿主没有并存的发布页，不需要 `beforeCompose`。
  - [x] 5.3 庆祝回报统一走 container（`container.read(locallyCelebratedMilestonesProvider.notifier).report(...)`），不在已销毁的 `WidgetRef` 上 read（`milestone_celebration_reporter.dart`:22-28）。
- [x] **T6 测试（widget，覆盖稿中每种状态）**（全部 AC）
  - [x] 6.1 `test/profile/milestone_celebration_test.dart`：D1 默认态（两按钮 + 链接都在、按钮在屏内）；L 级长正文可滚且按钮仍可见（`tester.getRect` 在视口内）；D2 主按钮转圈、次按钮 / 链接不透明度 0.4 / 0.5 且点击无效；次按钮转圈态；连点两次只触发一次（计数 fake）；D3 截图失败 → toast 文案出现、庆祝页仍在、按钮恢复、`milestone_share_failed{stage: render_card}`；B2 多宠态按钮组同样吸底。
  - [x] 6.2 发到社区成功：用 `captureMilestoneShareCard` 与 `BragPostEntry.prepareForTest` 测试缝 → 庆祝页关闭 → 发布页出现（找 `PublishComposePage`），preset Diary、预选宠物、1 张图、预填文案（单宠 / 账号级多宠）；`brag_post_tapped{source, code, level}`。
  - [x] 6.3 宿主已销毁：在一个页面上弹庆祝后把宿主页 pop 掉（或 dispose 宿主 ProviderScope 外的 widget），再点两个按钮都能工作、不抛 `StateError`。
  - [x] 6.4 `test/profile/milestone_share_test.dart`：建链失败 → 纯文案分享且 `stage: create_link`；分享服务抛错 → 不抛出、`stage: open_sheet`；`sharePositionOrigin` 被透传（fake `ShareFn` 记录参数）。
  - [x] 6.5 「去发布」宿主（`publish_compose_page` 里程碑路径）：庆祝页点发到社区 → 旧 sheet 被关、跳到里程碑列表、新发布页打开且是**空白草稿 + 预填**（断言新页 controller 文本 = 预填文本，不含上一条的正文 / 图片）。
  - [x] 6.6 现有庆祝相关测试改签名后全绿：`milestone_celebration_test`、`milestone_instant_celebration_test`、`milestone_per_pet_celebration_test`、`milestone_checkin_test`、`health_milestone_unlock_test`、`milestone_catchup_test`、`milestone_list_test`。
- [ ] **T7 L2**（AC7）：本地 Android 模拟器 + stag 后端，逐一触发 6 个弹出点 + 陪伴 100 / 365 天（stag 造数：改宠物 `created_at`）；断网验证站外分享退化；发布后到 Feed / 档案 / 另一账号（追更该宠物）关注流核对；截图对 D1–D3 打分 ≥ 95%。

## Dev Notes

### 6 处庆祝弹出点（生产代码，核 `dev_1.4.2` @ c496e3c3；PRD 写 5 处，漏了打卡 picker）

| # | 位置（文件:行） | 路径 / 场景 | 现在的 `onShare` | 改法要点 |
|---|---|---|---|---|
| 1 | `lib/features/content/presentation/publish_compose_page.dart:547` | 里程碑「去发布」发布成功 → 回填打卡 → 当场庆祝（`instant`） | `shareMilestoneWithLink(ref, …, petId: listPetId)` | 传 `petId: listPetId` + **`beforeCompose`**（T5.2：关旧 sheet + pushReplacement 里程碑列表 + 回报） |
| 2 | `lib/features/profile/presentation/health_list_page.dart:767` | 存健康记录后 500/800/1200ms 短轮询拉到（`instant`，`_celebrate`） | `shareMilestoneWithLink(ref, …)`（无 petId） | 传 `petId: _petId` |
| 3 | `lib/features/profile/presentation/milestone_instant_celebration.dart:140` | 打卡 / Tailsonality 完整解读后即时庆祝（`instant`，`_celebrate`） | `shareMilestoneWithLink(ref, …, petId: petId)` | 传 `petId`；回报改 container（:136 已在 show 前取 notifier，保持） |
| 4 | `lib/features/profile/presentation/milestone_list_page.dart:121` | 进列表补庆祝（`catchup`，`_maybeCatchUp`） | `shareMilestoneWithLink(ref, …)` | 传当前列表宠物 id + `accountPets`（2.3） |
| 5 | `lib/features/profile/presentation/milestone_list_page.dart:690` | 点已完成徽章重温（`revisit`，`_showCelebration`） | `shareMilestoneWithLink(ref, …)` | 同上；仍不回报 |
| 6 | `lib/features/profile/presentation/milestone_list_page.dart:1045` | 列表页「已打卡」picker 确认后（`instant`，`_confirm`） | `shareMilestoneWithLink(ref, …)` | 传当前宠物 id |
| — | `milestone_list_page.dart:79` | debug 截图钩子（`kDebugMode` + `DEV_CELEBRATE`），非生产 | 无 | 只改签名 |

### 现状要点

- `milestone_celebration.dart` `_onSharePressed`（:116-121）：`await onShare()` 后才 `_close()`，**无加载态**；`onShare` 是各宿主传入、捕获宿主 `ref` 的闭包。
- `milestone_share.dart` `shareMilestoneWithLink`（:19-54）：建链在 try 里（失败退化纯文案），**:53 `ref.read(shareServiceProvider)(message)` 在 try 外**——宿主已销毁时 `ref.read` 抛 → 分享面板不出、`_close()` 被跳过 → 「点了没反应」（PRD §3.6 现状）。且未传 `sharePositionOrigin`（memory：iOS 分享需 origin，此处是已知遗留隐患）。
- 即时庆祝已有正确示范：`milestone_instant_celebration.dart`:135-136「在 show 之前取好 notifier：关闭时宿主页可能已销毁」；本 story 把这个做法推广为「弹出时取 `ProviderContainer`」（仓库内已有先例：`follow_button.dart`:313、`home_page.dart`:142、`id_card_hd_flow.dart`:58）。
- `showGeneralDialog` 默认 `useRootNavigator: true`；`PublishComposePage.open` 是 `showModalBottomSheet(context)`（`publish_compose_page.dart`:130-156），用 `rootNavigatorKey.currentContext` 打开即在根导航上。
- `showAppToast(..., extraBottom:)`（`shared/widgets/app_toast.dart`:36-45）正是给「页面自带较高底栏」用的参数。

### 拍板点（按推荐实现，Completion Notes 复述）

1. **发布页在 dialog 关闭后由 `showMilestoneCelebration` 自己打开并 await 到关闭**（T2.3）：保证调用方收尾一定在发布页关闭之后，避免收尾里的 `Navigator.pop()` 把新发布页关掉。
2. **「去发布」宿主用 `beforeCompose` 先完成收尾**（T5.2）：规避 `publishControllerProvider` 单例被两页共用。
3. **站外分享成功后关闭庆祝页**（沿用现状）；发到社区则是「先关庆祝页再开发布页」。

### UI 数值出处（照说明，不量截图）

- 标题 26/1.2、正文 13/1.6、段间距 26/8/10/20/24/28/14、滚动内边距 28 × 32、按钮 52 高 · 字 14/700 · 圆角 14、芯片 padding 12×3、胶囊 14×7、KOLEKSI 14/16/16——均为 `milestone_celebration.dart` 现值（UI 引 origin/stag 160-282 行）。
- 吸底栏 ≈ 16 + 52 + 12 + 52 + 14 + 链接 + 安全区 34 ≈ 208；渐隐 32 高 `splashInk` 0→100%；次按钮描边 1.5、白 35%；加载转圈 20、白；置灰 40% / 50%；toast `extraBottom: 96`。

### 禁止事项与踩坑

- 不在按钮回调里用宿主 `WidgetRef`；不 `ref.read` 已 dispose 的东西。
- 不传 `milestoneCode` 给发布页；不加二维码 / 水印。
- 新文案只进 ARB + `flutter gen-l10n`；里程碑标题 / 庆祝文案仍走 `milestone_titles.dart` / `milestone_celebration_copy.dart` 既有表。
- 不整文件 `dart format`；`publish_compose_page.dart` 只动 :520-580 这段里程碑路径。
- 埋点只带 `code` / `level` / `stage` / `source`，不带宠物名、petId、正文。

### 测试

- 云端只跑 **L0**：`flutter analyze`（零警告）、`flutter test`。**L2（6 处弹出点端到端、关注流可见、断网退化、还原度）待本地验收**，Completion Notes 写明「L1/L2 待本地验收」，不得标 done。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.6、#4 FR-156、#7]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#1.2 第 7 条、D-21]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#D1、D2、D3、B1、B2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 3.2]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/3-1-app里程碑卡与发帖入口扩参.md]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/2-3-app新里程碑展示与补庆祝.md]

## Dev Agent Record

### Agent Model Used

Claude

### Debug Log References

- `flutter gen-l10n`（新增 `milestoneCelebrateShareOutside`，EN / ID 两份 ARB）。
- `flutter analyze` → No issues found。
- `flutter test`（全量）→ +4316 ~1（1 个既有 skip），All tests passed；复审修复后 +4320 ~1，All tests passed。
- 变异自检：把「去发布」宿主 `beforeCompose` 里等待 `publishControllerProvider` 释放的循环去掉 → `publish_milestone_celebration_compose_test` 立刻红（新发布页打不开 / 共用旧 controller），恢复后绿。

### Completion Notes List

- create-story：上下文分析完成，已生成完整开发指引（2026-11-04）。
- **云端只跑 L0；L1/L2 待本地验收**：本 story 无后端、无 L1；L2 待本地 = T7 / AC7 全部 —— 6 处弹出点（「去发布」后、健康记录、打卡 / Tailsonality 即时庆祝、补庆祝、重温、列表页打卡 picker）+ 陪伴满 100 / 365 天逐一点「Bagikan ke Komunitas」打开预填发布页；发布后 Feed / 成长档案 / 追更者关注流可见；断网验证站外分享退化纯文案；iOS 真机（含 iPad）「Bagikan ke luar」面板定位；D1 / D2 / D3 与 B1 / B2 按钮组还原度 ≥ 95%（渐隐、描边、转圈、置灰、toast 位置 extraBottom 96）。花结 SVG 仍待素材（3.1 兜底样式）。
- 名字核对（对 story 文字的修订，均未改 3.1 / 2.3 已提交契约）：
  1. `BragPostEntry.prepare` 签名为 `prepare(context, png, {toastExtraBottom})`（与 story 一致）；它只在**转码**失败时自己弹 toast，`captureMilestoneShareCard` 返回 null 时由庆祝页自己 `showAppToast(..., extraBottom: 96)`。
  2. 卡面画布用 3.1 常量 `kMilestoneShareCanvas`（= `CardCanvas.square`），屏外挂载照 3.1 文档注释 `Positioned(left: -2000, top: 0, width: 360)`。
  3. 庆祝回报统一走 container：新增 `reportMilestoneCelebratedIn(ProviderContainer, …)`（`milestone_celebration_reporter.dart`，语义同 `reportMilestoneCelebrated`）；即时庆祝那处原有「show 前取 notifier」保持。
  4. `shareMilestoneWithLink` 返回值由 `Future<void>` 改为 `Future<bool>`（面板是否拉起）：AC5.3 要求分享服务抛错时「按钮恢复」，故返回 false 时不关庆祝页、复位按钮；true 时沿用「await 后 `_close()`」。
- 拍板点复述：①发布页在 dialog 关闭后由 `showMilestoneCelebration` 自己经 `rootNavigatorKey.currentContext` 打开并 await 到关闭（T2.3）；②「去发布」宿主经 `beforeCompose` 先完成收尾（回报 → pop 旧 sheet → `pushReplacement(milestoneList)`），**并等单例 `publishControllerProvider` 随 autoDispose 释放**（轮询 `container.exists`，50ms × 最多 60 次，超时照开）后再开新页 —— 只 pop 不等时旧 sheet 退场动画期间仍在监听，新页会拿到同一个 controller（测试已证）；③站外分享面板返回后关闭庆祝页，发到社区则先关庆祝页再开发布页。
- `petId` 为 null 的弹出点（即时庆祝 / 健康记录在当前宠物未解析时）：发布页预选在打开前经 `readRequestPetIdFrom(container)`（3s 超时）取；仍取不到 → 不预选（发布页按自身默认逻辑）。卡面宠物：账号级取 `accountPets`，否则只在宠物列表**已加载**时按 petId 取名字 / 头像，不为此新发列表请求；取不到 → `petName` + 编码前缀物种默认头像。
- 埋点：`milestone_share_failed {code, stage}` 只在庆祝页一个函数里写字面量（四个 stage 都经它）；`brag_post_tapped` 带 `code` / `level`（大写，同 `milestone_celebration_shown`）/ `source`；用户在出图过程中点 ✕ 放弃不上报失败。
- 测试宿主改造（按设计打破的既有测试，更新断言、未删未 skip）：庆祝页弹出时 `ProviderScope.containerOf` → `milestone_celebration_test` / `milestone_account_v142_test`（AC6 组）/ `milestone_uncelebrated_badge_test` 宿主补 `ProviderScope`；庆祝层屏外预挂的里程碑卡同样画宠物头像 / 名字 / 物种 emoji → `milestone_account_v142_test` 的 B1/B2 可见内容断言限定在 `milestoneCelebration` 内容区内（`_inView`）；`milestone_per_pet_celebration_test` 的「逐只庆祝点分享建链」改点次按钮 `milestoneShareOutside`（站外建链已从主按钮移到次按钮）。

- **复审修复（2026-11-04）**：
  - [CONFIRMED] 出图期间点 ✕、截图恰在 240ms 退场动画内完成时 `mounted` 仍为 true，原代码会再 `pop` 一次弹掉宿主路由（「去发布」连弹两层、健康记录连带关列表页）。修：`_close()` 置 `_closed = true`；新增 `_gone`（`!mounted || _closed || ModalRoute.of(context)?.isCurrent != true`），`_close` / `_seeAll` / 两个按钮入口、每个 await 之后、带图 `pop` 之前都判，满足即 return（用户主动放弃不报失败）。测试：「退场动画期间截图完成，宿主仍在」（变异自检：去掉守卫即红）+ 退场期间连点 ✕ / 查看全部只关一次。
  - [PLAUSIBLE a] `captureMilestoneShareCard` / `prepare` 抛异常时 `_busy` 卡在 community：整段 try/catch，异常按 null 处理（toast + `render_card` + 复位）；`prepare` 自己弹过 toast 时不重复弹。补测试。
  - [PLAUSIBLE b] `beforeCompose` 跳到里程碑列表后 `_maybeCatchUp` 可能先补弹、新发布页叠在它上面：`_maybeCatchUp` 只在本页 `ModalRoute.of(context)?.isCurrent == true` 时弹（同即时庆祝 `_hostIsCurrent` 口径），build 内判一次（登记依赖）、帧后与取宠物后各再判一次（被盖住则放开 `_catchupDone`）；盖住的页关掉后 isCurrent 变化触发 rebuild，照常补弹。补测试（被盖住不弹 → 揭开后补弹一次；变异自检通过）。

#### 待确认

1. `shareMilestoneWithLink` 改返回 `bool`、分享服务抛错时不关庆祝页（按 AC5.3「按钮恢复」的最保守解读）。
2. 「去发布」宿主等待 controller 释放的上限 3s（超时照开，极端情况下仍可能共用草稿）；如需更强保证可在 3.x 后续把 `publishControllerProvider` 改 family（本 story 限定只动 publish_compose_page 里程碑路径段，未改）。
3. 吸底栏左右内边距取 28（与滚动区一致；UI 说明未写明吸底栏水平边距）。
4. 复审 (b) 仍有一个窗口：「去发布」宿主 `beforeCompose` 在 `pushReplacement(milestoneList)` 后要等旧 controller 释放（约一次 sheet 退场动画）才开新发布页；若里程碑列表在这段时间内已拉到数据且有**其它**未庆祝条目，补庆祝会先弹（此时本页确是栈顶），新发布页随后叠在补庆祝之上。刚庆祝的那条本身已由 `justCelebrated` + 本地标记挡住，不会重复。彻底消除需改导航顺序或发布控制器生命周期，超出本 story 范围。

### File List

- petgo_app/lib/features/profile/presentation/widgets/milestone_celebration.dart（布局吸底 + 两按钮 + container + 屏外卡 + 打开发布页）
- petgo_app/lib/features/profile/domain/milestone_share.dart（container 版、分享调用进 try、sharePositionOrigin、onFailed、返回 bool）
- petgo_app/lib/features/profile/data/current_pet_provider.dart（`readRequestPetIdFrom`）
- petgo_app/lib/features/profile/data/milestone_celebration_reporter.dart（`reportMilestoneCelebratedIn`）
- petgo_app/lib/features/content/presentation/publish_compose_page.dart（里程碑路径：petId + beforeCompose）
- petgo_app/lib/features/profile/presentation/health_list_page.dart
- petgo_app/lib/features/profile/presentation/milestone_instant_celebration.dart
- petgo_app/lib/features/profile/presentation/milestone_list_page.dart（补庆祝 / 重温 / picker 三处）
- petgo_app/lib/l10n/app_en.arb、petgo_app/lib/l10n/app_id.arb（`milestoneCelebrateShareOutside`）
- petgo_app/test/profile/milestone_celebration_test.dart（改写 + 新增 D1/D2/D3/两按钮/宿主销毁）
- petgo_app/test/profile/milestone_share_test.dart（container 版 + create_link / open_sheet / origin / 源码扫描）
- petgo_app/test/content/publish_milestone_celebration_compose_test.dart（新增，T6.5）
- petgo_app/test/profile/milestone_account_v142_test.dart（宿主 / 断言随新规则更新；复审 (b) 新增「被盖住不补弹」）、petgo_app/test/profile/milestone_uncelebrated_badge_test.dart、petgo_app/test/profile/milestone_per_pet_celebration_test.dart（宿主 / 断言随新规则更新）

### Change Log

- 2026-11-04：复审修复（关闭竞态守卫 `_closed` / `_gone`、出图异常复位、补庆祝只在本页为栈顶时弹）+ 对应测试。
- 2026-11-04：Story 3.2 实现（庆祝页按钮组吸底、「Bagikan ke Komunitas」/「Bagikan ke luar」两按钮、加载态与失败态、6 处弹出点改 container、站外分享 try 保护 + sharePositionOrigin）。Status → review。

## 待确认 / 发现的规划冲突

1. **弹出点数量**：PRD §3.6 写 5 处、架构 §1.2 写 6 处；代码核实为 6 处生产 + 1 处 debug 钩子，本 story 全改。UI D1 / D2 说明仍写「5 个弹出位置」，以代码为准。
2. **PRD 对「宿主已销毁」的根因推断**与代码不完全一致：「去发布」路径里宿主发布 sheet 在 `await showMilestoneCelebration` 期间其实仍挂着（庆祝关闭后才 pop），真正的炸点是「分享调用在 try 外 + 依赖宿主 ref」；修法相同（container + try 包全），但新增发现**两个发布页共用 `publishControllerProvider`** 的风险，需按 T5.2 处理——规划文档未提及。
3. **UI D1 说明「5 个弹出位置不用改」**（只改布局时成立）与 epics「6 处弹出点全部改造」不矛盾：布局不需改调用方，但去掉 `onShare` / 传 container 必须改调用方。
4. **发帖成功来源**只落在 `diary_published`（见 3.1 待确认 1）。
