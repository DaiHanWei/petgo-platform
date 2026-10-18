# Story 2.3: App 帖子与场所收藏按钮

Status: ready-for-dev

> 版本 v1.4.0 · 主题 push-collect · 分支 `feat/1.4.0-push-collect`
> 口径优先级：`decision-log.md` ④ 节 > `推送开关与收藏/1-4-0推送开关与收藏prd.md` > `architecture-v1.4.0-push-collect-delta.md` > UI 稿 `推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html`（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。云端执行另守 `cloud-rules-push-collect.md`。
> 前置：**Story 2.1**（`POST|DELETE /api/v1/content-posts/{id}/collect`、`POST|DELETE /api/v1/places/{token}/collect` → `{collected}`；`ContentDetailResponse.collected` / `collectCount`；`PlaceDetailResponse.collected`）。**Story 1.3** 已新建 `test/analytics/v140_push_collect_events_test.dart`（④ 线埋点清单常量 `pushCollectInventory`，见 AC7）。
> 范围层：**仅 App**。UI 稿 C1 ~ C5、D1 ~ D4。**无迁移、无后端改动**。
> 🔴 与 ①②③ 并行：③ 的详情页礼物区、② 的「我的」页 Hewanku 改版在本分支**不存在**；本 story 只改底栏 `_actions()` 与场所详情顶栏，不引用、不自造 ①②③ 的类。

## Story

As a 用户,
I want 在帖子详情底栏和场所详情右上角一点就收藏,
so that 操作顺手、状态清楚，以后能在「我的」页再找到它们。

## Acceptance Criteria

**AC1 — 收藏数据层（本 story 建，2.4 复用）** `[L0]`（AD-8、AD-9、AD-10、AD-15、AD-16「收藏 / 取消成功后 invalidate 对应列表」）
1. 新目录 `lib/features/collect/`（AD 0.6 定的 App 包），本 story 只建以下三个文件，**2.4 在同一处续写，不另建平行的数据层**（划分见 Dev Notes「与前后 story 的边界」）：
   - `domain/collect_target.dart`：`enum CollectTarget { post, place }`，带两个静态工具——`String savedLocation(CollectTarget t)` → `'/me?saved=post'` / `'/me?saved=place'`（`saved` 的取值就是 `t.name`），`CollectTarget? fromSavedParam(String? raw)`（未知 / null → null）。这是「Lihat 跳哪」的**唯一定义**，本 story 只用前者，2.4 用后者解析。
   - `data/collect_repository.dart`：`CollectResult { final bool collected; fromJson }`（`json['collected'] == true`）；`abstract class CollectRepository { collectPost(int postId); uncollectPost(int postId); collectPlace(String placeToken); uncollectPlace(String placeToken); }` 均返回 `Future<CollectResult>`；`DioCollectRepository` 照 `like_repository.dart`:27-43 写法（`dio.post` / `dio.delete`）；`final Provider<CollectRepository> collectRepositoryProvider`（照 :45-46）。
   - `data/collect_providers.dart`：收藏变更信号 `collectionsRevisionProvider = NotifierProvider.family<CollectionsRevisionNotifier, int, CollectTarget>`，`build() => 0`、`void bump() => state = state + 1`（写法照 `CommentFocusNotifier`，`detail_providers.dart`:112-120）。**契约**：收藏或取消**成功**（后端已确认）后，按钮调用 `bump()`；2.4 的两个列表 provider `ref.watch(collectionsRevisionProvider(CollectTarget.post|place))` 自动重拉。信号只是计数器、不含用户数据，**不登记** `resetUserScopedCaches`。
2. `ApiPaths`（`core/network/api_paths.dart`）新增 `contentPostCollect(int id) => '$base/content-posts/$id/collect'`（放在 `contentPostLike` :327 旁）与 `placeCollect(String token) => '$places/${Uri.encodeComponent(token)}/collect'`（放在 `places` :459 段内）。
3. `ContentDetail`（`content/domain/content_detail.dart`）新增 `final bool collected`（构造参数**可选**、默认 `false`）与 `final int? collectCount`（可选、默认 null）；`fromJson`（:134-160）解析 `collected: json['collected'] == true`、`collectCount: json['collectCount'] is int ? … : null`；**`withFollowEpoch`（:45-68）必须同步带上两个新字段**（漏了 → 详情一经 `detailProvider` 拷贝就丢收藏态，见 Dev Notes「实现提示」）。
4. `PlaceDetail`（`place/domain/place_detail.dart`）新增 `final bool collected`（可选、默认 `false`），`fromJson`（:87-108）`collected: json['collected'] == true`。字段名必须是 `collected`——契约测试禁用名单里的 `favorited` 继续禁用（AC9.2）。
5. Given 后端响应 `{collected:true, collectCount:3}`，When 解析 `ContentDetail`，Then 两字段原样；Given 响应缺这两个键（旧后端 / 游客），Then `collected = false`、`collectCount = null`。

