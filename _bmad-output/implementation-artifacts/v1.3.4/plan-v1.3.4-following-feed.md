# V1.3.4 Following 关注流 + 官方账号默认关注 —— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Social 页加 Discover / Following 两个页签；Following = 关注的人的公开帖（时间倒序、双列复用 Discover 卡片）+ 顶部关注头像排；普通用户默认关注官方账号，取关后不再被自动关注。

**Architecture:** 后端新增两个只读端点（关注流、头像排）+ 一张「官方号取关记录」表 + `user_follows.source` 来源列；自动关注走内部方法（不限流、不发事件），注册时由事件监听触发、存量由迁移回填。App 首页头部换成页签，Following 页签复用 `FeedMasonryView` 与 `FeedState`，空状态复用抽出的推荐关注组件。

**Tech Stack:** Spring Boot 4.0.6 / Java 21 / Flyway / PostgreSQL；Flutter 3.44 / Riverpod 3 / go_router / dio。

**Spec:** `_bmad-output/implementation-artifacts/specs/spec-v1.3.4-following-feed.md`（设计图 `_bmad-output/planning-artifacts/v1.3.4/following-feed-assets/`）

## Global Constraints

- 分支：在 `dev_1.3.4` 上直接开发（spec §5，2026-10-14）；每个 Task 一个提交，提交信息 `feat(v1.3.4): following 流 · <task 名>`，结尾带 `Co-Authored-By` 行；**不 push、不部署**（等用户指令）。
- Flyway：时间戳命名 `V<yyyyMMdd_HHmm>__<snake>.sql`，版本须 > `V20261013_0649`；不改任何已存在迁移；`ddl-auto=validate`。
- 打包一律 `mvn -B clean package`。
- 对外用户标识：新接口返回 `userToken`（`users.public_token`），同时带 `userId`（跳转现有主页路由用），与 `FollowListItem` 口径一致（D-2）。
- 过滤只升不降：关注流必须包含 `findFeed` 的全部过滤（PUBLISHED、PUBLIC、未删、排除我举报的帖、排除 `user_hide_relations(holder=我)` 的作者），并额外排除作者 `deleted_at IS NOT NULL` 或 `status <> 'ACTIVE'`。**Discover（`findFeed` / 推荐池）一行不改。**
- 自动关注：不发 `UserFollowedEvent`（官方号不收新粉丝通知）、不走 `checkRate` / `checkDailyLimit`；计数照常 `FollowCounterService.applyFollow`。仅 `role = USER` 的用户；目标 = `role = ADMIN` 且未注销、`status = ACTIVE` 的全部用户。
- `@TransactionalEventListener(AFTER_COMMIT)` 里写库必须 `@Transactional(propagation = REQUIRES_NEW)`（项目已踩过「AFTER_COMMIT + REQUIRED 静默不提交」）。
- App：用户可见文案只进 `app_en.arb` / `app_id.arb` 再 `flutter gen-l10n`；不整文件 `dart format`；新增用户维度 provider 必须登记到 `app.dart` 的 `resetUserScopedCaches`。
- 文案（spec §4）：页签 id「Jelajah」/ en「Discover」、id「Mengikuti」/ en「Following」；More id「Lainnya」/ en「More」；空状态标题 id「Belum ada postingan dari teman」/ en「No posts from friends yet」；说明 id「Ikuti orang yang kamu suka, momen dan diary mereka akan muncul di sini.」/ en「Follow people you like and their moments and diaries will show up here.」；底部 id「Jelajahi Discover ›」/ en「Explore Discover ›」；推荐区标题复用现有 key。

## Review Focus

1. **关注了人但对方帖子全部被过滤**（我举报隐藏了他 / 他被封号）：关注流为空 → 走空状态且头像排不显示，不得出现「有头像排、下方空白」。→ Task 3 / Task 8 测试。
2. **用户取关官方号后重新登录、或回填迁移因故重跑**：不得被自动关注回去。→ Task 2 测试。
3. **官方号本人 / 兽医注册或登录**：不触发自动关注（`role ≠ USER`）。→ Task 2 测试。
4. **翻页边界**：关注流第 2 页与第 1 页无重复无遗漏（同一 `created_at` 的多条帖用 id 兜底）。→ Task 3 测试。
5. **换账号**：A 登出、B 登录后 Following 头像排与帖子流不残留 A 的数据。→ Task 6 测试（provider 登记）。

