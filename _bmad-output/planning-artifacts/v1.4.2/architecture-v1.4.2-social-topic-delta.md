---
title: "V1.4.2 social-topic 主题 · 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: complete
completedAt: '2026-11-04'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-admin-1-4-2-social-topic-integrated-v1.html
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-push-collect-delta.md     # PushCategory / 收藏事件
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md   # 成长值 MILESTONE_S / LAUNCH_BACKFILL
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md        # content_pets / 宠物状态
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-11-04'
version: v1.4.2
theme: social-topic
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.2（main 4b707be3 + c496e3c3）
---

# V1.4.2 social-topic 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照 `dev_1.4.2` 代码核实。**权威顺序**：PRD（变更记录日期最晚的一条为准）> 本文件 > UI 稿。本文件与 PRD 冲突时以 PRD 为准并回改本文件；与代码现状冲突处已在 §1.2 列出、以代码为准。

## 1. 项目上下文分析

### 1.1 需求概览

8 项需求（FR-153 / 154 / 69 / 155 / 156 / 157 / 165，AB-10A 扩展 / AB-10B / AB-37），按改动性质分四类：

| 类别 | 需求 | 架构含义 |
|---|---|---|
| 新领域 | FR-69 话题、AB-10B 话题管理 | 全新表、正文解析、热度定时任务、话题页、后台列表页；本线最重 |
| 新关系 | FR-153 追更宠物 | 新表 `pet_follows`（与 `user_follows` 并列）、关注流并集查询、2 个通知类型 + 1 个推送分类 |
| 改现有规则 | FR-154 账号级里程碑、FR-157 @ 范围、AB-37 通知变量、FR-155 按天浏览、宠物推荐排序 | 风险在改动面：成长值幂等键、庆祝状态、@ 校验、运营人群统计 |
| 纯 App | FR-156 里程碑发社区、FR-165 隐藏入口、访客页失效修复 | 新里程碑卡、`BragPostEntry` 扩参数、6 处庆祝弹出点统一修 |

**非功能需求：**
- 性能：关注流并集查询（追更数 × Diary 量）；热度任务每小时扫 7 天窗口；按天浏览表定期清理（保留 30 天）。
- 一致性：冗余计数（`pet_profiles.follower_count`、话题帖子数）与关系表同事务维护；拉黑同事务删追更。
- 隐私 / 安全：不提供追更者名单；拉黑对被拉黑方无感知；话题名走敏感词过滤；@ 放开后仍做服务端交集校验。
- 护栏：对外标识不可枚举（话题对外不暴露自增 id）；Flyway 时间戳版本号；`ck_notifications_type` DROP + ADD 重列全集。

**规模：** 高复杂度、全栈。主要组件约 15 个（后端 8：话题 / 追更 / 热度 / 按天浏览 / 账号级里程碑 / @ 范围 / 通知变量 / 坑位扩展；App 5：富文本解析与输入高亮 / 话题页 / 关注列表筛选 / 里程碑卡 / 庆祝页重构；后台 3：话题管理 / 顶置页签 / 通知编辑器）。

### 1.2 已核实的代码现状（`dev_1.4.2`，与 PRD 写法有出入处以代码为准）

1. 庆祝状态在 `milestone_completions.celebrated_at`（`V20260910_1108`），不在 `pet_milestones`。
2. 顶置坑位实际值 `HOME_FEED`（`ContentPin.SLOT_HOME_FEED` 是常量名）；`content_pins.slot VARCHAR(32)` 无 CHECK。App 顶置接口 `GET /content-posts/pinned` 不带坑位参数。
3. 没有「封号」状态：即 `users.status = DEACTIVATED`，统一判据 `AccountQueryService.isActive`（`deletedAt == null && status == ACTIVE`）。访客视图四种失效（宠物不存在 / 主人注销 / 主人 DEACTIVATED / 对方拉黑我）统一 404。
4. 宠物删除是物理删除且不发事件（`ProfileDeletionService.deletePetRows`）→ `pet_follows` 对宠物的 FK `ON DELETE CASCADE` 即覆盖「宠物被删」。账号注销只匿名化 users 行（`AccountDeletionService.execute`），追更者一侧须显式清理。
5. 关注每日上限 500 是对 `user_follows` 直接 `countCreatedSince(... source='USER')`；分钟限频 `RedisRateLimiter`，key `rl:social:follow:<uid>`（30 / 分钟，关注取关共桶）。
6. 发帖事件 `ContentPublishedEvent(postId, authorId, type, petId, authorGrowthMomentCount, visibility, createdAt, petIds)` 已存在，人工审核通过时补发；`ContentService.restore` 不发任何事件；`ContentUnavailableEvent` 在作者删除与运营下架时都发。
7. 庆祝页弹出点为 6 处生产代码（PRD 写 5 处，漏了打卡 picker `milestone_list_page.dart:1045`）；`milestone_instant_celebration.dart:135` 只在关闭回报处规避宿主销毁，`onShare` 闭包仍用旧 `ref`；`milestone_share.dart:53` 的分享调用在 try 外。
8. @ 浮层（发帖页 / 评论框）都是 Column 内联，不是 Overlay；`MentionText._spans` 私有且只认 @，无可复用解析器；@ 名单由 `MentionDraft.userIdsIn(text)` 提交 `mentionedUserIds`。
9. 访客统计条现为 3 格（`VisitorStats`：`happyMomentCount / consultCount / milestoneCompleted / milestoneTotal / giftTotalValue`，礼物是总价值）；与作者态 `ArchiveStatsResponse` 共用 App 模型。
10. `BragPostEntry.open(context, {cardPng, text})` 固定 `preset: daily`，不透传宠物。
11. App 无功能开关机制（只有 `fromEnvironment` 用于 SDK / DEV 钩子）。
12. 关注类通知文案由后端拼（App `serverCopyTypes`）；新增通知 type 在 App 至少改深链映射、`_typeLabel / _typeBody`、`_iconStyle`、`_notifTypeForAnalytics`。
13. 宠物推荐 `findRecommendablePets` 的 liked 段无时间窗，且多宠帖的赞计入每只宠物（存量现象，本线只加 14 天窗口）。
14. 守卫测试：`PushCategoryTest` 断言 `NotificationType` 39 个并逐值比对映射；`MentionNotifyTest.lastRebuildMigration()` 双向比对最后一条 `ck_notifications_type` 重建迁移（现为 `V20261018_1400`，39 值）与枚举。
15. `MentionSanitizer` 只剔除已注销（`deletedAt`），不剔除 `DEACTIVATED`（存量）；@ 候选只来自点赞 / 评论（`MentionCandidateListener`），关注不写候选。
16. 运营通知预计触达 `OpsAudienceQuery.count`（基础条件 `role='USER' AND account_type='REAL' AND deleted_at IS NULL AND enabled AND status='ACTIVE'`）；`skipped_count` 现只在 IDS 模式计算；标题 120 / 正文 255 校验在 `AdminOpsNotifyService` 与 `OpsNotificationCampaignService` 两处。
17. `comment_weight` 在单行表 `feed_rank_config`（种子 2.0），`PlatformConfigService.feedRank()` 无缓存直读。

