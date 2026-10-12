# Story 2.1: Feed 卡与帖子详情的关注按钮，全局关注状态

Status: ready-for-dev

> 版本 v1.3.4 · 主题 follow · 分支 `feat/1.3.4-follow-notify`
> 口径优先级：`决策日志-follow.md` > `PRD-v1.3.4-follow.md` > `architecture-v1.3.4-follow-delta.md` > `ui-v1.3.4-follow.html`（均在 `_bmad-output/planning-artifacts/v1.3.4/`）
> 依赖：Epic 1 全部完成（Story 1.1 关注表 / `users.public_token` / 关注接口 / `FollowReader`；Story 1.3 `FollowButton` 与 `followStateProvider` 的最小形态、关注相关 l10n 与 toast、`user_followed` / `user_unfollowed` 埋点与事件名登记）。

## Story

As a 刷首页的用户,
I want 在帖子上直接关注发帖人,
so that 遇到喜欢的人不用先进主页。

## Acceptance Criteria

**AC1：Feed、顶置位、详情接口下发作者 token 与关注状态** `[L0]` `[L1]`
1. `FeedItemResponse` 新增两个字段：`String authorToken`（作者的 `users.public_token`；作者已注销时为 null，Jackson NON_NULL 不下发）和 `authorFollowState { iFollow, followsMe }`，含义都是相对当前查看者。`ContentDetailResponse` 加同样两个字段。字段名必须与上面完全一致（AD-6）。
2. 首页 Feed（`FeedService.assemble`，推荐序和时间倒序两条路径共用）对**整页作者集合只调一次** `FollowReader.followStates(viewerId, authorIds)`，禁止逐条查（NFR3 / AD-16）。顶置位（`loadPinnedSlot`）和详情（`ContentDetailService.getDetail`）只有一个作者，调一次 `followState(viewerId, authorId)`。
3. 游客（`viewerId == null`）不查库，`iFollow` 和 `followsMe` 都是 false。查看者就是作者本人时也都是 false（关注表不允许自己关注自己）。
4. `authorToken` 和用户昵称、头像一起在 `AccountQueryService.findAuthorViews` 这次批量查询里取出（见 Dev Notes「authorToken 的取法」），**不另外加查询**。
5. 「我的发布」（`myPosts`）和他人主页帖子网格（`userPublicPosts`）也用同一个 `FeedItemResponse.of`，但这两处不显示关注按钮：`authorFollowState` 传 null（不下发、不查库），`authorToken` 照常带上（不额外花查询）。这样处理的理由要写在代码注释里，写法参考同文件里 `decorationTags` 在这两处传 null 的注释。
6. 测试：
   - `FeedBatchAggregationTest` 新增断言：一页 20 条只调一次 `followStates`；游客和空页不调。
   - `FeedServiceTest`、`ContentDetailServiceTest`、`UserPublicPostsTest` 改成新的构造参数。
   - `FeedExpandedFieldsIntegrationTest`、`PinnedSlotIntegrationTest`、`ContentDetailControllerEndpointTest` 断言 JSON 里确实是 `authorToken`、`authorFollowState.iFollow`、`authorFollowState.followsMe` 这几个键，覆盖已关注、对方关注我、游客三种情况。

**AC2：App 解析新字段** `[L0]`
1. `FeedItem` 和 `ContentDetail` 新增：
   - `String? authorToken`；
   - `FollowState authorFollowState`：类型用 Story 1.3 的 `features/social/domain/follow_state.dart`；后端没下发这个字段时（如「我的发布」、旧后端）按 `FollowState.none`（两项都是 false）处理；
   - `int followEpoch`：不来自 JSON，默认 0，见 AC3。
2. 顶置位 `PinnedSlot.item` 用的是 `FeedItem.fromJson`，自动带上新字段。
3. 解析单测：新字段缺失、为 null 时都不崩溃，使用默认值。

**AC3：全局关注状态 `followStateProvider` 补全规则（AD-7 / NFR1）** `[L0]`
1. `followStateProvider` 的状态是一张按 userId 存的覆盖表 `Map<int, FollowOverride>`，每项是 `FollowOverride { iFollow, followsMe, pinnedSurface (String?), epoch (int) }`。用户退出登录或换账号时整表重建为空（沿用 Story 1.3 的做法：监听当前用户 id）。
2. **版本号 `epoch`**：notifier 维护一个只增不减的计数，`currentEpoch` 对外可读；重建时不归零，避免旧快照看起来比新覆盖还新。每写一条覆盖，计数先 +1，再把新值记到这条覆盖上。
3. **数据快照记下版本号**：每个数据源在**发请求前**读一次 `currentEpoch`，写进返回的每一条数据的 `followEpoch`。共四处：
   - `FeedController.build`（首屏，包括下拉刷新、切换分类 Tab、`homeRefreshProvider` 触发的重建）；
   - `FeedController.loadMore`（只写到新拉到的这一页上）；
   - `pinnedSlotProvider`；
   - `detailProvider`。
