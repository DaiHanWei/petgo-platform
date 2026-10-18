---
title: "V1.4.0 tailrent-level 主题 · 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
completedAt: '2026-10-18'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/1-4-0Tailrent等级prd.md
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-admin-1-4-0-tailrent-level-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md            # ① 节
  - _bmad-output/planning-artifacts/architecture.md                   # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md    # 「2026-10-18 V1.4.0 四条线共用地基」节
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-10-18'
version: v1.4.0
theme: tailrent-level
status: complete
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.0 @ 81005da7（= main 5ec8a2e4 + 地基 Story 0.1）
---

# V1.4.0 tailrent-level 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照代码。**权威顺序**：`decision-log.md`（① 节）> `1-4-0Tailrent等级prd.md` > 本文件 > UI 稿。
> 地基 Story 0.1 已落地的部分（两条共享约束、`GrowthQueryService` 签名、4 个跨分支事件类、后台抽屉页签头）**不在此重复设计**，只引用。

## 0. 项目上下文分析（2026-10-18 核实 dev_1.4.0 @ 81005da7）

### 0.1 范围
- **FR-143 成长值与等级**：14 条入账规则（含每日上限、被动三项合计 50）、消费返成长值、扣回、防刷、等级曲线（可追加）、升级反馈、存量补算、虚拟号展示等级、注销清理。
- **FR-144 展示与权益**：头像角标 + 三档头像框（Feed / 详情 / 评论 / 主页 / 关注条 / 「我的」页胶囊）、等级页、EXP 明细页、佩戴规则、Lv15+ 露出加权 ×1.05。
- **AB-32** 成长体系配置（规则 / 消费换算 / 等级表追加与重算 + 受影响人数确认）；**AB-33** 用户成长值页签、手动调整、单个与批量运营发放、虚拟号展示等级。
- 埋点 5 个。UI 稿 App 30 屏 + 后台 22 屏。**复杂度：高**（全栈：后端 + 后台 + App；跨 7 类领域事件、支付链路、Feed 排序、注销链路）。

### 0.2 已具备（地基 Story 0.1）
`GrowthQueryService` 签名 · `GrowthLevel` / `GrowthTier` · `GrowthLevelChangedEvent` · `GiftSentEvent` · `ContentCollectedEvent` / `ContentUncollectedEvent` · `NotificationType.GROWTH_LEVEL_UP` · `PaymentPurpose.PET_SLOT_RENT` / `GIFT` · 抽屉「成长值」空页签。

### 0.3 现状要点（与 PRD 不一致或需补的）
1. **每日登录**：`UserRepository.recordActiveDay`（`ON CONFLICT DO NOTHING`，返回 1 = 当天首次）的返回值被 `AccountQueryService.touchLastActive`（:367，REQUIRES_NEW）丢弃；链路在 `UserActivityFilter`（:55）finally 里、异常全吞。需改返回 boolean 并发事件。
2. **点赞**：`LikeService.unlike`（:56）直接删行、无事件；取消后再点赞会再发 `ContentLikedEvent`。表为 `content_likes`（`uq_content_likes_post_user`）。
3. **评论**：`ContentCommentedEvent(postId, commentId, commenterId, contentAuthorId, parentAuthorId, createdAt, parentCommentId, body)` 唯一发布点 `CommentService.publishCommented`（:295），由 `approveComment` 调用（自动过审 `CommentModerationListener`:53 / 人工 `ManualReviewService`:119）。自评照常发；回复复用同一事件（`parentAuthorId` 非空）。
4. **发帖**：`ContentPublishedEvent(postId, authorId, type, petId, authorGrowthMomentCount, visibility, createdAt)`；发布点 `ContentService`:603 / `approveReview`:347 / `publishTrusted`:682。**同一帖可发两次**（举报预处置 `applyReportHoldIfPublished` 改回审核中后再通过）→ 入账须按 postId 幂等。Moment / Tips 的 petId 恒 null。
5. **违规下架** `ContentRemovedEvent(postId, authorId, removedAt)`，仅 `ADMIN_TAKEDOWN`（`ContentService`:140）；`restore()`（:322）不发事件。
6. **里程碑** `MilestoneCompletedEvent(ownerId, code, level, title, source)` 无 petId（发布处 `MilestoneCompletionService`:136 有 petProfileId）；监听参照写法 `MilestoneAutoCompleteListener`（`@Async` + `@TransactionalEventListener` AFTER_COMMIT + 方法 `@Transactional`，幂等靠唯一约束 + 捕获 `DataIntegrityViolationException`）。**打卡** `PlaceCheckedInEvent(userId, placeToken, placeType, isNewStamp, stampCount, petIds)` 无 checkinId。
7. **支付**：`PaymentIntentPaidEvent(intentId, publicToken, userId, purpose, channel, amount, currency)` 唯一发布点 `PaymentIntentService.applyCallback`:327。纯 PawCoin 共 6 个 `PawCoinWalletService.debit(userId, coins, SPEND, refType, refId, idemKey)` 调用点，refType 与 `PaymentPurpose` 名一一对应；问诊（`ConsultPayService`:130）、AI 解锁（`AiUnlockService`:132）纯币**无事件**；KTP 高清发 `IdHdPawcoinUnlockedEvent`、keepsake 发 `KeepsakeUnlockedEvent`（QRIS 时与 `PaymentIntentPaidEvent` 重复）；MIXED（仅电商）coin 段既 debit（`CheckoutService.settlePawCoinSegment`:307）又计入 `intent.amount`；`ShopOrderPaidEvent(shopOrderId, orderToken)` 无 userId / 金额。1 PawCoin = Rp1（代码按 IDR 价直接扣币）。
8. **退款**：无任何退款事件。真实退钱点 **5 处**：`RefundService.refundToPawCoin`（:251）、`RefundService.payoutRefund`（:358）、`RefundExecutionService.execute`（:94，TO_BANK 分支置 REFUNDED 时钱尚未打出）、`AdminShopOrderExceptionService.refundCoinSegment`（:331）、`ConsultSuspensionService.refundByChannel`（:110）。`AdminTicketRefundService` 不直接退钱（委托 `RefundService`）。
9. **AuthorView**（`auth/dto/AuthorView`）唯一组装 `AccountQueryService.findAuthorViews`（:191）= `basicViews`（:230）+ `attachTags`（:245，批量）；关注列表 / 关注条 / @ 候选走 `findAuthorViewsWithoutTags`（:208）→ 等级须加在 `basicViews` 层。响应 DTO 为拍平字段（`FeedItemResponse` / `ContentDetailResponse` / `CommentResponse` / `SharedPostResponse` / `PlaceCommentResponse` 等），App 无 AuthorView 模型（`FeedItem` / `ContentDetail` / `Comment` / 主页仓储各自拍平）。`/me`：`MeService.getMe` → `UserProfileResponse`；App `UserProfile`（`login_response.dart`）。
10. **Feed**：`FeedRankScorer.finalScore(c, now, p, decay, honor, throttle)`（:77），限流 Map 按 **postId** 键（`RankThrottleService.factorsFor`），加作者系数须改 Scorer 签名与 `FeedRankEngine.Input`。参数在 `feed_rank_config` 单行表，经 `PlatformConfigService.feedRank()` 读、`AdminConfigService.updateFeedRank` 写（diff 进 `config_change_logs`），页面 `AdminAlgoParamController`；加一个参数改 5 处（迁移列 + CHECK、`FeedRankForm`、`FIELDS`、`ERROR_FIELDS`、模板）。
11. **每日上限并发**：稳的先例 `share_reward_quotas`（`(user_id, period)` 唯一 + `insertIfAbsent` ON CONFLICT + 条件 UPDATE `tryGrant`）；`id_card_share_rewards` 先 count 后判断，并发不严格。WIB：`shared/schedule/ScheduleWindow.WIB`（另有 3 处私有重复定义）。
12. **可复用组件**：后台赠送 PawCoin 行内表单（`AdminUserController`:151 / `AdminUserService.grantPawCoin`:85）、服务端确认弹窗 `AdminConfirmModal` + `fragments/confirm-modal.html`、algo-params 原生 diff 确认（`admin-core.js`:84）、种子批量预览不阻塞整批（`SeedBatchPublishService`）、虚拟号抽屉「账号物种定位」卡、权限码 `AdminPermissions`（须入 `GROUPS` 且在 `AdminPageCatalog` 恰好归属一页，新码不预授）、审计 `AdminAuditService.record`。App：`StartupPopupLedger`（枚举 `StartupPopupKind`）、`ops_popup_dialog`、`showMilestoneCelebration`（不复用，仅参照）、`disableAnimations` 先例 5 处、头像组件 `LetterAvatar`（12 处）/ `InitialAvatar`（5 处）/ 主页 `public_profile_page.dart`:270、关注条 `following_strip.dart`:112。通知：`NotificationService.send` / `sendWithCopy`（推送前跳过虚拟号），参照 `MilestoneNotifyListener`；App 通知中心 `_iconStyle` / `_typeLabel` / `_typeBody`、深链 `deep_link_routes.dart`。
13. **注销**：`AccountDeletionService.execute`（:143）非事务、各模块自开事务；新模块插在 tailsonality 之后、PawCoin 作废之前。