### 1.3 技术约束与依赖

- 代码基线 `dev_1.4.2`（main 4b707be3 之后）；Flyway 当前最新 `V20261030_0330`。
- 定时任务：原生 `@Scheduled`（cron + zone 或 `fixedDelayString="${petgo.*}"`），单实例部署无 ShedLock；需要串行用 `pg_advisory_xact_lock`；WIB 用 `ScheduleWindow.WIB`。
- 与同版本电商线交界：`ck_notifications_type` 全集重建、`PushCategory`——后合并的一方负责（见 `README.md`）。
- 对外契约：`PetFollowedEvent`；发帖事件 `topicIds`（供 1.5.0 活动引擎）。

### 1.4 跨模块关注点

- **可见性过滤统一**：拉黑 / 举报 / 注销 / `DEACTIVATED` 四条规则在关注流、话题页、@ 候选、追更通知四处一致（现有关注流按 D-4 不排除「对方拉黑我」）。
- **冗余计数**：宠物追更数、话题帖子数在关系变更同事务维护，覆盖删除 / 下架 / 恢复 / 禁用。
- **富文本标记**：话题与 @ 共用一套解析器（正文展示 + 输入框高亮）。
- **通知与推送**：2 个新通知类型、1 个新推送分类、运营通知变量替换。
- **埋点**：新增 `topic_` 前缀，登记到 `test/analytics/v112_events_test.dart` 前缀表。

## 2. 起步与技术栈

棕地（brownfield）增量，不选起步模板；技术栈与版本基线沿用根 CLAUDE.md 与基线架构，不新增依赖。
- App 输入框高亮：自写 `TextEditingController.buildTextSpan` 子类，不引第三方富文本编辑器（2026-11-04 评估，见 2.1）。
- 后台通知变量编辑区：原生 `contenteditable` + 不可编辑 `<span>` 标签 + 少量 JS，不引编辑器库、不引前端构建链。
- 护栏不变：异步只用 `@Async` + DB 状态机；禁 MQ / 通用缓存 / 新中间件；schema 归 Flyway。

### 2.1 输入框高亮选型评估（2026-11-04，操作者同意）

需求：前 5 个有效话题高亮（规则与服务端一致）；只高亮从候选点选的 `@昵称`、删字即失效；发布页与评论框同一套；候选面板贴键盘上沿；不破坏输入法联想下划线、字数上限、键盘避让；@ 提交方式不变（`MentionDraft.userIdsIn`）。

第三方（highlight_mentions / input_tagger / mention_tag_text_field / flutter_mentions / extended_text_field）不采用：①「前 5 个」「只认点选」「Topik baru 行」都是自有规则，库省不掉规则代码；②库多自带并自行定位候选浮层，与「贴键盘上沿」冲突；③多为 0.0.x、小作者、最近更新 2025 年，Flutter 3.44 升级风险；④extended_text_field 需整体替换 `TextField`，牵动键盘避让标准与既有测试。

采用自写三件：
1. **共用标记解析器**（纯 Dart、无 UI 依赖）：话题 + @，正文展示与输入高亮共用，取代私有且只认 @ 的 `MentionText._spans`。
2. **`TopicMentionTextController`**（重写 `buildTextSpan`）：发布页与评论框共用。
3. **前后端共用测试样例**：一份 JSON（输入 → 应识别的话题），Dart 与 Java 测试都读它，保证 App 高亮 = 服务端建话题。

注意：重写 `buildTextSpan` 须自行叠回输入法联想区间（composing）下划线，必须有测试；高亮只改颜色、不改字号字重，避免光标 / 选区错位；@ 有效性沿用 `MentionToken` 词边界判断（手打与已点选同名的 `@昵称` 也会高亮，与实际发送一致，可接受）。

## 3. 核心架构决策（2026-11-04，按推荐定稿）

迁移一律时间戳版本号 `V<yyyyMMdd_HHmm>__<snake>.sql`。

### 3.1 数据

**D-1 追更关系**：新表 `pet_follows(follower_id, pet_profile_id, created_at)`，PK 两列组合；两个 FK 均 `ON DELETE CASCADE`（宠物物理删除自动清）；索引 `(pet_profile_id, created_at)`、`(follower_id, created_at)`。`pet_profiles.follower_count INT NOT NULL DEFAULT 0`，同事务条件 ±1、`GREATEST(…,0)` 兜底（同 `FollowCounterService`）。不能追更自己的宠物在 service 层校验。

