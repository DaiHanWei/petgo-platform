---
title: "V1.3.4 follow 主题 · 架构增量"
status: draft
created: 2026-10-12
inputs: PRD-v1.3.4-follow.md · ui-v1.3.4-follow.html · 决策日志-follow.md
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: feat/1.3.4-follow-notify @ 28a7d30d（= main，含 V1.3.3）
---

# V1.3.4 follow 主题 · 架构增量（AD-1 ~ AD-16）

> 只写本版本新增或改变的技术决定，均已对照代码。**权威顺序**：`决策日志-follow.md` > `PRD-v1.3.4-follow.md` > 本文件 > UI 稿。

## 0. 现状要点（2026-10-12 核实）

- **关注完全不存在**；App 有源码扫描测试禁止他人主页出现 `follow`（`test/user_profile/public_profile_posts_test.dart:282-306`），`mini_profile_test` 断言无「Follow」文字——须随本版本改写。
- 他人主页 `GET /api/v1/users/{userId}/profile`（`PublicProfileController:71`，路径为**数字 id**，游客可读）返回 `PublicProfileResponse`（无 userId / token）；`postCount` / `likeCount` 实时计算。拉黑：我拉黑对方 → 403 `blocked-user`；对方拉黑我 → 计数归零、帖子空（伪装）。已注销 → 200 + `deactivated()`；**封号（`status = DEACTIVATED`）目前显示正常主页**。
- 拉黑：`user_hide_relations`（`BLOCK` / `REPORT`），写入 `UserHideRelationService.block`（:40）/ `unblock`（:53）/ `hideByReport`；读端口 `UserHideRelationReader`（`isBlocked` / `isHidden` / `hiddenEitherWay`）。
- 点赞通知：`LikeService.like` 新赞且非自赞时发 `ContentLikedEvent` → `ContentNotifyListener.onContentLiked` 每次一行一推送。评论：`ContentCommentedEvent` 在评论**审核通过**（`approveComment`）后才发；回复另通知一级评论作者。无去重、无聚合、无触发人列。
- 场所：创建者列 `places.marked_by_user_id`（实体 `createdBy`）；场所评论审核通过（`PlaceCommentService.approve` :173）**不发任何事件**。
- `notifications` 表无 actor / 聚合列；`NotificationItem`（type / title / body / deepLink… / read / createdAt）；App 通知中心按 type 显示本地文案，从不读 title / body；分组只有 Today / Earlier；未读圆点在最右。
- 限流 `RedisRateLimiter.check(key, limit, window)`（固定窗口）；**无按天限流先例**（按天上限都用 DB 计数）。
- App 无共享的点赞 / 关注状态（`LikeButton` 自持状态）；无数字缩写工具。

## 1. 数据模型

**AD-1 · 关注关系表与计数冗余。**
- `user_follows(follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMPTZ, PRIMARY KEY (follower_id, followee_id))`，两列 FK → `users(id) ON DELETE CASCADE`，CHECK `follower_id <> followee_id`；索引 `(followee_id, created_at DESC)`（粉丝列表）与 PK 覆盖关注列表（`(follower_id, created_at DESC)` 另建）。
- `users` 加 `following_count INT NOT NULL DEFAULT 0`、`follower_count INT NOT NULL DEFAULT 0`（PRD 影响面建议冗余）。关注 / 取关 / 拉黑取关在**同一事务**内对两人计数做条件 `UPDATE … ± 1`（`GREATEST(…, 0)` 兜底）。
- 账号注销：`AccountDeletionService` 在删用户前先显式删除其全部关注行并同步扣减对端计数（CASCADE 只删行、不扣计数）。封号不删关系、不改计数。

**AD-2 · 用户对外 token（D-2）。**
- `users` 加 `public_token VARCHAR(32) NOT NULL UNIQUE`，迁移内用 SQL 随机 base62 回填存量，新用户注册时生成（同 `PlaceTokenGenerator` 写法）。
- 下发位置：`PublicProfileResponse.userToken`、`UserProfileResponse`（/me）`.userToken`、`FeedItemResponse.authorToken`、`ContentDetailResponse.authorToken`、关注列表 / 推荐行、`USER_FOLLOWED` 通知的 `actorToken`。
- 现有数字 id 路由与字段**不动**；关注列表行同时带 `userId`（跳主页）与 `userToken`（关注操作）。

