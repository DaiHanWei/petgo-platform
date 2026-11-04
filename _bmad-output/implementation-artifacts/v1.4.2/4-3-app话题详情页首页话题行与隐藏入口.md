# Story 4.3: 话题详情页、首页话题行与隐藏三个入口（App，C1、C6–C11）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 social-topic · 分支 `feat/1.4.2-social-topic`（云端单会话串行）
> 口径优先级：`决策日志-social-topic.md` > PRD `追更话题/1-4-2追更宠物·里程碑·话题prd.md` > `architecture-v1.4.2-social-topic-delta.md` > UI 稿 `追更话题/ui-1-4-2-social-topic-integrated-v1.html`（每屏下方说明 = 实现说明，**数值照说明里的代码出处取，不量截图**；标「原有模块照线上」的不改样式）。
> 前置：**Story 4.1 / 4.2**（接口：`GET /topics/{name}`、`/topics/{name}/posts`、`/topics/{name}/related`、`/topics/home-row`、`GET /content-posts/pinned?topic=`；帖子出参 `topicNames`）。
> 范围层：**只 App**（`petgo_app/`）。正文话题高亮 / 点击、发布页输入框高亮与 # 联想 → **4.4**（本 story 的话题页列表卡片正文暂按现状渲染，4.4 接入 `RichBodyText` 后自动带高亮）。
> 🔴 **云端只跑 L0**（`flutter analyze` 零告警 + `flutter test`）；**L1 / L2（模拟器视觉、还原度 ≥ 95%）待本地验收**。用户说「模拟器」= Android 模拟器（adb）。

## Story

As a 浏览社区的用户,
I want 在 Discover 首页看到热门话题、进入话题页看同主题帖子,
so that 能按兴趣逛内容。

## Acceptance Criteria

**AC1 — 路由 `/topics/:name`（D-20）** `[L0]`
1. `core/router/app_router.dart` 新增顶层 `GoRoute(path: TopicPage.routePattern /* '/topics/:name' */)`，**shell 外**（push 时隐藏底部 Tab，同 `/places/:token`）；`TopicPage.routeFor(String name, {TopicEntrySource? from})` 生成 `'/topics/${Uri.encodeComponent(name)}?from=…'`。
2. **不加进 `_controlledLocations`**（`app_router.dart:170-177`）——游客天然可访问（同场所列表 `:596-598` 注释的做法）；后端对应 GET 已 permitAll（4.2 AC9）。
3. 深链 `tailtopia://topic/{name}` → `/topics/{name}`（`app.dart:68` `deepLinkToLocation` 加一支，name 为空落 `/home`）；首页 / 话题页推广卡 `openPromoTarget`（`promo_target.dart`）解析出的位置以 `/topics/` 开头时追加 `from=pin`（埋点 source = `pin`）。

