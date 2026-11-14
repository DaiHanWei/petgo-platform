---
title: "V1.5.0 会话 ⑥ 运营活动规则引擎（ops-activity）· 架构增量"
status: complete
completedAt: '2026-11-14'
version: v1.5.0
theme: ops-activity
branch: feat/1.5.0-ops-activity（从 dev_1.5.0 切出，切出时点 = gift-pack Story 4-1 已合进 dev_1.5.0 之后）
code_baseline: dev_1.5.0（ace782c81，= main 355a37dee + 地基 Story 0.1；规划时 4-1 尚未合入）
baseline: _bmad-output/planning-artifacts/architecture.md
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/运营活动规则引擎/1-5-0运营活动规则引擎prd.md          # FR-193 / AB-40，附录 A 定稿文案
  - _bmad-output/planning-artifacts/v1.5.0/运营活动规则引擎/ui-1-5-0-ops-activity-integrated-v1.html   # App 6 屏（拆页-App/）
  - _bmad-output/planning-artifacts/v1.5.0/运营活动规则引擎/ui-admin-1-5-0-ops-activity-integrated-v1.html  # 后台 33 屏（拆页-后台/）
  - _bmad-output/planning-artifacts/v1.5.0/architecture-v1.5.0-gift-pack-delta.md §5             # 冻结契约 EntitlementCreditService.grant
  - _bmad-output/implementation-artifacts/v1.5.0/4-1-礼包权益次数表与发放扣减接口.md
  - _bmad-output/implementation-artifacts/v1.5.0/6-3-app礼包koin与jatah合并页.md
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md（11-11 / 11-12 / 11-13 运营活动各条）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md「2026-11-14 V1.5.0 六会话共用地基」及「五线规划后补充的跨线口径」
---

# V1.5.0 会话 ⑥ 运营活动规则引擎 · 架构增量

> 只写本线新增或改变的技术决定，均对照 `dev_1.5.0` 代码核实（gift-pack 4-1 的代码规划时还不在 dev，按其冻结契约写）。**权威顺序**：`CROSS-STORY-DECISIONS.md` > `decision-log.md` > PRD（变更记录最晚一条为准）> 本文件 > UI 稿。PRD / UI 稿与代码现状冲突处在 §1 列出，按本文件处置执行。
>
> 路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`R/` = `petgo-backend/src/main/resources/`，`A/` = `petgo_app/lib/`，`T/` = `petgo-backend/src/main/resources/templates/admin/`。

## 0. 范围

| 部分 | 需求 | 本线做什么 |
|---|---|---|
| 引擎（后端） | FR-193：活动配置模型、参与即奖判定与发奖、扣回、排名结算、到账 / 获奖通知、活动名按语言下发 | Epic 1 |
| 后台 | AB-40：「成长体系 → 运营活动」页签、新建 / 编辑抽屉、发布确认、活动详情子页（参与即奖 / 排名）、停发、重新发放、剔除、确认发放、待办中心「排名待发放」 | Epic 2 |
| App | 两类新通知（读后端文案、图标、点开跳转、获奖推送直达帖子）、PawCoin 记录「Event · 活动名」 | Epic 3 |
| 联调 | 本地 L1 / L2，云端不跑 | Epic 4 |

不做：实物奖励；App 活动中心 / 活动页；「+N EXP」即时提示；场所打卡行为；PawCoin / 优惠券 / 权益次数扣回；新增 App 埋点（PRD §4）。

## 1. 已核实的代码现状与 PRD / UI 稿出入（以本表处置为准）

| # | PRD / UI 稿写法 | `dev_1.5.0` 实际 | 处置 |
|---|---|---|---|
| 1 | App 稿执行说明写通知类型 `ACTIVITY_REWARD` / `ACTIVITY_RANK_WON` | 地基已加 `NotificationType.OPS_ACTIVITY_REWARDED`、`OPS_ACTIVITY_RANK_WON`（`B/notify/domain/NotificationType.java` 末尾），`PushCategory` → `PROMO`，`ck_notifications_type` 已在 `V20261114_1000` 重建 | 一律用地基两个值；**不改** `ck_notifications_type`、不改枚举 |
| 2 | PRD §6「在现有监听里入账日常规则之后再查一次活动」 | `B/growth/listener/GrowthActivityListener` 每个方法 `@Async @TransactionalEventListener`，⑤ likes 线会在 `onLiked` 开头加 `if (e.automated()) return;` | 新建**独立**监听 `B/opsactivity/listener/OpsActivityContentListener`（同样 `@Async` + AFTER_COMMIT）。活动奖励不占日常额度，与日常入账无先后依赖；独立类避免与 ⑤ / 日常规则改动撞文件（OA-5） |
| 3 | PRD「点赞 / 评论用点赞 / 评论的创建时间判」 | `ContentLikedEvent.createdAt` = 点赞时 `Instant.now()`（≈ 点赞行创建时刻）；`ContentCommentedEvent.createdAt` = **评论变可见时**的 `Instant.now()`（`CommentService.publishCommented`），不是评论创建时刻 | 点赞用事件 `createdAt`；评论**按 `commentId` 查 `comments.created_at`**；发帖用事件 `createdAt`（= `content_posts.created_at`，过审路径 `approveReview` 也传 `p.getCreatedAt()`） |
| 4 | 自动点赞（⑤）`ContentLikedEvent.automated = true` 不应给活动奖励 | `dev_1.5.0` 的 `ContentLikedEvent` 还是 4 参，无 `automated` | 判定一律先过参与资格（OA-6：`account_type = 'REAL'` 等），虚拟号自然不得奖；开工时若 `ContentLikedEvent` 已有 `automated()` 则另加显式 `if (e.automated()) return;`，没有则在 Completion Notes 记「⑤ 合入后补一行」。**自动点赞永远不给点赞人发活动奖励**；被赞帖子的作者不因自动赞得任何活动奖励（排名热度只算真实用户，OA-16） |
| 5 | PRD「活动 EXP 走 `accrue()` 同样的锁和幂等」 | `GrowthLedgerService.accrue(AccrualCommand)`：不写 `note`、没有活动 ID 列、占每日额度（`claimQuota`）、不参与 / 幂等命中 / 规则停用都只返回 `false` | 新增 `GrowthLedgerService.accrueActivity(ActivityAccrualCommand)`：同一把账户行锁 + 同一幂等列，**不占额度**、写 `note`（印尼语名）+ `activity_id`，返回四态结果（OA-9） |
| 6 | 「下架扣回活动 EXP」 | `reverseDetailed` 插反向流水时不带 `note`，活动反向行在 EXP 明细会显示「Event」无名 | 反向流水**复制原流水的 `note` 与 `activity_id`**（只对原流水有值时；日常规则行不受影响）（OA-9） |
| 7 | `user_coupons` 发券来源加 `ACTIVITY`（带活动 ID） | `ck_user_coupons_source` 只在 `V20261105_1615` 定义过一次：`('NEWBIE','CLAIM','CODE','BANNER','REISSUE')`；无活动 ID 列；写入只经 `UserCouponLedgerDao.insert`（守卫测试） | 迁移 `V20261115_0610` DROP + ADD 重列 6 值；加 `activity_id`；DAO 加带活动 ID 的插入（OA-11） |
| 8 | PawCoin「交易类型沿用 `BONUS`，备注带活动 ID」 | `pawcoin_transactions` 无备注列，有 `ref_type VARCHAR(24)` / `ref_id BIGINT`；`PawCoinTxnItem` 只下发 `delta / type / refType / createdAt`；`PawCoinQueryService` 注释「不跨模块 join」 | `refType = "ACTIVITY"`、`refId = 活动 id`；App 要的活动名经 **pay 模块定义的端口** `PawCoinRefLabelResolver`（opsactivity 实现）批量解析，`PawCoinTxnItem` 末尾加可空 `refLabel`（OA-10、OA-19） |
| 9 | 到账 / 获奖通知「后端按收件人语言写好标题正文」 | `NotificationService.send(...)` 推送文案走 `pushText("notify.<TYPE>.title")`，**缺键才回退调用方传入的 title**；③ 的 `sendRendered` 不在 dev | 用现有 `send`（获奖，带推送）/ `sendWithoutPush`（到账），标题正文由本线按 `AccountQueryService.localeOf` 渲染后传入；**禁止**新增 `notify.OPS_ACTIVITY_REWARDED.*` / `notify.OPS_ACTIVITY_RANK_WON.*` 键（否则静态串覆盖动态文案）。本线文案键前缀 `notify.opsActivity.*`（OA-13、OA-18） |
| 10 | 后台确认弹层「加一个必填原因输入」 | `B/admin/shared/web/AdminConfirmModal` 有 diffs / facts / choices / select，无自由文本 | 加可选 `ReasonField`（`withReason(name, label, maxLength)`），`T/fragments/confirm-modal.html` 渲染；空值 422 落弹层内（停发、剔除共用）（OA-20） |
| 11 | 待办中心「运营待办 · 排名待发放」 | `NavBadgeService.QUEUES` 末尾是 `shop-order-exceptions`、`shop-refund-payouts`；`T/fragments/nav.html` 有「商城待办」`nav-subhead` 写法；队列 key 必须是 `AdminPageCatalog` 页面 key | `AdminPageCatalog` 加页面 `ops-activity-ranking`（路由 `/admin/growth?tab=activities&status=PENDING_GRANT`，`G_CONFIG`，查看 + 编辑都要 `growth.config_edit`，不出现在配置组导航里）；`QUEUES` 末尾追加该 key（OA-21） |
| 12 | 内容类型「Diary / Moment / Tips」 | `ContentType {DAILY, GROWTH_MOMENT, KNOWLEDGE}`；App 里 Diary = `GROWTH_MOMENT`、Moment = `DAILY`、Tips = `KNOWLEDGE` | 库存枚举名；后台显示按此映射 |
| 13 | 帖子被举报拉回审核后再过审 | `ContentService.approveReview` 会**再发一次** `ContentPublishedEvent` | 幂等键 `ACT:{活动}:POST:{帖子}` 挡住，不重复发 |
| 14 | 运营下架时点赞 | `doSoftDelete` 直接 `likes.deleteByPostId`，**不发** `ContentUnlikedEvent` | 下架只扣「发帖」活动给作者的 EXP；该帖上别人的点赞 / 评论活动 EXP 不扣（与日常规则同口径） |
| 15 | `EntitlementCreditService.grant` | 4-1 契约：`REQUIRED`、校验失败抛 `AppException`（注销受理中 / 虚拟号等 → `entitlement-user-inactive`） | 在发奖事务内调用；抛错 → 整份奖励回滚、记「发放失败」（PRD「任何一项出错整份重试」）。资格预检把 4-1 的口径并进来（OA-6），避免可预见的失败 |
| 16 | 话题范围：PRD §3.2 表格「发帖：指定话题（1–3 个）」，点赞 / 评论「可不限」；但 §3.2 又写「私密帖（不限话题的活动也不算）」 | 后台稿 D1 发帖话题无「可不选」提示，D2 / D3 有 | 按表格与后台稿：**发帖活动必须选 1–3 个话题**；点赞 / 评论 0–3 个（空 = 不限）；私密帖一律不算（代码照写）。见待拍板 Q-1 |