**D-2 话题**：
- `topics(id, name_display VARCHAR(30), name_norm VARCHAR(30) UNIQUE, type OFFICIAL|USER, status ACTIVE|HIDDEN|DISABLED, desc_id, desc_en, post_count INT, heat_score NUMERIC, featured_order INTEGER NULL（部分唯一索引）, disabled_at, created_at, updated_at)`。整数列用 INTEGER 不用 SMALLINT（Hibernate int → int4 坑）。
- `content_topics(post_id, topic_id)`，PK 两列组合，`post_id` FK `ON DELETE CASCADE`，索引 `(topic_id, post_id)`。
- 标准化：NFC 后 `toLowerCase(Locale.ROOT)`。建话题并发安全：`INSERT … ON CONFLICT (name_norm) DO NOTHING` 后再查。
- 写关联：发帖时解析正文，只给 `PUBLIC` 帖子写（审核中也写，读时过滤 `status = PUBLISHED`）；命中 DISABLED 不写；不存在则建 USER 话题（显示写法取首次出现的写法）。
- 禁用期间关联保留，恢复后禁用前的帖子自动回来；禁用期间同名话题不建关联、恢复后也不补。
- `post_count` 不做事务计数，由热度任务每小时重算（恢复帖子不发事件，事务计数维护不准；App 不显示帖子数）；后台列表最多滞后 1 小时（2026-11-04 拍板接受），抽屉与升级黄条内的帖子数实时查。
- 后台新建官方话题与用户话题重名：用户话题 ACTIVE / HIDDEN → 升级为官方、保持原状态、显示写法改为后台填写；DISABLED → 拒绝升级、提示先恢复（2026-11-04 拍板 R-5）；与官方话题重名 → 报错。

**D-3 按天浏览（FR-155）**：`content_post_view_days(post_id, view_date DATE, viewer_key VARCHAR(40))`，PK `(post_id, view_date, viewer_key)`，`view_date` 按 WIB；在现有 `ContentViewStatsService.recordView` 里一并 `INSERT … ON CONFLICT DO NOTHING`（同样 @Async、失败只记日志）；每日任务删 30 天以前的数据。采集与累计口径不变。

**D-4 账号级里程碑（FR-154）**：
- `user_milestone_flags(user_id, flag_key, occurred_at)`，PK `(user_id, flag_key)`，`flag_key ∈ {FIRST_FOLLOW_USER, FIRST_COLLECT_CONTENT}`；记「账号做过」，无宠物账号也记。
- `MilestoneCatalog` 三份清单各加两条：「Teman pertama」= C-S19 / D-S19 / **G-S12**，「Simpan pertama」= C-S20 / D-S20 / **G-S13**（OTHER 清单现到 G-S11；2026-11-04 就绪度评审 B1 更正）；存量宠物的 `pet_milestones` 行照 V77 先例用迁移补，新宠物由 `assignRoster` 物化。
- 存量补算独立迁移：写 flag →（ACTIVE 宠物）写完成记录 `celebrated_at = now()` → 每「账号 × flag」成长值 +5，规则码 `LAUNCH_BACKFILL`，幂等键 `BACKFILL142:{user}:{flag}`；照 `V20261018_1503` 写法同步成长值总额与等级，不触发升级弹窗、不发通知。

**D-5 追更日提醒去重**：`pet_diary_notify_log(follower_id, pet_profile_id, wib_date)`，PK 三列组合；插入成功才发（同一追更者 × 同一宠物 × WIB 日最多 1 条）。随按天浏览一起定期清理旧数据。

**D-6 话题页顶置坑位**：`content_pins.slot` 写 `TOPIC:{topicId}`（`VARCHAR(32)` 足够，不改表）；现有同坑位排期重叠校验、按坑位取生效排期直接复用；话题禁用时对 `slot = 'TOPIC:' || id` 的**全部未结束排期（生效中 + 排期中）**写 `terminated_at`（否则恢复后未来排期会自动上线，违背「恢复不自动恢复」）（同 `ContentPinTakedownListener` 做法），恢复不自动恢复。

**D-7 通知类型与推送分类（同一迁移）**：`ck_notifications_type` DROP + ADD，值 = **当前树里最后一条重建它的迁移的全集** + `PET_FOLLOWED`、`PET_DIARY_POSTED`（以现在的 `V20261018_1400` 为基数是 41 值；电商线若先合入则以其重建为准，不写死数量）；`ck_user_push_mutes_category` 同步重建加新分类 `PET_DIARY`；同步改 `PushCategoryTest`、`MentionNotifyTest`。与电商线交界见 `README.md`（后合并方在同一迁移重列全集）。

### 3.2 后端服务与接口

**D-8 追更接口**：
- `POST / DELETE /api/v1/me/pet-follows/{petId}`（petId 与现有站内访客 `/pets/{petId}` 同一标识）。
- `GET /api/v1/users/{token}/pet-follows?cursor=`（某用户追更的宠物列表；行内带宠物名、物种、头像、主人昵称、我是否追更）。
- 访客 `/profile` 加 `iFollow`；统计（与作者态 `ArchiveStatsResponse` 共用模型）加 `followerCount`，主人 Diary Tab 同步可用。
- 限频复用 `rl:social:follow:<uid>`（每分钟 30，与关注共桶）；每日 500 改为「`user_follows` USER 来源当日新增 + `pet_follows` 当日新增」。
- 取消追更不校验主人状态（主人 `DEACTIVATED` 期间也能取关）。追更时只校验：宠物存在且主人 `isActive`（否则 404）、**我**没有 BLOCK 主人（否则 403，对应「拉黑期间 A 不能追更 B 的宠物」）。**主人 BLOCK 了我不拦截**（不暴露被拉黑），且不给主人发 `PET_FOLLOWED`。只读宠物可被追更。（6.1 修正）
- 用户主页接口与 `/users/{token}/pet-follows` 返回 `petFollowingCount`，供「Hewan N」胶囊。（G-1）
- 访客 `/profile` 与 pet-follows 列表行带 `ownerToken`（主人公开 token）与 `iFollowOwner`，供埋点 `owner_id` / `is_following_owner`（R-2）。

