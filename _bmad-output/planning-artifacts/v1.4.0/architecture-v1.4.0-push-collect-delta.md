---
title: "V1.4.0 push-collect 主题 · 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
completedAt: '2026-10-18'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/1-4-0推送开关与收藏prd.md
  - _bmad-output/planning-artifacts/v1.4.0/推送开关与收藏/ui-1-4-0-push-collect-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                               # ④ 节
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md  # ① 契约（收藏成长值）
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-gift-delta.md            # ③ 并行模式
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md                      # 「2026-10-18」节
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-10-18'
version: v1.4.0
theme: push-collect
status: complete
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.0 @ ba55e1c5（地基 0.1 + ①②③ 规划文档；①②③ 代码在各自功能分支开发中）
---

# V1.4.0 push-collect 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照代码核实。**权威顺序**：`decision-log.md`（④ 节）> `1-4-0推送开关与收藏prd.md` > 本文件 > UI 稿。
> 🔴 **与 ①②③ 并行开发（P-1，沿用 ③ 的做法）**：PRD §6.1 原定 ④ 在 ①②③ 合入后 rebase 再做 UI。改为：本分支从 `dev_1.4.0` 切出、与 ①②③ 并行；②「我的」页 Hewanku 区、③ 详情页礼物区在本分支**不存在**，本线只改自己负责的区域（Postinganku 小节、操作栏、设置页、场所详情），①②③ 均合入后在对齐分支合并页面并做跨线端到端验收（§6）。

## 0. 项目上下文分析（2026-10-18 核实 dev_1.4.0 @ ba55e1c5）

### 0.1 范围
FR-150 推送分类开关（9 类、只管系统推送、服务端按账号存、「订单与账号」不可关、通知设置页 + 设置页入口）· FR-151 收藏（帖子 + 场所、详情页按钮、「我的」页 Postinganku / Disimpan Tab、不可见条目保留可移除、作者本人可见收藏数、被收藏发事件给 ①）。埋点 8 个。UI 稿 App 26 屏。无后台。**复杂度：中**（不涉钱；推送闸门要覆盖所有推送出口）。

### 0.2 已具备（地基 0.1）
`NotificationType` 39 值（含 ①②③ 新增 5 值 `GROWTH_LEVEL_UP` / `PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` / `PET_GIFT_RECEIVED` / `GIFT_UNDELIVERED_REFUND`，约束 `V20261018_1400`）· `content.event.ContentCollectedEvent(long postId, long collectorId, long authorId)` / `ContentUncollectedEvent(同字段)`（无 `createdAt`；全仓库尚无发布方与监听方）。**因此分类映射可在本分支一次写全，不必等 ①②③ 合入。**

### 0.3 现状要点（PRD §2 之外的核实发现）
1. **推送出口**：`NotificationPusher` 三个方法 `pushToUser`（@Async，:27）/ `pushToVet`（:34）/ `pushToUserNow`（同步，:40）。直接调用方只有两处：
   - `NotificationService`：`write(…, push=true)` :143（`send` :104 走这里）、`pushInteraction` :263（`sendInteraction` 事务提交后执行，:224-233）、`sendToVet` :336、`sendWithCopy` :391。`sendWithoutPush` :120 不推（`MILESTONE_SM_NODE` 由 `MilestoneNotifyListener`:64 调它）。
   - `OpsNotificationDispatcher.push` :289 → `pushToUserNow`（绕过 `NotificationService`，落库走 `writeOpsAnnouncements` :273；推送成功经 `tick` 计数）。
   - **另一处绕过**：问诊的 `ConsultCloseService` / `ConsultInterruptService` / `ConsultAcceptService` 经 `LiveTencentImClient.sendSystemMessage`（:176，自带 `OfflinePushInfo`，Ext `VET_REPLY`）推送。属「订单与账号」不可关 → **不加闸门**（P-3）。