## 2. 关键技术决定

### OA-1 包与归属
- 引擎：`B/opsactivity/{domain, repository, service, listener, job, notify, web}`。后台：`B/admin/growth/service/AdminOpsActivityService*`、`B/admin/growth/web/AdminOpsActivityController`，模板 `T/fragments/growth-activities*.html`、`T/fragments/drawer-growth-activity.html`、`T/growth-activity-detail.html`。
- 写入单点：`ops_activities` 与 `ops_activity_*` 共六张表只经 `B/opsactivity/` 包内代码写（守卫测试 `OpsActivityWriteGuardTest`，照 4-1 `EntitlementWriteGuardTest`）；后台服务只调引擎服务。
- 引擎**只调用**其他模块的公开入口：`GrowthLedgerService.accrueActivity / reverse`、`PawCoinWalletService.credit`、`ActivityCouponIssuer.tryIssue`（shop.coupon 包内新增）、`EntitlementCreditService.grant`、`NotificationService.send / sendWithoutPush`。

### OA-2 配置模型（§3.1 迁移 0600）
- `ops_activities` 一行一个活动：名称 ID / EN（各 ≤ 40）、后台备注、`action`（`POST | LIKE | COMMENT`）、`grant_mode`（`INSTANT | RANKING`）、`content_types`（JSONB 字符串数组，只发帖用）、`reward`（JSONB，参与即奖一份奖励）、`rank_tiers`（JSONB，排名档位）、三个上限、`granted_count`、起止时间、`status`（`DRAFT | PUBLISHED`）、排名状态 `rank_state`。
- 话题范围用子表 `ops_activity_topics (activity_id, topic_id)`（判定时按帖子话题求交集）。
- JSON 形状（`B/opsactivity/domain/ActivityReward`，record + Jackson，映射 `@JdbcTypeCode(SqlTypes.JSON)`，照 `ShopCoupon.scopeValues`）：
  ```json
  {"exp":20,"pawcoin":500,"couponId":12,"ktpHd":{"count":1,"template":"ANY"},"tailsonality":1,"gift1k":3}
  ```
  每项可空、至少一项非空。`ktpHd.template` ∈ 8 款 wire 后缀（`KTP | PASSPORT | KITAS | SIM | STUDENT_BINTANG | STUDENT_MALAM | STUDENT_CERIA | STUDENT_KLASIK`）或 `ANY` → `CreditType.KTP_HD_*`。`couponId` 存内部 id，后台表单用 `shop_coupons.public_token` 互转。
  档位：`[{"from":1,"to":1,"reward":{…}},{"from":2,"to":3,"reward":{…}}]`。
- 对外（后台 URL）用 `ops_activities.public_token`（32 位，`ShopTokenGenerator` 同写法）；后台内部行操作（重发、剔除）用记录 id（后台内部工具，同现有后台惯例）。

### OA-3 显示状态（派生，不落库）
`OpsActivityStatus`（Java 与列表 SQL 用同一 CASE，`OpsActivityStatusSql.CASE_EXPR` 常量共用）：
| 显示 | 条件 |
|---|---|
| `DRAFT` 草稿 | `status = 'DRAFT'` |
| `SCHEDULED` 未开始 | `PUBLISHED` 且 `now < start_at` |
| `FULLY_GRANTED` 已发完 | `PUBLISHED`、`INSTANT`、`now < end_at`、`total_limit IS NOT NULL AND granted_count >= total_limit` |
| `LIVE` 进行中 | `PUBLISHED` 且 `start_at <= now < end_at` 且不满足上一行 |
| `PENDING_GRANT` 待发放 | `RANKING`、`now >= end_at`、`rank_state IN ('NONE','READY')`（`NONE` = 名单生成中 F1） |
| `ENDED` 已结束 | 其余（含 `rank_state IN ('EMPTY','GRANTED')`） |

提前结束 = `end_at := now`、`ended_early_at := now`（之后一切按 `end_at` 判，排名立即进生成）。

### OA-4 配置校验（`OpsActivityRules`，服务端一次收齐全部字段错误，422 + 字段键；后台抽屉照 `drawer-growth-rule :: errors` 渲染）
1. 名称 ID / EN 必填，trim 后按码点 1–40；备注 ≤ 200；`start_at < end_at`（后台按 WIB 墙上时间填，`ScheduleWindow.WIB` 转 UTC）。
2. `action` 必选；`RANKING` 只允许 `POST`。
3. 话题：只能选 `topics.type = 'OFFICIAL' AND status = 'ACTIVE'`（保存时校验；之后话题被隐藏照常判定、被禁用后台提示）；`POST` 1–3 个，`LIKE / COMMENT` 0–3 个；`content_types` 只 `POST` 用、非空子集（默认三种全选），其他行为必须为空。
4. 奖励组合：至少一项；`exp` 1–1,000；`pawcoin` 1–100,000；`couponId` 必须是可选券（OA-11 第 1 条）；`ktpHd.count`、`tailsonality`、`gift1k` 各 1–100（**受 4-1 契约 `quantity 1..100` 约束**，PRD 未给上限）；`LIKE` 活动奖励只能含 `exp`（服务端拦，后台只列 EXP）。
5. `INSTANT`：`per_user_total_limit` 必填 1–10,000；`per_user_daily_limit` 可空，1 ≤ 值 ≤ 总次数；奖励含 `pawcoin` 或 `couponId` 时 `total_limit` 必填 1–1,000,000，否则可空；`rank_tiers` 必须为空。
6. `RANKING`：`rank_tiers` 1–5 档、第 1 档 `from = 1`、`from ≤ to`、相邻档 `next.from = prev.to + 1`、最后一档 `to ≤ 100`（待拍板 Q-3）；每档奖励组合同第 4 条；三个上限与 `reward` 必须为空。
7. 草稿与发布用同一套字段校验；发布另加：`end_at > now`；奖励（含任一档）含 PawCoin 时操作人须有 `user.grant_pawcoin`，否则 403（按钮置灰由页面做）。
8. 可改范围：`DRAFT` / `SCHEDULED` 全部可改（`SCHEDULED` 保存要再过一遍发布校验）；`LIVE` / `FULLY_GRANTED` 只能**延长** `end_at`（新值 > 旧值）或提前结束，其余字段提交了不同值 → 422；`ENDED` / `PENDING_GRANT` 一律只读；删除只限 `DRAFT`（物理删除，子表 CASCADE）。
9. 重叠提示（只提示不拦）：发布 / 未开始保存时，找 `PUBLISHED`、未结束、时间窗与本活动相交、`action` 相同、话题相交（任一方话题为空 = 全部）的活动，取第一条名称放进确认弹层黄条（PRD 写「进行中」，这里扩到时间窗相交，更早发现双发；只是提示）。

