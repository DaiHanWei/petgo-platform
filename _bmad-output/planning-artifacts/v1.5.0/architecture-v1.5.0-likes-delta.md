---
title: "V1.5.0 likes 主题（会话 ⑤ 点赞）· 架构增量"
status: complete
completedAt: '2026-11-14'
version: v1.5.0
theme: likes
branch: feat/1.5.0-likes（从 dev_1.5.0 切出，地基 Story 0.1 之后）
code_baseline: dev_1.5.0 @ 9680309cf（地基 0.1 已合）
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/自动点赞/1-5-0自动点赞prd.md            # PRD A，FR-212~223，AC1~AC50（英文）
  - _bmad-output/planning-artifacts/v1.5.0/Feed双击点赞/1-5-0Feed双击点赞prd.md      # PRD B，FR-209~211，AC1~AC24
  - _bmad-output/planning-artifacts/v1.5.0/Feed双击点赞/ui-1-5-0-feed-double-tap-like-integrated-v1.html  # 定稿 + 拆页-App/
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md               # 「2026-11-14 V1.5.0 六会话共用地基」
  - _bmad-output/planning-artifacts/architecture.md
---

# V1.5.0 likes 主题 · 架构增量

> 一个会话、一个分支 `feat/1.5.0-likes`，装两份互不依赖的小 PRD：
> **A 自动点赞**（只改后端，Epic 1–2，先做）+ **B Feed 双击点赞**（只改 App，Epic 3–4）。
> 权威顺序：`decision-log.md` > PRD（变更记录最晚一条为准）> 本文件 > UI 稿。与代码现状冲突处在 §1 列出，以代码为准。
> 本主题**不碰地基**：不改 `ck_notifications_type`、不新增通知类型、不改 `NotificationType` / `OpsAppPage` / `AppVersionGate` / `RetentionPushCalendar`。

---

## 0. 编号说明

- PRD A 内部编号：**FR-212 ~ FR-223、AC1 ~ AC50**（任务交代写的「FR-193~203」是旧号，PRD 2026-11-13 已整体 +19 改号；本主题一律用新号，PRD 里的 AC 编号原样引用，记作「A-AC*n*」）。
- PRD B：**FR-209 ~ FR-211、AC1 ~ AC24**，记作「B-AC*n*」。
- 本文件的决定编号：后端 **L-1 ~ L-14**，App **F-1 ~ F-9**，护栏 **G-1 ~ G-6**。

---

## 1. 代码核对（`dev_1.5.0` @ 9680309cf，2026-11-14）

### 1.1 PRD A 触点核对

| PRD 说法 | 代码现状 | 结论 / 处置 |
|---|---|---|
| 复用 `content/autocomment`：`AutoCommentJob` / `AutoCommentService` / `VirtualIdentityPicker` | 均在 `petgo-backend/src/main/java/com/tailtopia/content/autocomment/`。cron `${petgo.auto-comment.morning-cron:0 30 10 * * *}` / `evening-cron 0 30 16`，`zone = "Asia/Jakarta"`；防重入只有 `AtomicBoolean`（单实例） | 新包 `content/autolike/`，照抄 cron / 配置类 / 逐帖事务 / 留档写法；**多实例防重另做**（L-6） |
| `VirtualIdentityPicker.pick(pool, authorId, species)` | 排除自己 + 被作者隐藏的号 → 同物种 → GENERAL → 剩余，随机 | **不改 picker**；调用前先从 pool 去掉「已赞过此帖的号」与「非 ACTIVE 的号」（L-9） |
| 自动评论的号池 `virtualPool()`：VIRTUAL + enabled + 未注销 | 不过滤 `status = ACTIVE` | 自动点赞的池**多加 `status == UserStatus.ACTIVE`**（FR-216）；自动评论的池不动（PRD §6.2 不改自动评论） |
| `LikeService.like(postId, userId)` 幂等、新点赞发 `ContentLikedEvent` | 先 `existsBy` 再 `save`，撞唯一约束时 `catch DataIntegrityViolationException` 吞掉。返回 `LikeResponse(liked, likeCount)`，**看不出这次是否真新建了一行** | 新增 `LikeService.likeAutomated(postId, virtualUserId)`（L-3）：用 `INSERT … ON CONFLICT DO NOTHING` 返回行数判定「真新建」，不靠异常（在外层事务里吞 `DataIntegrityViolationException` 会把整个事务标成 rollback-only，提交时抛 `UnexpectedRollbackException`） |
| `ContentLikedEvent` 带 `is_automated` | `record ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt)`；`new ContentLikedEvent(` 生产代码只有 `LikeService` 一处，测试 20 处 | 加第 5 个分量 `boolean automated`，**同时保留 4 参构造器**（默认 `false`），测试与 ③ 新监听不用改（L-2） |
| 四个监听：成长值 / 里程碑 / @ 候选 / 通知 | `GrowthActivityListener.onLiked`（`@Async @TransactionalEventListener`）、`MilestoneAutoCompleteListener.onContentLiked`、`MentionCandidateListener.onContentLiked`、`ContentNotifyListener.onContentLiked`（同步 `@TransactionalEventListener`，`sendInteraction` 合并键 `LIKE:POST:{postId}`） | 只有成长值监听跳过自动赞（L-4）；其余三个不动 |
| 需排除的 7 处查询 | 见 §1.3 | **两处共用了展示用的 `countByPostIdIn`**，不能直接给它加过滤（L-5） |
| 日报 `SQL_AUTO_COMMENTS` / `fieldNoChange("自动评论成功数")` | `admin/dailyreport/DailyReportQuery.java:52`、`DailyReportCard.java:25,45`；`点赞数` SQL 已 `JOIN users u ON u.id = l.user_id AND <REAL>`（只数真人点的赞） | 照加一行（L-13）；`点赞数` 已满足 FR-218 第 8 项，补回归测试钉住 |
| AI 查数手册 `ai-query-agent/work/CLAUDE.md` §5.2 | 第 64 行：「统计用户行为时默认排除：官方运营账号（81）、种子虚拟作者（user id 1–20）、测试账号」 | 加一行：虚拟号**给出**的赞也排除，或直接按 `content_likes.is_automated = false` 过滤（L-14） |
| 配置 profile「local / dev 默认关」 | 只有 `application.yml`（`spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}`）、`application-dev.yml`、`application-prod.yml`；**没有 local profile**；stag 跑的是 prod profile（+stag 开关） | base 默认 `true`，`application-dev.yml` 覆盖为 `${AUTO_LIKE_ENABLED:false}`（L-12）。本机不设 profile 时就是 dev → 关 |
| 「Under review」= 被举报待判 | `PostStatus.UNDER_REVIEW` 同时表示「发帖人工审核挂起」与「举报 P0 预处置」（`ContentPost.pendingReview` / `applyReportHold`）；「rejected」= 软删 `deleted_at` | 判定统一用 `status`：只有 `PUBLISHED` 进检查；派发复查时 `UNDER_REVIEW` → `SKIPPED_REVIEW` 留在集合，`AUTHOR_DEACTIVATED` / 软删 / 非 PUBLIC → `POST_GONE` |
| 多实例安全「用库锁」 | 现有两种写法：①事务内 `pg_try_advisory_xact_lock(hashtext('…'))`（`TopicHeatJob`、`LogisticsStallJob`、`CouponExpiryReminderJob`）；②Redis `SET NX PX` + uuid 比对释放（`ShopPaymentReconcileJob`、`BiteshipJobLock`，用于「整轮不能包在一个事务里」的任务）。全仓库没有 `SKIP LOCKED`、没有 ShedLock | 计划阶段用①（一个事务做完）；派发阶段用②（每条赞一个事务）+ 行级条件更新认领 + 唯一键兜底（L-6） |