2. **`PATCH /api/v1/me`**（`MeController`:45，`UpdateMeRequest` 字段为 null 即不动）；设置类子资源参照 `TailsonalityOwnerTypeController`（GET + PUT + `rateLimiter.check`）。**SecurityConfig 没有 `/api/v1/me/**` 通配**，新端点必须显式 `hasRole("USER")`（否则兽医 token 以同号用户名义访问，`anyRequest().authenticated()` :349）。
3. **注销**：`AccountDeletionService.execute`（:143-224）各模块显式删除；users 行只匿名化（`AuthAccountDeletionService`:18）。`content_likes` **不删**（匿名化即可）——收藏是「仅自己可见」的私人数据，**必须显式删除**（PRD），照 `OnboardingMarkDeletionService` 写单表删除服务，接在 `authDeletion` 之前（`AccountDeletionServiceTest` 用 `inOrder` 校验）。
4. **点赞范式**：`LikeController` `POST|DELETE /api/v1/content-posts/{id}/like` → `LikeResponse(liked, likeCount)`；`LikeService.like` 先 `requireVisible`（未删 + PUBLISHED）、`exists` 再 `save`、`DataIntegrityViolationException` 吞成幂等、作者≠本人才发事件；`unlike` 直接 delete。实时 `COUNT(*)`。
5. **内容可见性**：`PostStatus` 只有 `PUBLISHED` / `UNDER_REVIEW` / `AUTHOR_DEACTIVATED`；删除与下架都是软删 `deleted_at`；`ContentVisibility { PUBLIC, PRIVATE }`。详情 `ContentDetailService.requireVisiblePost`（:76-101）：未删且（PUBLISHED 或作者本人看 UNDER_REVIEW）、浏览者举报过该帖 → 404、`hideRelations.isHidden(viewer, author)`（**单向**，拉黑或举报作者都算）→ 404；不拦 PRIVATE。批量双向工具 `UserHideRelationReader.hiddenEitherWay(userId, others)`（:75）。
6. **场所**：`places.status ∈ ACTIVE / DELISTED / MERGED`（+ `merged_into_id`）+ 软删 `deleted_at`；`PlaceRepository.resolveForView`（:140）ACTIVE 原样、MERGED 跳保留场所一层、其余 empty。详情 `GET /api/v1/places/{token}` 游客可访问；`PlaceListItemResponse` **无地址**，地址只在 `PlaceDetailResponse.addressText`。
7. **分页**：我的发布 `GET /api/v1/me/posts` 用 `content.service.FeedCursor`；通用 `shared.paging.KeysetCursor`（通知中心、点赞人列表、PawCoin 在用）。
8. **推荐**：`FeedRecommendationService` / `FeedRankInteractionStats` / `FeedRankScorer` 只读 `content_likes` 与 `comments`；`PetRecommendationService` 只 JOIN `content_likes`。
9. **App**：设置页 `_toggleRow(notificationCenterTitle, _notif, _onNotifToggle)`（`settings_page.dart`:169）+ `_refreshNotifStatus`（:79，含 `PushPermissionChangeReporter.record` 与 `syncRegistration`）；`openPushSettings()`（`push_permission_providers.dart`:45）；通知中心 `_iconStyle`（`notification_center_page.dart`:743）；`ContentDetail` 字段名是 `liked` / `likeCount` / `isAuthor`；点赞乐观更新在 `LikeButton`（`like_button.dart`:119）；`DetailBarMetrics`（`detail_bottom_bar.dart`:37）；场所 AppBar「⋯」`placeDetailMore`（`place_detail_page.dart`:179-194，`onReport` 非空才显示）；「我的」页 `_SectionTitle(meMyPostsTitle)` + `_MyPostsList`（`me_page.dart`:97-101 / :499）；**`showAppToast` 已有 `actionLabel` / `onAction`**（`app_toast.dart`:35）——PRD §6.5 ⑥「增加可选文字按钮」**无需改组件**；`showConfirmSheet(danger:)`；埋点命名守卫 `test/analytics/v112_events_test.dart`（前缀 + 词尾白名单）与 `v140_events_inventory_test.dart`；`resetUserScopedCaches`（`app.dart`:355）+ 守卫 `user_scoped_cache_registry_test.dart`。