**D-9 关注流并集**：改 `ContentPostRepository.findFollowingFeed`：作者 ∈ 我关注的人 **或**（`type = GROWTH_MOMENT` 且 `EXISTS content_pets ⋈ pet_follows(follower = 我)`）；一条查询天然去重，游标不变。仅「追更宠物」一路额外排除「作者 BLOCK 了我」（PRD 要求）；「关注的人」一路维持 1.3.4 现状不动。每条带 `reason`（作者在我关注中 → `followed_user`，否则 `followed_pet`）。首页额外返回 `followingUserCount`、`followingPetCount` 供 App 区分两种空态。
- **拉黑组合口径（2026-11-04 拍板 R-1）**：B 既关注 A 本人又追更 A 的宠物、A 拉黑 B 时，A 的内容（含其宠物 Diary）仍从「关注的人」一路出现（拉黑只删 A→B 的关注，维持 1.3.4 现状）；PRD FR-153 AC9 收窄为「B 未关注 A 本人时，A 宠物的 Diary 不出现」。

**D-10 追更通知**：
- `PET_FOLLOWED`：后端拼文案（App 加入 `serverCopyTypes`），推送归 `FOLLOWER`；24 小时去重按「接收人 × actor × 宠物」（`target_ref` = 追更者 user id 同 `USER_FOLLOWED`，宠物 id 记入 `variant`；2026-11-04 story 生成时修正，使推送点击可直达追更者主页），任一方向拉黑不发；点击进追更者主页；无回关按钮。
- `PET_DIARY_POSTED`：后端拼文案（ID「{宠物} punya cerita baru」/ EN「{pet} has a new story」），App 加入 `serverCopyTypes`；消费 `ContentPublishedEvent`，AFTER_COMMIT + `@Async`，写库 `REQUIRES_NEW`（吸取「AFTER_COMMIT 事务吞写」事故）；仅 `GROWTH_MOMENT` + `PUBLIC` + 有 `petIds`；收件人去作者本人、去任一方向拉黑；同一帖同一追更者只 1 条、文案取命中的第一只宠物；先过 D-5；分批写入，推送走现有全局限速器（25/s）；推送分类 `PET_DIARY`。
- 新增 `PetFollowedEvent(followerId, petProfileId, ownerId, followedAt)`，供通知与后续宠物等级 / 活动引擎。

**D-11 拉黑 / 注销联动**：`UserHideRelationService.block` 同事务删「拉黑者对被拉黑者所有宠物的追更」并修正计数；`AccountDeletionService` 增一步清该用户作为追更者的全部追更（宠物一侧由删宠级联）；封号（`DEACTIVATED`）不清理。

**D-12 账号级里程碑执行路径**：
- 监听 `UserFollowedEvent`（只在 USER 来源新建关注时发，系统自动关注官方号不发）与 `ContentCollectedEvent`（只帖子收藏；场所收藏不发事件），AFTER_COMMIT + `REQUIRES_NEW`。
- 插 flag 成功（首次）→ 给全部 ACTIVE 宠物写完成（`celebrated_at` 为空）→ 发 1 条新事件 `AccountMilestoneCompletedEvent(userId, flagKey, petIds, codes)`，驱动 1 条 `MILESTONE_SM_NODE`（文案不带宠物名）与 1 次成长值；插 flag 失败 → 什么都不做。
- 触发时账号没有 ACTIVE 宠物：成长值照发（账号行为，与存量补算一致）；不写完成、不发通知；之后建宠 / 恢复时按下一条直接补「已完成且已庆祝」。（G-3）
- 收藏自己的帖子同样触发（里程碑监听不跳过自收藏；成长值监听原有跳过逻辑不变）。（G-7）
- 通知点击：打开当前选中宠物的里程碑列表，该宠物非 ACTIVE 则打开账号下第一只 ACTIVE 宠物。（G-2）
- 通知载荷：`targetRef` 放账号下第一只 ACTIVE 宠物物种对应的编码；App 通知中心对这 6 个账号级编码**不拼宠物名**（`_milestoneCopy` 走不带 `{name}` 的标题 / 正文），庆祝页文案用 PRD §7 那四条。（R-4）
- 不走按宠物的现有完成事件（否则按宠物数重复通知、重复给分）。
- 成长值幂等键 `MILESTONE_ACCT:{userId}:{flagKey}`（按 flag，不按物种编码——猫狗编码不同会重复给分）；同时屏蔽这几个编码原有的按宠物给分。
- 新建宠物 / 只读恢复为 ACTIVE：账号已有 flag → 直接写「已完成且已庆祝」、不发任何事件；钩在 `assignRoster` 与 `PetSlotStateService.reconcile`。
- 庆祝回报（`POST /pet-profiles/me/milestones/celebrations`）收到这几个编码时，把账号下所有宠物的同一条一并置为已庆祝。