**AC2 — 话题详情页 `features/topic/presentation/topic_page.dart` 各状态（C6–C11）** `[L0]` `[L2]`
1. **C6 加载中**：顶栏只有返回键；页头骨架（话题名条高 16 + 两行说明条，进页时不知是否官方，按「可能有说明」占位）；Terbaru / Populer 页签照常显示可点；列表区 `FeedSkeleton`（`feed_skeleton.dart`，正好两张 `_SkeletonCard`）。
2. **C7 官方话题**：顶栏标题 = `displayName`（后台改写法后即显示新写法，PRD FR-69 AC11 的 App 端）+ 间距 8 +「Resmi」徽章；标题下一段说明（`AppTypography.caption` 13/1.4 `ink2`，上 4 下 12）；**无封面、无帖子数、无分享、无 ⋯**；页签之下、列表最上方放顶置位（两个页签显示同一张），推广卡 = `PromoPinnedCard`，内容顶置 = `MasonryCard` + `PinnedBadge`「Disematkan」，**只在有生效排期时渲染、无则整块不占位**；列表按时间倒序（Terbaru 默认选中）。
3. **C8 用户话题·Populer**：页头只有话题名（无 Resmi、无说明）；切到 Populer 按「赞 + 评论 × `comment_weight`」顺序（服务端排好，App 照列）；无顶置则不渲染顶置位。
4. **C9 空态**（当前页签首屏 0 条）：页头 / 页签 / 顶置照常；列表区换成 `EmptyState(hideIcon: true)` 形态——从页签下 32（`AppSpacing.xxl`）起排：标题 `topicEmptyTitle`（title 18）→ 8 → 副文 `topicEmptyBody`（caption 13）→ 16 → `FilledButton`「`topicEmptyCta` Buat Postingan」（主题默认 46 高 / 圆角 12 / 14.5 w700，`app_theme.dart:75-80`）。点按钮：登录 → `PublishComposePage.open(context, preset: ContentType.daily, initialText: '#<displayName> ')`（默认 Momen、预填并带尾随空格，`publish_compose_page.dart:117-153` 已有 `initialText`）；游客 → `requireLogin(ref, context, entrySource: 'topic_empty', onAllowed: …)` 拉登录。按钮下段距 32 →「`topicRelatedTitle` Topik populer lainnya」（15/1.45 w600）→ 8 → 推荐话题列表（`/topics/{name}/related`，≤10 行；每行 44 高整行 `InkWell`、左右 16：排名序号 13 w600 `tabular-nums`、前 3 名 `mint600` 其余 `muted` → 8 →「#displayName」body 15 单行省略 → 官方带 Resmi → 右侧 `chevron_right_rounded` 20 `muted`；无分隔线）。点一行 → `context.push(TopicPage.routeFor(name, from: related))`。推荐为空或取数失败 → 标题与列表整段不渲染。两个页签下显示同一份推荐。
5. **C10 不存在**（详情接口 404，含 DISABLED）：顶栏只有返回；`EmptyState(icon: Icons.search_off_rounded, title: topicNotFoundTitle, message: topicNotFoundBody, actionLabel: l10n.detailBackToFeed, onAction: maybePop)`——**照抄 `content_detail_page.dart:104-125` `_errorBody` 的 gone 分支**；不显示页头 / 页签。
6. **C11 加载失败**（非 404 的任何失败 / 断网）：同 C10 外壳；`Icons.cloud_off_rounded` + `detailNetworkError`「Gagal memuat, silakan coba lagi」+ 主按钮 `commonRetry`「Coba lagi」（重新请求本页）；整页支持下拉刷新。列表已出来后再翻页失败 → 沿用 `FeedMasonryView` 底部 `feedLoadMoreError`（不另画）；已出详情后切页签首屏失败 → 列表区 `EmptyState(hideIcon: true, title: detailNetworkError, actionLabel: commonRetry)`。
7. 顶栏：`AppBar` 走主题（返回键 `chevron_left_rounded` 28 由 `app_theme.dart:68-70` `backButtonIconBuilder` 自动给，**不要自画返回键**；`titleSpacing: 2` 为主题默认 `app_theme.dart:59`；`scrolledUnderElevation: 0`）——返回键与标题同一行。
8. 滚动：说明段与顶置卡随列表滚走，**Terbaru / Populer 页签吸在顶栏下方**（UI C7「滚动后顶栏」结论）。页签样式照 `follow_list_page.dart:139-158` `_tabBar`（14 w600、未选 `muted`、选中 `ink` + 3px `mint` 下划线整 Tab 宽、底部 1px `AppColors.line`、`dividerHeight: 0`）；页签文案 `topicSortLatest`「Terbaru」/ `topicSortPopular`「Populer」。吸顶实现见 Dev Notes「滚动结构」。
9. 游客可浏览；点赞 / 评论 / 关注 / 举报等互动沿用卡片现有登录拉起；**页面无分享入口**（卡片自带的帖子分享按钮保留，那是帖子分享不是话题分享）。

**AC3 — 话题页数据与交互** `[L0]`
1. 进页并行请求：详情 + 当前页签首屏 + 顶置；详情 404 → C10；详情或首屏任一网络失败 → C11（重试同时重发三者）；顶置失败 → 当作无顶置（不得连带页面失败，同首页 `pinnedSlotProvider` 口径）。
2. Terbaru：游标翻页（`nextCursor`）；Populer：页码翻页（`page` 从 1 递增，`hasMore=false` 停）。两个页签**各自缓存**已加载的列表，来回切换不重拉；下拉刷新重拉当前页签首屏 + 顶置 + 详情（+ 空态时的推荐）。
3. 卡片回调照首页 `home_page.dart` `_content` 里 `FeedMasonryView(...)` 的回调写法接：点卡进 `/content/:id`、点头像 `openUserProfile`、点 @ `openUserProfile(entry: mention)`、评论进 `?focus=comments`、⋯ / 长按举报——**举报 / 拉黑成功后从本页列表移除该帖 / 该作者的卡**（话题页有自己的列表状态，不能只调首页的 `removeFeedPost`；两边都调一遍）。
4. 切页签上报 `Analytics.capture('topic_sort_switched', {'sort': 'latest' | 'popular'})`——**只在用户主动切换时报**，进页默认选中不报。