### 0.4 横切关注点
推送闸门覆盖全部可关类型的出口（含绕过 `NotificationService` 的运营公告）· 映射全覆盖由编译期 + 单测双保险 · 收藏可见性读时判断（作者注销、举报预处置不发事件）· 收藏是私人数据（只本人可见、注销显式删）· 被收藏成长值只发事件 · 与 ①②③ 并行（对齐 story）。

### 0.5 拍板（2026-10-18，Dai 授权「按推荐执行」）
- **P-1 与 ①②③ 并行**：见文首；对齐 story 3.1 在 `feat/1.4.0-push-collect-align` 本地做。
- **P-2 收藏只限公开帖**：`visibility = PRIVATE` 的帖子不显示收藏按钮，接口 422 `collect-not-public`（PRD「公开的 Diary / Moment / Tips」）。
- **P-3 问诊 IM 系统消息推送不加闸门**：属「订单与账号」（`VET_REPLY` / `CONSULT_CLOSED`），本就不可关；`sendToVet` 发兽医账号，同样不加。
- **P-4 运营公告被开关拦下的人不推送、不计失败**：活动表没有「已推送数」列（`tick` 只是内存计数），后台「触达」显示的是写入通知中心的人数（`AdminOpsNotifyController`:185-196），被拦下的人照样计入 → 后台显示不变（Story 1.2 核实修正）。
- **P-5 「拉黑关系」按双向判断**：收藏列表里，我拉黑 / 举报作者，或作者拉黑我，该帖都显示不可见（PRD「我与作者有拉黑关系时」）；用 `hiddenEitherWay` 批量判断。
- **P-6 场所合并**：收藏指向的场所 `MERGED` → 列表与详情按保留场所显示（`resolveForView` 一层），不算不可见；收藏 / 取消一律落在**解析后的保留场所**上；同一用户因合并在列表里出现两条指向同一保留场所时，读时按保留场所去重（保留最近收藏的一条）。
- **P-7 取消收藏是否发 `ContentUncollectedEvent` 按「当时是否可见」判断**，不按入口区分：帖子此刻对收藏人可见（P-5 口径）→ 发；不可见 → 不发。满足 AC11c，且对任何入口一致。
- **P-8 埋点保留 PRD 原名**，在命名守卫里登记前缀 `collection_`、词尾 `_collected` / `_uncollected`（带注释），不改名、不放宽正则。

### 0.6 技术基线
沿用；不引新依赖、不加缓存层（闸门每次推送查一次库，走主键索引；运营公告按批预取）。后端包 `com.tailtopia.notify.preference`（推送设置）与 `com.tailtopia.collect.{domain,repository,service,web}`（收藏）；App `features/notify/`（通知设置页）与 `features/collect/`（收藏数据层、行组件、Disimpan Tab）。

## 1. 推送分类开关（FR-150）

**AD-1 · 分类枚举与映射 `notify.preference.PushCategory`。** 9 值：`LIKE` / `COMMENT` / `MENTION` / `FOLLOWER` / `GIFT` / `PET_REMINDER` / `ACHIEVEMENT` / `PROMO` / `ORDER_ACCOUNT`（`ORDER_ACCOUNT.lockable = false`，其余 true）。`static PushCategory of(NotificationType t)` 用 **Java 21 穷尽 `switch` 表达式、不写 `default`**——以后任何分支新增通知类型未指定分类即**编译失败**；另加单测遍历 `NotificationType.values()` 断言各属唯一分类（AC6，PRD 要求）。映射表照 PRD §3.1（`NEW_CONSULT_REQUEST`、占位类型 `CONSULT_CLOSED` / `CONTENT_REVIEW_APPROVED` / `IDENTITY_REQUIRE_MODIFY` 归 `ORDER_ACCOUNT`；`MILESTONE_SM_NODE` 归 `ACHIEVEMENT`）。