### OA-5 判定入口（参与即奖）
`OpsActivityContentListener`（`@Async` + `@TransactionalEventListener`，默认 AFTER_COMMIT；失败只记 ERROR：活动 id / 用户 id / 异常类名，不记正文昵称）：
| 事件 | 行为 | 发生时刻 | 过滤（在活动循环外先做） | 触发对象 / 幂等键 |
|---|---|---|---|---|
| `ContentPublishedEvent` | 发帖 | `e.createdAt()` | `visibility = PUBLIC`；作者资格 | `post_id`；`ACT:{a}:POST:{postId}` |
| `ContentLikedEvent` | 点赞 | `e.createdAt()` | `likerId ≠ authorId`；（有字段时）`!automated`；点赞人资格 | `post_id`；`ACT:{a}:LIKE:{likerId}:{postId}` |
| `ContentCommentedEvent` | 评论 | `comments.created_at`（按 `commentId` 查，查不到跳过） | `commenterId ≠ contentAuthorId`、`contentAuthorId > 0`；评论人资格 | `post_id` + `comment_id`；`ACT:{a}:COMMENT:{commenterId}:{postId}` |

1. 总开关：`GrowthLedgerService.campaignEnabled()` 为 false → 整个事件直接返回（关闭期间的行为**不补**，PRD「暂停发放」；见待拍板 Q-5）。
2. 候选活动：`status = 'PUBLISHED' AND grant_mode = 'INSTANT' AND action = :a AND start_at <= :occurredAt AND end_at > :occurredAt`（按**行为发生时刻**；活动已结束但帖子在活动内发、事后才过审的，照样命中）。
3. 范围：发帖用事件的 `topicIds`（已排除 DISABLED 话题）与活动话题求交、`type ∈ content_types`；点赞 / 评论按帖子 id 查 `content_topics`（一次查询，多个活动共用），活动话题为空 = 不限。
4. 每个命中的活动**各自独立**走 OA-7 预留 → OA-8 执行；一个活动失败不影响其他活动。
5. 点赞复查：对 `LIKE` 活动执行完成后复查 `ContentLikeRepository.existsByPostIdAndUserId`，不在了 → 走 OA-15 的取消点赞扣回（与 `GrowthActivityListener.onLiked` 同理：两个异步任务无先后保证）。
6. 幂等键统一由 `GrowthIdemKeys.activityPost / activityLike / activityComment / activityRank` 生成（「唯一生成幂等键的地方」），四种奖励（EXP、PawCoin、优惠券、权益次数）与发放记录用**同一个键**。所有键 ≤ 80 字符（`ledger_entries.idempotency_key VARCHAR(80)`）。

### OA-6 资格（谁能得活动奖励）
`OpsActivityEligibility.check(userId, activityId)` → `ELIGIBLE | INACTIVE | STOPPED`，一条 SQL：
- `GrowthParticipation.ACTIVE_REGULAR_USER` 同口径（`role = 'USER' AND account_type = 'REAL' AND deleted_at IS NULL AND status = 'ACTIVE'`）**并且** `account_deletions` 里没有这个用户的任何行（把 4-1 `grant` 的「注销受理中」口径并进来，避免 EXP 发了、次数失败）。`GrowthParticipation` 是包内类，在 growth 包加公开方法 `GrowthLedgerService.activityEligible(long userId)` 承载 SQL，不复制谓词。
- 停发名单 `ops_activity_user_stops` 有 (活动, 用户) → `STOPPED`。
- 预留时判一次；执行时再判一次（之间可能被停发 / 注销），不合格 → 发放记录 `VOID` + 原因。

### OA-7 预留（REQUIRES_NEW 事务 1，`OpsActivityGrantService.reserve`）
顺序：
1. `SELECT pg_advisory_xact_lock(hashtext('opsact:' || :activityId || ':' || :userId))`（同一人同一活动串行，保证次数上限准确）。
2. 幂等短路：`SELECT 1 FROM ops_activity_grants WHERE idem_key = :k` 有 → 结束（重复事件 / 取消重赞 / 同帖多条评论 / 删了再评），不再执行。
3. 资格（OA-6）不合格 → 不插记录，结束（停发后的行为不留明细；E1 的「已停发」行来自停发表，见 OA-20）。
4. 次数上限：`count(*) WHERE activity_id = :a AND user_id = :u AND status <> 'VOID'` ≥ `per_user_total_limit` → 结束；`per_user_daily_limit` 非空时同条件再加 `wib_date = :occurredWibDate`。**被扣回的点赞也占次数**（防「赞—取消—再赞别的帖」刷）。
5. 总份数（并发准确，PRD §6）：
   ```sql
   UPDATE ops_activities SET granted_count = granted_count + 1, updated_at = now()
    WHERE id = :a AND status = 'PUBLISHED'
      AND (total_limit IS NULL OR granted_count < total_limit)
   ```
   影响 0 行 = 已发完 → 结束（显示状态自然变「已发完」）。`granted_count` 不设上限也照样累加（列表「已发 N」用）。
6. 插入 `PENDING` 记录（`INSERT … ON CONFLICT (idem_key) DO NOTHING` 兜底；理论上已被第 1 步的锁 + 第 2 步挡住，真冲突时抛错让整个事务连同第 5 步的份数一起回滚）：`reward` 列存**本次应发**奖励快照（从活动 `reward` 复制），`occurred_at`、`wib_date`（发生时刻的 WIB 日）、触发对象。提交后把 grantId 交给 OA-8。

`VOID` / `FAILED` 不退还 `granted_count`（份数按「发起的发放」计；失败可重发，重发不再扣份数）。

### OA-8 执行（REQUIRES_NEW 事务 2，`OpsActivityGrantService.execute(grantId)`）
1. `SELECT … FROM ops_activity_grants WHERE id = :id FOR UPDATE`；`status NOT IN ('PENDING','FAILED')` → 返回（并发重发 / 重放安全）。
2. 总开关关 → 抛 `ActivityGrantStepException(CAMPAIGN_DISABLED)`；资格再判（OA-6）：`INACTIVE` / `STOPPED` → `status = 'VOID'`、`fail_reason = USER_INACTIVE | USER_STOPPED`，提交，结束。
3. 按固定顺序发（顺序即锁顺序，防死锁：账户行 → 钱包行 → 权益 advisory 锁 → 券模板行）：
   1. **EXP**：`GrowthLedgerService.accrueActivity(…)`（OA-9），结果 `GRANTED | REPLAYED` 记 `exp_granted`；`NOT_PARTICIPATING` → 同第 2 步 `VOID`；`CAMPAIGN_DISABLED` → 抛步骤异常。
   2. **PawCoin**：`PawCoinWalletService.credit(userId, n, BONUS, "ACTIVITY", activityId, idemKey)`（重放自动短路；Redis 幂等映射在提交后才写，回滚安全），记 `pawcoin_granted`。
   3. **权益次数**：KTP / 性格测试 / 免费礼物各一次 `EntitlementCreditService.grant(GrantCommand.activity(userId, type, n, activityId, idemKey, nameId, nameEn))`，同一 `idemKey` 不同类型各自成功；记 `ktp_credits / tailsonality_credits / gift_credits`。
   4. **优惠券**（最后发，模板行锁持有时间最短）：`ActivityCouponIssuer.tryIssue(…)`（OA-11）→ `ISSUED`（记 `user_coupon_id`）或 `SKIPPED_STOCK | SKIPPED_LIMIT | SKIPPED_INVALID`（**不抛错**，其余照发）。
4. `status = 'SENT'`、`sent_at = now`、`attempts += 1`，提交。
5. 任一步抛异常 → 事务 2 整体回滚（已入的 EXP / PawCoin / 次数 / 券一起撤销）→ 新开 REQUIRES_NEW 事务 3：`status = 'FAILED'`、`fail_reason` = 失败步骤码（`EXP_FAILED | PAWCOIN_FAILED | CREDIT_FAILED | COUPON_FAILED | CAMPAIGN_DISABLED | UNKNOWN`）、`attempts += 1`。ERROR 日志只记 grantId / activityId / userId / 步骤 / 异常类名。
6. 事务 2 提交成功后（事务外）调 OA-13 / OA-18 通知；通知失败不影响发放结果。
7. 「发放失败」= `FAILED`（系统错误，可重发）；券因库存 / 限领 / 失效没发出**不算**失败（`SENT` + `coupon_outcome = SKIPPED_*`）。