### 1.2 PRD B 触点核对

| PRD 说法 | 代码现状 | 结论 / 处置 |
|---|---|---|
| 点赞逻辑全在 `like_button.dart` | `petgo_app/lib/features/content/presentation/like_button.dart`：`ConsumerStatefulWidget`，状态 `_liked / _count / _inFlight` 在 State 里；`didUpdateWidget` 在不飞行时跟随外部值；埋点 `post_like_tapped`；`onSettled` 回调 | 状态外提到按帖子编号的 provider（F-1） |
| 挂载点 | `shared/widgets/masonry_card.dart:367`（Feed 卡）、`features/content/presentation/comment_composer.dart:460`（详情页底栏，`onSettled` → `syncFeedCounts`） | 两处都改读 provider |
| `syncFeedCounts` 只写「发现」「关注」 | `feed_controller.dart:235`，`ref.exists` 判断后调 `feedProvider` / `followingFeedProvider` 的 `syncCounts` | 保留；顶置 / 话题页靠 provider 覆盖（F-2） |
| `pinnedSlotProvider` / `topicPinnedProvider` / `topicPostsProvider` | 前两个是 `FutureProvider`（只读快照，无法原地改）；`topicPostsProvider` 是 `AsyncNotifierProvider.autoDispose.family<…, TopicPostsKey>`（无法从外面枚举所有 family 实例） | 不逐个「写回」，改为**按帖覆盖 + 快照版本号**（F-2），与关注态 AD-7 同一套做法 |
| 快照版本号先例 | `FeedItem.followEpoch` / `ContentDetail.followEpoch` / `PinnedSlot.withFollowEpoch`，在 `feed_controller.dart:99/146/267`、`following_feed_controller.dart:19/42`、`topic_providers.dart:25/109/142`、`detail_providers.dart:18` 发请求前打戳；`followStateProvider` 覆盖只在 `epoch > snapshotEpoch` 时生效 | 点赞照抄：新增独立的 `likeEpoch`（与 `pet_follow_state.dart` 自有计数器同理，不复用关注的计数器） |
| 大图页 220ms 判定 `lightbox_gestures.dart` 的 `kLightboxSingleTapDelay` | 常量在 `shared/media/lightbox_gestures.dart:89`；判定逻辑在 `image_lightbox.dart:398 _handleTapUp`（Timer），**没有距离判断、没有三连击忽略** | 抽一个纯逻辑判定器（F-4），只复用常量；大图页不动 |
| 叠加层测试 `feed_overlay_test.dart` | **不存在**。AD-8 Rule 6「三个角位」的断言在 `test/content/content_tag_test.dart:86`（另 `pinned_slot_test.dart` 测角标位置） | 新增中央动效层的结构断言写进 `content_tag_test.dart` 的同一组（或新建 `test/shared/feed_like_burst_test.dart`），UI 稿与 PRD 里的「更新 feed_overlay_test.dart」按此执行（F-7） |
| 颜色 | `colors.dart:45 coral = 0xFFF0425A`、`:46 gold = 0xFFF6A609`、`:41 dashedViolet = 0xFFC2B0EC`、`:183 likeHeart = coral` | 新增 `likeHeartLight = 0xFFFF8FA0`、`likeHeartDeep = 0xFFD42A55`（F-6） |
| 游客 `requireLogin` + `entrySource` | `auth_guard.dart:20` `requireLogin(ref, context, {onAllowed, entrySource = 'other'})`；现爱心按钮调用时**没传** entrySource（=`other`） | 双击传 `'double_tap_like'`；爱心按钮保持现状（不改它的 entrySource） |
| 卡片结构 | `MasonryCard` 是 `StatelessWidget`；图片区与正文区是两个 `GestureDetector(onTap: onTap, onLongPress: onLongPress)`（`:263`、`:298`）；根 `Semantics(button: onTap != null)` | 只改图片区那个 GestureDetector（F-4） |
| 推广卡 | `features/content/presentation/promo_pinned_card.dart` 不用 `MasonryCard` | 天然不受影响，加一条回归断言 |
| 埋点护栏 | `test/analytics/v112_events_test.dart`、`v116_events_test.dart`、`v116_feed_rank_attribution_test.dart`、`like_button_reason_test.dart` 都盯 `post_like_tapped` | 只加属性 `trigger`，事件名不动 |

### 1.3 PRD A 的 7 处排除点（逐条核对）

| FR-218 项 | 代码位置 | 现状 | 改法 |
|---|---|---|---|
| 2 首页互动分 | `content/rank/FeedRecommendationService.java:344` `likes.countByPostIdIn(ids)` → `RankCandidate.likes` | 与 Feed 卡**展示**的赞数（`FeedService.java:240`）是同一个方法 | 换成新方法 `countOrganicByPostIdIn`（L-5）；`percentile95` 冷启动兜底用的是 `RankCandidate.likes`，随之自动排除 |
| 2 P95 重算 | `content/rank/FeedRankInteractionStats.java:50` 同上 | 同上 | 换成 `countOrganicByPostIdIn` |
| 3 话题热度 | `topic/service/TopicHeatJob.java:72` `JOIN content_likes l ON l.post_id = v.post_id AND l.created_at >= :since` | 无过滤 | JOIN 条件加 `AND NOT l.is_automated` |
| 4 话题 Populer | `content/repository/ContentPostRepository.java:831` `LATERAL (SELECT COUNT(*) … FROM content_likes l WHERE l.post_id = p.id)` | 无过滤 | 加 `AND NOT l.is_automated` |
| 5 推荐关注 | `social/repository/UserFollowRepository.java:127` `recent_likes` CTE | 无过滤 | 加 `AND NOT l.is_automated` |
| 6 宠物推荐 | `content/repository/ContentPostRepository.java:105` `liked` CTE（`findRecommendablePets`） | 无过滤 | 加 `AND NOT l.is_automated`。⚠️ ③ 首日推荐页复用此查询，排除对它同样生效（符合 PRD「只影响排序」） |
| 7 后台按点赞时间 | `content/repository/ContentLikeRepository.java:84` `countInWindow`（唯一调用方 `AdminContentManageService.browseByLikeWindow:375`） | 无过滤 | JPQL 直接加 `AND l.automated = false`（只有后台用） |
| 8 日报点赞数 | `DailyReportQuery.java:56` | 已只数真人点的赞 | 不改，加回归测试 |
| 9 AI 查数 | `ai-query-agent/work/CLAUDE.md:64` | 只写了排除作者 1–20 | 加一行（L-14） |