**D-13 话题接口**（对外用 `name_norm` 做标识，不暴露自增 id）：
- `GET /api/v1/topics/{name}`：详情（显示写法、是否官方、按 Accept-Language 的说明）；DISABLED / 不存在 → 404。
- `GET /api/v1/topics/{name}/posts?sort=latest|popular&cursor=`：`latest` 游标同关注流；`popular` 按「点赞数 + `comment_weight` × 评论数」实时聚合排序、页码分页（库内无点赞 / 评论冗余计数列）；可见性过滤同关注流（游客不加按人的过滤）；被限流内容照常出现。
- `GET /api/v1/topic-suggestions?q=`（原写 `/topics/suggest`，与 `/topics/{name}` 撞路由——`suggest` 本身是合法话题名，2026-11-04 改）：无 `q` 按热度、有 `q` 前缀匹配 `name_norm`，官方在前、同类按热度，排除 HIDDEN / DISABLED；每项带 `official`。
- `GET /api/v1/topics/home-row`：运营指定（`featured_order`）在前，按热度补满 5 个，只补 `heat_score > 0`；每项带 `featured`（是否运营指定）、`official`。
- `GET /api/v1/topics/{name}/related`：10 个推荐（近 7 天热度倒序，排除当前、HIDDEN、DISABLED）。
- 现有 `GET /content-posts/pinned` 加 `?topic={name}`；已有的 `?slot=` 参数拒收 `TOPIC:` 前缀（返回空），防止按内部 id 枚举话题坑位。
- 发帖成功响应返回本帖生效话题 `[{name, official, created}]`（`created` = 本次新建），供 App 发布成功后上报 `topic_inserted`（R-2）。
- 话题页列表在该话题有生效的「内容顶置」时，被顶置的帖子不在列表重复出现（同首页 Feed 让位做法）。
- 帖子出参（**所有返回帖子正文的卡片 / 详情接口**：Feed / 详情 / 关注流 / 话题页 / 个人主页与宠物 Diary 列表等）新增 `topicNames`：正文中**可点**话题的标准名（ACTIVE 与 HIDDEN）；App 只把这些渲染为链接。
- `ContentPublishedEvent` 加 `topicIds`（供 1.5.0 活动引擎）。
- 话题名敏感词：随正文走现有审核，不单独审。
- 安全配置：`GET /topics/{name}`、`/posts`、`/related`、`/home-row` permitAll（游客可看）；`/topic-suggestions` 需登录。（G-4）
- 运营后台种子发帖 `publishTrusted` 同样调 `TopicService` 写关联。（G-5）

**D-14 热度任务**：每小时，`@Scheduled` + `pg_advisory_xact_lock`；一条集合 SQL 更新所有话题的 `heat_score` 与 `post_count`：近 7 天新发帖数 × 1 + 近 7 天按天浏览人数之和 × 0.1 + 近 7 天发生的点赞与评论 × 1；只统计 `PUBLIC` + `PUBLISHED` + 未删除帖子。

**D-15 @ 范围（FR-157）**：
- `GET /me/mention-candidates`：不带 `q` → 快捷列表（我关注的人按关注时间倒序，不足 30 用互动候选补，去重）；带 `q` → 在「我关注 ∪ 关注我 ∪ 互动候选」内昵称 `ILIKE`（转义 `%` `_`）最多 20 条，按三类先后排序；每条带 `relation`（following / follower / interacted）。
- 排除任一方向拉黑、已注销，以及 `DEACTIVATED`（新增，对齐「封号各处按不存在」）。
- `MentionSanitizer` 允许集合改为「候选表 ∪ 我关注 ∪ 关注我」，同样排除 `DEACTIVATED`；超 5 仍 422，其余非法静默丢弃。
- 官方号不特殊处理（其粉丝 = 全体用户，可 @ 任何人）。

**D-16 通知变量（AB-37）**：
- 存储令牌 `{{nickname}}`、`{{petName}}`（与界面语言无关）。
- **跳过计数分开（2026-11-04 拍板 R-3）**：新增列 `ops_notification_campaigns.skipped_no_pet_count INTEGER NOT NULL DEFAULT 0`（迁移 ⑧），只记「没有宠物」被排除的人；原 `skipped_count` 口径不变。
- 编辑器整体标签插入；前端序列化前检查纯文本节点含 `{{` 或任一语言的变量显示名即拦截；服务端对显示名再拦一次（`admin.err.ops-notify.var.typed`）。
- 字数每个变量按 20 计（标题 120 / 正文 255 两处校验同步改）。
- 收件人一条 SQL 批量带出昵称与宠物名：`LATERAL` 选宠物——有物种筛选只在该物种内，ACTIVE 优先，再按建档最早。
- 任一语言标题 / 正文用了宠物变量 → 人群追加「有宠物」；被排除人数记入 `skipped_no_pet_count`（所有人群模式）；预计触达、1 万人门槛按净人数；净人数 0 → 第 ④ 步拦截，服务端提交再拒。
- 空昵称 → 按收件人 locale 替换为「Sobat」/「there」；按发送时刻取值。
- 预览：本次人群随机 1 人（`ORDER BY random() LIMIT 1`）；发送记录摘要显示变量原样；日志不记正文与替换值。

**D-17 宠物推荐**：`PetRecommendationService.pageFor` 对当页候选批量查 `pet_follows` 排除已追更；`findRecommendablePets` liked 段加 `l.created_at >= :since`（近 14 天）；首页横滑行与集合页同步生效。

**D-18 后台**：
- 新页「话题」（`AdminPageCatalog` 在「内容标签」后），权限码新增 `TOPIC_VIEW` / `TOPIC_MANAGE`（授权方式沿用 1.4.0 新权限码做法）；写操作一律 `AdminAuditService.record`；新做开关组件；二次确认用浏览器原生 confirm。
- 顶置管理加「首页 Feed｜话题页」页签，`AdminContentPinController` 支持话题页坑位；内容顶置校验所选帖子属于该话题（已发布、公开）。
- 系统通知编辑区改 `contenteditable`（见 2.1、D-16）。
- 三语文案按 PRD §7「后台文案」表写入 `messages_{zh_CN,en,id}.properties`，复用词条直接引用原 key。

### 3.3 App