**AC4 — Discover 首页话题行 `features/topic/presentation/widgets/home_topic_row.dart`（C1）** `[L0]` `[L2]`
1. 位置：`home_page.dart` `_BerandaTop`（`:356-386`）里 `FeedTabRow(...)`（`:382`）**之后**、末尾 `SizedBox(height: 8)` 之前——即分类标签与顶置卡之间；只在 Jelajah（Discover）页签出现（`_BerandaTop` 本来就只在 Discover 分支渲染，Following 走 `FollowingTabView`）；随 Feed 滚动、不吸顶。
2. 数据：`GET /topics/home-row`（≤5）；0 个 / 取数失败 → **整行不渲染、不留占位**（同 `PetRecommendationStrip` 的口径）。首页下拉刷新时一并 `ref.invalidate(homeTopicRowProvider)`（加在 `home_page.dart:271` 起的 `onRefresh` 里，与 `petRecommendationsProvider` 同理——provider 关掉自动重试时漏刷会让断网首启后整行永久消失）。
3. 样式（纯文字链，无底色无描边）：横向滚动 `ListView`/`SingleChildScrollView(scrollDirection: horizontal)`，左右屏边距 16；每项「#displayName」13/1.4 w600 `AppColors.mint600`（#6C48AE，与正文话题高亮同色同字重）+ 官方项后跟 Resmi 徽章（间距 4）；项间距 16（`AppSpacing.lg`）；每项上下内边距 12，可点高度 44；首屏露出约 2.5 项（被屏边截断的那项即「可右滑」提示，不加渐隐 / 箭头）。
4. 点击：`context.push(TopicPage.routeFor(name, from: homeRow))` + `Analytics.capture('home_topic_row_tapped', {'topic_id': name /*标准名，R-2*/, 'position': index + 1, 'is_pinned_by_ops': featured})`。

**AC5 — Resmi 徽章共享件 `features/topic/presentation/widgets/topic_official_badge.dart`** `[L0]`
- 一个组件给 C1 首页行、C7 话题页标题、C9 推荐行、（4.4）C2 联想行共用：`AppColors.violet100`（#EFE6FF）底、`Icons.verified_rounded` 12（`mint600`）+「`topicOfficialBadge` Resmi」10/1.0 w600 `mint600`、内边距 上下 2 / 左右 4、圆角 4。**不要每处各画一个**。

**AC6 — FR-165 隐藏三个入口（D-22、P-11）** `[L0]`
1. 新建 `lib/core/config/feature_flags.dart`：
   ```dart
   /// V1.4.2 FR-165：暂时隐藏、组件保留（重新开放 = 改成 true 发版）。只在入口判断，不删组件、不拦其他进入路径。
   const bool kPlaceEntryEnabled = false;        // Social 首页「Tempat Ramah Hewan」入口行
   const bool kPassportEntryEnabled = false;     // Kenali Hewanmu「Jejak Anabul」卡（l10n.passportEntryTitle）
   const bool kBoardingPassEntryEnabled = false; // Kenali Hewanmu「Boarding Pass」卡
   ```
   （已验证：`const bool = false` 用于集合 if / if 语句，`dart analyze` 无 dead_code 告警。）
2. `home_page.dart:377` `const PlaceEntryRow()` → `if (kPlaceEntryEnabled) const PlaceEntryRow(),`（`PetRecommendationStrip` 直接上移，无空位）。
3. `pet_insights_page.dart:238-244`（护照卡 `insightPassport`）与 `:258-264`（登机牌卡 `insightBoardingPass`）各包 `if (kPassportEntryEnabled)` / `if (kBoardingPassEntryEnabled)`——它们在 `insightRows([...])`（`:81`）的列表字面量里，集合 if 去掉的卡不会留间距，其余卡依次上移。
4. **其他进入路径不变**：帖子 / Diary 打卡地点、收藏场所、场所评论通知、场所分享链接、订单详情、路由 `/places`、`PetInsightsRoutes.passport` / `boardingPass` 全部原样（PRD §3.9 AC2）。不删 `PlaceEntryRow`、`place_entry_row_test.dart` 照跑。