### 0.4 横切关注点
幂等（入账 / 扣回 / 批量发放 / 消费去重）· WIB 切天 · 每日上限与被动合计的并发正确性 · 扣回按原流水反向定位 · 等级变化的连锁反应（通知、庆祝页、头像框自动换戴、② 重算名额）· 列表批量取等级禁 N+1 · 注销清理 · 后台审计 · 不引 MQ / 缓存，只用 `@Async` + DB 状态机。

### 0.5 待产品拍板（第 4 步逐条给建议）
1. 违规下架后被恢复（`restore`），扣回的发帖成长值是否补回？
2. 回复别人评论时，`COMMENT_RECEIVED` 给帖子作者还是被回复者？
3. PRD 未列的两处 PawCoin 退款（电商异常单、问诊中断）是否同样「退款扣回」？
4. 电商退到银行：状态置 REFUNDED 即扣，还是钱实际打出后再扣？

### 0.6 技术基线（沿用，不引入新依赖）
- **后端**：Spring Boot 4.0.6 / Java 21 / PostgreSQL + Flyway（时间戳号）/ Redis 仅既有限流与幂等用途；异步只用 `@Async` + DB 状态机，**不加 MQ / 缓存层**；`ddl-auto=validate`。
- **后台**：沿用 Thymeleaf + htmx 管理后台与 0.3 第 12 条的现成组件。
- **App**：Flutter 3.44 / Riverpod / go_router / dio / intl；三档头像框代码绘制（渐变描边 + `SweepGradient`），**不引入动画 / 插画依赖**。
- **新包**：后端在既有 `com.tailtopia.growth` 下展开；App 新建 `features/growth/`。