4. **按钮怎么判断显示什么**（读「服务端初值 + 覆盖」）：先算「覆盖是否生效」，即这个用户有覆盖、且 `override.epoch > 快照的 followEpoch`。然后：
   - 覆盖生效且 `pinnedSurface == 本按钮的 surfaceKey`：这个按钮保留。`iFollow = true` 时显示「Mengikuti」（点一下就取关）；`false` 时显示「+ Ikuti」或「+ Ikuti Balik」。
   - 其他情况：取实际状态（覆盖生效就用覆盖，否则用快照）。`iFollow = true` 时**不显示按钮**；否则显示「+ Ikuti」或「+ Ikuti Balik」（`followsMe = true` 时显示后者）。
5. **什么叫「刷新」（AD-7「刷新数据后覆盖清除」的落地方式）**：某个位置的数据被重新从服务端拉了一次，就算这个位置「刷新」了。因为快照是在覆盖之后拉的，它的 `followEpoch` 不小于覆盖的 `epoch`，覆盖对它自动失效，按钮按服务端的真实值显示：已关注就隐藏，「Mengikuti」这个例外也随之结束。
   - **不做「刷新时把整张覆盖表清空」**：那样 A 位置一刷新，还没刷新的 B 位置（快照仍是「未关注」）会重新冒出「Ikuti」，各处状态就不一致了。
   - 覆盖表里的旧条目可以不删，留到下次登录态切换时随重建一起清掉。
6. 各数据源中算「刷新」的情况：

   | 数据源 | 算「刷新」 | 不算 |
   |---|---|---|
   | 首页 Feed（`feedProvider`） | 下拉刷新、切换分类 Tab、`homeRefreshProvider` 触发的重建、首屏失败后点重试、空态下拉 | 往下翻页（新页按新快照显示，已经在屏上的卡不受影响）、详情页通过 `syncCounts` 回写计数 |
   | 顶置位（`pinnedSlotProvider`） | 首页下拉刷新时一起 invalidate（现有逻辑） | 其他情况 |
   | 帖子详情（`detailProvider`，autoDispose） | 重新进入详情页；本页因 `commentsRefreshProvider`（发评论、删评论）重新拉取 | — |

7. **每个按钮要有唯一的 `surfaceKey`**（同一帖子出现在不同位置时也要能区分）：
   - Feed 普通卡：`feed:item:{postId}`；
   - 顶置卡：`feed:pinned:{postId}`（被顶置的帖子在后面几页可能再出现一次，见 `feed_view.dart:172-191`，两张卡的 key 必须不同）；
   - 详情页：`detail:{postId}`。
8. 单测（`test/social/follow_state_provider_test.dart`）覆盖：
   - 关注后，本按钮显示 Mengikuti，同一用户其他 surfaceKey 的按钮隐藏；
   - 在本按钮上取关后，其他位置重新显示「+ Ikuti」或「+ Ikuti Balik」；
   - 快照的 epoch 更新后，本按钮也隐藏；
   - 一个位置刷新后，另一个还没刷新的位置仍然隐藏；
   - 换账号后覆盖表清空、版本号不归零；
   - `followsMe = true` 时文案为「+ Ikuti Balik」。

**AC4：`FollowButton` 的文字按钮样式 `FollowButtonStyle.textInline`（UI 稿 C1 / C2 / C4 / C5）** `[L0]` `[L2]`
1. 组件参数：`targetUserId`、`targetToken`、`serverState`（快照里的 `FollowState`）、`snapshotEpoch`、`surfaceKey`、`source`、`style: FollowButtonStyle.textInline`。组件自己读 `authControllerProvider` 和 `followStateProvider`，`MasonryCard` 不用改成 Consumer。
2. 以下情况不渲染（返回 `SizedBox.shrink()`）：
   - 目标就是当前登录用户（自己的帖子，C7）；
   - 作者已注销，或 `targetToken` 为 null；
   - 按 AC3.4 的判断应当隐藏（已关注的作者，C3 / C6）。
3. 外观（UI 稿 `.fc-btn .fbtn`）：
   - 「+ Ikuti」/「+ Ikuti Balik」：`AppColors.mint`（#845EC9）13 号 w600，前面的「+」是 w700、和文字间隔 3；没有底色和边框，内边距约 4×2。
   - 「Mengikuti」：`AppColors.muted`，w500，前面没有「+」。
   - 可点区域至少 44×44（用 `ConstrainedBox` 或透明 padding 撑开），看上去的尺寸不变。
   - 按钮 key：`followInline_{surfaceKey}`。