**AC2 — 帖子详情底栏收藏按钮：位置与外观（C1 / C2）** `[L0]` `[L2]`（AD-15、PRD §6.5 ③、PRD AC7）
1. `comment_composer.dart` 的 `_actions()`（:444-480）在分享卡 `_tapTarget(DetailShareCardButton(...))`（:478）**之后**，先放一段与 :471-477 **同一公式**的间距 `SizedBox`，再放 `_tapTarget(CollectButton.post(...))`。顺序：点赞 · 分享卡 · 收藏。登录态 actions 分支（:416）与游客只读分支（:279）都走 `_actions()`，**两处自动都有**（游客也显示，C3）。
2. 新组件 `lib/features/collect/presentation/collect_button.dart` 的 `CollectButton.post`：图标 `Icons.bookmark_border_rounded`（未收藏，`AppColors.textSecondary` = ink2 #544864）/ `Icons.bookmark_rounded`（已收藏，`AppColors.mint` #845EC9），尺寸 `DetailBarMetrics.iconSize`（19）；外层 44×44 命中框由 `_tapTarget`（:483-490）提供，组件内命中区 `HitTestBehavior.opaque` 撑满。Key：图标命中区 `ValueKey('detailCollectButton')`。
3. 按下反馈：手指按下时图标 `AnimatedScale` 到 0.96、抬起 / 取消回 1.0（约 100ms）；只缩图标，不缩数字、不改命中框（UI 稿 C2 注「按下时 0.96 缩放反馈」）。
4. **私密帖不显示**（P-2）：`detail.visibility == 'PRIVATE'` 时 `_actions()` 不渲染收藏按钮及其前置间距（点赞 · 分享卡照旧）。同时把 `ContentDetail.visibility` 字段注释（:115-119「不要拿它当权限判据」）补一句例外：「收藏按钮按 P-2 对 PRIVATE 隐藏——这是产品规则（只收藏公开帖），不是权限判据；分享仍不得据此隐藏」。
5. `detail == null`（未接入互动栏的调用方）时整个 `_actions()` 照旧返回空占位（:446），不变。

**AC3 — 收藏 / 取消的交互状态机（C1 → C2 → C1、C5）** `[L0]` `[L2]`（AD-15、PRD §3.2「收藏成功反馈」、PRD AC7、decision-log ④ Q4）
1. 状态机照 `LikeButton._toggle`（`like_button.dart`:119-164）：`_inFlight` 为真直接 return（防连点）→ `requireLogin(ref, context, onAllowed: () {})` 未登录即 return（AC4）→ 记下 `prev` → `setState` 乐观翻转 + `_inFlight = true` → 调 `collectRepositoryProvider` 对应方法 → 成功以后端 `result.collected` 校正 → 失败回滚到 `prev`。`didUpdateWidget` 照 :101-117：换了目标（postId / placeToken 变）无条件跟随；同一目标仅在 `!_inFlight` 且外部 `initialCollected` 变化时跟随（详情因发评论重拉时会带回新值）。
2. **收藏成功**（后端 `collected == true` 且操作是收藏）：`showAppToast(context, l10n.collectSavedToast, actionLabel: l10n.collectSavedToastAction, onAction: …)`，点「Lihat」（`ValueKey('appToastAction')`，`app_toast.dart`:154）→ `router.go(CollectTarget.savedLocation(CollectTarget.post))` 即 `'/me?saved=post'`。`router` 在弹 Toast **之前**取 `GoRouter.of(context)` 存局部变量（Toast 挂 root Overlay、可能在详情页已关后才被点，届时 context 已卸载）。`/me` 是 shell Tab 根，**只能 `go` 不能 `push`**（`app_router.dart`:207-218 的 GlobalKey 撞车注释）。
3. **取消成功**：不弹任何 Toast，图标变回描边即可（Q4）。
4. **失败**（任何异常，含网络、5xx、2.1 的 422 `collect-not-public`、404）：图标弹回原状 + Toast——收藏失败 `l10n.collectSaveFailed`「Gagal menyimpan, coba lagi」，取消失败 `l10n.collectRemoveFailed`「Gagal membatalkan, coba lagi」（C5 注明两句不同，不得共用）。失败**不** bump 信号、**不**报埋点。
5. 收藏或取消**成功**后调用 `ref.read(collectionsRevisionProvider(CollectTarget.post).notifier).bump()`（AC1.1 契约）。
6. `showAppToast` **已有** `actionLabel` / `onAction`（`app_toast.dart`:35-44，2026-09-02 R-3 购物车撤销时加的，带动作时去掉 `IgnorePointer` :214）——PRD §6.5 ⑥ 与 UI 稿 C2 注「需要研发给 showAppToast 加一个可点的动作」**已过时，组件零改动**。
7. Given 已登录用户在他人公开帖子详情，When 点收藏，Then 图标立即变实心紫、Toast「Disimpan · Lihat」；点「Lihat」落到 `/me?saved=post`（2.4 合入前落在「我的」页默认态，属预期）；再点图标 → 变回描边、无 Toast。Given 请求失败，Then 图标弹回 + 对应失败 Toast。