### 0.7 产品拍板（2026-10-18，Dai）
- **P-1** 违规下架后被恢复（`restore`）**不补回**发帖 EXP；需要时运营在后台手动调整。
- **P-2** 回复评论时 `COMMENT_RECEIVED` **只给帖子作者**（`contentAuthorId`）；评论人 = 帖子作者时两边都不给（PRD 已定）。
- **P-3** PRD 未列的两处 PawCoin 退款（`AdminShopOrderExceptionService.refundCoinSegment`、`ConsultSuspensionService.refundByChannel`）**同样扣回**——凡真实退钱处都发退款事件。
- **P-4** 电商退到银行：退款记录置 `REFUNDED` 时即扣回，不等线下打款。
- **P-6** 存量补算的「历史活跃天数」只能从 `user_active_days` 起始日 **2026-09-25** 算起（该表迁移注明历史无法回填）；**接受此限制**，存量汇总弹窗如实显示天数，不改用代理口径。
- **P-7 官方号（`role = ADMIN`）**：与虚拟号同口径——**不参与成长值、不享任何权益（`getLevel(s)` = 不参与）**，但**显示运营设定的展示等级与对应头像框**（UI 稿 TailUs Lv12）。存量官方号上线时随机 6–8（同虚拟号），运营可改。
- **P-8 升级推送落点**：点击 `GROWTH_LEVEL_UP` 系统推送**直达等级页**（UI 稿 F2），作为「通知类推送落通知中心」（2026-08-07）的单类型例外。
- **P-9 虚拟号对真实用户的互动**：虚拟号点赞 / 评论真实用户时，真实作者**照常得**被赞 / 被评 EXP（受被动合计 50 约束）；虚拟号本身无流水。
- **P-10 无「退款成功」落点的两类退款**（问诊中断走 QRIS 只置退款中、电商异常单整单取消的现金段）：**暂不扣回 EXP**，等级线不改退款流程；该缺口记入待办，由支付链路单独处理。
- **P-5**（技术）`GROWTH_USER_ADJUST` 按 PRD 预授给 `OPS_MANAGER`（SUPER_ADMIN 天然全权），偏离「新权限码不预授」惯例；**实现方式为在 `AdminRole.OPS_MANAGER` 枚举权限集中加码**（该角色权限不在库表，2026-10-18 Story 4.2 核实），不写迁移。`GROWTH_CONFIG_VIEW` / `GROWTH_CONFIG_EDIT` 不预授。

## 1. 数据模型

**AD-1 · 成长值账户 `growth_accounts`。**
- 列：`user_id BIGINT PK FK users(id)`、`lifetime_total BIGINT NOT NULL DEFAULT 0 CHECK (>= 0)`、`balance BIGINT NOT NULL DEFAULT 0`（PRD 预留，1.4.0 不读写）、`level INT NOT NULL DEFAULT 1`、`frame_choice VARCHAR(16) NULL`（NULL = 自动；`NONE` / `BASIC` / `UPGRADED` / `ANIMATED`，CHECK）、`display_level INT NULL`（仅虚拟号）、`celebrate_from_level INT NULL` / `celebrate_to_level INT NULL`（待弹升级庆祝）、`backfill_popup_pending BOOLEAN NOT NULL DEFAULT false`、`version BIGINT NOT NULL DEFAULT 0`、`created_at` / `updated_at TIMESTAMPTZ`。
- 行懒建：首次入账 `INSERT … ON CONFLICT DO NOTHING`；存量真实用户与虚拟号由 AD-13 迁移一次建好。**无账户行 = Lv1、累计 0**。
- `level` 落库（入账、调整、改等级表时同事务重算），读等级不现算。
- 累计不得为负：扣回与运营负向调整都按 `GREATEST(lifetime_total + delta, 0)` 的语义处理；运营负向调整超出累计值时**拒绝**（后台校验提示），扣回则截断到 0 并在流水记实际扣减值。

**AD-2 · 流水 `growth_ledger`（只追加）。**
- 列：`id BIGSERIAL PK`、`user_id BIGINT NOT NULL`、`rule_code VARCHAR(32) NOT NULL`、`delta INT NOT NULL`（带符号，≠ 0）、`ref_type VARCHAR(32) NULL` / `ref_id VARCHAR(64) NULL`（关联对象，见 §5 口径表）、`pet_ids BIGINT[] NULL`（记录全部关联宠物；② 合并前取 `content_posts.pet_id` 单值）、`idem_key VARCHAR(160) NOT NULL UNIQUE`、`reverses_id BIGINT NULL FK growth_ledger(id)`（扣回指向原流水）、`reason VARCHAR(24) NULL`（扣回 / 调整原因码：`REFUND` / `UNLIKE` / `UNCOLLECT` / `TAKEDOWN` / `ADMIN_ADJUST`）、`note VARCHAR(200) NULL`（运营原因或活动名，原样显示）、`operator_admin_id BIGINT NULL`、`batch_no VARCHAR(32) NULL`、`wib_date DATE NOT NULL`、`created_at TIMESTAMPTZ NOT NULL`。
- 索引：`(user_id, created_at DESC, id DESC)`（明细分页）、`(reverses_id)`、`(ref_type, ref_id)`（扣回反查）、`(user_id, rule_code)`（里程碑 5 次上限计数）。
- **不接入 `ledger_entries` 总账**（PRD）。扣回只追加反向流水，**永不改删原流水**。

**AD-3 · 每日额度 `growth_daily_counters`。**
- `PRIMARY KEY (user_id, wib_date, counter_key)`、`used INT NOT NULL DEFAULT 0`。`counter_key` = 主动规则用规则代码（计次数），被动三项共用 `PASSIVE`（计分值）。
- 原子领额（照 `share_reward_quotas`）：`INSERT … ON CONFLICT DO NOTHING` 后 `UPDATE … SET used = used + :grant WHERE … RETURNING used`，`:grant` 先按剩余额度截断。主动规则按次（剩余 0 次即不入账）；**被动合计截断到恰好 50**（最后一笔可能只入一部分），流水记实际入账值。
- **只增不减**：扣回不返还当日额度（PRD）。WIB 日期一律取 `ScheduleWindow.WIB`。

**AD-4 · 配置表（迁移 seed，后台改）。**
- `growth_rules(code VARCHAR(32) PK, points INT NOT NULL, unit_amount INT NULL, daily_cap INT NULL, cap_group VARCHAR(16) NULL, enabled BOOLEAN NOT NULL, updated_at, updated_by)`：14 条规则 + `LAUNCH_BACKFILL` / `ADMIN_ADJUST` 两个系统代码（不可在后台编辑）。`SPEND_DIGITAL`（5 / 1000）、`SPEND_SHOP`（5 / 10000）、`GIFT_SENT`（5 / 1000）、`GIFT_RECEIVED`（4 / 1000）用 `points / unit_amount` 表比例。`cap_group = 'PASSIVE'`（上限 50）挂在三条被动规则上；**组上限存为 `growth_rules` 的伪行 `PASSIVE_GROUP`（`daily_cap = 50`，`points = 0`，不产生入账，后台可改）**（§7 V-1 定）。
- `growth_spend_tiers(purpose VARCHAR(16) PK, tier VARCHAR(16) NOT NULL CHECK IN ('DIGITAL','TOKO','NONE','GIFT'), locked BOOLEAN)`：初始值按 PRD 表；`PAWCOIN_TOPUP=NONE`、`GIFT=GIFT` 锁定。**新增支付用途未登记时按 DIGITAL 处理**（PRD「默认归入数字服务档」）。
- `growth_levels(level INT PK, threshold BIGINT NOT NULL, tier VARCHAR(16) NOT NULL)`：初始 Lv1–20 按 PRD；Lv1 门槛 0 不可改；门槛严格递增、段位单调由服务层校验；只能末尾追加。