**AC7 — 埋点（P-9、R-2）** `[L0]`
1. `topic_page_viewed`：进页成功拿到详情后报一次（同一页实例只报一次；C10 / C11 不报），属性 `{'topic_id': <标准名>, 'source': <来源>}`。本 story 接入 `home_row`（首页话题行）、`pin`（推广卡深链）；另加 `related`（C9 推荐行，见「待确认」第 2 条）；`post_body` / `feed_card` 由 4.4 接入——来源经路由 query `from=` 传入、`TopicEntrySource` 枚举映射 wire 值；缺省 / 不认识的 `from` → **不带 `source` 键**（不瞎填）。
2. `topic_sort_switched {sort}`、`home_topic_row_tapped {topic_id, position, is_pinned_by_ops}` 见 AC3 / AC4。
3. 事件名与属性 Map **写字面量**在调用点（`MentionContext` 类注释：守卫是正则扫源码，抽常量 = 对守卫隐身）。
4. `test/analytics/v112_events_test.dart:217` `allowedPrefixes` 登记 `'topic_'`、`'home_topic_'`（各配一行注释：新模块入表，名字由 PRD §4 定）；词尾 `_viewed` / `_switched` / `_tapped` 已在表内，`_inserted`（4.4 的 `topic_inserted`）也已在（`:388`）。
5. 属性值：`topic_id` = 标准名（≤30 字，`Analytics.scrub` 64 字上限内；键名不在 PII / 自由文本黑名单），**不传 displayName、不传正文**。

**AC8 — 文案（P-10、PRD §7）** `[L0]`
- `app_id.arb` / `app_en.arb` 同步新增（印尼语为主），改完 `flutter gen-l10n`，**不手改** `app_localizations*.dart`：

  | key | id | en |
  |---|---|---|
  | `topicEmptyTitle` | Belum ada postingan di topik ini | No posts in this topic yet |
  | `topicEmptyBody` | Yuk, jadi yang pertama berbagi di sini! | Be the first to share here! |
  | `topicEmptyCta` | Buat Postingan | Create Post |
  | `topicRelatedTitle` | Topik populer lainnya | Other popular topics |
  | `topicNotFoundTitle` | Topik tidak ditemukan | Topic not found |
  | `topicNotFoundBody` | Topik ini sudah tidak tersedia | This topic is no longer available |
  | `topicSortLatest` | Terbaru | Latest |
  | `topicSortPopular` | Populer | Popular |
  | `topicOfficialBadge` | Resmi | Official |
- 复用不新建：`detailNetworkError`、`commonRetry`、`detailBackToFeed`、`feedLoadMoreError`、`feedDeletedUser`。

**AC9 — 还原度** `[L2]`
- 本地 Android 模拟器（连 `api.tailtopia.id` 或 stag 真后端，按 memory「测试连正式后端联调」）逐屏对 C1、C6–C11 截图打分，**每屏 ≥ 95%**；差异逐条修或记入 Completion Notes。实现完在模拟器打开对应页面给用户看。

## Tasks / Subtasks

- [ ] **T0 开工核对**：4.1 / 4.2 已合入；Dev Notes 行号未漂移；`lib/core/config/feature_flags.dart` 不存在（D-22 新建）。
- [ ] **T1 数据层** `features/topic/data/topic_repository.dart` + `domain/topic.dart`（`TopicDetail`、`TopicSummary`、`TopicEntrySource`、`TopicNotFoundException`）+ `ApiPaths` 注册（AC1、AC3；D-13、D-20）
- [ ] **T2 共享件** `topic_official_badge.dart`（AC5）
- [ ] **T3 话题页** `topic_page.dart` + `widgets/topic_empty_view.dart` + providers（AC2、AC3）
- [ ] **T4 首页话题行** `widgets/home_topic_row.dart` + 接入 `_BerandaTop` / `onRefresh`（AC4）
- [ ] **T5 路由与深链** `app_router.dart`、`app.dart`、`promo_target.dart`（AC1）
- [ ] **T6 FR-165** `feature_flags.dart` + `home_page.dart` + `pet_insights_page.dart` + 受影响测试改期望（AC6）
- [ ] **T7 埋点 + 文案**（AC7、AC8）：ARB 两份 → `flutter gen-l10n`
- [ ] **T8 测试**（见「测试」）→ `flutter analyze`（零告警）+ `flutter test` 全绿
- [ ] **T9 本地 L2**：模拟器逐屏还原度 ≥ 95%（AC9）；Completion Notes 记分数