---

### Task 1: 迁移 —— 来源列 + 官方号取关表 + 存量回填

**Files:**
- Create: `petgo-backend/src/main/resources/db/migration/V20261014_1600__following_feed_official_follow.sql`
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/repository/UserFollowRepository.java`（`countCreatedSince` 只数 `source = 'USER'`；新增 `insertSystemIfAbsent`）
- Test: `petgo-backend/src/test/java/com/tailtopia/social/FollowIntegrationTest.java`

**Interfaces:**
- Produces:
  - 列 `user_follows.source VARCHAR(16) NOT NULL DEFAULT 'USER'`，CHECK `source IN ('USER','SYSTEM')`。
  - 表 `official_follow_optouts(user_id BIGINT FK users, official_id BIGINT FK users, created_at TIMESTAMPTZ DEFAULT now(), PRIMARY KEY(user_id, official_id))`。
  - `int UserFollowRepository.insertSystemIfAbsent(long followerId, long followeeId)`：同 `insertIfAbsent`，`source='SYSTEM'`。

- [ ] **Step 1: 写失败测试** `systemFollowRowsDoNotCountTowardsDailyLimit`：给 viewer 直接插 500 行 `source='SYSTEM'` 的关注（JdbcTemplate），再调 `followService.follow(viewer, 某人token)` → 成功（不抛 429 / 日限）。
- [ ] **Step 2: 跑测试确认失败**：`cd petgo-backend && mvn -B -q test -Dtest=FollowIntegrationTest#systemFollowRowsDoNotCountTowardsDailyLimit` → FAIL（列不存在或日限触发）。
- [ ] **Step 3: 写迁移**：加列 + 建表 + 回填。回填规则（单条 INSERT…SELECT，`ON CONFLICT DO NOTHING`，`source='SYSTEM'`）：follower ∈ `role='USER' AND deleted_at IS NULL AND status='ACTIVE'`；followee ∈ `role='ADMIN' AND deleted_at IS NULL AND status='ACTIVE'`；排除 `official_follow_optouts` 中的对、排除 `user_hide_relations(holder=follower, target=followee, source='BLOCK')`。回填后**按表重算**受影响用户的 `following_count`（全部 USER）与 `follower_count`（全部 ADMIN）——用 `count(*)` 子查询 UPDATE，不做 ±1。文件头注释写明「可重跑：ON CONFLICT + 重算计数」。
- [ ] **Step 4: 改 `countCreatedSince` 加 `AND source = 'USER'`，新增 `insertSystemIfAbsent`。**
- [ ] **Step 5: 跑测试**：同 Step 2 → PASS；再跑 `mvn -B -q test -Dtest='Follow*IntegrationTest'` → 全 PASS。
- [ ] **Step 6: 提交。**

### Task 2: 自动关注服务 + 注册钩子 + 取关记录

**Files:**
- Create: `petgo-backend/src/main/java/com/tailtopia/social/service/OfficialAutoFollowService.java`
- Create: `petgo-backend/src/main/java/com/tailtopia/social/service/OfficialAutoFollowListener.java`
- Create: `petgo-backend/src/main/java/com/tailtopia/social/repository/OfficialFollowOptoutRepository.java`（原生 SQL：`insertIfAbsent(userId, officialId)`、`exists(userId, officialId)`）
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/service/FollowService.java`（`unfollow`：目标 `role == ADMIN` 且真删了一行 → 记 optout）
- Test: `petgo-backend/src/test/java/com/tailtopia/social/OfficialAutoFollowIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `insertSystemIfAbsent`、`official_follow_optouts`；`FollowCounterService.applyFollow(long, long)`；`UserSignedUpEvent(long userId, String nickname)`。
- Produces: `void OfficialAutoFollowService.followOfficialAccounts(long userId)` —— 仅当该用户 `role=USER` 且有效时执行；对每个有效 ADMIN：已 optout 或我拉黑了他 → 跳过；`insertSystemIfAbsent == 1` → `applyFollow`。不发事件。