**AD-2 · 存储 `user_push_mutes`（迁移 `V20261018_1800`）。** 只存「关闭」的分类，无行 = 开启：`user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`、`category VARCHAR(24) NOT NULL CHECK (category IN (8 个可关分类))`、`created_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`PRIMARY KEY (user_id, category)`、另建 `(category, user_id)` 索引（运营公告批量预取）。`ORDER_ACCOUNT` 被 CHECK 排除，库里不可能出现。以后新增可关分类 = 重建该 CHECK（DROP + ADD 全集）。

**AD-3 · `PushPreferenceService`。** `boolean isEnabled(long userId, NotificationType type)`（`ORDER_ACCOUNT` 直接 true、不查库）· `Set<Long> mutedAmong(Collection<Long> userIds, PushCategory c)`（批量）· `PushSettingsView get(long userId)` · `PushSettingsView update(long userId, Map<PushCategory, Boolean>)`（关 = `INSERT … ON CONFLICT DO NOTHING`，开 = delete；幂等）· `void deleteByUserId(long userId)`（注销）。查询异常时 `isEnabled` **返回 true 并记 warn**（宁多勿漏，与去抖 Redis 异常同口径）。

**AD-4 · 接口。** `GET /api/v1/me/push-settings` → `{ categories: [{ code, enabled, lockable }] }`（固定 9 项、按 AD-1 顺序）；`PATCH /api/v1/me/push-settings`，body `{ categories: { "LIKE": false, … } }`（部分更新，照 `PATCH /api/v1/me` 语义）→ 返回完整设置。`ORDER_ACCOUNT: false` → 422 `push-category-locked`；未知 code → 400。SecurityConfig 显式 `hasRole("USER")`；`rateLimiter` 30 次 / 分钟 / 人。分组与说明文案归 App（不下发显示串）。

**AD-5 · 闸门位置（AC1–AC3）。** 「落库与加角标之后、调 pusher 之前」：
- `NotificationService.write` 的 `push` 分支（:143 前）、`sendWithCopy`（:391 前）、`pushInteraction`（开头，**先于** `acquirePushSlot`，避免关了推送的人占去抖键）：`if (!prefs.isEnabled(recipient, type)) return;`
- `OpsNotificationDispatcher`：每批 `writeOpsAnnouncements` 之后、逐条推送之前，`mutedAmong(批内收件人, PROMO)` 一次查询，命中者跳过 `push` 与 `tick`（P-4）。
- `sendToVet` 与 IM 系统消息不加（P-3）。闸门**不放** `NotificationPusher` / IM 客户端层（拿不到类型、且是异步）。
- 以后新增的推送出口必须调 `PushPreferenceService`（写进 CROSS-STORY-DECISIONS）。

**AD-6 · 注销。** `PushPreferenceService.deleteByUserId` 接入 `AccountDeletionService.execute`，在 `authDeletion` 之前（`ON DELETE CASCADE` 只是兜底，users 行不会被删）。

## 2. 收藏（FR-151）

**AD-7 · 表（迁移 `V20261018_1801`）。**
- `content_collections`：`id BIGSERIAL PK`、`user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`、`post_id BIGINT NOT NULL REFERENCES content_posts(id) ON DELETE CASCADE`、`created_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`UNIQUE (user_id, post_id)`；索引 `(user_id, created_at DESC, id DESC)`（列表）、`(post_id)`（计数）。
- `place_collections`：同形，`place_id BIGINT NOT NULL REFERENCES places(id) ON DELETE CASCADE`，`UNIQUE (user_id, place_id)`，索引 `(user_id, created_at DESC, id DESC)`、`(place_id)`。
- 不加冗余计数列（与点赞一致，实时 COUNT）。外键一律 CASCADE（存量测试 `deleteAll` 不被阻断）。