## Dev Notes

### 现状（改动前必读，2026-11-04 核 `dev_1.4.2` @ c496e3c3）

| 位置 | 现状 | 本 story 改什么 / 保留什么 |
|---|---|---|
| `lib/features/content/presentation/home_page.dart:356-386` `_BerandaTop` | Column：`SizedBox(8)` → `AppUpdateBanner` → `PlaceEntryRow`(:377) → `PetRecommendationStrip` → `FeedTabRow`(:382) → `SizedBox(8)` | `PlaceEntryRow` 包开关；`FeedTabRow` 后插 `HomeTopicRow()`；其余一处不改 |
| `home_page.dart:174-198` `wrapped` | header 在 loading / error / empty 三态也渲染 | 话题行随 header 一起出现在这些态（自身无数据即不渲染） |
| `home_page.dart:271` 起 `onRefresh` | 刷顶置、未读角标、宠物横滑、Feed | 加 `ref.invalidate(homeTopicRowProvider)` |
| `lib/features/profile/presentation/pet_insights_page.dart:81` `insightRows`；`:215-266` 五张卡 | 顺序 idCard → ageCard → passport(:239) → tailsonality → boardingPass(:259) | passport / boardingPass 包开关 |
| `lib/features/content/presentation/feed_view.dart:22` `FeedMasonryView` | 自有 `ScrollController _controller`(:125)、`SingleChildScrollView + Column`(:516)、`header` / `pinned` / `footer`、监听 `homeScrollTopProvider`(:364)、图片变高锚定滚动 `_anchorScroll` | 话题页复用它渲染卡片列表（见「滚动结构」） |
| `feed_view.dart:375-455` 顶置渲染 | 推广卡 `PromoPinnedCard` / 内容顶置 `MasonryCard + PinnedBadge`；埋点 `social_pinned_slot_viewed` / `_tapped` | 话题页顶置直接走 `pinned:` 参数复用（埋点照旧报，属既有事件） |
| `lib/features/content/data/feed_repository.dart:24 / :62` `getPinnedSlot()` | 无参数，固定首页坑位；**10 个实现类**（含测试 fake） | **不改签名**；话题页顶置在 `TopicRepository.getPinnedSlot(name)` 里调 `ApiPaths.contentPinnedSlot` + `queryParameters: {'topic': name}`，复用 `PinnedSlot` 解析（见「待确认」第 3 条） |
| `lib/features/content/presentation/content_detail_page.dart:104-125` `_errorBody` | gone / forbidden / network 三种 `EmptyState` | C10 / C11 照抄 gone / network 两支 |
| `lib/features/social/presentation/follow_list_page.dart:139-158` `_tabBar` | 页签样式（UI 说明写 L146-151，实际在这段） | 话题页页签照抄样式 |
| `lib/features/content/presentation/publish_compose_page.dart:117-153` `open(...)` | 已有 `preset` / `initialText`（超 1000 字截断） | C9「Buat Postingan」直接用，不改发布页 |
| `lib/core/router/app_router.dart:170-177` `_controlledLocations` | `/profile /triage /me /consult /notifications /publish` | 不加 `/topics` |
| `lib/app.dart:68` `deepLinkToLocation` | card / post / milestone / place / open(debug) | 加 `topic` |
| `lib/features/content/presentation/promo_target.dart` | deeplink → `deepLinkToLocation` → push / go | `/topics/` 开头追加 `from=pin` |
| `lib/features/auth/domain/auth_guard.dart:15` `requireLogin` | 登录态直接 `onAllowed`，游客弹强登录 | C9 按钮用 |
| `lib/core/theme/colors.dart` | `mint` #845EC9、`mint600` #6C48AE、`mintTint` #F8F2FF、`violet100` #EFE6FF、`ink2`、`muted`、`line` | 只用 token，不写色值 |
| `test/content/beranda_home_test.dart:81-83`、`test/profile/pet_recommendation_strip_test.dart:116 / 165 / 192 / 218`、`test/profile/pet_insights_test.dart:136-177` | 断言 `PlaceEntryRow` 存在 / 位置、五张卡顺序、点护照 / 登机牌卡 | **改期望**：`PlaceEntryRow` findsNothing、宠物横滑位置改相对 `AppUpdateBanner` / 顶部判；卡片顺序改为三张（`insightIdCard, insightAgeCard, insightTailsonality`）、护照 / 登机牌 findsNothing；点击用例删除或改成直接 `context.push(PetInsightsRoutes.passport)` 仍可达（AC6.4） |