4. 点击行为：
   - 游客：`requireLogin(ref, context, onAllowed: () {})` 弹登录框，**登录后不自动关注**（FR-140.19，与 `LikeButton` 的写法一致）。
   - 已登录：先写覆盖（`pinnedSurface = surfaceKey`，这样同一用户其他位置**立刻**隐藏），再调 `followRepository.follow(token)` 或 `unfollow(token)`。请求进行中再点不响应。
   - 成功：用接口返回的 `iFollow` / `followsMe` 校正这条覆盖。
   - 失败：把覆盖表恢复成点击前的那一条（之前没有就删掉），并提示：
     - 关注失败：「Gagal mengikuti, coba lagi」；
     - 取关失败：「Gagal berhenti mengikuti, coba lagi」；
     - 429：「Terlalu sering. Coba lagi nanti.」；
     - 409 `follow-blocked`、404：按关注失败或取关失败处理。

     文案和错误映射都直接用 Story 1.3 的 l10n key 和映射函数，不另写一份。
5. 埋点只在成功时报（D-6）：`user_followed` 或 `user_unfollowed`，属性 `{target_user_id, source, is_follow_back}`。
   - `source`：Feed 卡和顶置卡是 `feed_card`，详情页是 `content_detail`。
   - `is_follow_back`：点关注那一刻 `followsMe == true`；取关时上报 false。

**AC5：Feed 卡作者行改版（`MasonryCard`，UI 稿 C1 / C2 / C3）** `[L0]` `[L2]`
1. 作者行从左到右：头像 32 → 名字和时间那一列（`Expanded`）→ `FollowButton(textInline)` → 和「…」间隔 6 →「…」（`feedCardMore_{id}`，原样不动）。
2. 类型徽章（`_badgeStyle`，9.5 号 w700、圆角 5、内边距 6×2）从昵称那一行**移到时间那一行**：时间（11 号 muted）后面间隔 6 再放徽章。昵称那一行只剩 `UserTagRow`，它在空间不够时先丢标签、不截断昵称的规则不变。
3. 头像、昵称（13.5 号 w700）、时间、徽章的尺寸都不变；作者行原有的点击（进入主页）区域不变，**关注按钮的点击不会冒泡到「进主页」**（按钮用自己的 `GestureDetector`，`behavior: opaque`）。
4. 新增可选参数 `MasonryCard.followSurfaceKey`（String?）。不传就不显示关注按钮，这样「我的发布」等别的调用方完全不受影响。`FeedMasonryView` 给普通卡传 `feed:item:{id}`，给顶置卡传 `feed:pinned:{id}`。
5. 顶置卡上的关注按钮和普通卡完全一样。顶置角标仍然在图片区右上角（`pinnedBadge`），不移动。

**AC6：详情页作者行改版（`content_detail_page.dart::_authorRow`，UI 稿 C4 / C5 / C6 / C7）** `[L0]` `[L2]`
1. 作者行：头像 36 → 名字和时间那一列（`Expanded`）→ `FollowButton(textInline, surfaceKey: 'detail:{postId}', source: 'content_detail')`。原来放在最右边的分类徽章（`ContentTypeBadge`）**移到时间右边**，间隔 6。徽章大小见「待确认」。
2. 「…」仍然在 AppBar 里（`detailMenu`），不移动、不进作者行。点作者行进入主页的手势不变；关注按钮的点击不进主页。
3. 自己的帖子（`detail.isAuthor`）、已关注的作者、已注销的作者都不显示按钮。

**AC7：跨页面一致（FR-140.3，UI 稿 C2 / C5）** `[L0]`
1. 在 Feed 某张卡上点「+ Ikuti」：这张卡变成「Mengikuti」；同一作者在 Feed 的其他卡（含顶置卡）**同一帧内**隐藏按钮；进入这位作者其他帖子的详情页，也不显示按钮。
2. 在详情页点「+ Ikuti」：详情页变成「Mengikuti」；返回 Feed 时，同一作者的卡片按钮已经隐藏（C5 的说明文字）。
3. Feed 下拉刷新后，原来那张卡（快照已更新）的按钮也隐藏（C3）。
4. 「Mengikuti」上点取关：这个按钮变回「+ Ikuti」或「+ Ikuti Balik」，其他位置随之重新显示按钮。
5. 拉黑或举报作者后，现有的 `onAuthorHidden` 会移除这位作者的卡片，这部分**不改**。快照版本号的机制能保证：之后解除拉黑、再重新拉数据时，按钮按服务端真实值显示（拉黑已经在服务端删除了关注关系，AD-5），不会因为旧覆盖误判为「已关注」。