### OA-9 EXP 入账（growth 包改动）
- `ActivityAccrualCommand(long userId, long activityId, int exp, String idemKey, String refType, String refId, String nameId, Instant occurredAt)`；`refType`：发帖 / 点赞 / 评论 / 排名一律 `CONTENT_POST`，`refId` = 帖子 id。
- `GrowthLedgerService.accrueActivity(cmd)`（`@Transactional` REQUIRED，并入 OA-8 事务）：① 参与判定（`isParticipating`）否 → `NOT_PARTICIPATING` ② `campaignEnabled()` 否 → `CAMPAIGN_DISABLED` ③ `insertIfAbsent` + `lockById` 账户行 ④ `existsByIdemKey` → `REPLAYED`（视同成功）⑤ 插流水 `rule_code = 'OPS_CAMPAIGN'`、`delta = exp`、`note = nameId`（旧版 App 照旧显示）、`activity_id`、`wib_date = now 的 WIB 日`、**不调 `claimQuota`** ⑥ `applyDelta(account, exp, RuleCode.OPS_CAMPAIGN)`（升级照常弹庆祝）→ `GRANTED`。
- `INSERT_LEDGER_SQL` 不改；新增 `INSERT_ACTIVITY_SQL`（多 `note`、`activity_id` 两列）。
- `reverseDetailed`：原流水 `activity_id` 非空时，反向流水用 `INSERT_ACTIVITY_SQL` 复制 `note`、`activity_id`；其余路径逐字不变。
- `GrowthIdemKeys` 新增：`activityPost(long activityId, long postId)` → `ACT:{a}:POST:{p}`；`activityLike(a, likerId, postId)` → `ACT:{a}:LIKE:{u}:{p}`；`activityComment(a, commenterId, postId)` → `ACT:{a}:COMMENT:{u}:{p}`；`activityRank(a, userId)` → `ACTRANK:{a}:{u}`；`ACTIVITY_PARTICIPATION_PREFIX = "ACT:"`、`ACTIVITY_RANK_PREFIX = "ACTRANK:"`。
- `adjust()` / 批量发放 / `OPS_CAMPAIGN` 规则行一律不动。

### OA-10 PawCoin
`PawCoinWalletService.credit` 原样调用（`BONUS`、`refType = "ACTIVITY"`、`refId = activityId`、幂等键 = OA-5 的键）。不新增交易类型、不改 `ck_pawcoin_txn_type`。总账分录借方 `PLATFORM_REVENUE`（`creditCounterAccount(BONUS)` 现状，与后台赠送同科目）。

### OA-11 优惠券（shop.coupon 包内新增 `ActivityCouponIssuer`）
1. 可选券（后台下拉与保存校验同一查询）：`status = 'ACTIVE' AND acquire_type IN ('CLAIM','CODE') AND issue_end_at > now AND (valid_mode = 'RELATIVE' OR valid_end_at > now)`；下拉每项带剩余库存（`total_quantity − issued_count`，不限显示「不限」）与 `per_user_limit`。新人券（`NEWBIE`）不列（新人券唯一性语义，待拍板 Q-4）。
2. `tryIssue(long userId, long couponId, long activityId, Instant now)`（`MANDATORY`）：`CouponRowLock.lockFresh(couponId)` → 不存在或 `!isIssuableAt(now)` 或（FIXED 且 `valid_end_at <= now`）→ `SKIPPED_INVALID`；`userCoupons.countByUserIdAndCouponId(userId, couponId) >= per_user_limit` → `SKIPPED_LIMIT`；`incrementIssuedIfAvailable(couponId) == 0` → `SKIPPED_STOCK`；否则 `CouponLedgerService.issueForActivity(userId, coupon, activityId, now)`（新方法，`source = ACTIVITY`，DAO 新增 `insertWithActivity`，同一写入点，守卫测试照过）→ `ISSUED(userCouponId, couponNameId, couponNameEn)`。日志只记 userId / couponId / 结果分类。
3. `UserCouponSource` **末尾追加** `ACTIVITY`。App「我的优惠券」不显示来源（无改动）；后台用户抽屉优惠券页签加 `admin.v142.userCoupons.source.ACTIVITY` / `.event.ISSUED.ACTIVITY` 三语 + 默认文件（文案 = 「运营活动 / Campaign / Kampanye」，同 PRD A.2 用词）。
4. 后台活动页「优惠券已失效」提示：活动的券（`reward` 或任一档）满足第 1 条之否 → 黄条。

### OA-12 权益次数（调 gift-pack 4-1 冻结契约，**不改契约**）
- 只用 `GrantCommand.activity(userId, type, quantity, activityId, idemKey, nameId, nameEn)` + `EntitlementCreditService.grant`。`CreditSource.ACTIVITY`、`sourceRef = 活动 id`、label = 活动名快照（活动发放只发生在已发布之后，名称已冻结，快照稳定）。
- 「任意款」→ `CreditType.KTP_HD_ANY`（契约只允许 ACTIVITY 来源发，正好）；指定款 → 8 款之一（`CreditType.valueOf("KTP_HD_" + template)`）。
- 有效期（3 个月）、到期提醒、「Koin & Jatah Saya」免费次数记录「Event · {label}」、使用扣减全归 gift-pack，本线不碰。
- 次数**不扣回**（契约本就不提供）。
- 契约缺口见 §8，均不改契约。

### OA-13 到账通知（参与即奖，`OPS_ACTIVITY_REWARDED`）
- 条件：本次实际发出的奖励里有 EXP 以外的东西（PawCoin / 次数 / 券 `ISSUED`）；只有 EXP（含券被跳过只剩 EXP）不发。
- 每人每活动每 WIB 日最多 1 条：`INSERT INTO ops_activity_notify_days (activity_id, user_id, wib_date) VALUES (…) ON CONFLICT DO NOTHING`，插入成功才发（`wib_date` 取发送时刻的 WIB 日）。
- `NotificationService.sendWithoutPush(userId, OPS_ACTIVITY_REWARDED, title, body, "OPS_ACTIVITY_REWARDED", targetRef)`：`targetRef = "WALLET"`（含 PawCoin 或次数）/ `"COUPONS"`（只有 EXP + 券）。
- 文案（PRD 附录 A.1，按 `localeOf` 选 ID / EN，MessageFormat，注意单引号写 `''`）：标题 `notify.opsActivity.rewarded.title`；正文 `notify.opsActivity.rewarded.body.wallet` / `.body.coupons`；奖励列表项 `notify.opsActivity.reward.exp / .pawcoin / .ktpHd / .tailsonality / .gift1k / .coupon`；连接词 `notify.opsActivity.reward.join2`（A 和 B）/ `.joinLast`（A, B, dan C）。列表顺序固定 EXP → PawCoin → 免费次数（KTP → 性格测试 → 礼物）→ 优惠券；只列实际发出的项。券名取 `shop_coupons.name_id / name_en`。数字按 ID `1.000` / EN `1,000` 千分位（PRD A.1 原文）。
- 渲染器 `OpsActivityCopy`（纯函数，L0 可测）。

### OA-14 卡住的「待发」
`OpsActivityPendingSweepJob`（`@Scheduled(fixedDelayString = "${petgo.ops-activity.pending-sweep-ms:300000}", initialDelayString = "${petgo.ops-activity.pending-sweep-initial-ms:300000}")`）：`UPDATE ops_activity_grants SET status = 'FAILED', fail_reason = 'INTERRUPTED' WHERE status = 'PENDING' AND created_at < now − 10 分钟`（条件更新，多实例并发无害）。进程在事务 1 提交后、事务 2 前崩溃的记录由此变「发放失败」，运营重发即可（不自动重发，避免与正在跑的事务 2 抢）。

### OA-15 扣回（只扣 EXP，只扣参与即奖）
- **运营下架**：`OpsActivityContentListener.onRemoved(ContentRemovedEvent)`：查 `ops_activity_grants WHERE trigger_kind = 'POST' AND post_id = :p AND status = 'SENT' AND exp_granted > exp_reclaimed`，逐条 `GrowthLedgerService.reverseDetailed(new ReverseCommand(idemKey, TAKEDOWN, SOURCE_TAKEDOWN, null))`，`exp_reclaimed += 实扣额`（不扣成负数、只扣实际入账，由 `reverseDetailed` 保证）。同时把排名名单里该帖 `PENDING` 的行置 `NOT_GRANTED / POST_REMOVED`（OA-17）。**`ACTRANK:` 键的流水永不扣**（排名不扣，PRD §3.5）。
- **取消点赞**：`onUnliked(ContentUnlikedEvent)`：`trigger_kind = 'LIKE' AND user_id = likerId AND post_id = :p AND status = 'SENT'` → 同上 `ReverseReason.UNLIKE` / `SOURCE_UNLIKE`。再点赞：键已存在，不重复发（PRD「取消后再点不重复算」）。
- 作者自删帖、评论被删：不发事件，不扣。PawCoin / 券 / 次数：不扣。
- 后台「EXP 已扣回」= `exp_reclaimed > 0`。