### 滚动结构（吸顶页签）

`FeedMasonryView` 是 `SingleChildScrollView + Column` 且自带 `ScrollController`，放进 `NestedScrollView` 的 body 时内外滚动不联动。推荐做法（改动最小、首页零影响）：
1. 给 `FeedMasonryView` 加可选参数 `bool usePrimaryScrollController = false`：为 `true` 时用 `PrimaryScrollController.of(context)` 代替 `_controller`（`build` 的 `SingleChildScrollView.controller`、`_scrollToTop`、`_anchorScroll` 里的 `_controller` 统一走一个 `_effectiveController` getter），且**不监听** `homeScrollTopProvider`；默认 `false` 时逐字节保持现状。
2. 话题页：`NestedScrollView(headerSliverBuilder: [SliverToBoxAdapter(说明段，仅官方), SliverPersistentHeader(pinned: true, delegate: 页签 46 高)], body: <当前页签的 FeedMasonryView(usePrimaryScrollController: true, pinned: …, header: null)>)`；**不用 `TabBarView`**（两份列表会同时挂到同一个 PrimaryScrollController 上导致多 position 断言），页签切换只换 body 的数据源（用 `KeyedSubtree(key: ValueKey(sort))` 区分两个列表的状态）。空态 / 失败态 body 用 `CustomScrollView(primary: true)` 包，保证下拉刷新与吸顶一致。
3. 若实现中发现 `_anchorScroll` 在 NestedScrollView 下行为异常，退路：话题页传 `usePrimaryScrollController: true` 时同时关掉锚定补偿（加一个判断），代价仅是返回时偶有轻微漂移；在 Completion Notes 记录。
4. 再退一步（实在不稳）：页签不吸顶、随 header 滚走——这是 UI 偏差，需在 Completion Notes 标出并告知主会话，不得静默。

### 实现提示

- **目录（P-4）**：`lib/features/topic/{data/topic_repository.dart, domain/topic.dart, presentation/topic_page.dart, presentation/widgets/{home_topic_row.dart, topic_official_badge.dart, topic_empty_view.dart}}`；4.4 会在同目录加 `topic_suggest_panel.dart`。
- **ApiPaths**（`core/network/api_paths.dart`，新端点先登记再引用）：`topicDetail(String name)`、`topicPosts(String name)`、`topicRelated(String name)` 均 `'$base/topics/${Uri.encodeComponent(name)}…'`；`topicHomeRow = '$base/topics/home-row'`；`topicSuggestions = '$base/topic-suggestions'`（4.4 用；路径见 4.2「待确认」第 1 条）。
- **Repository**：`DioTopicRepository`；404 → 抛 `TopicNotFoundException`，其余 `DioException` 原样抛（UI 判 network）——**不要**自己处理 401（拦截器负责）。帖子列表解析复用 `FeedPage` / `FeedItem.fromJson`（`features/content/domain/feed_item.dart`），不另写卡片模型。
- **Providers**（Riverpod 3，手写）：`topicDetailProvider = FutureProvider.autoDispose.family<TopicDetail, String>`；`topicPinnedProvider`（同上，失败返回 null）；`topicRelatedProvider`；`topicPostsProvider = AsyncNotifierProvider.autoDispose.family<…, (String name, TopicSort sort)>`（照 `feed_controller.dart` 的分页状态写法：items / hasMore / loadingMore / loadMoreFailed，latest 存 `nextCursor`、popular 存 `nextPage`）；`homeTopicRowProvider = FutureProvider.autoDispose<List<TopicSummary>>(…, retry: (_, _) => null)`（照 `petRecommendationsProvider` `pet_recommendation_repository.dart:142-146`）。
- **话题名传递**：路由参数 / 埋点 / 接口一律用**标准名**（`TopicSummary.name`）；显示一律用 `displayName`。从正文进来时（4.4）手里只有正文写法，4.4 负责先 `normalize` 再拼路由。
- **ProviderScope 回调**：C9 按钮的登录后回调走 `ProviderScope.containerOf(context, listen: false)`（同 `home_page.dart:142` `_selectTab` 写法），登录弹窗关闭后 `WidgetRef` 不保证可用。
- **键盘**：本 story 无输入框。
- **不整文件 `dart format`**（存量未格式化，会带出几百行无关 diff）；手工对齐，只跑 `flutter analyze`。

