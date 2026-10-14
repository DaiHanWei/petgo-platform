# V1.3.4 帖子点赞人列表 —— 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点赞区拆成「爱心 = 点赞」「数字 = 点赞人列表」，点数字弹出可半屏 / 全屏切换的底部抽屉，列出点赞人（时间倒序、可关注）。

**Architecture:** 后端新增 `GET /api/v1/content-posts/{id}/likers` 游标分页接口，可见性复用帖子详情的判定，行结构复用关注列表的 `FollowListItem`；App 只改共用组件 `LikeButton`（Feed 卡片与详情页同时生效），新增抽屉组件与分页 provider。

**Tech Stack:** Spring Boot 4.0.6 / Java 21 / Flyway / PostgreSQL；Flutter 3.44 / Riverpod 3。

**Spec:** `_bmad-output/implementation-artifacts/specs/spec-v1.3.4-post-likers-sheet.md`

**前置：** 在 Following 计划（`plan-v1.3.4-following-feed.md`）全部完成后开始，同一分支 `dev_1.3.4`。

## Global Constraints

- 分支 `dev_1.3.4`；每个 Task 一个提交，信息 `feat(v1.3.4): 点赞人列表 · <task 名>`，结尾 `Co-Authored-By` 行；**不 push、不部署**。
- Flyway 时间戳命名，版本 > 当时树内最新迁移；不改已有迁移（含 `V10__init_content_likes.sql`）；`ddl-auto=validate`。
- 接口：`GET /api/v1/content-posts/{id}/likers?cursor=` → `FollowListPage{items, nextCursor, hasMore}`，每页 **20**；仅 `role=USER` 登录用户（SecurityConfig `hasRole("USER")`，游客 401、兽医 403）。
- 排序：`content_likes.created_at DESC, content_likes.id DESC`，自己**不置顶**。
- 过滤：排除我拉黑的人（`user_hide_relations` holder=我 且 `source='BLOCK'`，**单向**）、`deleted_at IS NOT NULL`、`status <> 'ACTIVE'` 的用户。
- 可见性：帖子对我不可见（已删 / 未发布且非作者 / 私密且非作者 / 我举报过 / 作者在我的隐藏关系里）→ 404，与帖子详情判定**同一份代码**。
- App：点爱心行为一行不改；数字热区放大但与爱心热区不重叠；本地计数为 0 时点数字无反应；游客点数字 → `requireLogin`（不打开抽屉）；抽屉半屏 ≈ 0.5、全屏 1.0，半屏下拉 / 点阴影 / 返回键关闭；标题 id「Suka」/ en「Likes」；空状态 id「Belum ada yang menyukai」/ en「No likes yet」；文案只进 ARB；不整文件格式化。
- 关注按钮复用 `FollowButton` 与全局 `followStateProvider`，自己那行不显示按钮。

## Review Focus

1. **点爱心与点数字在 Feed 小卡上的误触**：两个热区不重叠，点爱心不弹抽屉、点数字不切换点赞。→ Task 3 测试。
2. **抽屉打开期间帖子被删 / 作者被我拉黑**：接口 404 → 提示「内容已不存在」并关闭抽屉，不白屏。→ Task 3 测试。
3. **点赞人全部被过滤**（都是我拉黑的人）：显示空状态，而非无限加载。→ Task 1 / Task 3 测试。
4. **翻页边界**：同一时刻的多条点赞用 id 兜底，两页无重复。→ Task 1 测试。
5. **在抽屉里关注某人后关闭抽屉**：Feed / 详情页同一作者的关注按钮状态同步（全局状态），不残留。→ Task 3 测试。

---

### Task 1: 后端点赞人接口

**Files:**
- Create: `petgo-backend/src/main/resources/db/migration/V<时间戳>__content_likes_post_created_index.sql`（`CREATE INDEX idx_content_likes_post_created ON content_likes (post_id, created_at DESC, id DESC)`）
- Modify: `petgo-backend/src/main/java/com/tailtopia/content/service/ContentDetailService.java`（把 `getDetail` 开头的可见性判定抽成 `ContentPost requireVisiblePost(long postId, Long viewerId)`，`getDetail` 改调它，行为不变；若详情对 PRIVATE 帖另有判定，一并纳入）
- Modify: `petgo-backend/src/main/java/com/tailtopia/content/repository/` 下点赞仓库（新增按帖分页查询，原生 SQL 带过滤，`LIMIT 21`）
- Create: `petgo-backend/src/main/java/com/tailtopia/content/service/PostLikersService.java`
- Modify: `petgo-backend/src/main/java/com/tailtopia/social/service/FollowListQueryService.java`（把把一批 userId 组装成 `FollowListItem`（含 token、关注三态、self、blockedByMe）的逻辑暴露为 `public List<FollowListItem> itemsFor(List<Long> userIds, Long viewerId)`，原 `assemble` 改调它）
- Modify: `petgo-backend/src/main/java/com/tailtopia/content/web/LikeController.java`（加 GET 路由）、`SecurityConfig`（`hasRole("USER")`）
- Test: `petgo-backend/src/test/java/com/tailtopia/content/PostLikersIntegrationTest.java`