**AC4 — 游客点收藏（C3 / D3）** `[L0]`（PRD AC7a）
- Given 游客在帖子详情（只读输入框态）或场所详情，When 点收藏，Then 弹 `LoginHardDialog`（`requireLogin` → `loginGuideControllerProvider.showHardDialog`，`auth_guard.dart`:15-31），与点赞一致；**不发请求、不翻转图标、不报埋点、无 Toast**。`entrySource` 不传（与 `LikeButton` :122 一致，取默认 `other`）。文案零新增。

**AC5 — 作者本人看到收藏数 + 锁（C4）** `[L0]` `[L2]`（AD-10、PRD AC10、PRD §6.5 ③）
1. `CollectButton.post` 收 `int? ownerCount`，调用方传 `detail.isAuthor ? detail.collectCount : null`。非 null 时图标后依次显示：数字（12 号、ink2、与图标间距 3，`ValueKey('detailCollectCount')`）+ 锁 `Icons.lock_outline_rounded` 11 号 `AppColors.muted`（间距 3，`ValueKey('detailCollectLock')`）。UI 稿 `.composer .bk{gap:3px;font-size:12px;color:ink2}`。
2. 数字**不随作者自己的收藏 / 取消变化**（后端口径不计作者本人，AC10；`{collected}` 响应也不带计数）；`collectCount == 0` 时照常显示「0」+ 锁（见「拍板点」③）。
3. 非作者（含游客）`collectCount` 为 null → 只有图标，无数字无锁（= C1）。
4. 数字与锁不可点（不做「谁收藏了我」列表，PRD §3.2 不做项）；命中区仍只在图标上触发收藏。
5. Given 作者 B 的帖子被 A、C 和 B 自己收藏，When B 打开详情，Then 图标为实心紫（B 已收藏）+「2」+ 锁；When A 打开，Then 只有实心紫图标、无数字。

**AC6 — 场所详情顶栏收藏按钮（D1 ~ D4）** `[L0]` `[L2]`（AD-9、AD-15、PRD §6.5 ④、PRD AC8、decision-log ④ Q1）
1. `place_detail_page.dart` 的 `_scaffold`（:176-196）新增可选参数 `Widget? collectButton`；AppBar `actions`（:183-191）在「⋯」`IconButton(key: placeDetailMore)` **之前**插入 `if (collectButton != null) collectButton`。`build`（:121-169）里与 `onReport`（:163）**同一条件**传入：`collectButton: async.hasValue ? CollectButton.place(placeToken: async.value!.token, initialCollected: async.value!.collected) : null`。404 空态、其它失败态、加载中预览（`_loadingBody`）都不显示（D 泳道「不出现按钮」）。
2. `CollectButton.place` 外观：`IconButton`（默认 48 命中、图标默认尺寸，**与「⋯」同尺寸**），`key: ValueKey('placeDetailCollect')`，`tooltip: l10n.collectActionLabel`；图标未收藏 `Icons.bookmark_border_rounded` / `AppColors.ink`（与「⋯」同色），已收藏 `Icons.bookmark_rounded` / `AppColors.mint`；按下 0.96 同 AC2.3。
3. 交互同 AC3：成功 Toast「Disimpan · Lihat」→ `go('/me?saved=place')`；取消不提示；失败两种 Toast；成功 bump `collectionsRevisionProvider(CollectTarget.place)`。
4. 场所收藏数**不展示**（PRD）；正文 12 个模块、底部评论条、`_PreviewBody` 均不动（D1 注「只改顶栏」）。
5. 更新文件头的「反向验收」注释：`place_detail_page.dart`:43-46 与 `place_detail.dart`:9-12 的「没有收藏」改为「V1.4.0 ④ 起有收藏（只有 `collected` 一个字段、不展示计数）；评分 / 营业时间 / 可编辑仍明确不做」。
6. Given 已登录用户在存在的场所详情，When 点 🔖，Then 实心紫 + Toast，「Lihat」直达 `/me?saved=place`；Given 场所 404，Then 顶栏既无「⋯」也无 🔖。

**AC7 — 埋点（P-8，保留 PRD 原名）** `[L0]`（AD-17、PRD §4）
1. 只在**后端确认成功后**上报（与 `user_followed` / `user_unfollowed` 同口径，`v112_events_test.dart`:441-444 注释；失败、游客拦截不报）：
   - 帖子：`Analytics.capture('content_collected' | 'content_uncollected', {'post_id': detail.id, 'content_type': ShareCardEntry.analyticsContentType(detail.type), 'source': 'detail'})`（`content_type` 词表 `diary / moment / tips`，`open_share_card.dart`:26-30；`source` 当前只有 `detail` 一个值）。
   - 场所：`Analytics.capture('place_collected' | 'place_uncollected', {'place_id': <PlaceDetail.token>})`——**取场所 token**，不外露自增 id（AD-17；与既有 `place_shared` :387 同口径）。
   - 事件名在 `CollectButton` 内**以字面量**写在 `Analytics.capture('…'` 里（守卫按正则 `Analytics\.capture\(\s*'…'` 从源码提取，`v112_events_test.dart`:164-165；不得用变量拼名）。