**AD-8 · 帖子收藏 `CollectService`。** 收藏 / 取消方法 `@Transactional`（① AFTER_COMMIT 监听依赖事务）；写入用 `INSERT … ON CONFLICT DO NOTHING` 按影响行数判断是否新增（不照抄 `LikeService` 的 save + 吞异常）。端点放新建的 `collect.web.CollectController`（`PlaceControllerNoEditEndpointTest` 禁止 `PlaceController` 带 `@DeleteMapping`）：`POST | DELETE /api/v1/content-posts/{id}/collect` → `CollectResponse(collected)`（需登录 USER；游客 401 由 App `requireLogin` 先拦）。
- 收藏：可见性同详情 `requireVisiblePost(postId, me)`（不可见 404）；`PRIVATE` → 422 `collect-not-public`（P-2）；`exists` 再 `save`、唯一键冲突吞成幂等；**只在真正新增时**发 `ContentCollectedEvent(postId, me, authorId)`——**收藏人 = 作者也发**（PRD：① 自行判断不入账；1.4.2 里程碑要用）。
- 取消：删除成功（影响 1 行）且帖子此刻对我可见（P-5 / P-7 口径）→ 发 `ContentUncollectedEvent`；不可见或本就没收藏 → 不发。幂等，**永不 404**（帖子不存在 / 已软删也返回 `{collected:false}`）。收藏时同样先过 `CollectVisibility`，保证 ① 收到的每条收藏事件都有机会被扣回。
- 事件在服务事务内 `publishEvent`，① 以 AFTER_COMMIT 监听（① 合入前无监听方，本线不做任何成长值逻辑）。**不发通知**（PRD：被收藏不通知作者）。

**AD-9 · 场所收藏。** `POST | DELETE /api/v1/places/{token}/collect` → `{collected}`。`resolveForView` 解析（MERGED → 保留场所）；不存在 / 下架 → 404（取消收藏时：按 token 直接找原场所行删除，不要求可见，便于移除不可见条目）。不发任何事件、不给成长值、不展示计数（PRD）。

**AD-10 · 详情字段。**
- `ContentDetailResponse` 新增 `boolean collected`（游客 false）与 `Long collectCount`——**仅 `isAuthor` 时下发**，值 = `COUNT(*) WHERE post_id = ? AND user_id <> author_id`（不计作者自己，AC10）；非作者为 `null`（JSON 省略或 null，App 不显示）。新增字段，旧版 App 忽略。
- `PlaceDetailResponse` 新增 `boolean collected`（按解析后的场所 id 判断）。

**AD-11 · 我的收藏列表。** `GET /api/v1/me/collections/posts?cursor=` 与 `GET /api/v1/me/collections/places?cursor=`（`hasRole("USER")`），按收藏行 `created_at DESC, id DESC`、每页 20、`KeysetCursor`。
- 帖子行：`{ postId, collectedAt, available, post? }`；`available = true` 时 `post = { type, excerpt, coverUrl, author: { nickname, avatarUrl } }`（帖子表无标题列，`excerpt` = 正文前 120 code point；`coverUrl` 与 Feed 同口径原样下发）；**不可见时 `post` 为 null**（不下发原标题、作者、封面——UI E 组口径）。不可见判定（读时、批量）：软删 / `status ≠ PUBLISHED`（`AUTHOR_DEACTIVATED`、`UNDER_REVIEW`；作者本人看自己 UNDER_REVIEW 的帖按详情口径算可见）/ 我举报过该帖 / `hiddenEitherWay(me, authors)` 命中（P-5）。内容恢复后自然恢复。
- 场所行：`{ placeToken, collectedAt, available, name, place? }`；`name` 不可见时也下发（UI：不可见场所保留名称置灰）；`available = true` 时 `place = { type, coverUrl, addressText }`（`coverUrl` 与场所列表同口径缩略图）；**不可见时不下发封面**（下架可能正因图片违规），App 用灰色占位块表达「图片置灰」。游标非法按首页处理（`KeysetCursor.decodeOrNull` 先例）。不可见 = `resolveForView` 为空；MERGED 按 P-6 解析去重。
- 批量组装：一页最多 20 条，帖子 / 作者 / 首图 / 拉黑关系 / 举报都按 id 集合一次查（2.1 建 `CollectVisibility.visiblePostIds` 与 `ReportService.reportedPostIds`，2.2 复用），禁止逐条 N+1。