**AC8：首页回归（NFR7）** `[L0]` `[L2]`
1. 长昵称加 3 个运营标签加「+ Ikuti Balik」，在 320 宽屏幕上作者行不溢出：昵称完整显示、标签按规则丢弃，按钮和「…」都完整。
2. 顶置卡：角标的位置、按钮的显隐和普通卡一致；`feedPinnedCard`、`feedCardMore_{id}` 都还在。
3. 自己的待审帖（`findFeed` 会把作者本人的 `UNDER_REVIEW` 帖子放进本人的 Feed；App 上**没有待审标识**，作者看不出来）：这类帖子是自己的，不显示按钮，卡片其余部分不变。
4. 已注销作者的卡：不显示按钮，「已注销用户」的占位照常显示。

**AC9：测试** `[L0]`
1. `test/content/feed_action_row_test.dart` 新增：
   - 类型徽章和时间在同一行（徽章的 top 落在时间那一行的范围内，并且在时间右侧）；
   - 关注按钮在「…」左边；
   - 点关注按钮不会触发 `onAuthorTap` 和 `onTap`；
   - 自己的帖子、已关注作者不显示按钮；
   - `followsMe` 为 true 时文案为「Ikuti Balik」；
   - 游客点按钮会弹登录框，并且不发请求；
   - 失败后回滚并提示；
   - 成功后上报 `user_followed`，`source=feed_card`。
2. `test/content/feed_test.dart`：
   - 同一作者两张卡，点第一张后，第一张变「Mengikuti」、第二张按钮消失；
   - 下拉刷新（mock 返回 `iFollow=true`）后两张都没有按钮；
   - `feed_test.dart:108` 用到 `MasonryCard` 时没有包 `ProviderScope`，需要补上：`FollowButton` 在 build 里 watch provider，不包会抛异常。
3. `test/content/pinned_slot_test.dart`：
   - 顶置卡和普通卡同时出现同一作者时，在顶置卡上关注，普通卡的按钮隐藏；
   - 同一帖子既在顶置位又出现在列表里时，两张卡的 surfaceKey 不同；
   - 原有的角标位置断言（`:105-116`）保持通过。
4. `test/content/content_detail_test.dart`：
   - 未关注时显示按钮；
   - `isAuthor` 时不显示，已关注时不显示；
   - 点关注后变「Mengikuti」，再点变回；
   - 徽章在时间那一行；
   - 原有的「…」互斥用例保持通过。
5. `test/content/user_tag_test.dart`：原有用例保持通过（`UserTagRow` 本身不改）。如果在卡片层级加「长昵称加标签加按钮」的不溢出用例，放在 `feed_action_row_test.dart`。
6. `test/social/follow_state_provider_test.dart`：见 AC3.8。
7. `flutter analyze` 零告警；`flutter test` 全绿；`mvn -B clean package` 全绿。

## Tasks / Subtasks

- [ ] Task 1：后端 DTO 与批量取数（AC1）
  - [ ] 1.1 `AuthorView` 加 `publicToken`：保留原来 5 个参数的构造器，新参数传 null，这样测试里 31 处 `new AuthorView(...)` 都不用改；`anonymized` 和 `withTags` 要把这个字段带上；`toAuthorView` 从 `User.publicToken` 取值，已注销用户为 null
  - [ ] 1.2 `FeedItemResponse` 和 `ContentDetailResponse` 增加 `authorToken`、`authorFollowState`，工厂方法 `of(...)` 增加 `FollowState followState` 参数（null 表示不下发）
  - [ ] 1.3 `FeedService`：构造参数注入 `FollowReader`；`assemble` 整页调一次批量查询；`loadPinnedSlot` 单查；`userPublicPosts` 和 `myPosts` 传 null。`FeedService.java:35`「无关注」的注释由 Story 1.2 改，本 story 只核对是否已改
  - [ ] 1.4 `ContentDetailService.getDetail` 单查；游客和本人不查库
  - [ ] 1.5 测试改造与新增（AC1.6）
- [ ] Task 2：App 领域层（AC2、AC3）
  - [ ] 2.1 `FeedItem` 和 `ContentDetail` 加新字段，`copyWith` 带上 `followEpoch`（`syncCounts` 回写时不能把它丢掉）
  - [ ] 2.2 `followStateProvider` 补全：版本号、`pinnedSurface`、显示判断的纯函数（如 `resolveInlineFollow(...)`，方便单测）
  - [ ] 2.3 四个数据源在发请求前记下版本号（`FeedController.build`、`FeedController.loadMore`、`pinnedSlotProvider`、`detailProvider`）