**AD-3 · 通知表扩展（FR-141）。**
- `notifications` 加：`aggregate_key VARCHAR(64) NULL`（`LIKE:POST:{id}` / `COMMENT:PLACE:{id}`）、`comment_id BIGINT NULL`（评论 / 回复类指向具体评论）、`variant VARCHAR(16) NULL`（`REPLY` 区分「回复了你的评论」，D 文案见 §5）、`updated_at TIMESTAMPTZ`。
- 新表 `notification_actors(notification_id BIGINT FK CASCADE, actor_user_id BIGINT FK → users ON DELETE CASCADE, acted_at TIMESTAMPTZ, PRIMARY KEY (notification_id, actor_user_id))`——每条互动通知的触发人集合（评论 / 新粉丝恰一行；点赞 / 场所评论聚合多行）。不在 `notifications` 存昵称（PRD：读取时现查）。
- 「未读即合并」约束：部分唯一索引 `uq_notifications_unread_aggregate ON notifications (recipient_user_id, aggregate_key) WHERE is_read = false AND aggregate_key IS NOT NULL`。
- **CHECK 重建**（决策日志 D-1）：`ck_notifications_type` DROP + ADD 全集 **34 值** = `V20260915_1614` 的 31 值 + `OPS_ANNOUNCEMENT` + `USER_FOLLOWED` + `PLACE_COMMENTED`；开工当天对全部远端分支并集核查并在迁移注释记录。`NotificationType` 枚举加 `USER_FOLLOWED`、`PLACE_COMMENTED`（`OPS_ANNOUNCEMENT` 由 app 主题加枚举；本主题只在 CHECK 中预留，见 §8 风险）。

## 2. 关注接口（FR-140）

**AD-4 · 接口与规则。**
- `PUT /api/v1/users/{token}/follow`（关注，幂等）/ `DELETE /api/v1/users/{token}/follow`（取关，幂等），需登录 `USER`；返回 `{ iFollow, followsMe, followerCount }`（主页即时 ±1 用）。
- 拒绝：关注自己 → 422；我拉黑了对方（`isBlocked(me, target)`）→ 409 `follow-blocked`；对方已注销 / 封号 / 不存在 → 404 `not-found`。**对方拉黑了我照常成功**（D-4）。`REPORT` 来源不影响。
- 限流：`rl:social:follow:{userId}` 30 次 / 分钟（`RedisRateLimiter`）；每日新增上限 500 用 DB 计数（`user_follows` 中本人 `created_at` 落在当前 WIB 自然日的行数，与仓库其他每日上限同口径），超出 429 `rate-limited`（App 提示「Terlalu sering…」）。
- `USER_FOLLOWED` 通知：新建关注行后发给被关注者，**24 小时内同一 (actor, recipient) 已有该类型通知则不再发**（查 `notifications` + `notification_actors`）；接收方拉黑 / 隐藏了 actor → 不发（`suppressed` 同口径）。取关、拉黑取关**一律不发任何通知**（PRD AC9）。
- 列表：`GET /api/v1/users/{token}/following?cursor=` / `…/followers?cursor=`（keyset：`created_at DESC, id`，每页 20）；行 `{ userId, userToken, nickname, avatarUrl, deleted, iFollow, followsMe, blockedByMe, self }`（关系均相对**当前查看者**；游客全 false）。被删除用户由 CASCADE 消失；封号用户照常列出（点进主页显示不存在）。
- 推荐：`GET /api/v1/me/follow-suggestions`：`role = USER`、`deleted_at IS NULL`、`enabled`、`status = ACTIVE`、至少一条 PUBLIC 已发布帖子；排除自己、已关注、我对其有任何隐藏关系（BLOCK / REPORT）；按「近 30 天在其 PUBLIC 帖子上收到的赞」倒序，最多 30 个；虚拟号与真人都包括。