**D-19** 共用标记解析器 + `TopicMentionTextController`（见 2.1）；Feed 卡片、帖子详情改用新正文组件替换 `MentionText`；评论区展示不解析话题（输入框仍高亮 @）。
**D-20** 话题页路由 `/topics/:name`，不在受控前缀下，游客天然可访问；正文话题、首页话题行、空态推荐、顶置都跳它。
**D-21** 里程碑发社区（FR-156）：`BragPostEntry.open` 加 `preset`、`petIds`（不加 `milestoneCode`，会触发自动打卡）；里程碑卡 `CardCanvas.square`，在庆祝层屏外挂载（不用 `Offstage`，不绘制截不出图），截图前等徽章与头像画上屏（沿用 `TsRemoteArtImage.waitPainted` 的思路）；庆祝页在弹出时用 `ProviderScope.containerOf(context)` 取 `ProviderContainer` 传入，不再持有宿主 `WidgetRef`，6 处弹出点统一改；按钮组吸底。`BragPostEntry` 把来源透传到发布页，发布成功事件带 `source = milestone_celebration`。（G-6）
**D-22** FR-165：新建 `lib/core/config/feature_flags.dart`，3 个 `const bool` 关闭入口，组件代码保留。
**D-23** 新通知类型：`PET_FOLLOWED` 进 `serverCopyTypes`、点击进追更者主页、无回关按钮；`PET_DIARY_POSTED` 进帖子详情（走现有「已不存在」探测）；`PushCategoryCode` 加新分类，放 INTERAKSI 组「Pengikut baru」之后，「Pengikut baru」说明改两行。

### 3.4 实施顺序

1. 地基：迁移与枚举（D-1 ~ D-7，含通知类型、推送分类）
2. 追更后端（D-8 ~ D-11）
3. 账号级里程碑（D-12，含存量补算）
4. 话题后端与热度任务（D-13 ~ D-14）
5. @ 范围、通知变量、宠物推荐（D-15 ~ D-17）
6. 后台（D-18）
7. App（D-19 ~ D-23）

### 3.5 按推荐定下、需留意的 5 处

1. 话题对外用标准名做标识（如 `/topics/kucinglucu`），App 拿到正文即可跳转，无需话题 id；标准名不敏感。
2. 「作者拉黑了我」只在追更宠物一路排除；关注的人那一路维持 1.3.4 现状（D-4，避免被拉黑方察觉）。
3. Populer 用实时聚合 + 页码分页，翻页间排名变化可能少量重复 / 遗漏，现数据量可接受。
4. @ 候选与 @ 校验排除 `DEACTIVATED`（PRD「封号各处按不存在」，现代码只排已注销）。
5. 首页话题行只用 `heat_score > 0` 的话题补位。

## 4. 实现模式与一致性规则

通用约定（命名映射、ProblemDetail、`/me`、日志脱敏等）见基线架构与根 CLAUDE.md，不重复；以下只列本线可能各做各的地方。

- **P-1 话题名规则唯一实现**：后端 `com.tailtopia.topic.domain.TopicNameRules`（解析 / 标准化 / 前 5 去重），App `lib/shared/rich_text/body_tokens.dart`；两边测试读同一份样例 `petgo-backend/src/test/resources/fixtures/topic-parse-cases.json`（App 测试按相对路径读）。禁止在别处另写正则。
- **P-2 可见性过滤四件套照抄**：举报过的帖、我隐藏的作者、作者已注销、作者非 ACTIVE。新查询（话题帖子、关注流追更一路）与 `findFollowingFeed` 现有写法逐条一致，每条各一个测试；「作者 BLOCK 了我」只在追更宠物一路加。
- **P-3 事件监听统一写法**：写库监听 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`；有扇出再加 `@Async`。新事件用 `record`：`PetFollowedEvent` 在 `social/event`，`AccountMilestoneCompletedEvent` 在 `profile/event`。
- **P-4 代码位置**：后端新模块 `com.tailtopia.topic`（`domain / repository / service / web`），后台 `admin/topic`；追更进 `social`（`PetFollowService`、`PetFollowController`）；账号级里程碑进 `profile`。App 新 `features/topic/{data,domain,presentation}`；解析器与正文组件进 `shared/rich_text/`（content / mention / topic 共用）；追更进 `features/social`。
- **P-5 字段与错误码**：JSON camelCase（`topicNames`、`iFollow`、`followerCount`、`followingUserCount`、`followingPetCount`、`reason`）；枚举值小写下划线（`followed_user / followed_pet`、`following / follower / interacted`、`latest / popular`）。话题不存在或禁用 404；追更不可见宠物 404；追更自己的宠物 422；超频 429（沿用 `RedisRateLimiter`）；通知净人数 0 → 422。全部 ProblemDetail。游标沿用 `FeedCursor` Base64URL；Populer 用 `page`。
- **P-6 时间**：「按天」一律 `LocalDate.now(ScheduleWindow.WIB)`，不各写 `ZoneId.of`；库内时间 UTC `timestamptz`。
- **P-7 幂等键定死**：`MILESTONE_ACCT:{userId}:{flagKey}`、`BACKFILL142:{userId}:{flagKey}`。
- **P-8 迁移拆分**（一件事一个，排在 `V20261030_0330` 之后）：① `pet_follows` + 计数列 ② `topics` + `content_topics` ③ `content_post_view_days` ④ `pet_diary_notify_log` ⑤ `user_milestone_flags` + 新编码里程碑行 ⑥ 账号级里程碑存量补算 ⑦ 重建 `ck_notifications_type`（当前树最后一次重建的全集 + 2）与 `ck_user_push_mutes_category`，注释写明「后合并方负责重列全集」 ⑧ `ops_notification_campaigns.skipped_no_pet_count`（R-3）。
- **P-9 埋点**：事件名写字面量（不用常量）；新前缀 `topic_`、`home_topic_` 登记到 `test/analytics/v112_events_test.dart`；属性严格按 PRD §4。
- **P-10 文案**：App 新 key 前缀 `petFollow*`、`topic*`、`followingFeedNoNew*`、`milestoneShare*`；后台严格用 PRD §7 表「建议 key」，标「复用」的引用原 key。
- **P-11 功能开关**：`feature_flags.dart` 常量命名 `kXxxEntryEnabled`；只在入口判断，不删组件。
- **P-12 测试**：后端新增 / 改动查询与 service 写单测（空集、被拉黑、已注销 / DEACTIVATED、翻页游标）；App 新组件 widget 测试覆盖稿中每种状态；两个守卫测试（`PushCategoryTest`、`MentionNotifyTest`）在同一 story 内一起改；现有测试全绿。

## 5. 项目结构与边界

路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`R/` = `petgo-backend/src/main/resources/`，`A/` = `petgo_app/lib/`。＋ 新增，～ 改动。

### 5.1 后端

```
R/db/migration/
  ＋ …__create_pet_follows.sql                               D-1
  ＋ …__create_topics_and_content_topics.sql                 D-2
  ＋ …__create_content_post_view_days.sql                    D-3
  ＋ …__create_pet_diary_notify_log.sql                      D-5
  ＋ …__milestone_account_flags_and_roster.sql               D-4（flag 表 + 新编码 roster 行）
  ＋ …__milestone_account_flags_backfill.sql                 D-4（存量补算）
  ＋ …__rebuild_ck_notifications_type_and_push_mutes.sql     D-7