2. 命名守卫 `test/analytics/v112_events_test.dart`：前缀 `content_`（:265）、`place_`（:296）已在表内；**词尾 `_collected` / `_uncollected` 不在 `allowedSuffixes`（:346-455）**——按 P-8「扩表、不改名」在 `'_followed'`/`'_unfollowed'`（:443-444）之后加两条并写注释：「收藏 / 取消收藏（V1.4.0 ④ push-collect · P-8，PRD §4 定死 `content_collected` 等四个名字）：过去式，**服务端确认成功后**才报。`*_uncollected` 不以 `_collected` 结尾，两个都要登记。扩表、不改名。」前缀 `collection_` 由 2.4 用到时再加（守卫惯例「用到了才加」，:354-355）。属性契约清单 `propNames`（:492-516）补 `'post_id', 'content_type', 'place_id'`（带一行注释）。不删守卫、不放宽正则、不进 `legacyEvents`。
3. `test/analytics/v140_push_collect_events_test.dart`（1.3 已建）：往 `pushCollectInventory` 追加四条同名映射。**不改** `v140_events_inventory_test.dart`（Toko 线旧清单）；属性键 `post_id` / `content_type` / `source` / `place_id` 须过其 NFR-5 全局体检（:75 起，禁 `name` / `token_value` 等——`place_id` 不命中）。

**AC8 — 文案（新增 arb，EN / ID 两份，`collect*` 前缀）** `[L0]`（PRD §6.5 ⑦）

| key | EN | ID | 用处 |
|---|---|---|---|
| `collectSavedToast` | Saved | Disimpan | 收藏成功 Toast（C2 / D2） |
| `collectSavedToastAction` | View | Lihat | Toast 动作 |
| `collectSaveFailed` | Couldn't save, try again | Gagal menyimpan, coba lagi | 收藏失败（C5 / D4） |
| `collectRemoveFailed` | Couldn't remove, try again | Gagal membatalkan, coba lagi | 取消失败（C5 注） |
| `collectActionLabel` | Save | Simpan | 场所按钮 tooltip、帖子按钮 Semantics label（见「拍板点」④） |

- EN 写进模板 `app_en.arb` 并带 `@key.description`（文件惯例，如末尾 `@postLikersLoadFailed`）；ID 写进 `app_id.arb`；两份 key 必须对齐（`test/l10n/microcopy_rules_test.dart` J2），EN 不得混入印尼语（D-3）。改完跑 `flutter gen-l10n`（或 `flutter pub get` 触发）。
- **不复用**同字面量的既有键：`meNicknameSaveFailed` / `phoneSaveNetworkError`（同为「Gagal menyimpan, coba lagi」，但语义绑昵称 / 手机号，`app_id.arb`:899 / :985）、`meViewArchive`「Lihat」（:866，「我的」页档案入口）。**与 Story 1.3 通知设置页的「Gagal menyimpan, coba lagi」也不共用**：两处各自建在自己模块前缀下（1.3 用 `pushSettings*`），理由见「拍板点」②。
- 登录弹窗、`placeDetailReport` 等沿用文案零改动。

**AC9 — 回归** `[L0]` + 还原度 `[L2]`
1. `flutter analyze` 绿；`flutter test` 中下列文件绿（按设计会被打破的断言**更新为表达新规则，不删不 skip**）：
   - `test/content/share_entry_and_landing_test.dart`:79「底栏默认态两个图标：点赞 | 分享」→ 改为「三个图标：点赞 | 分享 | 收藏」，并补 `find.byKey(ValueKey('detailCollectButton'))` findsOneWidget；`detailCommentIcon` findsNothing 保留。:88「顶栏「···」仍在」保持不变。
   - `test/place/place_detail_wire_contract_test.dart`:188-191 注释与 :205 组名「详情模型里没有收藏 / …」→ 改为「没有评分 / 营业时间 / 打卡次数 / 可编辑；收藏只有 `collected` 一个字段」；:207 起的 `favorited` 用例与 :218 起的禁用名单**保留**（钉死字段名只能叫 `collected`），另补一条「`collected:true` 原样解析、缺键为 false」。
   - 其余渲染 `ContentDetailPage` / `PlaceDetailPage` 的既有测试（`content_detail_test`、`comment_write_test`、`place_checkin_test`、`place_detail_keyboard_test` 等）预期无需改：新字段均为可选、按钮只在点击时读 repository。若有按 AppBar `actions` 个数 / 底栏子组件个数断言的被打破，同样更新不删。
2. 不写 golden；不对整个文件跑 `dart format`（手工对齐缩进）。
3. L2：Android 模拟器（连真后端，2.1 已部署的环境）逐屏对照 UI 稿 C1 ~ C5、D1 ~ D4 打还原度，**≥ 95%**；360dp 宽设备上底栏输入框 + 三图标（作者态带数字与锁）不溢出。

## Tasks / Subtasks