### OA-16 排名结算任务（多实例安全）
`OpsActivityRankBuildJob`（`fixedDelayString = "${petgo.ops-activity.rank-build-ms:300000}"`，初始延迟同 OA-14 写法）：
1. 扫 `status = 'PUBLISHED' AND grant_mode = 'RANKING' AND rank_state = 'NONE' AND end_at <= now`（每次最多 20 个），逐个在**独立事务**里：
2. 抢占：`SELECT … FROM ops_activities WHERE id = :id AND rank_state = 'NONE' FOR UPDATE SKIP LOCKED`（不另设「生成中」状态）；拿不到行（别的实例正在建）或状态已变 → 跳过。同一事务内建完名单并把 `rank_state` 置 `READY`（有人）/ `EMPTY`（没人），`rank_built_at = now`。事务失败整体回滚，下一轮重来（PRD「结束后 1 小时内」，5 分钟一轮足够）。
3. 热度 SQL（截至 `end_at`，只算真实用户、按人数去重、不算作者自己）：
   ```sql
   WITH cand AS (
     SELECT p.id, p.author_id, p.created_at FROM content_posts p
       JOIN users au ON au.id = p.author_id
      WHERE p.created_at >= :start AND p.created_at < :end
        AND p.visibility = 'PUBLIC' AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
        AND p.type IN (:types)
        AND EXISTS (SELECT 1 FROM content_topics ct WHERE ct.post_id = p.id AND ct.topic_id IN (:topicIds))
        AND <作者资格：OA-6 口径，以 au 为别名>
   ), lk AS (
     SELECT l.post_id, count(DISTINCT l.user_id) AS n FROM content_likes l
       JOIN cand c ON c.id = l.post_id JOIN users u ON u.id = l.user_id
      WHERE l.user_id <> c.author_id AND l.created_at <= :end AND u.account_type = 'REAL' AND u.deleted_at IS NULL
      GROUP BY l.post_id
   ), cm AS (
     SELECT m.post_id, count(DISTINCT m.author_id) AS n FROM comments m
       JOIN cand c ON c.id = m.post_id JOIN users u ON u.id = m.author_id
      WHERE m.author_id <> c.author_id AND m.created_at <= :end AND m.deleted_at IS NULL
        AND m.moderation_status = 'VISIBLE' AND u.account_type = 'REAL' AND u.deleted_at IS NULL
      GROUP BY m.post_id
   ), co AS (
     SELECT k.post_id, count(DISTINCT k.user_id) AS n FROM content_collections k
       JOIN cand c ON c.id = k.post_id JOIN users u ON u.id = k.user_id
      WHERE k.user_id <> c.author_id AND k.created_at <= :end AND u.account_type = 'REAL' AND u.deleted_at IS NULL
      GROUP BY k.post_id
   ), scored AS (
     SELECT c.id, c.author_id, c.created_at, COALESCE(lk.n,0) AS likes, COALESCE(cm.n,0) AS commenters,
            COALESCE(co.n,0) AS collectors,
            COALESCE(lk.n,0) * 1 + COALESCE(cm.n,0) * 3 + COALESCE(co.n,0) * 2 AS score
       FROM cand c LEFT JOIN lk ON lk.post_id = c.id LEFT JOIN cm ON cm.post_id = c.id LEFT JOIN co ON co.post_id = c.id
   ), best AS (
     SELECT DISTINCT ON (author_id) * FROM scored ORDER BY author_id, score DESC, created_at ASC, id ASC
   )
   SELECT * FROM best ORDER BY score DESC, created_at ASC, id ASC
   ```
   - 「截至结束时刻」：点赞 / 收藏取生成时**仍存在**且 `created_at <= end_at` 的行（结束前点了又在结束后取消的不算，可接受）；评论取当前可见且结束前创建的。
   - 自动点赞由虚拟号发出，`account_type = 'REAL'` 已排除；⑤ 合入后可另加 `AND NOT l.is_automated`（等价，可选）。
   - 「同分先发的排前面」：`created_at ASC`，再按 `id ASC` 定死。
4. 结果**全部**写进 `ops_activity_rank_entries`（一人一行，`base_order` 从 1 连续），`rank` 按 OA-17 第 1 条分配。不截断到档位长度（剔除后要递补）。
5. 名单为空 → `EMPTY`（显示「已结束」、不进待办、F7）。

### OA-17 剔除 / 撤销 / 确认发放
1. 名次分配（`reassignRanks(activityId)`，在锁住活动行的事务里）：对 `status <> 'EXCLUDED'` 的行按 `base_order` 依次编号 1、2、3…写进 `rank`；`EXCLUDED` 行 `rank = NULL`。**`NOT_GRANTED` 行保留名次、不递补**（PRD §3.5）。档位按 `rank` 落在哪个 `[from, to]` 决定应发奖励；`rank` > 最后一档 `to` = 未获奖（不在 F2 列表显示）。
2. 剔除（`growth.config_edit`，原因必填 ≤ 200）：仅 `rank_state = 'READY'` 时；`PENDING` / `NOT_GRANTED` 行 → `EXCLUDED`（原因、操作人、时间），重排名次；同一人只有一行，「剔人不是剔帖」天然成立。撤销剔除：`EXCLUDED` → `PENDING`（若帖子此时已下架 / 作者不合格则 → `NOT_GRANTED`），重排。审计 `OPS_ACTIVITY_RANK_EXCLUDED / _RESTORED`。
3. 自动「不发放」：下架事件（OA-15）、确认发放时复核（帖子 `deleted_at` 非空或作者 OA-6 不合格）→ `NOT_GRANTED`，原因码 `POST_REMOVED | USER_INACTIVE`。
4. 确认发放（`growth.config_edit` + 任一档含 PawCoin 时 `user.grant_pawcoin`；总开关关 → 422）：
   - 确认弹层（`POST …/ranking/grant/confirm`）由服务端**现算**合计：人数、EXP、PawCoin、优惠券张数、三类次数合计（只算 `rank ≤ 最大档` 且 `PENDING` 的行，先做第 3 条复核）。
   - 提交（`POST …/ranking/grant`）：事务 A：`SELECT … FROM ops_activities WHERE id = :id FOR UPDATE`；`rank_state ≠ 'READY'` → 视为重复提交，直接返回已有结果（不重复发）；复核第 3 条；对每个 `rank ≤ 最大档` 的 `PENDING` 行插一条发放记录（`trigger_kind = 'RANK'`、`rank`、`post_id`、键 `ACTRANK:{a}:{u}`、`ON CONFLICT DO NOTHING`）并回填 `grant_id`、行状态 `GRANTED`；`rank_state = 'GRANTED'`、`rank_granted_at`、`rank_batch_no = "RANK" + yyMMddHHmm(WIB) + "-" + activityId`（≤ 32）；审计 `OPS_ACTIVITY_RANK_GRANTED`。提交。
   - 然后逐条调 OA-8 `execute`（同步，最多 100 人）；页面显示「已发放：批次 {no}，{a} 位用户成功，{b} 位失败」，成功 / 失败数从发放记录现算。请求中途中断 → 剩下的 `PENDING` 由 OA-14 变失败，运营在名单里重发。
   - 发奖时**不检查**参与即奖的次数 / 份数上限、不检查停发名单（排名靠剔除）。
5. 排名发奖不扣回（含 EXP）；获奖不再另发到账通知。

### OA-18 获奖通知（`OPS_ACTIVITY_RANK_WON`）
- OA-8 第 4 步成功、且 `UPDATE ops_activity_grants SET notified_at = now WHERE id = :id AND notified_at IS NULL` 影响 1 行（重发成功也只通知一次）→ `NotificationService.send(userId, OPS_ACTIVITY_RANK_WON, title, body, "OPS_ACTIVITY_RANK_WON", String.valueOf(postId))`（站内 + 推送；推送偏好 `PROMO`、虚拟号跳过由 `send` 现有逻辑处理）。
- 文案 `notify.opsActivity.rankWon.title`（`Selamat! Postinganmu juara #{rank} di {name} 🏆`）/ `.body`（`Hadiahmu: {rewards}. Terima kasih sudah ikut!`），奖励列表同 OA-13（含 EXP）。
- 帖子 id 作 `targetRef`：同 `CONTENT_LIKED` 等「id 寻址类」既有口径（`NotificationItem` 注释：只下发给本人、目标页本就按数字 id 寻址）。

### OA-19 活动名按语言下发
- EXP 明细（`GET /api/v1/me/growth/ledger`、等级页预览）：`GrowthLedgerPageQuery.PAGE_SQL` 改为 `LEFT JOIN ops_activities a ON a.id = g.activity_id`，有活动 id 时 `note` = 收件人语言的名称（`localeOf` = EN → `name_en`，其余 → `name_id`），无活动 id 照旧。**响应结构不变**，旧版 App 同样拿到按语言的名称。
- PawCoin 流水：pay 包新增端口 `B/pay/service/PawCoinRefLabelResolver { String refType(); Map<Long,String> labels(Collection<Long> refIds, Locale locale); }`（按 `refType` 分组注入，可为空列表，无实现时不填）；opsactivity 实现 `refType = "ACTIVITY"`。`PawCoinTxnItem` **末尾加** `String refLabel`（全局 NON_NULL，非活动行不下发）；保留旧 `from(t)` 工厂（`refLabel = null`）。`refId` 仍不下发。
- 通知标题正文：落库时已按语言渲染（OA-13 / OA-18）。