- [ ] Task 3：`FollowButton.textInline`（AC4）
- [ ] Task 4：Feed 卡改版（AC5、AC8）：`MasonryCard` 作者行、`followSurfaceKey`，`FeedMasonryView` 给两种卡传 key
- [ ] Task 5：详情页作者行改版（AC6）
- [ ] Task 6：测试（AC9）；L2 在模拟器上走一遍 C1 → C2 → C3、C4 → C5 → 返回 Feed、C6、C7，外加长昵称、顶置卡、自己的帖子

## Dev Notes

### 现状（改动前必读）

**后端**
- `FeedItemResponse`（`petgo-backend/src/main/java/com/tailtopia/content/dto/FeedItemResponse.java`）：record 定义在 `:30-84`，**唯一的工厂方法** `of(...)` 在 `:88-111`（类注释写明「首页与我的发布共用这一个工厂，不要另写」）。目前只有 `authorId`，没有 token，也没有关注状态。
- `ContentDetailResponse`（`…/content/dto/ContentDetailResponse.java`）：record 在 `:20-78`；两个重载的 `of` 在 `:82-103`，参数少的那个委托给完整版。只需给完整版加参数，参数少的那个传 null 或补上参数，然后检查所有调用点。
- `FeedService`（`…/content/service/FeedService.java`）：
  - `:35` 类注释「无算法、无关注、无缓存」（Story 1.2 / AD-15 负责改）；
  - `loadFeed` 的 ALL Tab 走推荐序 `recommendations.page(...)`（`:240`），再进 `assemble`（`:241`）；分类 Tab 走时间倒序，也进 `assemble`（`:307`）；
  - **`assemble`（`:317-337`）是两条排序路径共用的唯一组装处**，作者、点赞、已赞、评论数、装饰标签、@ 都是整页一次批量取。加关注状态只改这一处。`FeedRecommendationService`（`content/rank/`）只返回排好序的 `ContentPost` 列表，**不组装 DTO，不用改**；
  - `loadPinnedSlot`（`:354-398`）单条组装，`FeedItemResponse.of` 在 `:392`；
  - `userPublicPosts`（`:438-480`）和 `myPosts`（`:485-523`）复用同一个工厂，`decorationTags` 已经在传 null。
- `ContentDetailService.getDetail`（`…/content/service/ContentDetailService.java:71-125`）：作者信息在 `:97`，用 `findAuthorViews(List.of(...))` 取；`ContentDetailResponse.of` 在 `:123`；`viewerId` 可能为 null（游客）。
- `AccountQueryService.findAuthorViews`（`…/auth/service/AccountQueryService.java:130`）→ `basicViews`（`:151-159`）用 `users.findAllById` 一次查出 `User` 实体，再交给 `toAuthorView`（`:261-268`）。**`User` 实体已经整行加载，取 `publicToken` 不多花查询**。
- `AuthorView`（`…/auth/dto/AuthorView.java:18`）是一个 5 字段的 record；`new AuthorView(` 在 main 里 2 处、test 里 18 个文件共 31 处。这个类型目前只在 Java 内部传递，没有直接序列化给 App（`PublicProfileResponse`、`CommentResponse` 等都是从它取字段），所以加字段不会让任何现有接口多出一个键。
- 关注相关的读端口 `social/read/FollowReader`（`followState` / `followStates`，返回 `FollowState(iFollow, followsMe)`）由 Story 1.1 交付；本 story 只调用。`FeedService` 依赖 `social.read` 的接口已有先例（`UserHideRelationReader`，`:69`）。

**App**
- `MasonryCard`（`petgo_app/lib/shared/widgets/masonry_card.dart`）：是 `StatelessWidget`（`:32`）。
  - 作者行在 `:136-221`：外层 `Row[Expanded(GestureDetector(onAuthorTap) → Row[头像 32, 9, Expanded(Column[昵称行, 1, 时间])]), 「…」]`。
  - **类型徽章目前在昵称行**（`:181-194`，`UserTagRow` 外面包了 `Flexible`，后面是 6 的间隔和徽章）；时间单独一行（`:197-202`）。
  - 「…」在 `:210-219`（key 为 `feedCardMore_{id}`，左边距 `AppSpacing.sm`）。
  - 顶置角标通过 `pinnedBadge` 挂在**图片区右上角**（`:244`），不在作者行，所以纯文字的顶置卡没有角标（现状，本 story 不改）。
  - 本组件只有 `feed_view.dart` 在用（`:323` 顶置卡、`:375` 普通卡）。