**明确不改（PRD「故意保留」）**：Feed 卡 / 详情赞数（`FeedService.java:240`、`ContentDetailService`）、点赞人列表 `findLikersPage`、主页「Suka」`sumLikesOnPublicPostsByAuthor` / `sumLikesOnOwnPostsByAuthor`、后台内容列表默认模式赞数 `AdminContentManageService.likeCounts:585`、后台帖子详情赞数。

**PRD 没提、本主题不改**：后台数据看板 `admin/dashboard/metrics/MetricSql.java:54` 的互动源 CTE —— REAL 口径已要求「互动者是真人」，自动赞天然不进；ALL 口径按定义就是全量，保持不变（列入待拍板 Q-5）。

### 1.4 其他漂移

- README 会话表写的分支是 `feat/1.5.0-auto-like` + `feat/1.5.0-feed-double-tap-like`，PRD B §6 / UI 稿也写 `feat/1.5.0-feed-double-tap-like`；**本次规划按主会话指令合成一个分支 `feat/1.5.0-likes`**。README 行由主会话统一回写（本主题不改 README）。
- PRD B 和 UI 稿说「从 `origin/main` 拉分支」，本主题按 V1.5.0 规则从 `dev_1.5.0`（地基之后）切。
- PRD A 的代码基线是 `main 4fed8dd6`；`dev_1.5.0` 上 autocomment / LikeService / 各查询与之一致，未见影响本主题的改动。

---

## 2. PRD A 自动点赞：后端决定

### L-1 `content_likes.is_automated`

- 迁移 `V20261115_0500__add_content_likes_is_automated.sql`：`ALTER TABLE content_likes ADD COLUMN is_automated BOOLEAN NOT NULL DEFAULT false;` + `COMMENT ON COLUMN`。PG 11+ 带常量默认值的加列不重写表，存量行即为 `false`。不建索引（所有排除都是在已按 `post_id` / `created_at` 命中的行上再加一个布尔条件）。
- 实体 `ContentLike` 加 `@Column(name = "is_automated", nullable = false) private boolean automated;`，`of(postId, userId)` 保持 `false`，JPQL 属性名 `automated`。
- **只有本任务写 `true`**；App 点赞、后台暖贴、运营手工点赞一律 `false`（A-AC46）。

### L-2 `ContentLikedEvent` 透传

```java
public record ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt, boolean automated) {
    /** 真人点赞（既有调用点与测试沿用）。 */
    public ContentLikedEvent(long postId, long likerId, long authorId, Instant createdAt) {
        this(postId, likerId, authorId, createdAt, false);
    }
}
```

保留 4 参构造器 = 既有 20 处测试、③ 新增的新手任务监听都不用改。**任何监听都不得因为 `automated` 而跳过，除成长值（L-4）**；③ 的新手任务 ②「获得第一个赞」照常计入（A-AC42，决策 2026-11-12）。

### L-3 `LikeService.likeAutomated`

```java
/** 自动点赞专用（FR-215 / FR-217）：真新建才发事件；返回是否新建与新计数。不做可见性校验（调用方已复查）。 */
@Transactional
public AutomatedLikeResult likeAutomated(long postId, long virtualUserId, long authorId)
```

- `ContentLikeRepository.insertAutomatedIfAbsent(postId, userId)`：原生 `INSERT INTO content_likes (post_id, user_id, created_at, is_automated) VALUES (:postId, :userId, now(), true) ON CONFLICT ON CONSTRAINT uq_content_likes_post_user DO NOTHING`，`@Modifying` 返回行数。
- 行数 1 → `events.publishEvent(new ContentLikedEvent(postId, virtualUserId, authorId, now, true))`；行数 0 → 不发事件。返回 `record AutomatedLikeResult(boolean created, long likeCount)`。
- 事件仍是进程内事件，各监听 `AFTER_COMMIT` 才跑 —— 外层派发事务回滚时通知 / 推送 / 里程碑都不会发生（A-AC35）。
- 既有 `like(postId, userId)` 一行不动（App 接口行为不变）。

### L-4 成长值跳过

`GrowthActivityListener.onLiked` 开头：`if (e.automated()) return;`。理由：PRD 只要求不发 `LIKE_RECEIVED`；`LIKE_GIVEN` 给虚拟号本来就不入账，整段跳过等价且最简单，也省掉一次 `existsByPostIdAndUserId` 复查（A-AC30）。里程碑 `FIRST_LIKE` 自带的成长值走里程碑通道，不受影响（A-AC31）。

### L-5 排除口径：新方法而不是改旧方法

`ContentLikeRepository` 新增：

```java
/** 排序 / 推荐 / 热度用的赞数：不含自动赞（V1.5.0 FR-218）。⚠️ 展示赞数仍用 countByPostIdIn。 */
@Query("SELECT l.postId AS postId, COUNT(l) AS likeCount FROM ContentLike l "
     + "WHERE l.postId IN :postIds AND l.automated = false GROUP BY l.postId")
List<PostLikeCount> countOrganicByPostIdIn(@Param("postIds") Collection<Long> postIds);
```

- `countByPostIdIn` 保留给展示（Feed 卡、后台列表默认模式），**禁止加过滤**；两个方法的 javadoc 互相指向，写明「排序用 organic，展示用全量」。
- 原生 SQL 一律写 `AND NOT l.is_automated`；JPQL 写 `l.automated = false`。
- **今后任何对 `content_likes` 做排序 / 推荐 / 统计的新查询都必须带这个过滤**（写进 CROSS-STORY，见 §5）。

### L-6 定时与多实例防重

```
@Scheduled(cron = ${petgo.auto-like.morning-cron}, zone = "Asia/Jakarta")  → plan(MORNING)
@Scheduled(cron = ${petgo.auto-like.evening-cron}, zone = "Asia/Jakarta")  → plan(EVENING)
@Scheduled(fixedDelayString = ${petgo.auto-like.dispatch-interval-ms:60000}, initialDelayString = 30000) → dispatch()
```