## 2. 入账引擎

**AD-5 · 唯一入口 `GrowthLedgerService`。**
- `accrue(AccrualCommand)`：①参与判定（`users.account_type = REAL` 且未注销、未停用）→ ②规则启用？→ ③锁账户行（`SELECT … FOR UPDATE`，懒建）→ ④插流水（幂等键冲突 = 已处理，直接返回）→ ⑤领每日额度（不足则回滚本次插入，见下）→ ⑥更新 `lifetime_total` 与 `level` → ⑦等级变化走 AD-10。同一事务。
- 实现顺序提示：额度判定放在插流水之前、在同一事务内；幂等键冲突与额度不足都以「不入账」结束，不抛给监听方。
- `reverse(ReverseCommand)`：按幂等键或 `(ref_type, ref_id, rule_code)` 找原流水 → 已扣回过（存在 `reverses_id = 原id` 且累计已达原值）即返回 → 插反向流水（幂等键 `REV:{原id}:{来源键}`）→ 更新累计与等级。**只扣原流水实际入账的部分**；原流水不存在（当初因额度未入账）则不扣（AC14）。
- `adjust(AdminAdjustCommand)`：运营调整 / 运营活动（单个），幂等键 `ADMIN:{grantToken}`（同赠送 PawCoin 的 token 写法）。
- 所有监听：`@Async` + `@TransactionalEventListener`（AFTER_COMMIT），方法 `@Transactional`，照 `MilestoneAutoCompleteListener`。失败记 ERROR 日志、不重试（V1 姿态；运营可手动补）。

**AD-6 · 规则 → 事件 → 幂等键（防刷全靠幂等键）。**

| 规则 | 来源事件 | 入账给 | 幂等键 | 关键过滤 |
|---|---|---|---|---|
| `DAILY_LOGIN` | `UserDailyActiveEvent`（新增，AD-7） | 本人 | `DAILY_LOGIN:{u}:{date}` | — |
| `POST_PUBLISHED` | `ContentPublishedEvent` | 作者 | `POST:{postId}` | 私密 Diary 照常；同帖二次发布被键挡住 |
| `COMMENT_POSTED` | `ContentCommentedEvent` | 评论人 | `COMMENT_POSTED:{commentId}` | 评论人 = 帖子作者 → 不给、不占额度 |
| `COMMENT_RECEIVED` | 同上 | **帖子作者**（P-2） | `COMMENT_RECEIVED:{commentId}` | 同上 |
| `LIKE_GIVEN` | `ContentLikedEvent` | 点赞人 | `LIKE_GIVEN:{liker}:{postId}` | 终身一次（键不含时间）；自赞无事件 |
| `LIKE_RECEIVED` | 同上 | 作者 | `LIKE_RECEIVED:{liker}:{postId}` | 同上；作者为虚拟号 → 不入账 |
| `COLLECT_RECEIVED` | `ContentCollectedEvent`（④） | 作者 | `COLLECT_RECEIVED:{collector}:{postId}` | 收藏人 = 作者 → 不入账 |
| `PLACE_CHECKIN` | `PlaceCheckedInEvent`（补 `checkinId`） | 本人 | `CHECKIN:{checkinId}` | — |
| `MILESTONE_S/M/L` | `MilestoneCompletedEvent`（补 `petId`） | 本人 | `MILESTONE:{u}:{code}:{petId或0}` | 同账号同 code 已有 ≥ 5 条 → 不入账（里程碑照常完成） |
| `GIFT_SENT` / `GIFT_RECEIVED` | `GiftSentEvent`（③） | 送礼人 / 宠物主人 | `GIFT_SENT:{giftOrderId}` / `GIFT_RECEIVED:{giftOrderId}` | 不设上限；按 `totalAmount` 四舍五入 |
| `SPEND_DIGITAL` / `SPEND_SHOP` | AD-8 | 付款人 | AD-8 | 档位 NONE / GIFT 不入账 |
| `OPS_CAMPAIGN` / `ADMIN_ADJUST` | 后台 | 目标用户 | `ADMIN:{token}` / `BATCH:{batchNo}:{u}` | 虚拟号排除 |
| `LAUNCH_BACKFILL` | AD-13 迁移 | 存量用户 | `BACKFILL:{u}` | — |

- 扣回：取消点赞（`ContentUnlikedEvent`，新增）→ 两条 `LIKE_*` 各按原键反查扣回；取消收藏（`ContentUncollectedEvent`，④）→ `COLLECT_RECEIVED`；违规下架（`ContentRemovedEvent`）→ 只扣 `POST:{postId}`；退款（AD-9）→ 对应消费流水。作者自删帖、评论被删**不扣**（PRD）。P-1：`restore` 不补回。

**AD-7 · 需补的事件（属本主题改动）。**
- `UserDailyActiveEvent(userId, wibDate)`：`AccountQueryService.touchLastActive` 改为返回 `boolean`（`recordActiveDay` 返回 1），`UserActivityFilter` 在 finally 中仅当为 true 时发布（无事务上下文 → 监听用 `@Async @EventListener`）。异常仍吞掉，不影响请求。
- `ContentUnlikedEvent(postId, likerId, authorId)`：`LikeService.unlike` 改为按删除行数判断，真删到行且非自赞时发布。
- `PlaceCheckedInEvent` 末尾追加 `long checkinId`；`MilestoneCompletedEvent` 末尾追加 `Long petId`（账号级里程碑为 null）。**只追加字段**，同步修所有构造点与测试。
- `ShopOrderPaidEvent` 追加 `long userId, long paidTotal`（现金 + PawCoin 实付总额，IDR）。