- `FeedMasonryView`（`features/content/presentation/feed_view.dart`）：
  - 顶置卡 `:314-361`（key 为 `feedPinnedCard`）；普通卡 `:362-405`；下拉刷新 `RefreshIndicator` 在 `:410`。
  - 「被顶置的帖子在后面几页再出现一次」是已知现象，有专门的埋点（`:172-191`），所以**同一个 postId 可能同屏出现两张卡**。
- `HomePage`（`features/content/presentation/home_page.dart`）：
  - 下拉刷新 `:213-225` 依次 invalidate `pinnedSlotProvider`、`unreadCountProvider`、`petRecommendationsProvider`，再 `await feedProvider.refresh()`；
  - 失败态和空态的下拉、重试在 `:159-164`、`:189`；
  - 点头像进主页（`openUserProfile`，带拉黑和举报后的收尾）在 `:237-245`。
- `FeedController`（`features/content/presentation/feed_controller.dart`）：
  - `build` 在 `:80-92`，watch `feedCategoryProvider` 和 `homeRefreshProvider`，所以切 Tab、宠物状态变化都会重建；
  - `loadMore` 在 `:97-124`，在原有 items 后面追加；
  - `removeItem` / `removeByAuthor` 在 `:128-146`；
  - `syncCounts` 在 `:151-168`，用 `FeedItem.copyWith` 回写计数；
  - `refresh` 在 `:171-174`，即 `invalidateSelf`；
  - `syncFeedCounts` 在 `:186-191`，Feed 还没建时不写（「顶置卡是独立快照，不在此回写」）；
  - `pinnedSlotProvider` 在 `:198-205`，取失败时返回 null。
- `author_moderation_callbacks.dart:23-36`：`onAuthorHidden` 移除这位作者的全部卡片，并 invalidate 宠物推荐。**本 story 不改**（理由见 AC7.5）。
- `ContentDetailPage`（`features/content/presentation/content_detail_page.dart`）：
  - `:64-74` 监听 `detailProvider`，有新值时 `syncFeedCounts`（只回写计数，**不要把关注状态也加进去回写**，那会改掉 Feed 的快照，打乱 AC3 的版本号判断）；
  - `_DetailScaffold` 在 `:198` 已经读到 `currentUserId`；「…」在 AppBar 里（`:207-212`）；
  - `_authorRow` 在 `:365-421`：`Row[头像 36, sm 间隔, Expanded(Column[UserTagRow(15/w700), 时间 caption textTertiary]), sm 间隔, 徽章]`，**徽章在最右边**（`:391-397`，11 号 w700、内边距 9×4、圆角 7，样式来自 `ContentTypeBadge.of`）。整行包在 `GestureDetector(detailAuthorRow)` 里，点了进主页；作者已注销时不可点。
- `detailProvider`（`detail_providers.dart:11`）是 `FutureProvider.autoDispose.family`：离开详情页就丢缓存，再进来会重新拉。
- `LikeButton`（`like_button.dart`）是「自己管状态」的写法，可以照着写：
  - `didUpdateWidget` 只在请求不在进行中时跟随外部的新值（`:68-87`）；
  - 门控用 `requireLogin(ref, context, onAllowed: () {})`（`:93`）；
  - 先乐观更新，然后用后端返回的真值校正，失败回滚。

  `FollowButton` 和它的区别：**状态放在全局覆盖表里，不放在组件的 State 里**（这样同一用户其他位置才能立刻同步）。组件自身只保留「请求进行中」这个本地标记。
- 领域模型：
  - `FeedItem`（`features/content/domain/feed_item.dart`）：`copyWith` 在 `:190`，**只开放了三项计数**，加 `followEpoch` 时要一起带上；`fromJson` 在 `:211`。
  - `ContentDetail.fromJson`（`content_detail.dart:85`）。
  - `PinnedSlot.fromJson`（`pinned_slot.dart:34-45`）复用 `FeedItem.fromJson`。
- 埋点前缀测试 `test/analytics/v112_events_test.dart`：`allowedPrefixes`（`:217`）里**已经有 `user_`**；`allowedSuffixes`（`:330`）里还没有 `_followed` / `_unfollowed`。这两个后缀应该由 Story 1.3（第一次上报这两个事件）补上。本 story 只核对，1.3 没补就在这里补。
- 现有测试中会受影响的：
  - `feed_test.dart:108` 用到 `MasonryCard` 时**没有包 `ProviderScope`**（目前能通过，是因为 `LikeButton` 在 build 里不碰 `ref`）；
  - `pinned_slot_test.dart:87-116` 断言顶置卡和普通卡是同一个组件、角标在图片区右上角；
  - `feed_action_row_test.dart:132` 断言「发布时间在名字下面」（徽章移到时间那一行后应仍然成立）；
  - `content_detail_test.dart:192-226` 断言「…」只出现删除或举报中的一个。