- [ ] **Step 1: 写失败测试**（`OfficialAutoFollowIntegrationTest extends ApiIntegrationTest`）：
  - `newUserFollowsOfficialAccount`：建 ADMIN 号 → 调 `followOfficialAccounts(newUser)` → `user_follows` 有一行 `source=SYSTEM`；双方计数 +1；`notifications` 无 `USER_FOLLOWED`。
  - `idempotent`：连调两次计数只 +1。
  - `unfollowOfficialRecordsOptoutAndBlocksReFollow`：自动关注 → `followService.unfollow(user, adminToken)` → optout 存在 → 再调 `followOfficialAccounts` → 无关注行。
  - `nonUserRolesSkipped`：VET、ADMIN 自身调用 → 无新增行。
  - `listenerRunsOnSignup`：发布 `UserSignedUpEvent`（在事务内提交）后等待 → 关注行存在（`@Async` 用 Awaitility 或现有测试里等待异步的写法）。
- [ ] **Step 2: 跑测试确认失败**：`mvn -B -q test -Dtest=OfficialAutoFollowIntegrationTest` → FAIL（类不存在）。
- [ ] **Step 3: 实现** `OfficialAutoFollowService`（`@Transactional`）、`OfficialAutoFollowListener`（`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + 内部调用走 `REQUIRES_NEW`，参照 `shared/im/ImAccountRegistrationListener`）、`FollowService.unfollow` 记 optout（只对 ADMIN 目标）。
- [ ] **Step 4: 跑测试**：同 Step 2 → PASS；`mvn -B -q test -Dtest='Follow*IntegrationTest,OfficialAutoFollow*'` → 全 PASS。
- [ ] **Step 5: 提交。**

### Task 3: 关注流接口 `GET /api/v1/me/following-feed`

**Files:**
- Modify: `petgo-backend/src/main/java/com/tailtopia/content/repository/ContentPostRepository.java`（新增 `findFollowingFeed`）
- Modify: `petgo-backend/src/main/java/com/tailtopia/content/service/FeedService.java`（新增 `loadFollowingFeed`）
- Create: `petgo-backend/src/main/java/com/tailtopia/content/web/FollowingFeedController.java`
- Test: `petgo-backend/src/test/java/com/tailtopia/content/FollowingFeedIntegrationTest.java`

**Interfaces:**
- Produces:
  - `List<ContentPost> ContentPostRepository.findFollowingFeed(long viewerId, boolean hasCursor, Instant cursorTs, Long cursorId, Pageable page)`：WHERE = `findFeed` 中除 `UNDER_REVIEW` 本人例外、类型 / 宠物 / 顶置让位以外的全部条件 + `p.authorId IN (SELECT f.followeeId FROM UserFollow f WHERE f.followerId = :viewerId)` + 作者 `deletedAt IS NULL AND status = ACTIVE`；`ORDER BY p.createdAt DESC, p.id DESC`。
  - `FeedPageResponse FeedService.loadFollowingFeed(long viewerId, String cursor)`：`PAGE_SIZE + 1` 取数、`FeedCursor` 游标、`assemble(..., RANK_MODE_CHRONO)`。
  - `GET /api/v1/me/following-feed?cursor=` → `FeedPageResponse`；仅登录（游客 401）。
- [ ] **Step 1: 写失败测试**：
  - `onlyFollowedAuthorsPublicPostsNewestFirst`：关注 A 不关注 B；A 发公开 / 私密 / 已删各 1、B 发公开 1 → 只返回 A 的公开帖。
  - `excludesReportHiddenAndBannedAuthors`：关注 C、D；C 在 `user_hide_relations(holder=viewer, source=REPORT)`；D `status=DEACTIVATED` → 两者的帖都不出现。
  - `excludesPostsIReported`。
  - `paginationNoDupNoGap`：同一 `created_at` 的 25 条帖 → 两页合计 25 条、id 不重复。
  - `guestGets401`。
- [ ] **Step 2: 跑测试确认失败**：`mvn -B -q test -Dtest=FollowingFeedIntegrationTest` → FAIL。
- [ ] **Step 3: 实现 repository 查询、service 方法、controller。**
- [ ] **Step 4: 跑测试** → PASS；回归 `mvn -B -q test -Dtest='Feed*'` → 全 PASS（Discover 未受影响）。
- [ ] **Step 5: 提交。**

### Task 4: 关注头像排接口 `GET /api/v1/me/following-strip`

**Files:**
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/repository/UserFollowRepository.java`（新增原生查询 `findStripUserIds`）
- Create: `petgo-backend/src/main/java/com/tailtopia/social/service/FollowingStripService.java`
- Create: `petgo-backend/src/main/java/com/tailtopia/social/dto/FollowingStripResponse.java`
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/web/FollowController.java`（加路由）
- Test: `petgo-backend/src/test/java/com/tailtopia/social/FollowingStripIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record FollowingStripResponse(List<Item> items)`，`record Item(long userId, String userToken, String nickname, String avatarUrl)`。
  - `GET /api/v1/me/following-strip` → 最多 **20** 个；仅登录。
  - 排序：被关注者最近一条「对我可见的公开帖」（同 Task 3 过滤口径）的 `created_at` 倒序，`NULLS LAST`；再按关注时间 `user_follows.created_at DESC`；再 `user id DESC`。排除：注销 / 非 ACTIVE、`user_hide_relations(holder=我)` 中的人。
- [ ] **Step 1: 写失败测试**：`orderByLatestVisiblePostThenFollowTime`（3 人：最新发帖 / 较早发帖 / 未发帖 → 顺序正确）、`capsAt20`（关注 25 人 → 20 条）、`excludesBannedDeletedAndHidden`、`guestGets401`。
- [ ] **Step 2: 跑测试确认失败**：`mvn -B -q test -Dtest=FollowingStripIntegrationTest` → FAIL。
- [ ] **Step 3: 实现**（一条原生 SQL 取 id 列表，再批量取用户字段，禁止逐个查）。
- [ ] **Step 4: 跑测试** → PASS。
- [ ] **Step 5: 提交。**

### Task 5: 推荐关注补宠物物种 / 生日

**Files:**
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/dto/FollowListItem.java`（新增可空字段 `petType`、`petBirthday`，为空不序列化）
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/service/FollowSuggestionService.java`（批量查推荐用户的宠物档案填充）
- Test: `petgo-backend/src/test/java/com/tailtopia/social/FollowSuggestionIntegrationTest.java`

**Interfaces:**
- Produces: `FollowListItem.petType: String?`（`CAT`/`DOG`/`OTHER`）、`petBirthday: LocalDate?`；仅推荐接口填充，关注 / 粉丝列表仍为 null（不输出）。
- [ ] **Step 1: 写失败测试** `suggestionCarriesPetTypeAndBirthday`：推荐用户有宠物（DOG、生日 2024-01-01）→ 返回 `petType=DOG, petBirthday=2024-01-01`；无宠物者两字段缺省。另断言 `/users/{token}/following` 响应 JSON 不含 `petType` 键。
- [ ] **Step 2: 跑确认失败**：`mvn -B -q test -Dtest=FollowSuggestionIntegrationTest` → FAIL。
- [ ] **Step 3: 实现**（宠物档案按 owner 批量一次查；V1 每人一只，取未删的那只）。
- [ ] **Step 4: 跑测试** → PASS；`mvn -B -q test -Dtest='Follow*'` 全 PASS。
- [ ] **Step 5: 提交。**

### Task 6: App 数据层（接口、模型、provider）

**Files:**
- Modify: `petgo_app/lib/core/network/api_paths.dart`（`meFollowingFeed = '$base/me/following-feed'`、`meFollowingStrip = '$base/me/following-strip'`）
- Modify: `petgo_app/lib/features/content/data/feed_repository.dart`（`Future<FeedPage> getFollowingFeed({String? cursor})`，解析复用现有 `FeedPage`）
- Create: `petgo_app/lib/features/social/data/following_strip_repository.dart`（`FollowingStripUser{int userId, String userToken, String? nickname, String? avatarUrl}`、`followingStripProvider`：`FutureProvider.autoDispose<List<FollowingStripUser>>`）
- Create: `petgo_app/lib/features/content/presentation/following_feed_controller.dart`（`followingFeedProvider = AsyncNotifierProvider<FollowingFeedController, FeedState>`，`build / loadMore / refresh / removeItem / removeByAuthor / syncCounts` 与 `FeedController` 同签名同语义，取数改调 `getFollowingFeed`；`FeedState.category` 填 `FeedCategory.all`）
- Modify: `petgo_app/lib/features/social/data/follow_suggestion_repository.dart`（模型加 `String? petType`、`DateTime? petBirthday`）
- Modify: `petgo_app/lib/app.dart`（`resetUserScopedCaches` 登记 `followingFeedProvider`、`followingStripProvider`）
- Test: `petgo_app/test/social/following_feed_controller_test.dart`

**Interfaces:**
- Produces: `followingFeedProvider`、`followingStripProvider`、`FollowingStripUser`、`FeedRepository.getFollowingFeed`、建议项 `petType` / `petBirthday`。
- [ ] **Step 1: 写失败测试**（fake repository override）：`loadsFirstPageAndMore`（两页拼接、`hasMore` 正确）、`refreshReplacesItems`、`resetUserScopedCachesInvalidatesFollowing`（调 reset 后 provider 重新 build）。
- [ ] **Step 2: 跑确认失败**：`cd petgo_app && flutter test test/social/following_feed_controller_test.dart` → FAIL。
- [ ] **Step 3: 实现上述文件。**
- [ ] **Step 4: 跑测试** → PASS；`flutter analyze` 零问题。
- [ ] **Step 5: 提交。**

### Task 7: 推荐关注组件抽出（含物种 · 年龄副标题）

**Files:**
- Create: `petgo_app/lib/features/social/presentation/widgets/suggested_follow_list.dart`（`SuggestedFollowList({required String source})`：标题 + 行列表；每行头像 + 昵称 + 副标题「物种 · 年龄」+ `FollowButton`；行点击进主页；加载失败静默隐藏（T-24 口径））
- Modify: `petgo_app/lib/features/social/presentation/follow_list_page.dart`（`_selfEmpty` 改用 `SuggestedFollowList(source: 'suggested')`，行为不变）
- Test: `petgo_app/test/social/suggested_follow_list_test.dart`

**Interfaces:**
- Consumes: Task 6 的 `petType` / `petBirthday`；`formatPetAge(l10n, birthday)`（`features/profile/domain/pet_age.dart`）；物种文案同 `public_profile_page.dart:840` 的 switch（`petTypeCat` / `petTypeDog` / `petTypeOther`）——抽成 `String? petSpeciesLabel(AppLocalizations, String?)` 放 `features/profile/domain/pet_age.dart` 旁，`public_profile_page` 改调它。
- Produces: `SuggestedFollowList`。
- [ ] **Step 1: 写失败测试**：`rendersSpeciesAndAgeSubtitle`（DOG + 2 岁 → 「Anjing · 2 thn」类文本按 l10n 断言）、`noPetNoSubtitle`、`hiddenOnError`。
- [ ] **Step 2: 跑确认失败**：`flutter test test/social/suggested_follow_list_test.dart` → FAIL。
- [ ] **Step 3: 实现，改 `follow_list_page` 与 `public_profile_page` 调用点。**
- [ ] **Step 4: 跑** `flutter test test/social test/user_profile` → 全 PASS。
- [ ] **Step 5: 提交。**

### Task 8: Social 页签 + Following 页（头像排、帖子流、空状态）

**Files:**
- Create: `petgo_app/lib/features/content/presentation/social_tab.dart`（`enum SocialTab { discover, following }`、`socialTabProvider = NotifierProvider<…, SocialTab>` 默认 discover；`SocialTabBar` 组件：两个大字号页签，选中品牌紫 + 下划线）
- Create: `petgo_app/lib/features/content/presentation/following_tab_view.dart`（`FollowingTabView`：调用 `followingFeedProvider` / `followingStripProvider`；有帖 → `FeedMasonryView(header: FollowingStrip, feedTab: 'following', …)`；无帖 → `FollowingEmptyView`）
- Create: `petgo_app/lib/features/content/presentation/widgets/following_strip.dart`（横滑头像排：每项 56 圆头像 + 紫色外圈 + 昵称单行省略；末尾「More」虚线圈；点头像 → 用户主页；点 More → `FollowListPage.location(myId, FollowListTab.following)`）
- Create: `petgo_app/lib/features/content/presentation/widgets/following_empty_view.dart`（图标 + 标题 + 说明 + `SuggestedFollowList(source: 'following_empty')` + 「Explore Discover ›」→ `socialTabProvider` 置 discover）
- Modify: `petgo_app/lib/features/content/presentation/home_page.dart`（AppBar title 由品牌 logo 改为 `SocialTabBar`；body 按 `socialTabProvider` 切换现有 `_content` 与 `FollowingTabView`；游客点 Following → `requireLogin(ref, context, entrySource: 'following_tab', onAllowed: …)`，登录成功后置 following）
- Modify: `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（Global Constraints 中的 7 条文案）
- Test: `petgo_app/test/content/following_tab_view_test.dart`、`petgo_app/test/content/home_social_tab_test.dart`