B/topic/                                                     ＋ 新模块
  domain/     Topic, TopicType, TopicStatus, TopicNameRules
  repository/ TopicRepository, ContentTopicRepository
  service/    TopicService（发帖解析写关联）, TopicQueryService（详情 / 帖子 / 联想 / 首页行 / 推荐）, TopicHeatJob
  web/        TopicController
B/admin/topic/   web/AdminTopicController, service/AdminTopicService     ＋ D-18
B/social/
  ＋ domain/PetFollow, repository/PetFollowRepository, service/PetFollowService, web/PetFollowController, event/PetFollowedEvent
  ～ service/FollowService（每日额度两表合计）
  ～ service/UserHideRelationService（block 联动删追更）
B/content/
  ～ repository/ContentPostRepository（findFollowingFeed 并集；findRecommendablePets 14 天）
  ～ service/FeedService（reason、两个计数）
  ～ service/ContentService（发帖调 TopicService；出参 topicNames）
  ～ event/ContentPublishedEvent（+topicIds）
  ～ service/ContentViewStatsService（按天写入 + 清理任务）
  ～ web/…（pinned 接口加 ?topic=）
B/profile/
  ～ domain/MilestoneCatalog（+6 条）, MilestoneAutoEvent, MilestoneAutoCompleteMap
  ＋ domain/UserMilestoneFlag, repository/…, service/AccountMilestoneService, event/AccountMilestoneCompletedEvent
  ～ service/MilestoneService（assignRoster 钩子、庆祝回报按账号）
  ～ slot/PetSlotStateService（只读恢复钩子）
  ～ visitor/VisitorStats, VisitorProjectionService, InAppVisitorPetController（iFollow、followerCount）
  ～ recommend/PetRecommendationService（排除已追更）
B/growth/listener/GrowthActivityListener                     ～ 账号级一次性计分、屏蔽按宠物计分
B/notify/
  ～ domain/NotificationType（+2）, preference/PushCategory（+PET_DIARY）
  ＋ service/PetFollowNotifyListener, PetDiaryNotifyListener
  ～ service/MilestoneNotifyListener（消费账号级事件）
  ～ ops/…：OpsRecipient, OpsNotificationDispatcher, OpsNotificationCampaignService（变量替换、跳过计数）
B/auth/service/OpsAudienceQuery, OpsAudienceSql              ～ 净人数、有宠物条件、昵称 / 宠物名批量取
B/mention/web/MentionCandidateController, service/MentionSanitizer   ～ ?q=、relation、允许集合扩大
B/account/service/AccountDeletionService                     ～ 清追更者一侧
B/admin/pin/web/AdminContentPinController, service/AdminContentPinService   ～ 话题页坑位、选帖限定
B/admin/opsnotify/…                                          ～ 变量校验、预览接口、净人数
B/admin/shared/AdminPageCatalog                              ～ 话题页 + TOPIC_VIEW / TOPIC_MANAGE
R/templates/admin/
  ＋ topics.html, fragments/topics-list.html, fragments/drawer-topic.html
  ～ content-pins.html, fragments/pins-list.html, fragments/drawer-content-pin.html
  ～ ops-notification-new.html, fragments/ops-notification-steps.html, fragments/ops-notifications-list.html
R/static/admin/  ＋ admin-topics.js   ～ admin-ops-notify.js（contenteditable）   ～ admin.css（开关、变量标签）
R/i18n/messages_{zh_CN,en,id}.properties                     ～ PRD §7 后台文案
petgo-backend/src/test/resources/fixtures/topic-parse-cases.json   ＋ P-1 共用样例
```

### 5.2 App

```
A/shared/rich_text/                                          ＋ D-19
  body_tokens.dart, topic_mention_text_controller.dart, rich_body_text.dart（替代 MentionText）
A/features/topic/                                            ＋ D-13 / D-20
  data/topic_repository.dart
  domain/topic.dart, topic_suggestion.dart
  presentation/topic_page.dart, widgets/home_topic_row.dart, topic_suggest_panel.dart, topic_empty_view.dart
A/features/social/
  ＋ data/pet_follow_repository.dart, domain/pet_follow_state.dart
  ＋ presentation/widgets/pet_follow_button.dart, followed_pet_row.dart
  ～ presentation/follow_list_page.dart（Pengguna / Hewan 筛选、Hewan 空态推荐宠物）
A/features/profile/
  ～ presentation/visitor_archive_view.dart（页头合并、追更按钮、失效态两屏）
  ～ presentation/widgets/diary_header.dart（leading 参数）, pet_info_card.dart（第 4 格 Pengikut）
  ～ data/timeline_repository.dart（区分不存在 / 网络失败）
  ～ presentation/widgets/milestone_celebration.dart（按钮组吸底、多宠头像、无 KOLEKSI、容器注入）
  ～ domain/milestone_share.dart（try 包全、容器注入）
  ～ presentation/milestone_instant_celebration.dart, milestone_list_page.dart（6 处弹出点）
  ＋ presentation/widgets/milestone_share_card.dart（1:1 里程碑卡 + 花结）
  ～ domain/milestone_badge_assets.dart（first_friend、first_save）
  ～ presentation/pet_insights_page.dart（隐藏两张卡）