**AD-12 · 推荐不接入（AC12）。** 推荐与排序代码不读两张收藏表；加一条结构测试断言 `content.rank` / `profile.recommend` 包源码不出现 `content_collections` / `ContentCollection`。

**AD-13 · 注销。** `CollectDeletionService.deleteByUserId`（删本人两张表的行）接入 `AccountDeletionService`，在 `authDeletion` 之前。**他人收藏的、指向注销作者帖子的行不删**（读时显示不可见，用户可自行移除）。

## 3. App

**AD-14 · 设置页与通知设置页（UI A / B 组）。** `settings_page.dart` AKUN 分组「Notifikasi」`_toggleRow` → `_navRow(value: …)`，右值按系统权限「Aktif」/ 红色「Notifikasi sistem nonaktif」+ 6px 红点（`_navRow` 需加可选 `valueColor` / `valueDot` 参数，其他行不受影响）；删除 `_onNotifToggle` / `_showOpenSettingsDialog`（首次申请权限由现有软引导负责），`_refreshNotifStatus` 保留。新页 `features/notify/presentation/push_settings_page.dart`，路由 `/me/settings/notifications`（落在 `/me` 受控前缀）；样式复用设置页同一套（把 `_sectionTitle` / `_card` / `_divider` 抽到同目录小组件或照抄常量，**不改设置页外观**）；分组 4 组、图标取 `_iconStyle` 同类；乐观更新 + 失败回弹 Toast；骨架屏 / 失败重试；`AppLifecycleState.resumed` 刷新权限卡。数据层 `pushSettingsProvider` 登记进 `resetUserScopedCaches`。

**AD-15 · 收藏按钮（UI C / D 组）。** 新组件 `CollectButton`（照 `LikeButton` 的乐观更新、`_inFlight` 防连点、`requireLogin` 门控、按下 0.96 缩放）；帖子详情 `_actions()` 在分享卡之后加入（间距照 `DetailBarMetrics` 现有公式），`detail.visibility == PRIVATE` 时不显示（P-2）；作者本人显示 `collectCount` + 11 号锁。场所详情 AppBar `actions` 在「⋯」左侧加按钮，显示条件同 `onReport`。收藏成功 Toast「Disimpan」+ `actionLabel: Lihat` → `go('/me?saved=post|place')`（`showAppToast` 现成参数，PRD §6.5 ⑥「加按钮」无需改组件）；收藏 / 取消成功后 `collectionsRevisionProvider(CollectTarget)` `bump()`，2.4 的列表订阅自动重拉；取消不提示；失败回弹 + 两种 Toast。

**AD-16 · 「我的」页 Disimpan（UI E 组）。** `me_page.dart` 把 `_SectionTitle(meMyPostsTitle)` 换成 Tab 行（Postinganku / Disimpan + 锁），Postinganku 内容不变；Disimpan 内分段胶囊（样式照 `FeedTabRow`）+ 隐私横条 + 列表（新组件 `CollectedPostRow` / `CollectedPlaceRow`，分页加载更多）；不可见条目点开 `showConfirmSheet(danger: true)` → 调 DELETE 接口（P-7 由后端判断是否发事件，App 无需区分）；空态 / 失败态只在 Tab 内容区。`/me?saved=post|place` 查询参数切到 Disimpan 对应子分类。他人主页（`public_profile_page.dart`）不改。两个列表 provider 登记 `resetUserScopedCaches`；收藏 / 取消成功后 invalidate 对应列表。