**AD-8 · 统一消费信号 `GrowthSpendListener`。**
- **现金**：监听 `PaymentIntentPaidEvent`，`purpose ∉ {SHOP_ORDER, PAWCOIN_TOPUP, GIFT}` → 金额 = `amount`，幂等键 `SPEND:PI:{intentId}`，`ref = (purpose, 业务单号)`。
- **纯 PawCoin**：`PawCoinWalletService.debit` 在 `type = SPEND` 成功扣款后发布 `PawCoinSpentEvent(userId, coins, refType, refId, idempotencyKey)`；监听排除 `refType ∈ {SHOP_ORDER, GIFT}`，金额 = `coins`（1 币 = Rp1），幂等键 `SPEND:COIN:{debit 的 idempotencyKey}`。
- **电商**：监听补字段后的 `ShopOrderPaidEvent`，金额 = `paidTotal`，幂等键 `SPEND:SHOP:{shopOrderId}`——QRIS / MIXED / 纯币三种付法都只经此一处，MIXED 的币段与现金段不会各算一次。
- Keepsake 的 `KeepsakeUnlockedEvent`、KTP 的 `IdHdPawcoinUnlockedEvent` **不监听**（前者 QRIS 时与 `PaymentIntentPaidEvent` 重复）。
- 换算：`round(amount × points / unit_amount)`（HALF_UP），档位读 `growth_spend_tiers`（未登记 = DIGITAL）。
- **`ref` 口径须可被退款反查**：每个用途的 `ref_id` 取「退款事件也拿得到」的业务单号（问诊 = 问诊订单、AI 解锁 = 解锁单、电商 = shopOrderId 等）；现金路径另记 `intentId`。逐用途映射由 story 核对代码后写入 §5 口径表并配测试。

**AD-9 · 退款扣回 `RefundSucceededEvent`。**
- 字段：`refundKey`（退款记录唯一键）、`userId`、`purpose`、`bizRefType`、`bizRefId`、`paymentIntentId`（可空）、`refundAmount`、`originalAmount`。
- 发布点（P-3 / P-4，全部真实退钱处）：`RefundService.refundToPawCoin`、`RefundService.payoutRefund`、`RefundExecutionService.execute`（含 TO_BANK 分支，置 `REFUNDED` 时发）、`AdminShopOrderExceptionService.refundCoinSegment`、`ConsultSuspensionService.refundByChannel`。各在退款状态落库的同一事务内 `publishEvent`，监听 AFTER_COMMIT。
- 扣回额 = `round(原入账 × refundAmount / originalAmount)`；**同一原流水累计扣回 ≤ 原入账**（反查已扣总和后截断）；幂等键 `REV:REFUND:{refundKey}`。送礼不退款，无扣回（PRD）。

## 3. 等级与连锁反应

**AD-10 · 等级变化。**
- `level = max(level) where threshold ≤ lifetime_total`（读 `growth_levels`）。变化时同事务：更新 `level`，发布 `GrowthLevelChangedEvent(userId, old, new)`（升降都发，② 据此重算名额）。
- **只有升级且来源非 `LAUNCH_BACKFILL`** 时：①写 `celebrate_from_level`（已有待弹则保留原 from，只更新 to——连跳多级只弹一次、显示最终等级）、`celebrate_to_level`；②AFTER_COMMIT 发一条 `GROWTH_LEVEL_UP` 通知 + 推送（`NotificationService.sendWithCopy`，深链 → 等级页，参照 `MilestoneNotifyListener`）。降级不通知、不弹窗。
- **头像框读时推导**（不存「当前框」）：可用档 = 由当前等级段位决定（BUDDY → BASIC，GUARDIAN → UPGRADED，STAR → ANIMATED，均含更低档）。`frame_choice = NULL` → 自动取最高可用档（NEWBIE 无框）；`frame_choice` 已选且仍可用 → 用它；已选但因降级不可用 → 退回最高可用档（AC19）。`NONE` = 用户主动不戴。
- 权益判定（② 名额、③ 专属礼物、Feed 加权）一律走 `GrowthQueryService.getLevel(s)`，读 `growth_accounts.level`；替换地基的占位方法体，签名不变。

**AD-11 · 升级庆祝与存量汇总弹窗的投递。**
- 接口 `GET /api/v1/me/growth/popups` → `{ celebration: {fromLevel, toLevel, tierUp, unlockedBenefits[], frameAction} | null, backfill: {level, activeDays, milestoneExp, total} | null }`；`POST /api/v1/me/growth/popups/{kind}/ack`（`kind ∈ celebration | backfill`）清掉对应字段，幂等。
- App 拉取时机：冷启动进首页、回前台、自己完成可能得 EXP 的动作后（发帖成功、评论、点赞、打卡、支付成功——入账是异步的，延迟约 1.5s 拉一次）、前台收到 `GROWTH_LEVEL_UP` 推送。
- 优先级：升级庆祝 > 存量汇总 > 运营开屏弹窗。两者弹出时都登记 `StartupPopupLedger`（`StartupPopupKind` 新增 `growthCelebration` / `growthBackfill`），运营弹窗协调器既有逻辑自动让路。
- 形态由 App 按 `tierUp` 选：段位内 → 卡片（UI 稿 D1 / D7），跨段位 → 浅色全屏（D2–D4）；「戴上新头像框」按钮仅在 `frame_choice` 非空（手动选过）时出现（D3），调用 AD-16 切框接口。