- **计划阶段**（检查 + 关闭 + 排期）整个包在一个 `TransactionTemplate`（`PROPAGATION_REQUIRES_NEW`）里，第一句 `SELECT pg_try_advisory_xact_lock(hashtext('auto_like_plan'))`，拿不到 → `log.warn("auto like {} plan skipped: another instance is planning")` 返回（照 `TopicHeatJob`）。启动时的数量级：上线首轮约 1,400 帖检查、约 820 条排期，单事务秒级。
- **同一槽只排一次**：`auto_like_runs` 唯一键 `(run_date, slot)`，`INSERT … ON CONFLICT DO NOTHING` 返回 0 → 本槽已被别的实例排过 → 跳过（A-AC2）。`auto_like_logs` 唯一键 `(post_id, run_date, slot)` 再兜一层：同一帖同一槽最多一行 = 最多一个赞（FR-212 第 2 条）。
- **上一轮未结束则跳过（A-AC1）**：排期前查 `auto_like_runs` 是否存在 `status = 'ACTIVE' AND window_end > now()`；存在 → 记日志跳过、不写 run 行。默认 cron 下早晚间隔 6h / 18h > 3h 窗口，只有 stag 把 cron 调密时才会触发。
- **派发阶段**每条赞一个事务（要调 `LikeService`、要逐条成败），不能用 xact 锁 → 用 Redis 锁 `lock:auto-like-dispatch`（`SET NX PX`，TTL = 5 分钟，uuid 比对释放；照 `BiteshipJobLock` 在本包写一个私有小工具，不跨包引用 `shop.logistics` 的包私有类）。拿不到锁 → 本 tick 跳过（不记 warn，避免每分钟刷日志，记 debug）。
- **行级认领**：每条在自己的事务里先 `UPDATE auto_like_logs SET updated_at = now() WHERE id = :id AND status = 'PLANNED'`，返回 0 → 别人处理过，跳过；返回 1 → 该行行锁持有到事务结束。即使 Redis 锁失效（TTL 过期 / Redis 抖动），两个实例也不会对同一行各点一次。
- 与自动评论**完全独立**：不同 Bean、不同锁、不同表；一边抛错不影响另一边（A-AC3）。调度线程池现为 4（`application.yml`），新增 3 个调度方法不会饿死其他扫描器。

### L-7 表设计（三张表）

**`auto_like_targets`**（`V20261115_0510__init_auto_like_targets.sql`，Story 2.1）—— 目标集 + 「检查过」记录，一帖一行：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PK` | |
| `post_id` | `BIGINT NOT NULL UNIQUE REFERENCES content_posts(id) ON DELETE CASCADE` | 唯一 = 每帖只检查一次（FR-213 第 3 条、A-AC13） |
| `author_id` | `BIGINT NOT NULL` | 不设外键（同 `auto_comment_logs.virtual_user_id`，不挡注销路径）；按作者轮流用 |
| `post_created_at` | `TIMESTAMPTZ NOT NULL` | 冗余帖子发帖时间：排序（最老优先）与 7 天关闭不用回表 |
| `checked_at` | `TIMESTAMPTZ NOT NULL` | 检查时刻（= 本轮开始时刻） |
| `like_count_at_check` | `INTEGER NOT NULL` | 检查时赞数（成效对比 §7 SM-1 用） |
| `joined` | `BOOLEAN NOT NULL` | 是否进集合 |
| `left_at` | `TIMESTAMPTZ` | 离开时刻；未进集合的行 = `checked_at` |
| `leave_reason` | `VARCHAR(24)` | 见下表 CHECK |
| `failed_attempts` | `INTEGER NOT NULL DEFAULT 0` | 只计「没被叫停的轮次」里的 FAILED（L-10） |
| `last_liked_at` | `TIMESTAMPTZ` | 最近一次 LIKED 时刻，按作者轮流用（FR-214） |
| `created_at` / `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |

约束：`ck_auto_like_targets_leave_reason CHECK (leave_reason IN ('AT_TARGET_ON_CHECK','HOLDOUT','REACHED_TARGET','MAX_AGE','POST_GONE','NO_IDENTITY','TOO_MANY_FAILURES'))`；`ck_auto_like_targets_left CHECK ((left_at IS NULL) = (leave_reason IS NULL))`；`ck_auto_like_targets_not_joined CHECK (joined OR left_at IS NOT NULL)`。
索引：`ix_auto_like_targets_open ON (author_id, post_created_at, post_id) WHERE joined AND left_at IS NULL`。

**`auto_like_runs`** + **`auto_like_logs`**（`V20261115_0520__init_auto_like_runs_and_logs.sql`，Story 2.2）：

`auto_like_runs`（一槽一行）：`id`、`run_date DATE NOT NULL`（WIB）、`slot VARCHAR(8) NOT NULL CHECK IN ('MORNING','EVENING')`、`started_at TIMESTAMPTZ NOT NULL`、`window_end TIMESTAMPTZ NOT NULL`、`status VARCHAR(8) NOT NULL CHECK IN ('ACTIVE','DONE','STOPPED')`、`stop_reason VARCHAR(32)`（`NO_POOL` / `CONSECUTIVE_FAILURES`）、`launch BOOLEAN NOT NULL`、`checked_count` / `joined_count` / `planned_count` / `consecutive_failures INTEGER NOT NULL DEFAULT 0`、`created_at`、`updated_at`；`UNIQUE (run_date, slot)`。

`auto_like_logs`（一条计划的赞一行）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | identity PK | |
| `run_id` | `BIGINT NOT NULL REFERENCES auto_like_runs(id)` | |
| `post_id` | `BIGINT NOT NULL REFERENCES content_posts(id) ON DELETE CASCADE` | **不设**指向 `content_likes` 的外键（帖子物理删会删赞，PRD 附录 A） |
| `run_date` / `slot` | 同 runs | `UNIQUE (post_id, run_date, slot)` |
| `planned_at` | `TIMESTAMPTZ NOT NULL` | 本轮开始 + 随机 `[0, spread)` |
| `window_end` | `TIMESTAMPTZ NOT NULL` | 本轮开始 + spread（冗余，派发不用 JOIN） |
| `status` | `VARCHAR(16) NOT NULL` | `CHECK IN ('PLANNED','MISSED','LIKED','REACHED_TARGET','SKIPPED_REVIEW','POST_GONE','NO_IDENTITY','FAILED')` |
| `virtual_user_id` | `BIGINT` | 不设外键 |
| `like_count_before` | `INTEGER` | 尝试前赞数 |
| `error_code` | `VARCHAR(64)` | 只存异常类名 / 固定码，不存内容 |
| `attempted_at` | `TIMESTAMPTZ` | 尝试时刻；日报按它的 WIB 日期数 LIKED |
| `created_at` / `updated_at` | | |

索引：`ix_auto_like_logs_due ON (planned_at) WHERE status = 'PLANNED'`；`ix_auto_like_logs_liked_at ON (attempted_at) WHERE status = 'LIKED'`（日报）。
所有新表、新列都写 `COMMENT ON`（仓库惯例，见 `V86__add_column_comments.sql`）。日志只存 id 与计数，不存帖子正文（FR-219 末条）。

### L-8 一轮计划的顺序（Story 2.1 + 2.2）

在 L-6 的计划事务里按序：