### UI 数值出处速查（UI 稿 C1、C6–C11 说明）

| 元素 | 数值 | 出处 |
|---|---|---|
| 返回键 | `chevron_left_rounded` 28，44 宽返回区居中 | `app_theme.dart:68-70` |
| 顶栏标题起点 | 46（返回区 44 + `titleSpacing` 2） | `app_theme.dart:59` |
| Resmi 徽章 | violet100 底、verified 12、10/1.0 w600、内边距 2/4、圆角 4；距标题 8 | UI C1 / C2 / C7 `.lC-resmi` |
| 官方说明 | caption 13/1.4 ink2，上 4 下 12 | UI C7 |
| 页签 | 14 w600、3px 下划线、46 高 | `follow_list_page.dart:139-158` |
| 话题行项 | 13/1.4 w600 mint600、间距 16、上下 12、可点高 44 | UI C1 |
| 骨架 | 标题条 16 高；卡骨架照 `feed_skeleton.dart`（头像 32、间距 9 等） | UI C6 |
| 空态 | 上 32 → 标题 18 → 8 → 副文 13 → 16 → 按钮 46 | UI C9 + `EmptyState` |
| 推荐行 | 44 高、左右 16、序号 13 w600 前 3 mint600、话题 15、chevron 20 muted | UI C9 |
| 异常态 | `EmptyState` 48 → 12 → 标题 → 8 → 副文 → 16 → 按钮 | UI C10 / C11 |

### 测试

- **L0（云端跑，`flutter test`）**——widget 测试覆盖稿里每种状态（UI 稿「测试」总则）：
  - `test/topic/topic_page_test.dart`（fake `TopicRepository` 覆盖 provider）：C6 加载中（骨架 + 页签可见）；C7 官方（标题 = displayName、Resmi、说明、无分享 / ⋯ 图标、推广卡顶置渲染）；C8 用户话题（无 Resmi / 说明、无顶置时无 `PinnedBadge`）；切 Populer 发 `topic_sort_switched {sort: popular}`、进页默认不发；C9 空态（标题 / 副文 / 按钮 / 10 行推荐、前 3 序号颜色 mint600；推荐为空整段不在；登录态点按钮调 `PublishComposePage.open` 预填 `#Name `——用可注入的打开函数或 `debugPublishOpener` 钩子断言参数；游客点按钮触发登录引导）；C10（404 → `search_off_rounded` + 两行文案 + `detailBackToFeed`）；C11（异常 → `cloud_off_rounded` + `commonRetry`，点后重新请求）；`topic_page_viewed` 只报一次、`source` 随 `from` 映射、缺省无 `source` 键、C10 不报（用 `Analytics.debugCaptureSink`）。
  - `test/topic/topic_posts_paging_test.dart`：latest 用游标续拉、popular 用页码续拉；两页签切换不重拉；翻页失败显示 `feedLoadMoreRetry`。
  - `test/topic/home_topic_row_test.dart`：5 项渲染、官方项有 Resmi；空表 / 异常 → `findsNothing` 且不占高度；点第 2 项 → 路由 `/topics/<name>?from=home_row` + `home_topic_row_tapped {topic_id, position: 2, is_pinned_by_ops}`。
  - `test/topic/topic_official_badge_test.dart`：颜色 / 字号 / 圆角取 token（防回归）。
  - `test/content/beranda_home_test.dart`（改）：话题行在 `FeedTabRow` 之下；Following 页签无话题行；`PlaceEntryRow` findsNothing。
  - `test/profile/pet_insights_test.dart`、`test/profile/pet_recommendation_strip_test.dart`（改期望，见现状表）。
  - `test/core/deep_link_topic_test.dart`：`tailtopia://topic/KucingLucu` → `/topics/KucingLucu`；空 name → `/home`；`openPromoTarget` 落 `/topics/…` 时带 `from=pin`。
  - `test/analytics/v112_events_test.dart`：前缀表加两项后整套通过（它会扫到本 story 新增的三个事件名）。
  - 路由：游客直开 `/topics/x` 不被 redirect 到 `/home`（`app_router` 现有路由测试的写法）。