**AD-5 · 拉黑联动。** `UserHideRelationService.block` 同事务删除 `holder → target` 的关注行并扣计数；`target → holder` 保留；不发通知。解除拉黑不恢复。

**AD-6 · 主页 / 我的页 / Feed / 详情接口扩字段。**
- `PublicProfileResponse` 加 `userToken`、`followingCount`、`followerCount`、`iFollow`、`followsMe`。被拉黑方看拉黑者：帖子 / 获赞 / 内容继续伪装，**关注 / 粉丝数显示真实值**（PRD）。**封号用户按已注销处理返回 `deactivated()`**（PRD AC11）。
- `/me` 加 `userToken`、`postCount`（全部帖子，D-5）、`likeCount`（全部帖子获赞）、`followingCount`、`followerCount`；邮箱字段保留在接口（App 不再展示，PRD）。
- `FeedItemResponse` / `ContentDetailResponse` 加 `authorToken`、`authorFollowState { iFollow, followsMe }`：每页一次批量查询（`user_follows` 按 author 集合），游客恒 false。

## 3. App（FR-140）

**AD-7 · 全局关注状态。** `followStateProvider`（`Notifier<Map<int, FollowOverride>>`，按 userId，登录态切换时重建）：记录本次会话里改过的关系 `{ iFollow, followsMe, pinnedSurface }`。规则：
- 各处按钮读「服务端初值 + 覆盖」。
- 在「非常驻 Following」的位置点关注：该位置（`pinnedSurface` = 本按钮实例 key）保留 Following 可立即取关；**同一用户的其他所有位置立即隐藏**（PRD）；刷新数据后覆盖清除。
- 常驻位置（他人主页、我的 Following 列表）始终显示三态。
- 我的 Following 列表取关不移除行，刷新后消失。

**AD-8 · 页面。** 我的页、他人主页改「居中名片」（四格数据、整宽主按钮、宠物区列表化——本版本一只宠物、组件按列表做）；新增关注列表页（复用 `blocked_users_page` 行样式、骨架、错误态）；Feed 卡 / 详情头部加文字关注按钮、类型徽章移到时间右侧（影响首页所有卡片，回归长昵称、顶置角标、本人待审帖）；游客点关注走 `requireLogin`（登录后不自动关注）。
- 新增 `formatCompactCount(int, Locale)`（`shared/utils/`）：≥ 1.000 → K、≥ 1.000.000 → M，一位小数、去末尾 0，小数点随语言（id「,」/ en「.」）（D-3）。

## 4. 通知生成（FR-141）

**AD-9 · 写入路径。** `NotificationService` 新增 `sendInteraction(recipient, type, actorId, aggregateKey?, targetRef, commentId?, variant?)`：
- 有 `aggregateKey` 且存在**未读**同键行 → 同事务 `INSERT notification_actors … ON CONFLICT DO NOTHING`、`updated_at = created_at = now()`（移到最上），**不**再自增未读角标；否则新建一行 + actor 行 + 角标 +1。并发靠部分唯一索引 + 冲突重试。
- 点赞：`aggregate_key = LIKE:POST:{postId}`；取消点赞不回退（PRD）。
- 评论 / 回复：不聚合，`comment_id`、回复时 `variant = REPLY`。
- 新粉丝：不聚合。
- 场所评论（新）：`PlaceCommentService.approve` 发 `PlaceCommentApprovedEvent(placeId, commentId, authorId)` → 监听器在评论人 ≠ 场所创建者、创建者未注销、未隐藏评论人时 `sendInteraction(creator, PLACE_COMMENTED, aggregateKey = COMMENT:PLACE:{placeId}, targetRef = placeToken)`。
- 既有 `suppressed`（接收方或内容作者隐藏了 actor）全部沿用。