1. 开关关 → 只记 `auto like {slot} skipped: disabled`，不进事务（A-AC40）。
2. 拿 advisory 锁；查「上一轮未结束」；写 `auto_like_runs` 行（ON CONFLICT 跳过）。`now` = 本轮开始时刻，后续所有年龄都按它算（「Post age = created_at 到本轮开始」）。
3. `launch = (SELECT NOT EXISTS (SELECT 1 FROM auto_like_targets))` —— **必须在第 4、5 步之前**判。
4. 关闭：未关闭且 `post_created_at <= now − max-post-age` → `MAX_AGE`（FR-215 七天限）；未关闭且 `failed_attempts >= max-failures` → `TOO_MANY_FAILURES`（L-10）。
5. 检查（Story 2.1 `AutoLikeCheckStep`）：原生查询取候选 `(post_id, author_id, created_at, like_count)`：
   ```sql
   SELECT p.id, p.author_id, p.created_at,
          (SELECT COUNT(*) FROM content_likes l WHERE l.post_id = p.id) AS like_count   -- 展示口径：真人 + 虚拟，含自动赞
   FROM content_posts p JOIN users u ON u.id = p.author_id
   WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
     AND u.account_type = 'REAL' AND u.role = 'USER' AND u.deleted_at IS NULL
     AND u.status = 'ACTIVE'                -- 待拍板 Q-8：比自动评论多这一条
     AND p.created_at <= :minCut            -- now − check-window-min（≥ 36h）
     AND p.created_at >  :maxCut            -- 普通轮 now − check-window-max（< 60h）；首轮 now − max-post-age（< 7d）
     AND NOT EXISTS (SELECT 1 FROM auto_like_targets t WHERE t.post_id = p.id)
   ORDER BY p.created_at, p.id
   ```
   逐条：作者是对照组 → `joined=false, leave_reason=HOLDOUT`；`like_count >= like-target` → `joined=false, AT_TARGET_ON_CHECK`；否则 `joined=true`。`INSERT … ON CONFLICT (post_id) DO NOTHING`。纯文字帖照样进（A-AC8）。审核中的帖不满足 `PUBLISHED` → 本轮不检查、不写行，下一轮还在窗口内且已恢复就会被检查（A-AC12）。
6. 号池（L-9）为空 → `runs.status = STOPPED, stop_reason = NO_POOL`，**检查结果照常提交**、不排期、不关任何帖（A-AC26）。
7. 排期（Story 2.2）：取全部 `joined AND left_at IS NULL` 的行，按作者分组；每组按 `last_liked_at NULLS FIRST, post_created_at, post_id` 取前 `max-likes-per-author-per-run` 条（A-AC43）；全部入选行按 `post_created_at, post_id`（最老优先，A1）写 `PLANNED` 日志，`planned_at = now + random(0, spread)`（毫秒精度，`ThreadLocalRandom`，可注入随机源便于测试）。**不设每轮上限**（A-AC14）。
8. 回写 `runs` 的 `checked_count / joined_count / planned_count`，提交。

### L-9 派发一条（Story 2.3）

每个 tick（拿到 Redis 锁后）：

1. `UPDATE auto_like_logs SET status='MISSED', updated_at=now() WHERE status='PLANNED' AND window_end <= now()`（A-AC45，不计失败）；`UPDATE auto_like_runs SET status='DONE' WHERE status='ACTIVE' AND window_end <= now()`。
2. 开关关 → 到此为止（已排的会在窗口结束后被第 1 步收成 MISSED）。
3. 取到期行：`status='PLANNED' AND planned_at <= now() AND window_end > now()` 且所属 run `ACTIVE`，按 `planned_at` 升序，每 tick 最多 `dispatch-batch`（默认 200）条。
4. 号池：`users.findByAccountTypeOrderByIdDesc(VIRTUAL)` 过滤 `isEnabled && deletedAt == null && status == ACTIVE`（每 tick 查一次）。为空 → 所有 ACTIVE run 置 `STOPPED / NO_POOL`，本 tick 结束，**不改任何帖子**（A-AC26）。
5. 逐条一个事务（`TransactionTemplate` REQUIRES_NEW）：
   1. 条件认领（L-6）；0 行 → 跳过。
   2. 复查帖子：不存在 / 软删 / 非 PUBLIC / `AUTHOR_DEACTIVATED` / 作者不再是 REAL+USER+未注销+ACTIVE（Q-8）→ 日志 `POST_GONE`，目标集 `left (POST_GONE)`；`UNDER_REVIEW` → `SKIPPED_REVIEW`，留在集合（A-AC19）。
   3. `like_count_before = countByPostId`；`>= like-target` → `REACHED_TARGET` + `left (REACHED_TARGET)`（A-AC18）。
   4. 选号：`pool` 去掉 `ContentLikeRepository.findLikerIdsAmong(postId, poolIds)`（新方法：`SELECT l.userId FROM ContentLike l WHERE l.postId = :postId AND l.userId IN :ids`）→ `VirtualIdentityPicker.pick(filtered, authorId, species)`；物种取 `ContentSpeciesResolver.resolve(post).species()`，空则 `GENERAL`（同自动评论 `speciesOf`）。`null` → `NO_IDENTITY` + `left (NO_IDENTITY)`（A-AC25）。
   5. `likeService.likeAutomated(postId, virtualId, authorId)`：`created` → `LIKED`，写 `virtual_user_id / like_count_before / attempted_at`，`targets.last_liked_at = now`；新计数 `>= like-target` → `left (REACHED_TARGET)`（A-AC16 / AC17）。`!created`（竞态下该号已赞）→ 见待拍板 Q-2（默认：日志 `FAILED`、`error_code='ALREADY_LIKED'`，**不**计入帖子失败数、**不**计入连续失败数）。
   6. 非 FAILED 的结果把 `runs.consecutive_failures` 清零。
6. 单条抛异常 → 该事务回滚（赞、日志、目标集一起回滚，A-AC35）；再开一个小事务：日志 `FAILED`（`error_code` = 异常类名截 64）、`targets.failed_attempts + 1`、`runs.consecutive_failures + 1`；到 `stop-after-consecutive-failures` → 叫停（L-10）。继续下一条（A-AC36）。小事务也失败（如库挂了）→ 只 `log.warn` 类名，本 tick 结束，行仍是 `PLANNED`，下个 tick 重试。

### L-10 失败计数与叫停

- 「第 3 次 FAILED 离开」在**下一轮计划的第 4 步**执行（不是失败当下），这样被叫停的轮次可以先把自己的失败数撤回，满足「叫停轮的失败不计入任何帖子的 3 次」（A-AC37 / AC38）。效果等价：失败第 3 次后该帖不会再被排期。
- 叫停（连续失败到 10 条，A10）在同一个小事务里：`runs.status = STOPPED, stop_reason = CONSECUTIVE_FAILURES`；`UPDATE auto_like_targets SET failed_attempts = failed_attempts − 1 WHERE post_id IN (本轮 FAILED 且 error_code <> 'ALREADY_LIKED' 的 post_id) AND failed_attempts > 0`；本轮剩余 `PLANNED` → `MISSED`。没有任何帖子因叫停离开集合。