- [ ] **T0 开工核对**：对照 2.1 实际提交核实——两个端点路径与响应体键名（预期 `collected`）、`ContentDetailResponse.collected` / `collectCount` 的 JSON 名与「非作者为 null 还是省略」、`PlaceDetailResponse.collected`、私密帖错误码 `collect-not-public`；核实 1.3 是否已建 `pushCollectInventory`、已加哪些 arb / 守卫条目。有出入先改本文件并在 Completion Notes 记一句
- [ ] **T1 数据层**（AC1）：`collect_target.dart`、`collect_repository.dart`、`collect_providers.dart`、`ApiPaths` 两条、`ContentDetail` / `PlaceDetail` 新字段（含 `withFollowEpoch`）
- [ ] **T2 `CollectButton`**（AC2.2-2.3 / AC3 / AC4 / AC5 / AC6.2 / AC7.1）：`.post` / `.place` 两个命名构造共用一套状态机
- [ ] **T3 帖子详情接入**（AC2.1 / AC2.4 / AC5.1）：`_actions()` 加按钮与间距、私密帖隐藏、`visibility` 注释补例外
- [ ] **T4 场所详情接入**（AC6）：`_scaffold` 参数 + actions 顺序 + 头注释更新
- [ ] **T5 埋点守卫与清单**（AC7.2 / AC7.3）
- [ ] **T6 arb 两份**（AC8）+ `flutter gen-l10n`
- [ ] **T7 测试**：新增 + 更新既有（AC9.1）；`flutter analyze` + 相关 `flutter test`；Completion Notes 标「L2 待本地验收（还原度 ≥ 95%）」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ ba55e1c5）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo_app/lib/features/content/presentation/comment_composer.dart` | `_actions()`（:444-480）：`LikeButton`（:454-468，`source: 'detail'`、`expandCountHitArea: false`、`onSettled` 回写 Feed）→ 间距 `SizedBox`（:471-477，`iconGap > minTapTarget - iconSize ? 差值 : 0`，即两个 44 热区自带透明边、只补不足部分）→ `_tapTarget(DetailShareCardButton)`（:478）；`_tapTarget`（:483-490）= `ConstrainedBox(min 44×44) + Center(widthFactor/heightFactor 1)`；游客分支（:266-282）与登录态 actions 分支（:416）都调 `_actions()` | 分享卡后加同公式间距 + `_tapTarget(CollectButton.post)`；私密帖不加 | 两态互斥（`resolveBottomBarMode`）、游客分支照常渲染动作、点赞 / 分享两个按钮不动 |
| `…/content/domain/detail_bottom_bar.dart` | `DetailBarMetrics`（:37-52）：`iconSize = 19`、`iconGap = 22`、`minTapTarget = 44`（`detail_bottom_bar_test.dart`:62-71 钉着） | 只引用 | 三个常量值 |
| `…/content/presentation/like_button.dart` | `_inFlight`（:91）、`didUpdateWidget` 跟随规则（:101-117）、`_toggle`（:119-164：`requireLogin` :122 → 埋点 :127 → 乐观 :139 → 真值校正 :149 → 回滚 :155-163，**失败无 Toast、无按压缩放**）；心形 `heartSize = 20`（:55，不是 19） | **不改**；`CollectButton` 照其状态机另写，补上失败 Toast 与 0.96 缩放 | V1.1.6 Story 3.2「直接复用、不得重写」（:16-17） |
| `…/content/presentation/content_detail_page.dart` | `DetailShareCardButton`（:769 起）：`GestureDetector(key: detailShareCardIcon)` + `Icons.ios_share_rounded` 19 / `textSecondary`（:799-806） | 不改（外观对齐参照） | — |
| `…/content/presentation/share_card/open_share_card.dart` | `ShareCardEntry.analyticsContentType(wireType)`（:26-30）→ `diary / tips / moment` | 只引用 | 词表 |
| `…/content/domain/content_detail.dart` | 字段 `likeCount`（:75）/ `liked`（:77）/ `isAuthor`（:80）/ `visibility`（:120，默认 `'PUBLIC'`，注释 :115-119「不要拿它当权限判据」）；构造参数（:9-33，除 9 个 required 外全可选）；`withFollowEpoch`（:45-68）逐字段拷贝；`fromJson`（:134-160） | 加 `collected` / `collectCount`（可选），三处同步；`visibility` 注释补例外 | 测试里 7 处 `ContentDetail(` 构造不因新字段而改 |
| `…/content/presentation/detail_providers.dart` | `detailProvider = FutureProvider.autoDispose.family<ContentDetail, int>`（:12，发 / 删评论会重拉）；`CommentFocusNotifier` 计数信号写法（:112-120） | 不改；信号写法照抄到 `collect_providers.dart` | — |
| `…/content/data/like_repository.dart` | `LikeRepository` 抽象 + `DioLikeRepository` + `likeRepositoryProvider`（:21-46） | 不改；照写 `CollectRepository`（**不往 Like / Place / Detail repository 上加方法**——测试里 13 个 fake `implements` 它们，加抽象方法会全部编译失败） | — |
| `…/core/network/api_paths.dart` | `contentPostLike(int id)`（:327）；`places = '$base/places'`（:459） | 加 `contentPostCollect` / `placeCollect` | — |
| `…/features/auth/domain/auth_guard.dart` | `requireLogin(ref, context, {pendingAction, onAllowed, entrySource = 'other'})`（:15-31）：已登录 → `onAllowed` + true；否则 `showHardDialog` + false | 只引用 | — |
| `…/shared/widgets/app_toast.dart` | `showAppToast(context, msg, {duration, top, actionLabel, onAction})`（:35-44）**已支持动作**；动作键 `ValueKey('appToastAction')`（:154）；点动作先收起再回调（:58-64）；带动作时去 `IgnorePointer`（:214） | **零改动** | 90+ 处既有调用外观 |
| `…/place/presentation/place_detail_page.dart` | `PlaceDetailPage`（:101，`ConsumerWidget`，`token` :105）；`build`（:121）中 `onReport: async.hasValue ? … : null`（:163）、`composer` 同条件（:166）；`_scaffold`（:176-196）AppBar `actions` 只有 `if (onReport != null) IconButton(key: placeDetailMore, tooltip: placeDetailReport, icon: Icon(Icons.more_horiz))`（:183-191，**默认 48 按钮 / 24 图标**）；404 → `EmptyState(placeDetailGoneTitle)`（:220-226）；头注释「没有收藏」（:43-46）；既有埋点 `place_shared{place_id: p.token}`（:387） | `_scaffold` 加 `collectButton`，插在「⋯」前；头注释更新 | 「⋯」显示条件、输入条位置（bug 512 注释 :193-200）、正文模块 |
| `…/place/domain/place_detail.dart` | 构造（:14-31）；头注释「没有收藏」（:9-12）；`fromJson`（:87-108）；`checkedInToday` 用 `bool?` 区分游客省略（:66-70） | 加 `collected`（`bool`，游客 / 旧后端 → false 即可：没登录就不可能收藏过） | 其余字段 |
| `…/place/data/place_repository.dart` | `placeDetailProvider = FutureProvider.autoDispose.family<PlaceDetail, PlaceDetailQuery>`（:294-297） | 不改 | — |
| `…/core/router/app_router.dart` | `/me` 在 `_controlledLocations`（:158-165）；Tab 根页 `_tabRootPage`（:190-195）里 `AppTab.me => const MePage()`——**目前不读 query**（对照 Toko 读 `?category=`）；shell 分支（:1218-1225）；Tab 根只能 `go`（:207-218 注释） | **不改**（读 `?saved=` 归 2.4） | — |
| `petgo_app/test/analytics/v112_events_test.dart` | 事件名从源码字面量提取（:164-165）；`allowedPrefixes`（:217-344，含 `content_` :265、`place_` :296，**无 `collection_`**）；`allowedSuffixes`（:346-455，**无 `_collected` / `_uncollected`**；先例 `_followed` / `_unfollowed` :441-444）；`propNames`（:492-516，**无 `post_id` / `content_type` / `place_id`**，但三者已在 `lib/` 用过） | 加两个词尾 + 三个属性名 | 规则本身、`legacyEvents` 不增 |
| `petgo_app/test/analytics/v140_push_collect_events_test.dart` | 1.3 新建的 ④ 线清单 `pushCollectInventory` | 追加四条 | 1.3 的两条；`v140_events_inventory_test.dart` 不动 |
| `petgo_app/test/place/place_detail_wire_contract_test.dart` | 反向验收注释（:188-191）、组「🔴 AC6：详情模型里没有收藏 / …」（:205）、`favorited` 等多余键被忽略（:206-218）、源码禁用名单（:220-240 起，含 `favorited`） | 更新组名与注释、补 `collected` 解析用例 | 禁用名单 |
| `petgo_app/test/content/share_entry_and_landing_test.dart` | :79「底栏默认态两个图标：点赞 \| 分享」；:88 顶栏「···」护栏；:296 私密 Diary 仍可分享 | 更新 :79 标题与断言 | :88、:296 |
| `petgo_app/lib/l10n/app_en.arb`（模板，带 `@` 描述）/ `app_id.arb` | 无 `collect*` 键；同字面量键见 AC8 | 加 AC8 五个键 | 既有文案 |

### 拍板点（按建议实现，Completion Notes 复述）

- **① 数据层归属**：收藏写接口、`CollectTarget`、变更信号放在 `features/collect/`，由本 story 建；2.4 在**同一个** `CollectRepository` 上追加列表方法（`listPosts(cursor)` / `listPlaces(cursor)`）与列表 provider，移除不可见条目直接复用本 story 的 `uncollectPost` / `uncollectPlace`。理由：两条 story 都要用同一组 DELETE 端点，分两处建必然重复；本 story 先跑（sprint 顺序 2.3 → 2.4），由先跑者定骨架。本 story 测试里的 fake 一律写 `class _FakeCollectRepo extends Fake implements CollectRepository`（`flutter_test` 的 `Fake`），2.4 加方法时不打破本 story 的测试。
- **② 失败文案各建各的键**：「Gagal menyimpan, coba lagi」在仓库里已有两个同字面量键（昵称、手机号），说明仓库惯例是「按场景建键」而非通用键；收藏失败与通知设置保存失败在 EN 侧语义不同（「保存收藏」vs「保存设置」），共用会把以后的分场景改文案变成牵一发动全身。故本 story 用 `collectSaveFailed`，1.3 用自己的 `pushSettings*` 键，**不新建 `commonSaveFailed`**。
- **③ 作者看到 0 也显示「0 + 锁」**：PRD 只说「显示收藏数并带小锁」，没说 0 隐藏；数字出现与否若随值变化，作者会以为功能时有时无。
- **④ 新增 `collectActionLabel`（Save / Simpan）**：PRD 文案表没列，但场所顶栏 `IconButton` 与「⋯」一样需要 `tooltip`（读屏 / 长按），帖子图标需要 `Semantics(button: true, label: …)`；不加就是无障碍空洞。
- **⑤ 埋点在成功后报**：PRD §4 未写上报时机；`content_collected` 是过去式结果事件，照关注线 `user_followed` 先例只报成功（失败 / 游客不报），漏斗分母不被网络抖动污染。
- **⑥ 埋点清单分组**：④ 线清单单独成文件 `test/analytics/v140_push_collect_events_test.dart`（照 ③ 的 `v140_gift_events_test.dart`；规划复核 2026-10-18 统一口径），Toko 旧清单不动。

### 实现提示

- **`CollectButton` 结构**：`ConsumerStatefulWidget`，两个命名构造 `CollectButton.post({required int postId, required String wireType, required bool initialCollected, int? ownerCount})` 与 `CollectButton.place({required String placeToken, required bool initialCollected})`；内部一个 `_toggle()`，按 target 选 repository 方法、埋点名、`savedLocation`、失败文案。post 变体 build 一个 `GestureDetector(opaque) → Row(min)[AnimatedScale(Icon 19), if ownerCount != null …数字 + 锁]`；place 变体 build `IconButton(icon: AnimatedScale(Icon))`。按下态用 `Listener(onPointerDown / onPointerUp / onPointerCancel)` 置 `_pressed`，不与 `onTap` 抢手势。
- 埋点在 `_toggle` 里按分支写**四个字面量**（`if (post) Analytics.capture('content_collected', …) else …`），别写成 `'${prefix}_collected'`——守卫只认字面量。
- 成功后的顺序：校正状态 → bump 信号 → 埋点 → （收藏时）Toast。Toast 用 `if (!mounted) return;` 之后的 `context`；`GoRouter.of(context)` 在 `await` 之前取。
- `withFollowEpoch` 是 `ContentDetail` 唯一的拷贝点（`lib/` 里 `ContentDetail(` 只有构造声明、拷贝、`fromJson` 三处）；漏拷新字段的表现是「进详情时收藏态总是 false、作者看不到数字」，L0 测试要钉住（见「测试」）。
- 底栏可用宽度：输入框是 `Expanded`，三图标 + 作者数字锁约 44×3 + 数字宽；L2 在 360dp 设备上看一眼不溢出即可，不改 `DetailBarMetrics`。
- 「Lihat」落点 `/me?saved=post|place`：2.4 合入前 `MePage` 不读参数，会落在「我的」页默认态——不是本 story 的 bug，Completion Notes 记一句。
- 场所 token：按钮与埋点都用 `PlaceDetail.token`（后端已按 `resolveForView` 解析 MERGED → 保留场所，P-6），不用路由里的原始 token。

### 架构约束

- AD-15：`CollectButton` 照 `LikeButton` 的乐观更新 / `_inFlight` / `requireLogin` / 0.96 缩放；帖子按钮在分享卡后、间距照 `DetailBarMetrics` 现有公式；PRIVATE 不显示（P-2）；作者显示数量 + 11 号锁；场所按钮在「⋯」左侧、条件同 `onReport`；Toast 用 `showAppToast` 现成参数；取消不提示；失败回弹 + 两种 Toast。
- AD-10：`collectCount` 仅作者下发、不计自己；场所只有 `collected`。
- AD-16（与 2.4 的接口）：收藏 / 取消成功后让对应列表失效——本 story 以 `collectionsRevisionProvider` 信号实现，2.4 订阅。
- AD-17 / P-8：四个事件保留 PRD 原名，守卫扩表不改名；`place_id` 取 token。
- §4 实现模式：帖子沿用 `/content-posts/{id}`（存量约定），场所用 token；App 不渲染后端显示串。
- 云端：只跑 L0；不写 golden；不整文件 `dart format`。

### 与前后 story 的边界（本 story 不做）

- **后端**全部在 2.1（端点、详情字段、`PlaceDetailResponseContractTest` 里「无收藏键」断言的更新也归 2.1）。本 story 只消费。
- **2.4「我的」页 Disimpan**：Tab 行、分段、列表、不可见条目移除抽屉、`/me?saved=` 解析（经 `CollectTarget.fromSavedParam`）、`MePage` / `_tabRootPage` 读参数、列表 provider 及其 `resetUserScopedCaches` 登记、`collection_` 前缀与 `collection_list_viewed` / `collection_item_opened` 埋点、「Disimpan」Tab 文案键——都归 2.4。2.4 **复用**本 story 的 `CollectRepository`（追加列表方法）、`CollectTarget`、`collectionsRevisionProvider`，不另建。
- **Feed 卡片**不加收藏按钮（PRD 不做项）；不做「谁收藏了我的帖子」。
- **③ 送礼礼物区**不在本分支；详情页「点赞 · 分享卡 · 收藏 + 正文后礼物区」的合并与间距复核在对齐 story **3.1**（`feat/1.4.0-push-collect-align`）。
- 不改 `LikeButton`、`showAppToast`、`DetailBarMetrics`。

### 测试

**验证层级**
- **L0（云端可跑）**：
  - `test/collect/collect_repository_test.dart`：`CollectResult.fromJson` 真 / 假 / 缺键；`ApiPaths.contentPostCollect(5)`、`ApiPaths.placeCollect('a b')`（token 被 encode）；`CollectTarget.savedLocation` 两值、`fromSavedParam('post'|'place'|'x'|null)`。
  - `test/collect/collect_button_test.dart`（fake repository `extends Fake implements CollectRepository`，覆盖 `collectRepositoryProvider`；`Analytics.debugCaptureSink` 收埋点）：
    - 帖子：点击立即变 `bookmark_rounded` + mint；成功后 Toast「Disimpan」与 `appToastAction`「Lihat」存在，点动作后路由到 `/me?saved=post`（用 `GoRouter` 测试壳断言 location）；再点 → 变描边、**无 Toast**；收藏失败 → 回描边 + 「Gagal menyimpan, coba lagi」；取消失败 → 回实心 + 「Gagal membatalkan, coba lagi」；请求未返回时连点只发一次请求；游客 → `showHardDialog` 被调、repository 零调用、图标不变、无埋点。
    - 成功各报一次 `content_collected` / `content_uncollected`，属性恰为 `{post_id, content_type, source: 'detail'}` 且 `content_type` 是词表值；失败不报。
    - 成功后 `collectionsRevisionProvider(CollectTarget.post)` 自增 1，失败不变。
    - `ownerCount: 2` → 显示「2」+ 锁；`ownerCount: 0` → 「0」+ 锁；null → 都没有；作者自己收藏后数字不变。
    - `didUpdateWidget`：同一帖外部 `initialCollected` 变化且不在飞行中 → 跟随；飞行中 → 不覆盖乐观值。
    - 场所：`placeDetailCollect` 点击 → `place_collected{place_id: token}`、落点 `/me?saved=place`、信号 `CollectTarget.place` 自增。
  - `test/content/content_detail_collect_test.dart`：`fromJson` 解析两字段与缺省；**`withFollowEpoch` 后两字段保留**；渲染详情底栏：公开帖有 `detailCollectButton` 且位于分享卡之后（比较 `tester.getCenter` 的 x）；`visibility: 'PRIVATE'` 时 `detailCollectButton` findsNothing、分享卡仍在；游客只读态也有收藏按钮。
  - `test/place/place_detail_collect_test.dart`：详情成功态 AppBar 有 `placeDetailCollect` 且在 `placeDetailMore` 左侧；404 空态两者都没有；`collected: true` 初始为实心紫。
  - 更新：`share_entry_and_landing_test.dart`:79、`place_detail_wire_contract_test.dart`:188-205（见 AC9.1）；`v112_events_test.dart`、`test/analytics/v140_push_collect_events_test.dart` 新条目与 `v140_events_inventory_test.dart` NFR-5 体检跑绿；`test/l10n/microcopy_rules_test.dart` 绿。
- **L1**：无（纯 App）。
- **L2（本地）**：Android 模拟器连已部署 2.1 的后端（`test-against-prod-backend` 惯例为真后端；本线未上线前用 stag），走 C1 → C2（Toast → Lihat）→ C1、C3 游客、C4 作者数字锁、C5 断网失败、D1 ~ D4；逐屏对照 UI 稿打还原度 ≥ 95%。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-push-collect.md#Story 2.3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-push-collect-delta.md#0.3 第 9 条、0.5 P-2 / P-6 / P-8、AD-8、AD-9、AD-10、AD-15、AD-16、AD-17、§6]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/1-4-0推送开关与收藏prd.md#3.2 收藏（入口 / 收藏成功反馈 / 计数与通知）、AC7 / AC7a / AC8 / AC10、§4 埋点、§6.5 ③ ④ ⑥ ⑦]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#④ 推送开关与收藏（Q1 场所按钮与「⋯」并排、Q2 操作栏 = 点赞 · 分享卡 · 收藏、Q4 收藏 Toast 指向「我的」取消不提示；第四步补取消失败文案）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html#C1 ~ C5、D1 ~ D4]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/cloud-rules-push-collect.md]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log