**AD-17 · 埋点（P-8）。** `push_settings_viewed{system_permission}` · `push_category_toggled{category, enabled}` · `content_collected` / `content_uncollected{post_id, content_type, source=detail}` · `place_collected` / `place_uncollected{place_id}`（**place_id 取场所 token**，不外露自增 id）· `collection_list_viewed{tab}` · `collection_item_opened{tab, is_unavailable}`。清单单独新建 `test/analytics/v140_push_collect_events_test.dart`（照 ③ 的 `v140_gift_events_test.dart`；`v140_events_inventory_test.dart` 是 Toko 旧清单不动，其 NFR-5 属性体检照常要绿）：1.3 建，2.3 / 2.4 追加。

## 4. 实现模式
- 推送闸门只经 `PushPreferenceService`；映射只在 `PushCategory.of`。
- 收藏事件只由 `CollectService` 发布；本线不写任何成长值代码、不监听这两个事件。
- 地基契约（两条 CHECK、5 个新通知类型、两个收藏事件字段）只引用不改。
- 对外标识：帖子沿用现有 `/content-posts/{id}` 与 App `/content/{id}`（存量约定，非本线新增）；场所用 token。
- 测试：AC1 ~ AC12 及 AC2a / 2b / 5a / 7a / 8a / 9a / 11a / 11b / 11c 逐条映射；映射全覆盖；注销清理在 `authDeletion` 之前（`inOrder`）。

## 5. 迁移
`V20261018_1800`（`user_push_mutes`）· `V20261018_1801`（`content_collections` / `place_collections`）。提交前 `bash scripts/ci/check-flyway-versions.sh origin/main`。

## 6. 与 ①②③ 的对齐（对齐分支 `feat/1.4.0-push-collect-align`，①②③ 均合入 `dev_1.4.0` 后本地做）
- App：「我的」页以 ② 定稿为底合并（Hewanku 区 + 本线 Postinganku / Disimpan Tab）；详情页以 ③ 定稿为底合并（礼物区 + 本线操作栏收藏按钮，重排间距按 PRD §6.2「点赞 · 分享卡 · 收藏」）；①③ 若给 `_iconStyle` 新增 `GROWTH_LEVEL_UP` / `PET_GIFT_RECEIVED` / `PET_SLOT_*` 图标，通知设置页「Hadiah」「Tonggak & level」图标与之对齐。
- 后端：确认 ①②③ 未新增 `NotificationType`（地基契约禁止）→ 映射编译与单测照常绿；①③ 新增的推送出口（如 ③ 的 `sendGiftReceived`）若不经本线已加闸门的三条路径，补 `isEnabled` 判断。
- 端到端：① 合入后验收 AC11 / 11a / 11b / 11c（被收藏 +2、每日 50 上限、自收藏无流水、取消扣回、移除不可见条目不扣）。

## 7. 校验
覆盖：AC1 / AC2b → AD-1 / AD-5 · AC2 / AC2a → AD-5 / P-4（CSAT 走 `send`，按 `PROMO` 拦；`TICKET_RESOLVED` 不可关）· AC3 → AD-1 / AD-4 · AC4 → AD-2 / AD-4 · AC5 / AC5a → AD-14 · AC6 → AD-1 · AC7 / AC7a / AC8 / AC8a → AD-8 / AD-9 / AD-15 / AD-16 · AC9 / AC9a → AD-11 / AD-16 · AC10 → AD-10 · AC11 ~ AC11c → AD-8 / P-7（成长值由 ① 入账，对齐分支验收）· AC12 → AD-12；注销 → AD-6 / AD-13；埋点 → AD-17。一致性：无新依赖；地基契约只引用。