**AD-12 · 改等级表后的批量重算。**
- 预览：保存前用 SQL 按新表计算「升级 N 人 / 降级 N 人」渲染服务端确认弹窗（`AdminConfirmModal`）。
- 执行：新表写入后插 `growth_recalc_jobs(id, status, requested_by, upgraded, downgraded, started_at, finished_at, failure_reason, created_at)`（唯一索引保证同时只有一个任务在跑），`@Async` 执行：**先用 SQL 选出等级会变化的用户，再逐个经 `GrowthLevelChangeHandler`（AD-10 单一连锁出口）重算**——不得用一条 UPDATE 直接改 `level`，否则 handler 判定「无变化」而漏发事件与升级通知（2026-10-18 Story 4.1 核实修正）。状态机 `PENDING → RUNNING → DONE / FAILED`，后台页显示最近一次结果。
- 追加等级（AC9）走同一流程。

**AD-13 · 存量补算（上线一次性，Flyway 迁移内纯 SQL，参照 V77 不发通知）。**
- 对每个真实、未注销用户：`EXP = Σ 已完成里程碑分值（S5 / M15 / L25，同 code 最多 5 次） + 历史活跃天数 × 3`；写一条 `LAUNCH_BACKFILL` 流水（幂等键 `BACKFILL:{u}`）、建账户行、按等级表算 `level`；EXP > 0 的置 `backfill_popup_pending = true`。
- 虚拟号：建账户行，`display_level` 随机 6–8，不写流水。新建虚拟号时（`AdminVirtualAccountController` 创建路径）同样随机分配。
- **不发** `GrowthLevelChangedEvent` / 通知 / 庆祝（② 名额按当前等级实时算，不受影响）。
- 迁移与配置表 seed 同批、在配置表之后执行。

## 4. 展示、接口、后台、注销

**AD-14 · 作者等级下发（批量，禁 N+1）。**
- 新增 `GrowthQueryService.getBadges(Collection<Long> userIds) → Map<Long, GrowthBadge(level, tier, frame)>`：真实用户读账户（无行 = Lv1 / NEWBIE / 无框）；虚拟号用 `display_level` 与其段位、自动最高框；已注销 / 停用 → 不返回（App 不显示角标）。
- `AuthorView` 加 `GrowthBadge growth`（可空），在 **`AccountQueryService.basicViews`** 层批量填充（`findAuthorViews` 与 `findAuthorViewsWithoutTags` 都覆盖）。
- 拍平 DTO 加 `authorLevel`、`authorTier`、`authorFrame`（均可空）：`FeedItemResponse`、`ContentDetailResponse`、`CommentResponse`、`SharedPostResponse`、`PlaceCommentResponse`，以及主页、关注列表 / 关注条、@ 候选等使用 AuthorView 的响应——story 须全仓检索 AuthorView 使用点逐一覆盖。
- `/me`（`UserProfileResponse`）加 `growth: {level, tier, frame, lifetimeTotal, nextThreshold}`。

**AD-15 · Feed 作者等级加权。**
- `feed_rank_config` 加列 `author_level_boost DOUBLE PRECISION NOT NULL DEFAULT 1.05 CHECK (BETWEEN 1.00 AND 2.00)`（与该表其他数值列一致：实体为 `double`，NUMERIC 会令 `ddl-auto=validate` 启动失败）；`FeedRankForm` / `AdminAlgoParamController.FIELDS` / `ERROR_FIELDS` / 模板同步，插在「限流系数」之后（UI 稿 K1）。
- `FeedRecommendationService` 用 `GrowthQueryService.getLevels(作者 ids)` 得到 `Map<authorId, factor>`（`participating && level ≥ 15` → boost，否则 1.0；**Lv15 门槛写死**）传入 `FeedRankEngine.Input`；`FeedRankScorer.finalScore` 乘法链末尾 × authorFactor。**只作用于首页推荐流**，分类 Tab 不动。虚拟号不吃加权（AC12）。

**AD-16 · App 接口（`/api/v1/me/growth` 前缀，需登录 USER）。**
- `GET /api/v1/me/growth`：等级页首屏一次取齐——当前等级 / 段位 / 累计 / 下一级门槛（满级 null）、等级表（App 据此插值每级颜色）、头像框四档（可用 / 当前生效 / 是否手动）、规则列表（规则代码、每次分值、每日上限、今日已得）、各段位权益（1.4.0 不含优惠券）、最近 3 条流水。
- `GET /api/v1/me/growth/ledger?cursor=`：keyset（`created_at DESC, id DESC`），每页 20；行 `{ruleCode, delta, reason, note, createdAt}`，App 按 WIB 日分组。
- `PUT /api/v1/me/growth/frame` `{frame: NONE|BASIC|UPGRADED|ANIMATED}`：校验可用，写 `frame_choice`（此后升级不再自动换）。
- 弹窗接口见 AD-11。
- **显示串规则**：规则名、原因、段位名、权益全由 App 按代码本地化；只有运营填写的 `note`（活动名 / 调整原因）原样显示。
- 错误：RFC 9457 ProblemDetail（不可用的框 → 409 `frame-locked`）。

**AD-17 · 后台（AB-32 / AB-33）。**
- 新页 `/admin/growth`（`AdminPageCatalog` 登记于「⚙️ 配置与安全」、排在算法参数之后），三页签：规则（480 抽屉编辑 + 勾选框启停 + 原生 diff 确认）、消费换算（锁定行只读）、等级表（AD-12 确认弹窗）；右上「批量发放」仅 `GROWTH_USER_ADJUST` 可见。
- 权限码（`AdminPermissions`，入 `GROUPS`）：`GROWTH_CONFIG_VIEW` / `GROWTH_CONFIG_EDIT` / `GROWTH_USER_ADJUST`（P-5）。只读视图按惯例禁用输入 + 卡底提示；抽屉调整卡无权限置灰不隐藏。
- 用户抽屉「成长值」页签（地基已建容器）：概览、调整表单（普通调整 / 运营活动，行内表单同赠送 PawCoin）、明细（规则 + 时间筛选）；调整二次确认用 `AdminConfirmModal`；虚拟号只显示展示等级。
- 批量发放：600 抽屉 + 三步条，照 `SeedBatchPublishService` 的预览 / 不阻塞整批；批次号 `GB-{yyyyMMddHHmm}-{rand}`，按 `(batchNo, user)` 去重；无效 ID（不存在 / 已注销 / 已停用）与虚拟号列入预览排除。
- 虚拟号展示等级：`/admin/virtual-accounts` 列表加列、详情抽屉加「展示等级」卡（同「账号物种定位」卡）。
- 审计（`AuditActions` 新常量）：规则修改、换算修改、等级表修改、单个调整、批量发放、展示等级修改，均记改前改后。