**Interfaces:**
- Consumes: Task 6、Task 7 全部产出；`requireLogin`（`features/auth/domain/auth_guard.dart:15`）；`FeedMasonryView`（`feed_view.dart:20`）。
- [ ] **Step 1: 写失败测试**：
  - `discoverIsDefaultAndUnchanged`：首页默认 Discover，分类 chips 与瀑布流仍在。
  - `guestTapFollowingOpensLogin`：游客点 Following → 弹登录引导、页签不切换。
  - `stripShowsUpTo20PlusMore`：strip 返回 20 人 → 20 头像 + 末尾 More；点 More push 关注列表路由。
  - `noVisiblePostsShowsEmptyWithoutStrip`：关注流首页空、strip 非空 → 只显示空状态（无头像排）、含推荐区与 Explore Discover。
  - `exploreDiscoverSwitchesTab`。
  - `followingCardsHaveNoFollowButton`：关注流卡片 `authorFollowState.iFollow=true` → 无「Ikuti」。
- [ ] **Step 2: 跑确认失败**：`flutter test test/content/following_tab_view_test.dart test/content/home_social_tab_test.dart` → FAIL。
- [ ] **Step 3: 实现上述文件，`flutter gen-l10n`。** 埋点：`FeedMasonryView.feedTab = 'following'`；新增 `social_tab_viewed {tab}`、`following_strip_tapped {target: avatar|more}`（走 `Analytics.capture` 白名单惯例）。
- [ ] **Step 4: 跑** `flutter analyze` 零问题；`flutter test` 全量 PASS。
- [ ] **Step 5: 提交。**

### Task 9: 联调验收（L1 + 模拟器 + 还原度）

**Files:** 无代码改动（发现问题回到对应 Task 修）。
- [ ] **Step 1: 后端全量**：`cd petgo-backend && mvn -B clean package` → BUILD SUCCESS（含全部测试）。
- [ ] **Step 2: 本地 L2 环境**：用新 jar 重启本地后端（`petgo_l2` 库，迁移自动跑），确认 `/actuator/health` UP，且存量 USER 均已关注官方号（SQL 抽查）。
- [ ] **Step 3: 模拟器走查**（debug 包 `--dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080 --dart-define=DEV_GOOGLE_STUB=true`）：spec §6 六条验收逐条截图；Review Focus 五条逐条复现。
- [ ] **Step 4: UI 还原度**：对照 `A2` / `A3` 逐屏打分，≥ 95%（头部页签、头像排、空状态；帖子卡片按拍板复用双列不计偏差）。
- [ ] **Step 5: 汇报结果，等用户指令再合 stag / 部署。**