### 全局关注状态：pinnedSurface 的实现思路

- **为什么要有版本号**：AD-7 要求「点关注的那个按钮保留 Mengikuti，同一用户其他位置立即隐藏，刷新后清除」。这里的「刷新」是**按位置算的**：Feed 刷新了，详情页的快照还是旧的。如果在任何一处刷新时清空整张覆盖表，还没刷新的那些位置会按旧快照（未关注）重新显示「Ikuti」，违反 NFR1。比较「覆盖写入时的版本号」和「快照拉取时的版本号」就能让每个位置各自判断，不需要任何「清除」动作。
- **版本号在发请求前读**：如果请求还在路上时用户点了关注，服务端返回的数据可能包含也可能不包含这次关注；在请求前读版本号，就让覆盖继续生效，以用户刚刚的操作为准。
- **重建时不归零**：版本号放在 notifier 外的静态计数器里，或放在一个永不重建的 provider 里。否则换账号后计数从 0 开始，旧快照的版本号可能比新覆盖还大。
- **常驻位置**（他人主页、我的 Mengikuti 列表，归 Story 1.3 / 2.2）一直显示三态，不走「已关注就隐藏」的分支，但同样读「快照加覆盖（按版本号判断是否生效）」。如果 Story 1.3 的最小实现没有版本号，本 story 扩展后要把主页的调用点改成同一套判断（主页的快照在拉取时也要记下版本号）。Story 2.2（列表）和 3.4（新粉丝回关）直接复用本 story 的判断函数和 `surfaceKey` 约定（如 `list:{tab}:{ownerId}:{userId}`、`notify:{notificationId}`，由各自的 story 决定）。
- 同一篇帖子的详情页在导航栈里打开了两次时，两页的 surfaceKey 都是 `detail:{postId}`，两页会同时显示 Mengikuti。可以接受（同一张内容）。

### authorToken 的取法

在 `AuthorView` 上加 `publicToken` 字段（record 新增字段，另保留一个 5 参数的构造器委托过去），由 `toAuthorView` 填值。这样 Feed、详情、顶置位、评论区、主页等所有调用 `findAuthorViews` 的地方都能**零额外查询**地拿到 token，Story 2.2 / 3.3 也能直接用。已注销用户的 `anonymized` 不带 token。备选做法是另写一个 `findPublicTokens(ids)` 批量方法，但每页会多一次查询，不推荐。

### 架构约束

- AD-2 / D-2：新增的对外标识只用 `public_token`。现有的 `authorId` 字段和数字 id 路由保持不动。
- AD-6 / AD-16 / NFR3：关注状态整页一次批量查询；游客不查库。
- AD-7 Rule 4（v1.1.6）：Feed 两条排序路径和「我的发布」共用一个组装口径和一个工厂方法，不另写。
- AD-7（本版本）：全局覆盖表，按位置算刷新，本按钮保留，其他位置隐藏。
- 网络失败时按钮回滚并提示；游客点按钮弹登录框，登录后不自动关注；关注和取关都**不弹确认框**（PRD）。
- 不加任何新的中间件或缓存层。

### 本 story 不做

- 他人主页的按钮和 Pengikut ±1（Story 1.3）；关注列表和推荐（Story 2.2 / 2.3）；通知里的回关按钮（Story 3.4）。
- 不改评论区、@ 候选、迷你主页、分享落地页（`shared_post_page.dart`）里的作者行。
- 不改首页推荐算法，关注关系不影响 Feed 排序（FR-140.20）。
- 不新增 l10n 文案（按钮文案和 toast 都用 Story 1.3 的）。
- 不给关注埋点加 `feed_tab` / `rank_mode`（PRD 没有要求）。

### 测试