- **L1**：无后端改动；联调属 L2。
- **L2（本地 Android 模拟器 + 真后端）**：首页话题行可横滑、点进话题页；官方 / 用户 / 空态 / 不存在（后台禁用一个话题后从旧链接进）/ 断网失败；Populer 顺序与后台点赞评论一致；顶置（6.2 未上线前可在 stag 库直插 `content_pins` slot=`TOPIC:{id}`）按时段显示；Kenali Hewanmu 只剩三张卡且无空位；从帖子打卡地点 / 订单详情仍能进场所 / 护照 / 登机牌页；逐屏还原度 ≥ 95%。
- 云端只跑 L0：`flutter gen-l10n && flutter analyze && flutter test`；Completion Notes 写「L1/L2 待本地验收」。

### Project Structure Notes

- 新增：`lib/features/topic/**`（见实现提示）、`lib/core/config/feature_flags.dart`、`test/topic/**`、`test/core/deep_link_topic_test.dart`。
- 改动：`home_page.dart`、`pet_insights_page.dart`、`feed_view.dart`（仅加可选参数）、`app_router.dart`、`app.dart`、`promo_target.dart`、`api_paths.dart`、两份 ARB、`v112_events_test.dart` 与上述三个受影响测试。
- 与架构 §5.2 的差异：话题页顶置不经 `feed_repository.dart`（避免改 10 个实现类的签名），改由 `TopicRepository` 取。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-social-topic.md#Story 4.3]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md#D-13、D-20、D-22、P-4、P-9、P-10、P-11、P-12、§5.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md#3.3 话题详情页 / 发现入口；3.9 FR-165；§4 埋点；§7 文案]
- [Source: _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html#C1、C6–C11]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-social-topic.md#R-2]
- [Source: petgo_app/CLAUDE.md#路由、i18n、测试]

## 待确认 / 发现的规划冲突（不改规划文档）

1. **PRD §7 文案表缺四个 App 词条**：「Resmi」徽章、「Terbaru / Populer」页签只在 PRD 正文出现，没给 EN。本 story 取 `Resmi / Official`（对齐后台「官方 = Official / Resmi」术语）、`Terbaru / Latest`、`Populer / Popular`，新建 `topic*` 键；UI 稿说「Terbaru arb 已有 `placeSortRecent`」，但那是场所模块的键，跨模块复用改一处会牵连另一处，故新建。请设计侧确认 EN。
2. **`topic_page_viewed.source` 缺取值**：PRD §4 只有 `post_body / feed_card / home_row / pin`，C9 推荐话题点进去不属于任何一个。本 story 新增 `related`；若不接受，改为该入口不带 `source`。
3. **话题页顶置取数位置**：架构 §5.2 写「`feed_repository.dart`（pinned 带 topic）」；改 `FeedRepository.getPinnedSlot` 签名要动 10 个实现类（含测试 fake），本 story 改为在 `TopicRepository` 里调同一个接口。纯实现差异，不影响行为。
4. **吸顶页签与现有 `FeedMasonryView` 结构冲突**：UI C7 要求页签吸顶、说明与顶置随列表滚走，现组件是自带控制器的 `SingleChildScrollView`，无法放进 `NestedScrollView` 联动。本 story 给它加一个默认关闭的可选参数（首页零影响）；若主会话不希望动 `feed_view.dart`，退路是页签不吸顶（UI 偏差）。
5. **「Buat Postingan」预填写法**：PRD 写「预填 #话题名 」，本 story 用显示写法（`#KucingLucu `）而不是标准名（`#kucinglucu `），与用户在页面标题上看到的一致；两者进同一话题。
6. 话题页列表卡片正文的话题高亮 / 点击在 4.4 接入；4.3 单独验收时话题页卡片正文是普通文字，属预期。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log