### OA-20 后台（AB-40）
- 页签：`T/growth.html` `.tabs` 加第 4 个 `data-gtab="activities"`（`tab=activities` 时默认选中），面板片段 `T/fragments/growth-activities.html`（筛选：状态下拉 + 名称搜索；表格 10 列；空态 C2 / C3；分页 20）。页头「批量发放」不动；「新建活动」在页签工具栏右侧，仅 `growth.config_edit` 可见。
- 抽屉：`tpl-shared :: drawer('growthactivity', …)`，480 宽，结构 / 422 写法照 `drawer-growth-rule.html`，时间 `datetime-local` 照 `drawer-ops-popup.html`；行为切换联动（D1 ~ D3、D7）；「＋ 添加奖励」6 种、已加的置灰；档位编辑；未开始编辑（D12）、进行中只改结束时间（D13）、已结束只读（D14）。
- 确认弹层全走 `confirm-modal.html`：发布 D9 / D10（`facts` + 重叠时 `danger` 黄条、按钮「仍然发布」）、保存修改（`diffs`）、删除草稿 C5、提前结束 E6、重新发放 E4、停发 E5（原因）、剔除 F3（原因）、确认发放 F4（合计）。
- 详情子页：`GET /admin/growth/activities/{token}`（新模板 `T/growth-activity-detail.html`，面包屑回 `/admin/growth?tab=activities`），按 `grant_mode` 渲染 E 或 F 布局。
- 停发（E5）：插 `ops_activity_user_stops`（同人同活动唯一，重复提交幂等）；之后预留 / 执行都拦（OA-6）；**不能恢复**；明细筛「已停发」= 停发表行（每个停发用户一行：时间 = 停发时刻、关联帖子「—」、发了什么「不再发放」、状态「已停发」+ 原因），与发放记录按时间倒序合并分页（UNION ALL 查询）。
- 重新发放（E3 / E4）：只针对 `FAILED`；单条或勾选批量（一次 ≤ 100）；含 PawCoin 的记录要 `user.grant_pawcoin`；逐条调 OA-8 `execute`（同一幂等键，已到账部分不重复）；toast「已重新发放 {n} 条」（n = 本次变 `SENT` 的条数）。停发用户的失败记录重发 → 执行时变 `VOID / USER_STOPPED`。
- 导出 CSV：`GET …/grants.csv?status=`，UTF-8 BOM，列：时间（WIB）、用户 ID、昵称、关联帖子 ID、发了什么、状态、原因；最多 50,000 行。
- 权限：查看 `growth.config_view`；新建 / 编辑 / 删除草稿 / 提前结束 / 停发 / 剔除 / 撤销剔除 / 确认发放 / 重新发放 `growth.config_edit`；含 PawCoin 的发布 / 确认发放 / 重新发放另需 `user.grant_pawcoin`（缺 → 按钮置灰 + 原因，直接 POST 403）。Controller 方法级 `@PreAuthorize`，PawCoin 权限在服务层按活动内容判。
- 审计（`AuditActions` 新增 + `admin.audit.action.<CODE>` 四个 properties）：`OPS_ACTIVITY_CREATED`、`OPS_ACTIVITY_UPDATED`、`OPS_ACTIVITY_PUBLISHED`、`OPS_ACTIVITY_DRAFT_DELETED`、`OPS_ACTIVITY_ENDED_EARLY`、`OPS_ACTIVITY_USER_STOPPED`、`OPS_ACTIVITY_GRANTS_RETRIED`、`OPS_ACTIVITY_RANK_EXCLUDED`、`OPS_ACTIVITY_RANK_RESTORED`、`OPS_ACTIVITY_RANK_GRANTED`。
- 文案 `admin.v150.activity.*`，PRD 附录 A.2 逐条照抄（中 / EN / ID，默认 `messages.properties` = EN）；附录没有的（失败原因码、不发放原因「作者已注销 / 停用」、用户券来源「运营活动」）按 Q-7 默认写，Completion Notes 标「待产品复核」。

### OA-21 待办中心「排名待发放」
- 计数 = `grant_mode = 'RANKING' AND rank_state = 'READY' AND rank_built_at < now − 7 天`。
- `AdminPageCatalog` 新页面 key `ops-activity-ranking`（见 §1 #11）；`NavBadgeService.QUEUES` 末尾追加；`supplierFor` 加分支调 `OpsActivityAdminQuery.overdueRankingCount()`；`RELATED_GROUPS` 加 `Set.of("ops-activity-ranking")`。
- `T/fragments/nav.html`：「商城待办」两项之后，照同样写法加小标题 `admin.v150.activity.nav.opsTasks`「运营待办」+ 跳转项「排名待发放 ↗」（角标 oob 填入）；只对有 `growth.config_edit` 的人显示。

### OA-22 总开关
`growth_rules.OPS_CAMPAIGN.enabled = false` → 预留不做（OA-5 第 1 条）、执行抛 `CAMPAIGN_DISABLED`（记失败、可重发）、确认发放 422「运营活动规则已停用」（复用 `admin.err.growth.campaignDisabled`）。后台活动页签顶部在关闭时显示一条黄条（文案用 PRD A.2 外的新 key，Q-7）。

## 3. 数据（迁移原文要点）

> 版本号已预分配（§7），只能用 `V20261115_06xx`。整数 `INT`、金额 `BIGINT`，**禁止 SMALLINT**；时间 `TIMESTAMPTZ`（UTC）。新表 FK 指向会被测试 `deleteAll` 的表时一律 `ON DELETE CASCADE` / `SET NULL`。`user_id` 照 `entitlement_credits` / `keepsake_purchases` 不加 FK（奖励流水，注销只匿名化 users 行，记录保留）。

### 3.1 `V20261115_0600__create_ops_activities.sql`（Story 1-1）