### L-11 对照组

`AutoLikeHoldout.isHoldout(long userId)`：`SHA-256(salt + ":" + userId)` 取前 8 字节按无符号长整数 `mod 100 < holdout-percent`。盐 `holdout-salt` 默认固定串 `tt-auto-like-holdout-v1`，**各环境相同、写死在 `application.yml` 默认值里**，不进 env（改盐 = 换一批对照组）。不落名单（A-AC48）。只作用于「作者」：对照组作者的帖照样检查、记 `HOLDOUT`，从不进集合（A-AC47 / AC49）。

### L-12 配置 `AutoLikeProperties`（前缀 `petgo.auto-like`）

| key | env | 默认 | 备注 |
|---|---|---|---|
| `enabled` | `AUTO_LIKE_ENABLED` | base `true`；`application-dev.yml` 覆盖 `false` | stag / 生产跑 prod profile → 开；本机默认 dev → 关 |
| `morning-cron` / `evening-cron` | `AUTO_LIKE_MORNING_CRON` / `AUTO_LIKE_EVENING_CRON` | `0 30 10 * * *` / `0 30 16 * * *` | Asia/Jakarta |
| `check-window-min` / `check-window-max` | `AUTO_LIKE_CHECK_WINDOW_MIN` / `_MAX` | `36h` / `60h` | `Duration` |
| `max-post-age` | `AUTO_LIKE_MAX_POST_AGE` | `7d` | 同时是首轮上限 |
| `like-target` | `AUTO_LIKE_TARGET` | `5` | |
| `likes-per-run` | — | `1` | 见待拍板 Q-1：启动校验只接受 1 |
| `max-likes-per-author-per-run` | `AUTO_LIKE_MAX_PER_AUTHOR` | `1` | |
| `spread-window` | `AUTO_LIKE_SPREAD_WINDOW` | `3h` | |
| `holdout-percent` | `AUTO_LIKE_HOLDOUT_PERCENT` | `10` | 0–100 |
| `holdout-salt` | — | `tt-auto-like-holdout-v1` | 不进 env |
| `max-failures` | `AUTO_LIKE_MAX_FAILURES` | `3` | |
| `stop-after-consecutive-failures` | `AUTO_LIKE_STOP_AFTER_FAILURES` | `10` | |
| `dispatch-interval-ms` | `AUTO_LIKE_DISPATCH_INTERVAL_MS` | `60000` | 实现参数，非 PRD |
| `dispatch-batch` | — | `200` | 实现参数 |

改任何值需改配置 + 重启（FR-222，A-AC41）。`application.yml` 里照自动评论那段写注释（含「stag 想立刻验证把 cron 临时设成 `0 */10 * * * *`」）。**`.env.example` 不加**（全部有默认值；需要关时才设 `AUTO_LIKE_ENABLED=false`）。

### L-13 日报行

`DailyReportQuery.SQL_AUTO_LIKES = "SELECT count(*) FROM auto_like_logs l WHERE l.status = 'LIKED' AND " + day("l.attempted_at") + " = ?"`；`DailyReport.Metrics`（`autoComments` 所在的嵌套记录）加 `long autoLikes`（紧跟 `autoComments`）；`DailyReportCard` 在「自动评论成功数」下一行 `fieldNoChange("自动点赞成功数", count(c.autoLikes()))`，脚注加「自动点赞 = 虚拟账号自动点的赞，不计入点赞数与活跃率，不做环比」（A-AC39）。

### L-14 AI 查数手册

`ai-query-agent/work/CLAUDE.md` §5.2 第 64 行后加：「虚拟账号（user id 1–20）**给出**的点赞同样排除；按点赞统计时优先直接过滤 `content_likes.is_automated = false`（自动点赞，V1.5.0 起）。以后新增虚拟账号要同步加进这条名单。」（FR-218 第 9 项）

---

## 3. PRD B Feed 双击点赞：App 决定

### F-1 点赞状态 provider（FR-210）

新文件 `features/content/domain/post_like_state.dart`：

```dart
/// 某帖在本会话里被点过赞之后的覆盖值（V1.5.0 FR-210）。写入时打全局点赞版本号。
class PostLikeOverride {
  final bool liked; final int count; final bool inFlight; final int epoch;
}

/// 全局点赞版本号：放在 notifier 外，登录态重建不归零（同 follow_state.dart 的做法）。
int _likeEpochCounter = 0;

class PostLikeStore extends Notifier<Map<int, PostLikeOverride>> {
  int get currentEpoch => _likeEpochCounter;
  /// 有效值：覆盖比快照新（override.epoch > snapshotEpoch）或仍在飞行 → 用覆盖；否则用快照。
  ({bool liked, int count, bool inFlight}) effective(int postId, {required bool snapLiked, required int snapCount, required int snapEpoch});
  /// 唯一的点赞动作入口（爱心按钮与双击都调它），返回本次是否真的发起了请求。
  Future<LikeToggleOutcome> toggle(int postId, {required bool snapLiked, required int snapCount, required int snapEpoch, required bool wantLiked, required LikeTrackingContext tracking});
}
final postLikeStoreProvider = NotifierProvider<PostLikeStore, Map<int, PostLikeOverride>>(PostLikeStore.new);
```

- `toggle` 内含 `LikeButton._toggle` 的全部现有逻辑：飞行中直接返回 `ignoredInFlight`；先写乐观覆盖（`inFlight: true`，新 epoch）→ 调 `likeRepositoryProvider` → 成功写后端真值覆盖（`inFlight: false`，新 epoch）并调 `syncFeedCounts`（F-2）→ 失败写回点击前的值（`inFlight: false`）。
- **登录门控与埋点留在 Widget 层**（需要 `BuildContext` 弹登录窗、需要区分 `trigger`）：`requireLogin` 通过后再调 `toggle`；埋点在 `toggle` 真正发起请求前由 store 统一报（参数 `tracking` 带 `source / feedTab / rankMode / reason / trigger`），保证「已赞再双击 / 飞行中再双击不报」只有一处判断。
- 失败时回到「点击前的有效值」，而不是删除覆盖：删除会让另一张卡退回更旧的快照。
- 换账号：在 `app.dart` 的 `resetUserScopedCaches` 里 `ref.invalidate(postLikeStoreProvider)`（已赞态是账号维度；见既有记忆「换账号缓存重置」——登出不失效）。

### F-2 「写回所有列表」= 覆盖 + 版本号（关键取舍）

PRD 要求「点赞结果落定后写回所有持有这条帖子的列表」。实现上：