**AD-10 · 推送。** 推送文案在发送时用与列表**同一套模板**按接收方语言拼（§5）；评论原文进推送前已过审核（事件在审核通过后才发）。点赞去抖：Redis `SET notify:likepush:{postId} NX EX 600`，拿到才推（10 分钟内同帖最多一条，文案取当时聚合结果）；场所评论同点赞去抖：`notify:placecommentpush:{placeId}` 10 分钟内最多一条（决策日志 D-7）；评论 / 回复 / 关注每次推。虚拟号不推（现状）。

**AD-11 · 列表读取时拼文案。** `NotificationCenterService.list` 对 `CONTENT_LIKED` / `CONTENT_COMMENTED` / `USER_FOLLOWED` / `PLACE_COMMENTED` 且**有 actor 行**的条目：
- 一页一次批量查 actor（最新一位：按 `acted_at` 取第一个「未注销、未被我拉黑」的；N = 其余同样过滤后的人数）、评论当前状态（删除 → 「评论已删除」文案）、新粉丝的 `iFollow`。**禁止逐条查询**。
- DTO `NotificationItem` 加 `title` / `body`（已拼好的完整文案，覆盖原字段）、`actorName`（App 加粗用）、`actorCount`、`actorUserId`、`actorToken`、`actorIFollow`（新粉丝回关按钮用）。
- 无 actor 行（存量）→ 原样返回，App 显示旧通用文案（PRD）。

## 5. 文案模板

后端 i18n（`messages_{id,en}.properties`）新增模板，**推送与列表共用**：点赞单人 / 多人、评论、回复、场所评论单人 / 多人、新粉丝、评论已删除（文案以 UI 稿「新增印尼语文案」表为准，EN 同表）。App 端新增：分组五段、按钮三态、Tab、空态、推荐标题、三条 toast。

## 6. App 通知中心（FR-141）

**AD-12.** 五段分组按**手机本地时间**（Today / Yesterday / 近 7 天 / 近 30 天 / 更早）；非今天时间显示「8 Okt, 15:57」；未读圆点移到类型图标右上角；新增两种类型图标（`person_add` 紫 / `location_on` 打卡色）；有后端文案的类型读 `title` / `body` 并加粗 `actorName`；新粉丝行右侧回关按钮（读 AD-7 状态，点击不进主页）；举报已处理点击无动作；场所评论跳场所详情（`target_ref` = 场所 token）；`USER_FOLLOWED` 点击进对方主页（`actorUserId`）。`deep_link_routes` 补两种类型映射。

## 7. 埋点

**AD-13.** App：`user_followed` / `user_unfollowed {target_user_id, source, is_follow_back}`（D-6）、`follow_list_viewed {tab, is_self, is_empty}`、`suggested_follow_shown`、`notification_follow_back_tapped`；既有 `app_notification_item_tapped` 加 `actor_count`。事件前缀登记测试同步（`user_` / `follow_` / `suggested_` 前缀）。

## 8. 风险与跨主题约束

**AD-14 · 与 app 主题的通知类型。** 两分支 CHECK 都列 34 值（D-1）；`NotificationType` 枚举里的 `OPS_ANNOUNCEMENT` 由 app 主题加——本主题**不加**该枚举值（避免两边枚举冲突），CHECK 预留不影响运行。两分支合并时以「枚举 ⊆ CHECK」为验收。

**AD-15 · 已知注释 / 测试推翻。** `public_profile_page.dart:116-119`「明确不做关注」、`FeedService.java:35`「无关注」、`public_profile_posts_test` 的 `follow` 禁词、`mini_profile_test` 无 Follow 断言，随对应 story 更新。

**AD-16 · 性能。** 主页 / Feed 关注态批量查询；列表 keyset 分页；通知列表每页一次批量查 actor。

## 9. 迁移清单（时间戳命名）

| 迁移 | 内容 |
|---|---|
| `…__create_user_follows_and_counters.sql` | 关注表 + `users` 两计数列 |
| `…__users_public_token.sql` | `public_token` + 回填 + 唯一 |
| `…__notifications_actor_aggregate.sql` | 通知新列 + `notification_actors` + 部分唯一索引 |
| `…__rebuild_ck_notifications_type_follow_place.sql` | CHECK 全集 34 值 |