**Interfaces:**
- Produces: `FollowListPage PostLikersService.likers(long postId, long viewerId, String cursor)`；`GET /api/v1/content-posts/{id}/likers?cursor=`；游标复用关注列表的 `KeysetCursor`（created_at + content_likes.id）。
- [ ] **Step 1: 写失败测试**：
  - `newestFirstWithPagination`：25 人点赞（含同一 `created_at` 多条）→ 第一页 20、`hasMore=true`，第二页 5，id 不重复；顺序 = 点赞时间倒序。
  - `excludesBlockedDeletedBanned`：点赞人含我拉黑的（BLOCK）、举报隐藏的（REPORT，应**保留**）、已注销、封号 → 只有前两类中的 REPORT 那位与正常用户出现。
  - `blockedByThemStillShown`：对方拉黑了我 → 对方仍在列表。
  - `selfRowFlagged`：我点过赞 → 我那行 `self=true`，位置按时间不置顶。
  - `invisiblePost404`：已删帖、他人私密帖、我举报过的帖、作者被我拉黑的帖 → 404。
  - `allFilteredEmptyPage`：点赞人全是我拉黑的 → `items=[]`、`hasMore=false`。
  - `guest401VetForbidden`。
  - 回归：`ContentDetail*` 测试全绿（抽方法不改行为）。
- [ ] **Step 2: 跑确认失败**：`DB_NAME=… mvn -B -q test -Dtest=PostLikersIntegrationTest` → FAIL。
- [ ] **Step 3: 实现上述文件。**
- [ ] **Step 4: 跑** `-Dtest='PostLikersIntegrationTest,ContentDetail*,Follow*'` → 全 PASS。
- [ ] **Step 5: 提交。**

### Task 2: App 数据层

**Files:**
- Modify: `petgo_app/lib/core/network/api_paths.dart`（`static String postLikers(int postId) => '$contentPosts/$postId/likers';`）
- Create: `petgo_app/lib/features/content/data/post_likers_repository.dart`（`Future<FollowListPageData> getLikers(int postId, {String? cursor})`，解析复用关注列表现有的页模型；若现有模型是私有的则复用其 `fromJson`）
- Create: `petgo_app/lib/features/content/presentation/post_likers_controller.dart`（`postLikersProvider = AsyncNotifierProvider.autoDispose.family<…, LikersState, int>`；`build` 拉首页、`loadMore`、`retry`；404 时状态为 `gone`）
- Test: `petgo_app/test/content/post_likers_controller_test.dart`
- [ ] **Step 1: 写失败测试**（fake repo）：`loadsAndPaginates`、`goneOn404`、`errorThenRetry`。
- [ ] **Step 2: 跑确认失败**：`flutter test test/content/post_likers_controller_test.dart` → FAIL。
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑** → PASS；`flutter analyze` 零问题。
- [ ] **Step 5: 提交。**

### Task 3: App 抽屉 + LikeButton 拆分点击区

**Files:**
- Create: `petgo_app/lib/features/content/presentation/post_likers_sheet.dart`（`Future<void> showPostLikersSheet(BuildContext context, {required int postId, required String source})`：`showModalBottomSheet(isScrollControlled: true, barrierDismissible)` + `DraggableScrollableSheet(initialChildSize: 0.5, minChildSize: 0.5 以下下拉关闭, maxChildSize: 1.0, snap: true, snapSizes: [0.5, 1.0])`；顶部把手 + 标题；列表行复用关注列表行组件（若为私有，抽到 `features/social/presentation/widgets/follow_user_row.dart` 并让关注列表改用它）；空状态 / 失败 + 重试；`gone` → toast「内容已不存在」（复用现有 key）并关闭）
- Modify: `petgo_app/lib/features/content/presentation/like_button.dart`（爱心与数字拆成两个独立 `GestureDetector`/`InkWell`：爱心 → 现有 `_toggle`；数字 → 计数 0 无反应、游客 `requireLogin`、否则 `showPostLikersSheet`；新增必填参数 `likersSource`，数字热区用 `Padding` 扩大但不覆盖爱心）
- Modify: `petgo_app/lib/shared/widgets/masonry_card.dart`（传 `likersSource: 'feed_card'`）、`petgo_app/lib/features/content/presentation/comment_composer.dart`（传 `'content_detail'`）
- Modify: `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（标题、空状态两条）
- Test: `petgo_app/test/content/like_button_split_test.dart`、`petgo_app/test/content/post_likers_sheet_test.dart`
- [ ] **Step 1: 写失败测试**：
  - `tapHeartTogglesLikeOnly`、`tapCountOpensSheet`、`zeroCountNoop`、`guestTapCountShowsLogin`（不打开抽屉）。
  - `sheetStartsHalfExpandsFullCollapsesAndDismisses`（拖拽断言 extent 0.5 → 1.0 → 0.5 → 关闭）、`tapBarrierDismisses`。
  - `rowsShowFollowButtonExceptSelf`、`followInSheetSyncsGlobalState`、`emptyState`、`goneShowsToastAndCloses`。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现，`flutter gen-l10n`。** 埋点：`post_likers_opened {source}`；抽屉内关注沿用 `user_followed`，`source = 'likers'`。
- [ ] **Step 4: 跑** `flutter analyze` 零问题；`flutter test` 全量 PASS。
- [ ] **Step 5: 提交。**

### Task 4: 联调验收

- [ ] **Step 1:** `cd petgo-backend && mvn -B clean package` → BUILD SUCCESS。
- [ ] **Step 2:** 本地 L2 后端换新 jar 重启，模拟器（debug 包连 10.0.2.2:8080、桩登录）逐条走 spec §6 五条验收 + Review Focus 五条，截图。
- [ ] **Step 3:** 汇报结果，等用户指令再合 stag / 部署。