```sql
CREATE TABLE ops_activities (
    id                    BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_token          VARCHAR(32)  NOT NULL,
    name_id               VARCHAR(40)  NOT NULL,
    name_en               VARCHAR(40)  NOT NULL,
    internal_note         VARCHAR(200) NULL,
    action                VARCHAR(16)  NOT NULL,
    grant_mode            VARCHAR(16)  NOT NULL,
    content_types         JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- 只发帖：["DAILY","GROWTH_MOMENT","KNOWLEDGE"] 子集
    reward                JSONB        NULL,                            -- INSTANT 每份奖励
    rank_tiers            JSONB        NULL,                            -- RANKING 档位
    per_user_daily_limit  INT          NULL,
    per_user_total_limit  INT          NULL,
    total_limit           INT          NULL,
    granted_count         INT          NOT NULL DEFAULT 0,
    start_at              TIMESTAMPTZ  NOT NULL,
    end_at                TIMESTAMPTZ  NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    published_at          TIMESTAMPTZ  NULL,
    ended_early_at        TIMESTAMPTZ  NULL,
    rank_state            VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    rank_built_at         TIMESTAMPTZ  NULL,
    rank_granted_at       TIMESTAMPTZ  NULL,
    rank_batch_no         VARCHAR(32)  NULL,
    created_by            BIGINT       NOT NULL,                        -- admin_accounts.id，不加 FK
    updated_by            BIGINT       NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_activities_public_token UNIQUE (public_token),
    CONSTRAINT ck_ops_activities_action CHECK (action IN ('POST', 'LIKE', 'COMMENT')),
    CONSTRAINT ck_ops_activities_mode CHECK (grant_mode IN ('INSTANT', 'RANKING')),
    CONSTRAINT ck_ops_activities_ranking_post CHECK (grant_mode = 'INSTANT' OR action = 'POST'),
    CONSTRAINT ck_ops_activities_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_ops_activities_rank_state CHECK (rank_state IN ('NONE', 'READY', 'EMPTY', 'GRANTED')),
    CONSTRAINT ck_ops_activities_window CHECK (end_at > start_at),
    CONSTRAINT ck_ops_activities_shape CHECK (
        (grant_mode = 'INSTANT' AND reward IS NOT NULL AND rank_tiers IS NULL AND per_user_total_limit IS NOT NULL)
        OR (grant_mode = 'RANKING' AND rank_tiers IS NOT NULL AND reward IS NULL
            AND per_user_daily_limit IS NULL AND per_user_total_limit IS NULL AND total_limit IS NULL)),
    CONSTRAINT ck_ops_activities_limits CHECK (
        (per_user_total_limit IS NULL OR per_user_total_limit BETWEEN 1 AND 10000)
        AND (per_user_daily_limit IS NULL OR (per_user_daily_limit >= 1 AND per_user_daily_limit <= per_user_total_limit))
        AND (total_limit IS NULL OR total_limit BETWEEN 1 AND 1000000)
        AND granted_count >= 0),
    CONSTRAINT ck_ops_activities_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX ix_ops_activities_match ON ops_activities (action, start_at, end_at)
    WHERE status = 'PUBLISHED' AND grant_mode = 'INSTANT';
CREATE INDEX ix_ops_activities_rank_due ON ops_activities (end_at)
    WHERE status = 'PUBLISHED' AND grant_mode = 'RANKING' AND rank_state = 'NONE';
CREATE INDEX ix_ops_activities_created ON ops_activities (created_at DESC, id DESC);

CREATE TABLE ops_activity_topics (
    activity_id BIGINT NOT NULL REFERENCES ops_activities (id) ON DELETE CASCADE,
    topic_id    BIGINT NOT NULL REFERENCES topics (id),
    CONSTRAINT pk_ops_activity_topics PRIMARY KEY (activity_id, topic_id)
);
CREATE INDEX ix_ops_activity_topics_topic ON ops_activity_topics (topic_id);

CREATE TABLE ops_activity_grants (
    id                    BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    activity_id           BIGINT       NOT NULL REFERENCES ops_activities (id) ON DELETE CASCADE,
    user_id               BIGINT       NOT NULL,
    trigger_kind          VARCHAR(16)  NOT NULL,
    post_id               BIGINT       NULL,                 -- 发帖 = 该帖；点赞 / 评论 = 被互动的帖；排名 = 获奖帖
    comment_id            BIGINT       NULL,                 -- 只评论
    rank                  INT          NULL,                 -- 只排名
    idem_key              VARCHAR(80)  NOT NULL,
    occurred_at           TIMESTAMPTZ  NOT NULL,             -- 行为发生时刻（排名 = 确认发放时刻）
    wib_date              DATE         NOT NULL,             -- occurred_at 的 WIB 日（每人每天上限）
    reward                JSONB        NOT NULL,             -- 应发快照
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    fail_reason           VARCHAR(32)  NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    exp_granted           INT          NOT NULL DEFAULT 0,
    exp_reclaimed         INT          NOT NULL DEFAULT 0,
    pawcoin_granted       BIGINT       NOT NULL DEFAULT 0,
    coupon_outcome        VARCHAR(16)  NULL,
    user_coupon_id        BIGINT       NULL REFERENCES user_coupons (id) ON DELETE SET NULL,
    ktp_credits           INT          NOT NULL DEFAULT 0,
    tailsonality_credits  INT          NOT NULL DEFAULT 0,
    gift_credits          INT          NOT NULL DEFAULT 0,
    notified_at           TIMESTAMPTZ  NULL,
    sent_at               TIMESTAMPTZ  NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_activity_grants_idem UNIQUE (idem_key),
    CONSTRAINT ck_ops_activity_grants_trigger CHECK (trigger_kind IN ('POST', 'LIKE', 'COMMENT', 'RANK')),
    CONSTRAINT ck_ops_activity_grants_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'VOID')),
    CONSTRAINT ck_ops_activity_grants_coupon CHECK (coupon_outcome IS NULL
        OR coupon_outcome IN ('ISSUED', 'SKIPPED_STOCK', 'SKIPPED_LIMIT', 'SKIPPED_INVALID')),
    CONSTRAINT ck_ops_activity_grants_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_ops_activity_grants_reclaim CHECK (exp_reclaimed >= 0 AND exp_reclaimed <= exp_granted),
    CONSTRAINT ck_ops_activity_grants_amounts CHECK (exp_granted >= 0 AND pawcoin_granted >= 0
        AND ktp_credits >= 0 AND tailsonality_credits >= 0 AND gift_credits >= 0 AND attempts >= 0),
    CONSTRAINT ck_ops_activity_grants_rank CHECK ((trigger_kind = 'RANK') = (rank IS NOT NULL))
);
CREATE INDEX ix_ops_activity_grants_activity_created ON ops_activity_grants (activity_id, created_at DESC, id DESC);
CREATE INDEX ix_ops_activity_grants_activity_user ON ops_activity_grants (activity_id, user_id, wib_date);
CREATE INDEX ix_ops_activity_grants_activity_status ON ops_activity_grants (activity_id, status);
CREATE INDEX ix_ops_activity_grants_post ON ops_activity_grants (post_id, trigger_kind) WHERE post_id IS NOT NULL;
CREATE INDEX ix_ops_activity_grants_pending ON ops_activity_grants (created_at) WHERE status = 'PENDING';

CREATE TABLE ops_activity_user_stops (
    activity_id        BIGINT       NOT NULL REFERENCES ops_activities (id) ON DELETE CASCADE,
    user_id            BIGINT       NOT NULL,
    reason             VARCHAR(200) NOT NULL,
    operator_admin_id  BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ops_activity_user_stops PRIMARY KEY (activity_id, user_id)
);

CREATE TABLE ops_activity_notify_days (
    activity_id  BIGINT      NOT NULL REFERENCES ops_activities (id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL,
    wib_date     DATE        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_ops_activity_notify_days PRIMARY KEY (activity_id, user_id, wib_date)
);

CREATE TABLE ops_activity_rank_entries (
    id                BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    activity_id       BIGINT       NOT NULL REFERENCES ops_activities (id) ON DELETE CASCADE,
    user_id           BIGINT       NOT NULL,
    post_id           BIGINT       NOT NULL,                 -- 不加 FK（帖子软删，名单要留痕）
    post_created_at   TIMESTAMPTZ  NOT NULL,
    base_order        INT          NOT NULL,                 -- 生成时的先后（1 起连续），之后不变
    rank              INT          NULL,                     -- 当前名次；剔除 = NULL
    score             INT          NOT NULL,
    like_users        INT          NOT NULL,
    comment_users     INT          NOT NULL,
    collect_users     INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reason            VARCHAR(200) NULL,                     -- 剔除原因（运营填）或不发放原因码
    excluded_by       BIGINT       NULL,
    excluded_at       TIMESTAMPTZ  NULL,
    grant_id          BIGINT       NULL REFERENCES ops_activity_grants (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_activity_rank_entries_user UNIQUE (activity_id, user_id),
    CONSTRAINT uq_ops_activity_rank_entries_order UNIQUE (activity_id, base_order),
    CONSTRAINT ck_ops_activity_rank_entries_status CHECK (status IN ('PENDING', 'EXCLUDED', 'NOT_GRANTED', 'GRANTED')),
    CONSTRAINT ck_ops_activity_rank_entries_excluded CHECK ((status = 'EXCLUDED') = (rank IS NULL)),
    CONSTRAINT ck_ops_activity_rank_entries_counts CHECK (score >= 0 AND like_users >= 0
        AND comment_users >= 0 AND collect_users >= 0 AND base_order >= 1)
);
CREATE INDEX ix_ops_activity_rank_entries_rank ON ops_activity_rank_entries (activity_id, rank);
CREATE INDEX ix_ops_activity_rank_entries_post ON ops_activity_rank_entries (post_id) WHERE status = 'PENDING';
```
文件头注释：六张表只经 `B/opsactivity/` 写（守卫测试）；注销不删（奖励流水，users 行匿名化）；`granted_count` 只经条件 UPDATE 改；幂等键格式见 `GrowthIdemKeys.activity*`；显示状态派生不落库（OA-3）。

### 3.2 `V20261115_0605__growth_ledger_activity_id.sql`（Story 1-2）

```sql
ALTER TABLE growth_ledger ADD COLUMN activity_id BIGINT NULL REFERENCES ops_activities (id) ON DELETE SET NULL;
CREATE INDEX ix_growth_ledger_activity ON growth_ledger (activity_id) WHERE activity_id IS NOT NULL;
COMMENT ON COLUMN growth_ledger.activity_id IS 'V1.5.0 运营活动规则引擎：活动发放（及其扣回）流水的活动 id；rule_code 仍为 OPS_CAMPAIGN，note 存印尼语名（旧版 App 显示用）';
```
`GrowthLedgerEntry` 实体加 `@Column(name = "activity_id") private Long activityId;`（只读映射）。

### 3.3 `V20261115_0610__user_coupons_activity_source.sql`（Story 1-2）

```sql
-- 🔴 全量重建共享 CHECK：当前树里 ck_user_coupons_source 只在 V20261105_1615 定义过一次（5 值），
--    照抄全集 + ACTIVITY = 6 值。开工当天 grep 全部迁移 + git fetch 远端分支核对，若有更晚的重建以最晚那条为准再加。
ALTER TABLE user_coupons DROP CONSTRAINT ck_user_coupons_source;
ALTER TABLE user_coupons ADD CONSTRAINT ck_user_coupons_source
    CHECK (source IN ('NEWBIE', 'CLAIM', 'CODE', 'BANNER', 'REISSUE', 'ACTIVITY'));
ALTER TABLE user_coupons ADD COLUMN activity_id BIGINT NULL REFERENCES ops_activities (id) ON DELETE SET NULL;
ALTER TABLE user_coupons ADD CONSTRAINT ck_user_coupons_activity
    CHECK (source <> 'ACTIVITY' OR activity_id IS NOT NULL);
CREATE INDEX ix_user_coupons_activity ON user_coupons (activity_id) WHERE activity_id IS NOT NULL;
```
`UserCoupon` 实体加 `activityId`（只读映射）。

## 4. API 契约

### 4.1 App（全部 `role=USER`，只取 JWT `sub`；无新增端点）