**AD-18 · 注销与清理。**
- `GrowthAccountDeletionService.deleteByUserId`：删 `growth_ledger`、`growth_daily_counters`、`growth_accounts` 中该用户的行；在 `AccountDeletionService.execute` 中 tailsonality 之后、PawCoin 作废之前调用（AC10）。
- 停用账号保留数据但不显示等级（AD-14）。

## 5. 实现模式与一致性规则

**5.1 命名**
- 表 `growth_*`（7 张：`growth_accounts` / `growth_ledger` / `growth_daily_counters` / `growth_rules` / `growth_spend_tiers` / `growth_levels` / `growth_recalc_jobs`）。
- 枚举落库 `VARCHAR` + UPPER_SNAKE：规则代码沿用 PRD 原文（`DAILY_LOGIN` … `SPEND_SHOP`）+ `LAUNCH_BACKFILL` / `ADMIN_ADJUST` / `OPS_CAMPAIGN`；头像框 `NONE / BASIC / UPGRADED / ANIMATED`；段位沿用地基 `GrowthTier`。
- 新事件过去式 `record`，放**发布方模块**的 `event` 包：`auth.event.UserDailyActiveEvent`、`content.event.ContentUnlikedEvent`、`pay.event.PawCoinSpentEvent`、`pay.event.RefundSucceededEvent`。
- 后端包 `com.tailtopia.growth.{domain,repository,service,event,web,listener}`；后台 `admin.growth`。
- App：`features/growth/{data,domain,presentation}`；头像角标 + 头像框做成共享装饰组件 `shared/widgets/growth_avatar_decoration.dart`，三个头像组件只包这一层，**不各画一遍**；arb 键前缀 `growth`（如 `growthRuleDailyLogin`、`growthTierPemula`）。

**5.2 幂等键**
- 全部经 `GrowthIdemKeys` 静态方法生成，**禁止在别处手拼字符串**。格式 `{类型}:{字段}:{字段}`，清单见 AD-6 / AD-8 / AD-9。

**5.3 数值与时间**
- EXP 换算一律 `BigDecimal` + `RoundingMode.HALF_UP`；金额 IDR 整数，1 PawCoin = Rp1。
- WIB 切天只用 `ScheduleWindow.WIB`，不新增私有时区常量。

**5.4 写入纪律**
- 写成长值的**只有** `GrowthLedgerService.accrue / reverse / adjust` + AD-12 重算任务 + AD-13 迁移；其他模块只发事件。
- 流水只追加不改删（注销清理除外）。
- 等级变化连锁（待弹庆祝、通知、`GrowthLevelChangedEvent`）只在 `GrowthLevelChangeHandler` 一处。

**5.5 读取纪律**
- 列表接口只能用批量 `getBadges` / `getLevels`，测试断言查询次数（禁 N+1）。
- 权益判断只用 `getLevel(s)`，展示只用 `getBadges`，两者不混用（虚拟号走展示等级）。

**5.6 App 一致性**
- 段位色只在 `colors.dart` 定义 4 个常量（Pemula #66748C · Sahabat #0A7FA3 · Penjaga #2B4FBF · Bintang #4A2E9E→#B5307F 渐变）；每级颜色由 `growthLevelColor(level, levels)` 插值。
- 角标：头像直径 ≥ 32 显示数字，< 32 只显示圆点，阈值一个常量。
- 动效框读 `MediaQuery.disableAnimations`，开启时静止。
- 升级庆祝 / 存量汇总弹出前先登记 `StartupPopupLedger`。
- App 不渲染后端显示串（`note` 例外，原样显示）。

**5.7 测试约定**
- 每条规则至少：正常入账、当日额度满、幂等重放、扣回；PRD AC1–AC15a 逐条映射到测试。
- 被动合计与日额度有并发测试（两线程同时入账不超上限）。
- 退款扣回覆盖：部分退款、多次累计不超扣、原流水未入账不扣。

## 6. 项目结构与边界

**6.1 后端新增**（`petgo-backend/src/main/java/com/tailtopia/`）
```
growth/
  domain/      GrowthAccount · GrowthLedgerEntry · GrowthRule · GrowthSpendTier · GrowthLevelDef
               GrowthRecalcJob · FrameChoice · RuleCode · GrowthBadge（+ 地基 GrowthLevel / GrowthTier）
  repository/  各表 Repository + GrowthDailyCounterRepository（原子领额 SQL）
  service/     GrowthLedgerService · GrowthLevelChangeHandler · GrowthIdemKeys · GrowthQueryService（替换占位）
               GrowthRecalcService · GrowthAccountDeletionService · GrowthPageQuery（等级页聚合）
  listener/    GrowthActivityListener（登录 / 发帖 / 评论 / 点赞 / 打卡 / 里程碑 / 收藏 / 下架）
               GrowthSpendListener · GrowthRefundListener · GrowthGiftListener · GrowthNotifyListener
  web/         MeGrowthController（/api/v1/me/growth/**）+ dto/
admin/growth/  AdminGrowthConfigController · AdminGrowthUserController · AdminGrowthBatchController + service/
resources/templates/admin/growth*.html · fragments/drawer-users.html（填 growth 面板）· drawer-virtual-account.html
```