A/features/content/
  ～ presentation/publish_compose_page.dart（新控制器、# / @ 面板贴键盘、第 6 个话题提示）
  ～ presentation/comment_composer.dart（@ 高亮、去过滤框）
  ～ presentation/following_tab_view.dart, widgets/following_empty_view.dart（暂无新动态）
  ～ presentation/brag_post_entry.dart（preset、petIds、新来源 milestoneCelebration）
  ～ presentation/content_detail_page.dart；A/shared/widgets/masonry_card.dart（RichBodyText）
  ～ data/feed_repository.dart（pinned 带 topic）
A/features/mention/presentation/mention_picker.dart, data/mention_candidate_repository.dart   ～ 去过滤框、?q=、300ms 防抖
A/features/notify/domain/push_settings.dart, notification_item.dart; presentation/push_settings_page.dart, notification_center_page.dart   ～
A/core/router/app_router.dart（/topics/:name）, deep_link_routes.dart（两个新通知类型）   ～
A/core/config/feature_flags.dart                             ＋ D-22
home_page.dart（话题行插在 FeedTabRow 之后；PlaceEntryRow 按开关隐藏）   ～
A/l10n/app_id.arb, app_en.arb                                ～ PRD §7
petgo_app/assets/milestone/first_friend.webp, first_save.webp   ＋ 徽章素材（需设计提供）
petgo_app/test/analytics/v112_events_test.dart               ～ 新前缀
```

### 5.3 边界与集成点

- 话题 → 帖子：只在 `ContentService` 发帖时单向调 `TopicService`；话题模块只读 `content_posts` / `content_topics`，不反向依赖内容 service。
- 追更 → 通知 / 里程碑：一律通过事件（`PetFollowedEvent`、`ContentPublishedEvent`、`AccountMilestoneCompletedEvent`），不直接互调。
- 对外契约：发帖事件 `topicIds`、`PetFollowedEvent`（1.5.0 活动引擎）。
- 与电商线：只在迁移 ⑦ 与 `PushCategory` 交界。

### 5.4 需求 → 位置

| 需求 | 后端 | 后台 | App |
|---|---|---|---|
| FR-153 追更 | social、content、profile/visitor、notify | — | social、profile、notify、content |
| FR-154 里程碑 | profile、growth、notify、迁移 ⑤⑥ | — | profile |
| FR-69 话题 | topic、content | admin/topic | topic、shared/rich_text、content |
| AB-10A / 10B | admin/pin、admin/topic | 话题页、顶置页签 | — |
| FR-155 按天浏览 | content（ViewStats） | — | — |
| FR-156 发社区 | — | — | profile、content（BragPostEntry） |
| FR-157 @ 范围 | mention | — | mention、content |
| AB-37 通知变量 | notify/ops、auth（人群统计） | 通知页 | — |
| FR-165 隐藏入口 | — | — | core/config、home、profile |

**外部依赖**：两枚新徽章（`first_friend`、`first_save`）图片与里程碑卡花结 SVG 需设计出正式素材；未到前按现有 `MilestoneBadge` 兜底样式显示，到了再替换。

## 6. 架构校验

### 6.1 一致性

无新依赖、无版本冲突；P-3 覆盖 D-10 / D-12 的监听写法，P-1 化解 2.1 的前后端一致性风险。
发现并已修正 1 处矛盾：D-8 原写「主人拉黑了我则不可追更」，与 PRD「被拉黑方可新追更、不拦截」冲突 → 改为只拦「我拉黑了主人」（403），主人拉黑我不拦截且不通知主人（已改入 D-8）。

### 6.2 需求覆盖

逐条对照 FR-153（AC1–13）、FR-154（AC1–10）、FR-69（AC1–13）、FR-155（AC1–4）、FR-156（AC1–8）、FR-157（AC1–7）、AB-37（AC1–8 + 3b）、FR-165（AC1–2）及 AB-10A / AB-10B 正文：全部有架构支撑。补齐 7 处缺口（均已改入对应决策）：

| 编号 | 缺口 | 补法 | 改入 |
|---|---|---|---|
| G-1 | 「Hewan N」胶囊无数据来源 | 用户主页与 pet-follows 列表返回 `petFollowingCount` | D-8 |
| G-2 | 账号级里程碑通知打开哪只宠物 | 当前选中宠物，非 ACTIVE 则第一只 ACTIVE | D-12 |
| G-3 | 触发时无 ACTIVE 宠物 | 成长值照发；不写完成、不通知；建宠 / 恢复时补「已完成且已庆祝」 | D-12 |
| G-4 | 话题接口游客权限 | 详情 / 帖子 / 推荐 / 首页行 permitAll，联想需登录 | D-13 |
| G-5 | 后台种子发帖带话题 | `publishTrusted` 也调 `TopicService` | D-13 |
| G-6 | 发帖成功埋点区分里程碑来源 | 来源透传到发布页 | D-21 |
| G-7 | 自收藏触发首次收藏 | 里程碑监听不跳过自收藏 | D-12 |

### 6.3 非功能

- 关注流 `OR EXISTS` 可能使索引失效：L1 用真实量级跑 `EXPLAIN`，过慢改两段 `UNION` 后分页；现规模不阻塞。
- 安全：话题只暴露标准名；@ 放开后仍服务端交集校验；通知日志不记正文；后台写操作审计。

### 6.4 就绪度

关键决策均落到表 / 接口 / 类 / 测试；外部依赖仅新徽章与花结 SVG 素材（未到用兜底样式）；无待定项（按推荐定下的 5 处见 3.5）。**结论：可进入 epics / stories 拆分。**