| 接口 | 变化 |
|---|---|
| `GET /api/v1/me/growth/ledger`、等级页预览 | 结构不变；`ruleCode = OPS_CAMPAIGN` 且有活动 id 的行（含扣回行），`note` = 按收件人语言的活动名（OA-19） |
| PawCoin 余额与流水（`PawCoinQueryService.view` 对应的现有接口） | `items[]` 末尾加可选 `refLabel: string`：仅 `refType = "ACTIVITY"` 时下发，= 按收件人语言的活动名；其他行不下发该键 |
| `GET /api/v1/me/notifications`（通知中心，现有） | 新出现两类：`type = OPS_ACTIVITY_REWARDED`（`deepLinkType` 同名，`targetRef = WALLET \| COUPONS`，`title / body` 为后端按语言渲染的定稿文案）；`type = OPS_ACTIVITY_RANK_WON`（`targetRef = 帖子 id`）|
| 推送 payload（现有） | `OPS_ACTIVITY_RANK_WON`：标题正文 = 通知落库文案、`deepLinkType = OPS_ACTIVITY_RANK_WON`、`targetRef = 帖子 id`；到账类不推送 |

App 路由：`OPS_ACTIVITY_REWARDED` → `targetRef == "COUPONS"` ? `/me/coupons` : `/me/pawcoin`（在 `pushPayloadToLocation` 的「无 targetRef 短路」之前）；`OPS_ACTIVITY_RANK_WON` → `/content/{id}`（非数字落通知中心）。`kPushDirectTypes` 加 `OPS_ACTIVITY_RANK_WON`；通知中心点开获奖条目先走 `_postGone` 探帖（已删 → toast `detailGoneTitle`，同追更宠物）。

### 4.2 后台（Thymeleaf + htmx，均在 `/admin/growth` 下）

| 路由 | 权限 | 说明 |
|---|---|---|
| `GET /admin/growth?tab=activities&status=&q=&page=` | view | 页签（列表片段 `GET /admin/growth/activities/list` 供筛选刷新） |
| `GET /admin/growth/activities/new/drawer`、`GET …/{token}/drawer` | edit / view | 新建 / 编辑或只读抽屉 |
| `GET /admin/growth/activities/coupons?q=` | edit | 可选券下拉（OA-11 第 1 条）|
| `GET /admin/growth/activities/topics?q=` | edit | 官方 ACTIVE 话题候选 |
| `POST /admin/growth/activities`（草稿新建）、`POST …/{token}`（保存） | edit | 422 回抽屉错误槽；未开始的保存先回确认弹层（diffs） |
| `POST …/publish/confirm`、`POST …/{token}/publish` | edit（+ PawCoin 时 grant_pawcoin） | D9 / D10 |
| `POST …/{token}/delete/confirm`、`…/delete` | edit | 只草稿 |
| `POST …/{token}/end-early/confirm`、`…/end-early` | edit | E6 |
| `GET /admin/growth/activities/{token}` | view | 详情子页（E / F）|
| `GET …/{token}/grants?status=&q=&page=`、`GET …/{token}/grants.csv?status=` | view | 明细片段 / CSV |
| `POST …/{token}/grants/retry/confirm`、`…/grants/retry`（`ids`） | edit（+ grant_pawcoin） | E3 / E4 |
| `POST …/{token}/stops/confirm`、`…/stops`（`userId`, `reason`） | edit | E5 |
| `POST …/{token}/ranking/{entryId}/exclude/confirm`、`…/exclude`（`reason`）、`…/restore` | edit | F3 / 撤销 |
| `POST …/{token}/ranking/grant/confirm`、`…/ranking/grant` | edit（+ grant_pawcoin） | F4 |

错误：字段校验 422（错误槽）；权限 403；状态不允许（如进行中改话题、非草稿删除、名单未就绪）409 + toast 文案键。

## 5. 契约使用要点（gift-pack 4-1）
- 只调 `EntitlementCreditService.grant(GrantCommand)`，工厂 `GrantCommand.activity(...)`，**不调 `consume` / `summary`**、不直写 `entitlement_*` 表（4-1 守卫测试也会拦）。
- 发奖事务 2 内调用（契约 `REQUIRED`）；同一份奖励多类型共用一个 `idemKey`；重发同键 `replayed = true` 视作成功。
- `GrantCommand.activity` 的 `nameId / nameEn` 传活动名（≤ 40，契约上限 80）。

## 6. 旧版 App 兼容（≤ 1.4.x）

| 改动 | 旧版表现 | 保证方式 |
|---|---|---|
| 活动 EXP | EXP 明细「Event · {名称}」（`note`，现在按语言） | `GrowthLedgerRow` 结构不变 |
| 活动 PawCoin | 流水显示「Bonus」（`refType = ACTIVITY` 落到 `BONUS` 标签） | `refLabel` 为新增可选键，旧版忽略 |
| 活动优惠券 | 「我的优惠券」照常显示（不显示来源）| `CouponView` 不变 |
| 活动权益次数 | 旧版没有「Koin & Jatah Saya」免费次数区，看不到也用不了（KTP / 性格测试只能付费）| gift-pack 已接受（其架构 §6）|
| 到账 / 获奖通知 | 未知类型：通知中心显示系统兜底标题，点开留在通知中心 | App 现有 default 分支 |
| 获奖推送 | 推送标题正文是后端渲染文案；点开进通知中心 | `send` 现有链路 |
| 后台 | 无关 | — |

**前后端同批**：新版 App 依赖本线的通知类型路由与 `refLabel`；后端可先上线，旧版无影响。

## 7. 迁移清单（预分配，日期 20261115，HHMM 0600–0659，不得另取）

| 版本 | 文件 | Story |
|---|---|---|
| `V20261115_0600` | `__create_ops_activities.sql` | 1-1 |
| `V20261115_0605` | `__growth_ledger_activity_id.sql` | 1-2 |
| `V20261115_0610` | `__user_coupons_activity_source.sql` | 1-2 |
| `V20261115_0615` ~ `_0659` | 备用（实现中需补列时按序取，回写本表与 sprint-status）| — |

本线**不改** `ck_notifications_type`（地基所有）、不改 `ck_pawcoin_txn_type`、不改任何 `entitlement_*` 约束。本线**负责重建**：`ck_user_coupons_source`（0610），DROP + ADD 重列全集，值取当前树最后一条定义它的迁移。

## 8. 与 gift-pack 4-1 契约的缺口（只登记，不改契约；按下列做法绕开）

| # | 缺口 | 本线做法 |
|---|---|---|
| G-1 | 契约没有「只校验不写」的资格检查方法；`grant` 校验失败抛 `AppException`，在 `REQUIRED` 下会把调用方事务标成只回滚 | 本线资格预检（OA-6）把 4-1 的拒绝口径（注销、注销受理中、虚拟号）全并进来，可预见的拒绝在调 `grant` 前就拦掉；仍抛错时整份奖励回滚记「发放失败」，与 PRD「任何一项出错整份重试」一致 |
| G-2 | `quantity` 上限 1..100，PRD 未给活动发次数的上限 | 后台表单与服务端校验把三类次数限制在 1–100（OA-4 第 4 条）；需要更大值时另提契约变更 |
| G-3 | 契约不暴露「某活动发出了多少次数」的查询 | 后台合计读本线 `ops_activity_grants` 的 `ktp_credits / tailsonality_credits / gift_credits`，不读 `entitlement_credits` |
| G-4 | 契约有效期固定「到账起 3 个月」，不可按活动配置 | 与 PRD §3.3「沿用同一规则」一致，无需改 |

## 9. 跨线交界

- **地基（Story 0.1，已在 dev）**：`NotificationType.OPS_ACTIVITY_REWARDED / OPS_ACTIVITY_RANK_WON`、`ck_notifications_type`、`PushCategory` —— 只用，不改。
- **② gift-pack**：4-1 契约（§5）；6-3 改 `pawcoin_page.dart`（页面结构、Tab），本线 Story 3-2 只改 `_txnLabel` 函数与 `PawCoinTxnItem` 模型，**不动** `_LedgerRow` / 页面结构；`notification_center_page.dart` 里 6-3 给 `PACK_CREDIT_EXPIRING` 加的读后端文案分支与本线两类新类型是**同一行**，合并时取并集。
- **⑤ likes**：`ContentLikedEvent.automated`（OA-5 / §1 #4）；`GrowthActivityListener` 本线不改。
- **③ onboarding-recall / ① health**：`kPushDirectTypes` 三方各加各的值，合并取并集，本线只加 `OPS_ACTIVITY_RANK_WON`；`NotificationService.sendRendered`（③）本线不用。
- **④ 物种**：无交界。

## 10. 验证层级约定

- **L0**（云端可跑）：`./mvnw -B clean package -DskipTests` + 纯单元测试（Mockito / 纯函数，不起 Spring / DB / Redis）；迁移文本测试（照 `PetSlotMigrationTextTest` 读迁移文本：CHECK 全集、DROP + ADD、版本号、无 SMALLINT）；`AdminMessagesParityTest`、`AuditActionLabelsTest`；`flutter analyze` + `flutter test`。
- **L1**（本地 Docker）：`*IntegrationTest` / `ApiIntegrationTest` 子类、Flyway 真跑、`ddl-auto=validate`、并发（总份数、每人上限、同键重发）、排名 SQL、后台渲染烟测 `AdminPagesRenderSmokeTest`。
- **L2**（本地真机 / 模拟器 / 真凭证）：通知中心两类条目视觉、获奖推送点开直达、PawCoin 记录、后台 33 屏还原度 ≥ 95%、三语切换。