**6.2 后端修改（只追加，不重构）**
- `auth`：`AccountQueryService.touchLastActive` 返回值、`UserActivityFilter`、`AuthorView.growth`、`basicViews`、`UserProfileResponse`。
- `content`：`LikeService.unlike`、各拍平 DTO；`content/rank`：`FeedRankScorer` / `FeedRankEngine.Input` / `FeedRecommendationService`；`FeedRankConfig` / `FeedRankForm` / algo-params。
- `profile`：`MilestoneCompletedEvent.petId`；`place`：`PlaceCheckedInEvent.checkinId`。
- `pay`：`PawCoinWalletService.debit` 发 `PawCoinSpentEvent`、退款五处发 `RefundSucceededEvent`；`shop/order/event/ShopOrderPaidEvent` 补 `userId` / `paidTotal`。
- `admin`：`AdminPermissions` / `AdminPageCatalog` / `AuditActions`、虚拟号创建路径随机展示等级。
- `account/service/AccountDeletionService`：插入 `GrowthAccountDeletionService`。

**6.3 App（`petgo_app/lib/`）**
```
features/growth/  data/growth_repository.dart · domain/（GrowthSummary / Ledger / Badge / Popups 模型）
                  presentation/ level_page · exp_ledger_page · frame_picker_row · level_up_celebration（卡片 + 全屏）
                                backfill_popup（复用 ops_popup_dialog 卡片）· growth_popup_coordinator
shared/widgets/growth_avatar_decoration.dart（角标 + 三档框）
改：letter_avatar / initial_avatar / public_profile_page / following_strip / me_page（等级胶囊）
    FeedItem · ContentDetail · Comment · 主页仓储 · UserProfile（作者等级字段）
    startup_popup_ledger（+2 枚举）· notification_center_page（GROWTH_LEVEL_UP 图标 / 文案 / 深链）
    deep_link_routes · colors.dart（段位色）· app_en.arb / app_id.arb（growth* 键，取 UI 稿文案表）
```

**6.4 迁移（本主题预分配，云端不得另取号）**

| 号 | 内容 |
|---|---|
| `V20261018_1500` | 建 `growth_accounts` / `growth_ledger` / `growth_daily_counters` / `growth_rules` / `growth_levels` + seed 规则 / Lv1–20 / `PASSIVE_GROUP`（Story 1.1） |
| `V20261018_1501` | `feed_rank_config.author_level_boost` |
| ~~`V20261018_1502`~~ | **作废不复用**：OPS_MANAGER 权限写死在 `AdminRole` 枚举（`RolePermissionResolver` 直接读），迁移插表不生效；P-5 改为在枚举里加码（Story 4.2） |
| `V20261018_1503` | 存量补算 + 虚拟号展示等级（AD-13，须在 1500 之后；Story 2.2） |
| `V20261018_1504` | `growth_spend_tiers` + seed 档位（Story 1.3） |
| `V20261018_1505` | `growth_recalc_jobs`（Story 4.1） |

**6.5 跨线边界**
- 对 ②：`GrowthLevelChangedEvent` 升降都发；`getLevel` 返回落库真实等级。
- 对 ③：`GiftSentEvent` 只由本线监听；`GIFT` 不走消费返。
- 对 ④：收藏 / 取消收藏事件只由本线监听；`GROWTH_LEVEL_UP` 的推送分类由 ④ 映射。
- 本线不碰：两条共享约束、抽屉页签头、② 的 `ContentPublishedEvent.petIds`（② 合并后流水改取该字段）。

**6.6 建议 story 边界**
- **E1 后端引擎**：①表 + seed + `accrue / reverse` 入口 + 替换 `GrowthQueryService`；②日常行为入账与扣回（含补 4 个事件）；③消费返与退款扣回；④等级变化连锁 + 通知 + 弹窗接口 + 重算任务；⑤存量补算 + 虚拟号展示等级 + 注销。
- **E2 展示与 App**：①后端下发（AuthorView / `/me` / 等级页接口）；②头像装饰组件 + 接入全部位置；③等级页 + EXP 明细 + 切框；④升级庆祝 + 存量弹窗 + 通知中心；⑤Feed 加权（后端 + 算法参数页）。
- **E3 后台**：①成长体系三页签 + 权限；②用户抽屉成长值页签 + 调整；③批量发放；④虚拟号展示等级。

## 7. 校验结论（2026-10-18）

**覆盖**：PRD AC1–AC20、AB-32 / AB-33、埋点 5 个均有落点——AC1 / AC2 → AD-3 / AD-6；AC3 / AC14 / AC14a → AD-6 / AD-7；AC4 / AC5 → AD-8；AC6 / AC13 → AD-9 / AD-10；AC7 → AD-6；AC8 / AC9 → AD-10 / AD-11 / AD-12；AC10 → AD-18；AC11 → AD-13；AC12 → AD-13 / AD-14 / AD-15；AC15 / AC15a → AD-6；AC16 / AC19 / AC20 → AD-10 / AD-14 / AD-16；AC17 → AD-16 + V-2；AC18 → AD-15；AB-32 / AB-33 → AD-12 / AD-17。

**一致性**：无新依赖、无 MQ / 缓存；写入单入口（5.4）、读取批量（5.5）、幂等键集中（5.2）三条规则与 AD 无冲突；跨线契约与地基 Story 0.1 / CROSS-STORY-DECISIONS 一致。

**校验补丁**
- **V-1** 被动合计上限存为 `growth_rules` 伪行 `PASSIVE_GROUP`（已回写 AD-4）。
- **V-2** 等级页「今日已得」= 当天（`wib_date = 今天`）该规则流水正数之和；「今日已满」读 `growth_daily_counters`。
- **V-3** 埋点 5 个全部 App 侧发：`growth_level_up` 在取到升级庆祝数据、弹出前发（含 `is_tier_up`）；`growth_level_page_viewed` 的 `source` 取 me_page / celebration / notification；服务端不另埋，BI 读 `growth_ledger`。
- **V-4** 存量补算活跃天数限制见 P-6。

**已知限制**
- 入账为 AFTER_COMMIT 异步、失败不重试（V1 姿态），极少数漏账由运营手动补；App 动作后约 1.5s 拉弹窗，偶发未赶上时在下次触发点补弹。
- 违规下架后恢复不补回（P-1）。