1. 每个持有帖子快照的数据源在**发请求前**记 `likeEpoch`（与 `followEpoch` 并列、同一处打戳）：`FeedItem.likeEpoch`、`ContentDetail.likeEpoch`、`PinnedSlot.withLikeEpoch`（或把 `withFollowEpoch` 扩成同时打两个戳，由 story 定）。打戳点即 §1.2 列出的 `followEpoch` 打戳点中涉及帖子的那些：`feed_controller.dart`（首屏、自己的新帖插入、翻页、顶置）、`following_feed_controller.dart`（首屏、翻页）、`topic_providers.dart`（顶置、首屏、翻页）、`detail_providers.dart`。
2. `LikeButton` 与双击都读 `effective(...)`：同一帖在顶置卡 / 普通卡 / 关注页签 / 话题页 / 详情页读到同一个覆盖值（B-AC11、AC14 ~ AC17）。
3. 下拉刷新拿到的新快照 epoch ≥ 覆盖 epoch → 以后端为准（B-AC13）；飞行中的覆盖不论 epoch 都优先（「不能覆盖进行中的乐观更新」）。
4. 仍保留 `syncFeedCounts` 写回 `feedProvider` / `followingFeedProvider`（在 store 落定时调用，替代原来只有详情页调用）：保证这两条流里 `FeedItem` 本身也是新值（分享卡、`pendingFeedFocus` 等别处读 item 时不旧）。`syncFeedCounts` 的注释「顶置卡不在此回写」改为说明「顶置与话题页靠 `postLikeStoreProvider` 覆盖同步」。
5. 为什么不逐个写 `pinnedSlotProvider` / `topicPinnedProvider` / `topicPostsProvider`：前两个是 `FutureProvider` 只读快照；第三个是 autoDispose family，外部拿不到全部 key；关注态（AD-7）已在同样的问题上用了覆盖 + 版本号，同一套做法、同一类测试（见 `follow_state.dart`）。列入待拍板 Q-6（默认按本条）。

### F-3 `LikeButton` 改造

- 改成读 `ref.watch(postLikeStoreProvider.select(...))` 的薄组件，外观、热区、`likersSource`、`expandCountHitArea`、`onSettled`（保留，向后兼容）全部不变。新增可选 `snapshotEpoch`（默认 0 = 任何覆盖都比快照新）；`MasonryCard` 传 `item.likeEpoch`，详情底栏传 `detail.likeEpoch`。
- 现有三份测试 `like_button_test.dart` / `like_button_split_test.dart` / `like_button_reason_test.dart` **不改预期**（B-AC12）；只允许因构造方式变化补 `ProviderScope` 的 override。
- 顶部注释「不得重写」改为：「状态已于 V1.5.0 FR-210 外提到 `postLikeStoreProvider`；本组件只负责外观、门控与触发。改点赞逻辑去改 store。」
- 点爱心点赞（未赞 → 已赞）时：图标 1.0 → 1.2 → 1.0（约 200ms）弹跳 + `HapticFeedback.lightImpact()`；取消不弹不震；减少动态效果时不弹、照常震（FR-211）。
- 爱心按钮埋点带 `trigger: 'button'`。

### F-4 图片区单击 / 双击判定器

新文件 `shared/media/feed_tap_arbiter.dart`（纯 Dart，无 Widget 依赖，可单测）：

```dart
/// Feed 图片区单击 / 双击仲裁（V1.5.0 FR-209）。窗口复用 kLightboxSingleTapDelay（220ms），不另定时长。
class FeedTapArbiter {
  FeedTapArbiter({required this.onSingleTap, required this.onDoubleTap, Duration window = kLightboxSingleTapDelay, double slop = kDoubleTapSlop});
  void tapUp(Offset globalPosition);   // 第一下：起 220ms 计时；窗口内第二下且距离 ≤ slop → onDoubleTap，并进入 220ms「吞第三下」期
  void cancel();                       // 开始滚动 / 横滑 / 长按 / 卡片销毁时调用：撤销待执行的单击
  void dispose();
}
```

- 规则：第一下抬起起计时；220ms 内第二下、两点距离 ≤ `kDoubleTapSlop`（Flutter 常量 100 逻辑像素）→ 双击；距离超出 → 第一下立刻按单击执行、第二下作为新的第一下；220ms 内没第二下 → 单击（进详情）；双击后 220ms 内的点击一律忽略（B-AC5）。
- 时间源可注入（测试用 `fakeAsync`）。
- `MasonryCard` 图片区那个 `GestureDetector` 去掉 `onTap`，改用 `onTapDown/onTapUp/onTapCancel` 喂判定器；`onLongPress` 不变（长按开始时 `cancel()`，B-AC10）；正文区 `GestureDetector` 一行不动（B-AC7）。
- 需要状态 → `MasonryCard` 图片区抽成一个小的 `StatefulWidget`（如 `_FeedImageTapArea`），卡片本身仍是 `StatelessWidget`。
- 滚动：Flutter 手势竞技场里，纵向拖动开始时 `TapGestureRecognizer` 会收到 `onTapCancel`，判定器 `cancel()` → 不进详情、不算双击（B-AC9）。多图 `PageView` 横向拖同理；第二下有轻微位移但没超过拖动阈值时，Tap 识别器仍胜出 → 不翻页（B-AC4）。
- 读屏：`MediaQuery.accessibleNavigationOf(context) == true` 时图片区退回原来的 `onTap`（不挂判定器），语义仍是「打开详情」。
- 推广卡 `promo_pinned_card.dart` 不用 `MasonryCard`，不受影响（加一条回归断言）。

### F-5 双击的动作表（FR-209 规则表 → 代码）

| 情况 | 判定 | 动作 |
|---|---|---|
| 游客 | `requireLogin(ref, context, onAllowed: () {}, entrySource: 'double_tap_like')` 返回 false | 只弹登录窗；不播动效、不震、不报点赞事件（B-AC6） |
| 有效值已赞 | `effective.liked == true` | 只播动效；不发请求、不震、不报（B-AC2） |
| 有效值飞行中 | `effective.inFlight` | 只播动效；不发第二次请求、不报 |
| 未赞 | 其余 | `store.toggle(wantLiked: true, trigger: double_tap)` + 播动效 + `HapticFeedback.lightImpact()` + 通知爱心按钮弹跳（B-AC1） |
| 请求失败 | store 回滚 | 动效照常播完，不提示（B-AC8） |

「通知爱心按钮弹跳」：store 覆盖里加一个只增不减的 `bounceTick`（或单独的 `likeBounceProvider.family<int>`），`LikeButton` 监听到变化就播一次弹跳；不在两个 Widget 之间传 `GlobalKey`。

### F-6 颜色常量

`core/theme/colors.dart` 在 `likeHeart` 旁加 `likeHeartLight = Color(0xFFFF8FA0)`、`likeHeartDeep = Color(0xFFD42A55)`（该文件规定业务代码禁止写死 hex）。小圆点用现有 `coral / gold / dashedViolet`。

### F-7 中央动效层（AD-8 Rule 6 增补）