- 后端 L0：`FeedBatchAggregationTest`、`FeedServiceTest`、`ContentDetailServiceTest`、`UserPublicPostsTest`。L1（连本分支的库，`DB_NAME=<新建空库>`，见记忆「后端测试要连本分支的库」）：`FeedExpandedFieldsIntegrationTest`、`PinnedSlotIntegrationTest`、`PinnedSlotHideFilterIntegrationTest`、`ContentDetailControllerEndpointTest`、`ContentFeedControllerEndpointTest`。
- App L0：`test/content/{feed_test, content_detail_test, pinned_slot_test, feed_action_row_test, user_tag_test}.dart`，新增 `test/social/follow_state_provider_test.dart`，跑 `test/analytics/v112_events_test.dart`。
- L2（本地模拟器）：按 UI 稿 C1 ~ C7 逐屏对照，再加 320 宽屏长昵称、顶置卡、自己的待审帖、游客点按钮四种情况。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.4/epics-v1.3.4-follow.md#Story 2.1]
- [Source: _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-follow-delta.md#AD-2 / AD-6 / AD-7 / AD-8 / AD-13 / AD-16]
- [Source: _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-follow.md#3.1「Following 按钮的显示范围」/「入口三：Feed 卡片与帖子详情头部」/ AC1 / AC8 / AC10 / §4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.3.4/决策日志-follow.md#D-2 / D-6]
- [Source: _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-follow.html#C1 ~ C7（`.fc-btn .fbtn` 样式）]

### 待确认项裁定（2026-10-12，操作者 Hex；详见 `planning-artifacts/v1.3.4/决策日志-follow.md` D-8~D-11 / T-x）

> **以下裁定优先于本文件正文与 Completion Notes 中的「待确认」与暂定做法**；标 ⚠️ 的条目与正文暂定做法不同，实现时以此为准。

- T-16 「+」按 UI 稿作前缀单独渲染，文案 key 只存「Ikuti」/「Ikuti Balik」（确认）。
- T-17 详情页徽章保持现有大小，只改位置（确认）。
- ⚠️ T-18 详情页发 / 删评论引起的重拉**不算刷新**：沿用本次进入时的快照版本号，关注按钮保持；重新进入详情页才按最新状态隐藏。
- ⚠️ D-8 被封号作者：Feed 卡与详情页**不显示关注按钮**（作者视图需能区分封号；若现有作者字段拿不到封号状态，后端补一个布尔，不外露原因）。帖子本身是否出现在 Feed 维持现状，不在本 story 改。
- D-9 官方内容账号照常显示关注按钮。
- T-9 409 / 404 同一 toast；404 不额外动作（Feed 场景无主页可重取）。
- T-19 `followStateProvider` / `FollowButton` 以 Story 1.3 为准，本 story 只扩展；用户 token 批量取统一为 `AccountQueryService.publicTokensOf`（与 Story 2.2 共用同一方法）。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- 待确认 1：**文案前面要不要加「+」**。epics 和架构写的是「+ Ikuti」。UI 稿的 HTML 文字是「Ikuti」，「+」是用 CSS `::before` 加上去的（`.fc-btn .fbtn::before{content:"+"}`），「Mengikuti」前面没有「+」。本 story 按 UI 稿处理：「+」作为前缀单独渲染，l10n 用 Story 1.3 的「Ikuti」/「Ikuti Balik」。如果需要把「+」写进文案 key，请拍板。
- 待确认 2：**详情页徽章的大小**。UI 稿 C4 中详情页时间右边的徽章和 Feed 用的是同一个样式（`.tb`：9.5 号、圆角 5），而现在的详情页徽章是 11 号、内边距 9×4、圆角 7。epics 只要求「头像 / 昵称 / 时间尺寸不变」，没说徽章。本 story 暂时保留详情页现有的徽章大小，只改位置。如需和 Feed 统一成 9.5 号，请拍板。
- 待确认 3：**详情页上发表或删除评论会让本页重新拉取**（`commentsRefreshProvider` 触发 invalidate `detailProvider`）。按「重新拉取该数据源就算刷新」的口径，用户刚点了关注、接着发一条评论，「Mengikuti」会随之消失（变成已关注、不显示按钮）。PRD 没写这种情况。如果希望详情页只在重新进入时才算刷新，需要让「发评论引起的重新拉取」沿用上一份快照的版本号，请拍板。
- 待确认 4：**被封号（`status = DEACTIVATED`）的作者**，帖子目前是否还会出现在 Feed 里，需要核实。如果会出现，按钮会显示，点了会因 404 失败并提示。PRD / AD 只规定了「封号用户的主页显示不存在」，没说 Feed 里怎么处理；本 story 不额外隐藏按钮。
- 待确认 5：**Story 1.3 的 `followStateProvider` 和 `FollowButton` 的实际接口**（1.3 的 story 文件还没出）。本 story 定义的版本号、`pinnedSurface`、`surfaceKey` 和显示判断函数，要与 1.3 对齐：1.3 已有的保留，本 story 在其基础上扩展，并按 AC3 改主页的调用点。同理，`AuthorView.publicToken` 的取法如果和 Story 1.1 / 1.2 冲突，以先合入的为准。
- L1 / L2 待本地验收。

### File List