- 位置：`FeedImage` 的 `Stack` 最上层（三个角位之上），整层 `IgnorePointer`（B-AC18「不拦截点击」）。由 `FeedImage` 新增可选参数 `burstController`（或 `likeBurst: Listenable`）驱动；`FeedImage` 不知道点赞逻辑。
- 定位：心中心 = `Offset(可见图片区宽 × 0.6, 可见图片区高 × 0.5)`，可见图片区就是 `AspectRatio` 之后的这块（已含比例上下限与 `maxImageHeight` 截高），所以四种比例天然一致（B-AC19）。
- **AD-8 Rule 6 补一句**（在 `_bmad-output/planning-artifacts/architecture.md` 的 AD-8 Rule 6 处，由 Story 4.2 加）：「图片区除三个角位外，另有一个**临时中央动效层**（双击点赞红心，V1.5.0 FR-211）：`IgnorePointer`、不常驻、不占角位、不改图片区结构；其他功能不得复用此层放常驻内容。」
- 结构断言写进 `test/content/content_tag_test.dart` 的 AD-8 Rule 6 组（或新建 `test/shared/feed_like_burst_test.dart`）：动效层存在时三个角位的位置不变、动效层是 `IgnorePointer`、动效结束后不在树里。

### F-8 动效实现

- `shared/widgets/feed_like_burst.dart`：一个 `AnimationController(duration: 670ms)` + `TweenSequence`；小心用 `Interval(70/670, 1.0)` 错开；圆点 80ms 起 450ms 内散开淡出。数值全部照 UI 稿 D1（story 4.2 内联一份）。
- 心形：`CustomPainter` 画 UI 稿 `HEART_D` path（viewBox 0 0 24 24）`M12 20.6C6.6 16.9 3 13.6 3 9.4 3 6.5 5.2 4.4 7.9 4.4c1.7 0 3.2.8 4.1 2.2.9-1.4 2.4-2.2 4.1-2.2 2.7 0 4.9 2.1 4.9 5 0 4.2-3.6 7.5-9 11.2z`，渐变 `likeHeartLight → likeHeart → likeHeartDeep`，同色 1.4 描边磨圆尖角，左上白色椭圆高光 65% + 小白点 55%，同色柔光（0/6/14，32%）。圆点画法参照 `features/gift/presentation/gift_send_animation.dart` 的粒子 `CustomPainter`。
- 重播：播放中再双击 → `controller.forward(from: 0.27)`（「放大到 1.12」那一刻），屏上始终一组心（B-AC20）。
- 减少动态效果：`MediaQuery.disableAnimationsOf(context)` → 原尺寸只淡入淡出约 400ms，无缩放、无上飘、无圆点；按钮不弹跳；照常震（B-AC21）。
- 离开屏幕：动效层的 State `dispose` / `deactivate` 时 `controller.stop()` 并清状态（B-AC22）；每张卡各自一个 controller，互不干扰（B-AC23）。不用 Rive。
- 动效出错不影响点赞（构建 / 绘制失败只影响这一层；点赞已在 store 里完成）。

### F-9 埋点

`post_like_tapped` 只加属性 `trigger`：`button` / `double_tap`；其余属性与值不变（双击时 `source = feed`，`feed_tab / rank_mode / reason` 同该卡片的爱心按钮）。只在真正发起点赞请求时报。游客双击转化走现有 `signup_succeeded`，`entrySource = double_tap_like`。护栏测试照常通过（只加属性）。后端 `content_likes` 不加来源字段（PRD B §4）——**注意与 PRD A 的 `is_automated` 不冲突**：那是「是否自动」，不是「从哪点的」。

---

## 4. 迁移清单（本主题号段 `V20261115_0500` ~ `V20261115_0559`，已预分配）

| 版本 | 文件名 | Story | 内容 |
|---|---|---|---|
| `V20261115_0500` | `V20261115_0500__add_content_likes_is_automated.sql` | 1.1 | `content_likes.is_automated` |
| `V20261115_0510` | `V20261115_0510__init_auto_like_targets.sql` | 2.1 | `auto_like_targets` |
| `V20261115_0520` | `V20261115_0520__init_auto_like_runs_and_logs.sql` | 2.2 | `auto_like_runs`、`auto_like_logs` |
| `V20261115_0530` ~ `_0559` | 预留 | — | 实现中确需新迁移时从这里顺取，回写 sprint-status 头注释 |

App 侧（PRD B）无迁移、无后端改动。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。

---

## 5. 护栏

- **G-1** 不引 MQ / ShedLock / Quartz / 缓存层：调度用原生 `@Scheduled`；锁只用既有的 PG advisory xact 锁与 Redis `SET NX`。
- **G-2** `ddl-auto=validate`：实体字段与迁移逐列对齐；`slot` / `status` 等 `VARCHAR` 不用长度 1（Hibernate CHAR(1) 坑）；计数列用 `INTEGER` 配 `int`（不用 `SMALLINT`）。
- **G-3** 日志：只记 id、计数、异常类名；不记帖子正文、昵称。
- **G-4** 不碰地基：不改 `ck_notifications_type`、不加通知类型；自动赞走现成 `CONTENT_LIKED` 合并与 10 分钟推送防抖（FR-217，代码已核：`notify:likepush:{postId}` 600 秒）。
- **G-5** 跨线口径（建议主会话收进 `CROSS-STORY-DECISIONS.md` 的 V1.5.0 段）：
  1. 「对 `content_likes` 做排序 / 推荐 / 热度 / 运营统计的新查询必须排除 `is_automated`；展示赞数不排除。」
  2. 「`ContentLikedEvent.automated = true` 的事件除成长值外所有监听照常处理；③ 新手任务 ② 不得排除。」
- **G-6** PRD B 合并顺序：`feat/1.5.0-likes` 在 ③ 的 `feat/1.5.0-onboarding` 合进 `dev_1.5.0` 之后再合；合并若需要与 ③ 推荐页卡片对齐，**从 `dev_1.5.0` 切对齐分支 `feat/1.5.0-likes-align` 把本分支合进去处理**，不反向合并 dev 进功能分支（Story 4.3）。PRD A（Epic 1–2）可以先行单独合（PRD A 要求尽快上线、只改后端）——见待拍板 Q-7。

---

## 6. 验证层级总览

| 层 | 内容 | 环境 |
|---|---|---|
| L0 | `./mvnw -B clean package -DskipTests` + 指定纯单元测试（holdout 哈希、选帖分组、排期随机窗口、派发状态机用 mock）；`flutter analyze` + `flutter test`（判定器、store、LikeButton 旧测试、动效结构断言、埋点护栏） | 云端可跑 |
| L1 | 迁移 + `validate` 启动；检查 / 排期 / 派发 / 日报 / 排除查询的集成测试（`ApiIntegrationTest`，真 PG + Redis）；两实例并发排期 | 本机 Docker postgres + redis |
| L2 | stag 打开开关、cron 调密实跑一轮：通知中心 / 推送 / 日报行；App 双击手感、动效、震动、读屏、中低端安卓掉帧（B-AC23 / AC24）、还原度 ≥ 95% | stag + 真机（Android + iOS） |
