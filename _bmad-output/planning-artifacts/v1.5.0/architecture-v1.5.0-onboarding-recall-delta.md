---
title: "V1.5.0 onboarding-recall 主题（会话 ③ 首日与召回）· 架构增量"
status: complete
completedAt: '2026-11-14'
version: v1.5.0
theme: onboarding-recall
branch: feat/1.5.0-onboarding-recall（从 dev_1.5.0 切出；两份 PRD 同一分支串行：先首日与新手任务，再推送与召回）
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.5.0 @ 9680309cf（含地基 Story 0.1）
inputDocuments:
  - _bmad-output/planning-artifacts/v1.5.0/首日与新手任务/1-5-0首日与新手任务prd.md      # FR-173 ~ FR-179
  - _bmad-output/planning-artifacts/v1.5.0/首日与新手任务/ui-1-5-0-onboarding-newbie-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/推送与召回/1-5-0推送与召回prd.md              # FR-180 ~ FR-186
  - _bmad-output/planning-artifacts/v1.5.0/推送与召回/ui-1-5-0-push-recall-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.5.0/decision-log.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md   # 「2026-11-14 V1.5.0 六会话共用地基」
  - _bmad-output/implementation-artifacts/v1.5.0/0-1-六会话共用地基.md
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md     # 账号级里程碑 FR-154 / 追更 FR-153
---

# V1.5.0 onboarding-recall · 架构增量

> 只写本主题新增或改变的技术决定，均对照 `dev_1.5.0`（9680309cf）代码核实。
> **权威顺序**：`decision-log.md` > `CROSS-STORY-DECISIONS.md`（地基归属表）> PRD（变更记录最晚一条为准）> 本文件 > UI 稿。本文件与 PRD 冲突时以 PRD 为准并回改本文件；与代码现状冲突处已在 §2 列出、以代码为准。
> 决定编号：`OR-1` ~ `OR-28`（本文件内唯一，story 里按此引用）。

## 1. 范围

| 段 | 需求 | 性质 |
|---|---|---|
| A 首日 | FR-173 首日推荐页、FR-174 推荐接口排序 + 游客访问、FR-175 游客页真实宠物区 | 后端改接口 + App 新页 / 改页 |
| B 新手任务 | FR-176 新手任务 7 项账号级、FR-177 Lulus Pemula 账号级 + `NEWBIE_GRADUATED`、FR-178 账号成就区 / 账号级里程碑页、FR-179 存量补算 | 后端为主（新表 + 监听 + 迁移补算）+ App 改页 / 新页 |
| C 推送 | FR-180 关注的人发帖推送、FR-181 同帖去重 + 每人每天 3 条、FR-182 纪念日催发布、FR-186 生日改 11:00 | 后端为主 + App 通知中心 / 设置 |
| D 召回 | FR-183 D1 / D3 / D7 五层召回、FR-184 直达 + 发布页 `petId`、FR-185 开关与放量、旧版门槛 | 后端改生命周期推送 + App 直达 / 落点 |

无后台（AB）需求；唯一的后台改动是成长值规则页多出一行 `NEWBIE_GRADUATED`（复用现有规则编辑，加 i18n 标签）。

## 2. 已核实的代码现状（`dev_1.5.0`，与 PRD / UI 稿写法有出入处以代码为准）

**后端**

1. 新手任务 `profile/domain/NewbieTask.java` 6 项，`NewbieTaskService.progress` 只读聚合（S1–S5 里程碑完成 + `health_records` 存在性），**无宠物抛 404**；响应 `NewbieTaskResponse{items[{key,done}], completedCount, total, lulusPemulaUnlocked}`。
2. Lulus Pemula 按宠物判定在 `MilestoneCompletionService.checkAndUnlockLulusPemula`（`complete()` 里 `isNewbiePrereq` 触发 + `maybeUnlockLulusPemulaForOwner/ForPet`），健康记录创建时 `MilestoneAutoCompleteListener.onHealthRecordCreated` 调 `maybeUnlockLulusPemulaForPet`。编码 `MilestoneCatalog.lulusPemulaCode`：CAT `C-S16` / DOG `D-S16` / OTHER `G-S9`。
3. 账号级里程碑（1.4.2 FR-154）：`AccountMilestoneFlag{FIRST_FOLLOW_USER, FIRST_COLLECT_CONTENT}`、表 `user_milestone_flags(user_id, flag_key, occurred_at)`，**自有约束 `ck_user_milestone_flags_key`**（`V20261104_2030`，2 值；加值须新迁移 DROP + ADD 重列全集）；`AccountMilestoneService.onFlagTriggered`（REQUIRES_NEW + 账号锁 `PetSlotLock`）→ 发 `AccountMilestoneCompletedEvent(userId, flagKey, petIds, codes, targetCode)`；三个消费者：`MilestoneNotifyListener.onAccountMilestone`（**petIds 空则不发通知**）、`GrowthActivityListener.onAccountMilestone`（固定 `MILESTONE_S`）、`MilestoneAnalyticsListener`。
4. 庆祝状态只在 `milestone_completions.celebrated_at`（按宠物行）；账号级回报靠 `MilestoneCelebrationService.reportCelebrated` 把同 flag 的全部宠物行一起置位。**无宠物账号没有任何地方能记「已庆祝」**。
5. 里程碑列表 `GET /api/v1/pet-profiles/me/milestones?petId=` 只按宠物；`LegacyMilestoneCompat.forClient`（按 `X-App-Version`）已有「旧版去掉新编码并重算计数」的范式；`ClientAppVersion.isV150OrLater` 地基已加。
6. 成长值：`RuleCode` 枚举 + `growth_rules` 表（无 CHECK）；后台可编辑顺序 `AdminGrowthConfigService.RULE_ORDER`、App 等级页规则顺序 `GrowthPageQuery.RULE_ORDER`；流水幂等键只经 `GrowthIdemKeys`（账号级 `MILESTONE_ACCT:{u}:{flag}`，1.4.2 补算 `BACKFILL142:{u}:{flag}`）。
7. 一次性引导标记 `OnboardingMarkKey` 枚举（`ktp_moved`、`tailsonality_entry`，线格式小写），未登记键 422；表 `user_onboarding_marks` 无值约束。App 侧只是两个字符串常量，未知键无害。
8. 推荐宠物 `PetRecommendationService.pageFor`：候选来自 `ContentPostRepository.findRecommendablePets`（content 侧原生 SQL，keyset 游标 = `(WIB 日期, interactions, last_at, pet_id)`），之后在 profile 侧过滤（有头像 / 非本人 / 已追更 / 双向拉黑 / owner 有效）；封面 `findLatestPublicCovers` **只取 Diary（`GROWTH_MOMENT`）带图公开帖的首图，不带帖子 id、赞数**。控制器挂 `/api/v1/me/pet-recommendations` 且**刻意只认 `role=USER`**；类注释明令「实时 SQL，不许加缓存层（别加 Caffeine / Redis）」。`/api/v1/public/**` 已整体 `permitAll`。
9. `user_follows` **有 `source` 列**（`USER` / `SYSTEM`，见 `V20261104_2040` 用法）——PRD「关注表没有来源字段」不准；但 PRD 规则（自动关注目标一律不推）不受影响，照 PRD。`UserFollowedEvent` 只在手动关注新建行时发；`PetFollowedEvent(followerId, petProfileId, ownerId, followedAt)` 在追更新建时发。
10. 事件：`ContentPublishedEvent(postId, authorId, type, petId, authorGrowthMomentCount, visibility, createdAt, petIds, topicIds)`——发帖与人工审核通过补发都会发；`ContentType` 只有 `DAILY`、`GROWTH_MOMENT`（Tips 是 `DAILY` 的子类，App 侧 `KNOWLEDGE` 只出现在成长值明细）。`ContentLikedEvent(postId, likerId, authorId, createdAt)`；`ContentCommentedEvent(postId, commentId, commenterId, contentAuthorId, parentAuthorId, createdAt, parentCommentId, body, replyToAuthorId)`。
11. 追更宠物推送 `PetDiaryNotifyListener` + `PetDiaryNotifyWriter`（`pet_diary_notify_log` 每追更者 × 宠物 × WIB 日 1 条，**撞了就连通知也不写**）→ `NotificationService.sendPetDiaryPosted`：写入时按收件人语言渲染标题、`body=""`、提交后推送（虚拟号 / 官方号不推、分类闸门 `PET_DIARY`）。**无每人每天总上限**。
12. `NotificationService.sendWithCopy`（生命周期、生日、纪念日都走它）：**落库的 title / body 是印尼语兜底串，不是收件人语言渲染结果**，推送用 `messages_*` 渲染。PRD FR-182 / FR-183「通知中心存按收件人语言渲染的文案」需要新方法。通知表 `title VARCHAR(120)`、`body VARCHAR(255)`、`target_ref VARCHAR(64)`、`variant VARCHAR(16)`。
13. 生日 / 纪念日 / 第一个生日：`ScheduledPushJob` 一个日扫 `0 0 1 * * *` UTC（08:00 WIB），`ScheduledPushPlanner` 纯逻辑，**日期按 UTC**（`PetProfileSnapshot.createdDate` 按 UTC 折算），不看宠物状态、不看同主人；去重表 `scheduled_push_marks(pet_profile_id, push_kind, node_key)`；`targetRef = petProfileId`。
14. 生命周期：`LifecyclePushJob` 日扫 `petgo.lifecycle-push.cron` 默认 `0 0 12 * * *` UTC（19:00 WIB），`LifecyclePushPlanner` 纯逻辑，**today 用 UTC 日期、注册日按 UTC 折算**；分层只看「有没有默认宠物名」「`publishedCount`」；变体 `CREATE_PROFILE / RECORD / FEED / REVIEW`，`targetRef = variant.name()`，`lifecycle_push_marks.variant VARCHAR(32)`；召回节点与 D1/D3/D7 同一开关；`daily-cap` 默认 200；配置 env `LIFECYCLE_PUSH_ENABLED / LIFECYCLE_PUSH_DAILY_CAP / LIFECYCLE_PUSH_WINBACK_AFTER_DAYS / LIFECYCLE_PUSH_CRON`。候选来自 `AccountQueryService.lifecycleSnapshots()`（REAL + USER + ACTIVE 全量）。
15. 「当天打开过 App」现成数据：`user_active_days(user_id, active_date WIB)`，`UserActivityFilter` 每个已认证请求写（每人每 WIB 日一行）。
16. 地基已有：`NotificationType.USER_POST_PUBLISHED`（`PushCategory.of` → `PET_DIARY`，`ck_notifications_type` 已含，**本线不重建**）、`users.last_app_version` + `AppVersionGate`、`RetentionPushCalendar`（`recallNodeUsers` 已按 WIB 注册日实现保守判定；`anniversaryPushPets` 恒空占位，本线替换方法体）。
17. 后端埋点 `shared.analytics.AnalyticsClient.capture`（同步，`MilestoneAnalyticsListener` 范式，不加 `@Async`）。

**App**（路径相对 `petgo_app/lib/`）

18. 引导：`/onboarding/nickname` → `/onboarding/pet-status`（`pet_status_page.dart:26-36`，`PATCH /me {petStatus}` 后 `completeOnboarding` 只改本地态；后端由 petStatus 推导 `onboardingCompleted`）→ `onboarding_branch.dart` HAS_PET 去 `/onboarding/profile`、其余 `/home`；建档引导「Nanti saja」`profile_onboarding_page.dart:84` go `/home`；建档成功 → `/profile/created`（`app_router.dart:847-881`，主 CTA → `/publish`、次链接 → `/profile`）。`app_router.dart:810-814` `/onboarding` 的 redirect 注释写着「[临时·勿提交] B 方案」——存量，本线不动。
19. 发布页：`/publish` 只解析 `preset` / `date` / `milestoneCode`（`app_router.dart:1302-1316`），**没有 `petId`**；`PublishComposePage.open(initialPetIds:)` 已有（1.4.2 里程碑分享在用），`PublishLandingPage` 不透传。成功后：带 milestoneCode → 即时庆祝 → `/profile/milestones`；其他 → `/publish/done`。只有 Diary 发 `diary_published {pet_count, source?}`，**无 `entry` 属性，Moment / Tips 无成功事件**。
20. 推荐卡 `features/profile/presentation/widgets/recommended_pet_card.dart`：第二行「物种 · N hari」，点击 → `requireLogin` + `/pets/{id}`；**卡上无追更按钮、无点赞**。`PetFollowButton`（`listCompact` 变体）游客 `requireLogin(entrySource:'pet_follow')` 后 return，不自动补做。可复用 `LikeButton`（`features/content/presentation/like_button.dart`，乐观更新 + 回滚 + 未登录门控）。
21. `diary_guest_page.dart`：零网络，ListView（示例条 / DiaryHeader / 示例时间线 / 情感文案）+ 吸底 `_stickyCta`。
22. 新手任务卡 `milestone_list_page.dart:361-557` `_NewbieCard`：收起露前 4 项，行不可点；`newbie_task_labels.dart` 6 个 key 写死，**未知 key 原样显示大写 key**（旧版收到新 key 会露原文 → 必须做旧版兼容，见 OR-9）。
23. 账号级编码表 `features/profile/domain/milestone_account_codes.dart` 只有 6 个（无 Lulus）；`milestone_celebration_copy.dart` 无 Lulus 条目（正文空）。`MILESTONE_SM_NODE` 在 `deep_link_routes.dart` **无映射**（一律进通知中心）。
24. 推送：`core/push/push_service.dart:60` `kPushDirectTypes = {GROWTH_LEVEL_UP, SHOP_ORDER_REORDERED, COUPON_EXPIRING}`；`deep_link_routes.dart` 生命周期变体 RECORD → 发布页预选 Diary、FEED → `/home`、REVIEW → `/profile`、未知 / CREATE_PROFILE → `/profile/create`；`COMPANION_ANNIVERSARY` → `/profile`；`PET_DIARY_POSTED` → `/content/{id}`。
25. 通知中心：`notification_item.dart:28-37` `serverCopyTypes` 7 个，且**要求 `actorName` 非空才走后端文案**；后端文案条目固定 1 行；图标 `_iconStyle`（D7 `calendar_month_rounded`）。
26. 推送设置：arb `petFollowPushTitle`「Hewan yang kamu ikuti」/ `petFollowPushDesc`（`app_id.arb:3037-3038`、`app_en.arb:4761-4764`），用于 `push_settings_page.dart:93`。
27. l10n 只有 `app_id.arb` / `app_en.arb`。所有请求已带 `X-App-Version`（`client_info_interceptor.dart`）。埋点门面 `core/analytics/analytics.dart`，登录来源属性名 **`entry_source`**（PRD 写「登录来源属性」，以代码为准）。

## 3. 数据与迁移

### 3.1 迁移清单（预分配，本线专用号段 `V20261115_0300` ~ `V20261115_0359`，不得另取）

| # | 版本 | 文件 | story | 内容 |
|---|---|---|---|---|
| ① | `V20261115_0300` | `V20261115_0300__create_user_newbie_marks.sql` | 2-1 | 新表 `user_newbie_marks` |
| ② | `V20261115_0310` | `V20261115_0310__lulus_pemula_account_flag.sql` | 2-2 | 重建 `ck_user_milestone_flags_key`（3 值）+ `user_milestone_flags.celebrated_at` |
| ③ | `V20261115_0315` | `V20261115_0315__insert_growth_rule_newbie_graduated.sql` | 2-2 | `growth_rules` 插一行 `NEWBIE_GRADUATED` +20 |
| ④ | `V20261115_0320` | `V20261115_0320__newbie_tasks_launch_backfill.sql` | 2-4 | FR-179 存量补算（纯 SQL） |
| ⑤ | `V20261115_0330` | `V20261115_0330__create_follow_update_push_tables.sql` | 5-1 | `user_post_notify_log` + `user_post_push_daily` + `follow_update_push_quota` |
| — | `V20261115_0340` ~ `_0359` | 备用 | — | 实现中确需新迁移时按序取用，并回写 sprint-status 头注释 |

- 🔴 **本线不改 `ck_notifications_type`**（地基已含 `USER_POST_PUBLISHED`）；不改 `ck_user_push_mutes_category`（关注更新并入 `PET_DIARY`）。
- `ck_user_milestone_flags_key` 是 `user_milestone_flags` 自有约束，只有本线改；值取当前树最后一条重建它的迁移（现为 `V20261104_2030` 的 2 值）+ `LULUS_PEMULA`。
- 每条迁移幂等可重放（L1 用 `ScriptUtils` 自动提交重放）；新表小整数列用 `INT`（Hibernate int → int4），布尔用 `BOOLEAN`。
- 提交前 `bash scripts/ci/check-flyway-versions.sh origin/main`；打包一律 `mvn -B clean package`。

### 3.2 新表 / 改表

**① `user_newbie_marks`（OR-1）**——新手任务「做过」的账号记录，行为发生时写、永不撤。
```sql
CREATE TABLE IF NOT EXISTS user_newbie_marks (
    user_id     BIGINT      NOT NULL,
    task_key    VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),   -- UTC
    source      VARCHAR(16) NOT NULL DEFAULT 'RUNTIME',  -- RUNTIME / BACKFILL（只供排查与埋点区分）
    CONSTRAINT pk_user_newbie_marks PRIMARY KEY (user_id, task_key),
    CONSTRAINT fk_user_newbie_marks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_newbie_marks_key CHECK (task_key IN ('FIRST_DIARY', 'FIRST_LIKE_RECEIVED', 'FIRST_FOLLOW',
        'FIRST_LIKE_GIVEN', 'FIRST_COMMENT', 'FIRST_REPLY', 'ONE_WEEK')),
    CONSTRAINT ck_user_newbie_marks_source CHECK (source IN ('RUNTIME', 'BACKFILL'))
);
```
- 不复用 `user_milestone_flags`：那张表的每个 key 都会被 `AccountMilestoneService.applyExistingFlagsToPet` 解析成里程碑并扇到宠物上；新手任务 ④–⑦ 明确「只做任务、不新增里程碑」。
- 注销：`AccountDeletionService.execute` 显式删（users 行是匿名化不删，FK CASCADE 在注销路径不触发；同 1.4.2 flag 表）。

**② `user_milestone_flags` 改动（OR-6）**
```sql
ALTER TABLE user_milestone_flags DROP CONSTRAINT IF EXISTS ck_user_milestone_flags_key;
ALTER TABLE user_milestone_flags ADD CONSTRAINT ck_user_milestone_flags_key
    CHECK (flag_key IN ('FIRST_FOLLOW_USER', 'FIRST_COLLECT_CONTENT', 'LULUS_PEMULA'));
ALTER TABLE user_milestone_flags ADD COLUMN IF NOT EXISTS celebrated_at TIMESTAMPTZ;   -- 账号级「已庆祝」（UTC）
UPDATE user_milestone_flags SET celebrated_at = occurred_at
 WHERE celebrated_at IS NULL AND flag_key IN ('FIRST_FOLLOW_USER', 'FIRST_COLLECT_CONTENT');
```
- `celebrated_at` 只对 `LULUS_PEMULA` 有业务含义（账号级补弹的唯一判据，含无宠物账号）；另两个 flag 一律视为已庆祝（1.4.2 规则：无宠物不通知不庆祝；有宠物的走宠物行 `celebrated_at`）。

**③ `growth_rules` 插行（OR-8）**
```sql
INSERT INTO growth_rules (code, points, unit_amount, daily_cap, cap_group, enabled)
VALUES ('NEWBIE_GRADUATED', 20, NULL, NULL, NULL, true) ON CONFLICT (code) DO NOTHING;
```
（`growth_rules` 主键是 `code`，实现时核对；运营活动线也往这张表各插各的行，互不冲突。）

**⑤ 关注更新推送三表（OR-17 / OR-18）**
```sql
-- 每条 USER_POST_PUBLISHED 通知一行：发出数 / 推送数 / 未推原因（PRD §4 取数）
CREATE TABLE IF NOT EXISTS user_post_notify_log (
    follower_id  BIGINT      NOT NULL,
    post_id      BIGINT      NOT NULL,
    author_id    BIGINT      NOT NULL,
    wib_date     DATE        NOT NULL,
    push_outcome VARCHAR(16) NOT NULL,   -- PUSHED / AUTHOR_DUP / CAP_REACHED / MUTED / OLD_APP / NO_PUSH_ACCT / FAILED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_post_notify_log PRIMARY KEY (follower_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_user_post_notify_log_date ON user_post_notify_log (wib_date);
-- 同收件人 × 同作者 × WIB 日最多推 1 条（ON CONFLICT DO NOTHING 抢位）
CREATE TABLE IF NOT EXISTS user_post_push_daily (
    follower_id BIGINT NOT NULL, author_id BIGINT NOT NULL, wib_date DATE NOT NULL,
    CONSTRAINT pk_user_post_push_daily PRIMARY KEY (follower_id, author_id, wib_date)
);
-- 关注 + 追更两类合计每人每 WIB 日推送名额（原子 UPSERT 计数）
CREATE TABLE IF NOT EXISTS follow_update_push_quota (
    user_id BIGINT NOT NULL, wib_date DATE NOT NULL, used INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_follow_update_push_quota PRIMARY KEY (user_id, wib_date)
);
```
- 不加 FK、无 JPA 实体（JdbcTemplate 读写，同 `pet_diary_notify_log`）；`PetDiaryNotifyLogCleanupJob` 一并清理三表 `wib_date < 今天(WIB) − 30 天`。
- `push_outcome` 不加 CHECK（取值只在 Java 常量收口，排查表不值得为加值写迁移）。

## 4. 首日与新手任务（FR-173 ~ FR-179）

### 4.1 推荐接口（FR-174）

- **OR-2 两个入口、一套实现**：已登录仍走 `GET /api/v1/me/pet-recommendations`（契约不变、只追加字段）；**游客新开 `GET /api/v1/public/pet-recommendations?limit=`**（落在已 `permitAll` 的 `/api/v1/public/**`，不改 `SecurityConfig`、不动 `/me` 只认 USER 的既定口径）。UI 稿页头写「`/me/pet-recommendations` 允许匿名」——改为新路径，效果相同、不破坏 `/me` 语义，App 游客区调新路径。游客版只给第一页（无游标；`limit` 上限 10）。
- **OR-3 排序 = 三段 keyset**：
  - 查看者默认宠物（`DefaultPetPolicy.pick`；无宠物 / 游客 → 只有第 3 段）定出 `(petType, breedNorm)`，`breedNorm = lower(btrim(breed))`，空串视为无品种（无品种 → 跳过第 1 段）。
  - 段 0 同品种（`pp.pet_type = :type AND lower(btrim(pp.breed)) = :breed`）→ 段 1 同物种其他品种（`pp.pet_type = :type AND (breed 为空 OR lower(btrim(breed)) <> :breed)`）→ 段 2 全站（`pp.pet_type <> :type`；无宠物时不加物种条件）。三段互斥，同一只宠物不会出现两次。
  - content 侧 `findRecommendablePets` 加两个可空过滤参数（`petType`、`breedNorm`）与 `tierMode`（`SAME_BREED / SAME_SPECIES_OTHER_BREED / OTHER_SPECIES / ANY`），在现有 CTE 上 `JOIN pet_profiles pp ON pp.id = cp.pet_profile_id` 过滤（该仓库已有 `pet_profiles` join 先例，见 `ContentPostRepository.java:298`）；段内排序与游标键不变。
  - 游标 `PetRecommendCursor` 追加 `tier` 字段（编码向后兼容：旧串缺 tier → 视为段 0；坏串仍按第一页）。`pageFor` 在当前段取不满一页且该段已到底时，接着从下一段第一行续取，直到装满或三段都到底。
  - 物种匹配收口在一个方法 `PetRecommendationService.speciesKeyOf(PetProfile)`（现返回 `petType`）。🔴 物种扩展线（④，最后合）改存储方式时只改这一处与 SQL 过滤列，本线不预做。
  - 排除口径不变（有头像 / 非本人 / 已追更 / 双向拉黑 / owner 有效）；只读宠物照常推荐；不限同主人数量。
- **OR-4 返回字段追加**（`RecommendedPetResponse` 末尾加，旧字段语义不变）：`breed`（可空）、`coverPostId`（可空）、`coverLikeCount`（long，无帖为 0）、`coverLikedByViewer`（boolean，游客恒 false）、`match`（`SAME_BREED / SAME_SPECIES / GLOBAL`，埋点用）。
  - 封面口径改为「这只宠物**最近一条带图公开帖**」（`findLatestPublicCoverPosts`：`content_pets` 关联、`visibility=PUBLIC`、`status=PUBLISHED`、未删、`image_urls` 非空数组；**不再限 `GROWTH_MOMENT`**——PRD「最近一条带图的公开帖子」不限类型），一条 SQL 带出 `post_id`、首图、该帖 `content_likes` 计数；`coverLikedByViewer` 对本页帖子一次批量查（登录态）。`coverImageUrl` 与 `coverPostId` 同源，纯文字帖不当封面。旧版 App 只读 `coverImageUrl`，口径变化只是换成同一只宠物的另一张图，可接受。
- **OR-5 游客缓存不引中间件**：PRD「游客结果服务端缓存 10 分钟」用**进程内单值快照**实现——`PublicPetRecommendationSnapshot`（`AtomicReference<Snapshot(items, builtAt)>`，过期后由下一个请求重建，重建期间其他请求继续读旧值；`synchronized` 防并发重建风暴）。只缓存这一个固定结果（全站段、`limit=10`、无查看者），不做通用缓存层、不引 Caffeine / Redis。单实例部署（V1 姿态）下语义正确；类注释写清「这是 PRD 拍板的唯一例外」，并更新 `PetRecommendationService` 类头的「不许加缓存层」注释为「登录态不许缓存」。

### 4.2 首日推荐页（FR-173，App）

- **OR-7 只出现一次**：后端 `OnboardingMarkKey` 加 `FIRST_DAY_RECOMMEND("first_day_recommend")`；App 进推荐页前 `GET /me/onboarding-marks`，已有该键 → 直接进落点；展示成功后立即 `POST`（best-effort，失败不挡流程，与现有两个引导一致）。
- 引导出口（全部汇到 `/onboarding/recommend?entry=<after_post|skip_profile|skip_post|no_pet>&landing=<profile|home>`，推荐页结束 `go(landing)`）：
  1. `pet_status_page.dart` 选非 HAS_PET → `/onboarding/recommend?entry=no_pet&landing=home`；
  2. `profile_onboarding_page.dart`「Nanti saja」→ `entry=skip_profile&landing=home`；
  3. `/profile/created` 次链接 → `entry=skip_post&landing=profile`；主 CTA → `/publish?preset=growth-calendar&from=onboarding`；
  4. 发布页带 `from=onboarding`：发布成功（任何类型；有即时庆祝则在庆祝关闭后）→ `entry=after_post&landing=profile`，替换掉 `/publish/done`；在发布页返回 / 关闭不发 → `entry=skip_post&landing=profile`。
  - 推荐页：拉 `/me/pet-recommendations?limit=6`；失败或空 → 不渲染、直接 `go(landing)`（并照样写标记，PRD AC6 + 只出现一次）。
  - 卡片点击（头像 / 名字）不跳转；只有追更（`PetFollowButton` listCompact）与点赞（`LikeButton` 绑 `coverPostId`）两个动作；`coverPostId` 为空的卡不显示点赞胶囊。
- 卡片第二行改「品种 · Bersama N hari」（无品种回落物种），`RecommendedPetCard` 所有使用处一起变（社区横条用的是另一个小卡 `PetStripTile`，**不在**本次改动范围——PRD「所有推荐卡」指 `RecommendedPetCard` 的四个位置）。

### 4.3 游客展示页真实宠物区（FR-175，App）

- `diary_guest_page.dart` 情感文案块之后、吸底按钮之前插入横滑区（卡宽 140，6 张），数据来自 `GET /api/v1/public/pet-recommendations?limit=6`；失败 / 空整块不渲染（展示页其他部分照旧零网络）。
- 点帖子图 → 帖子详情 `/content/{coverPostId}`（游客可看）；点追更 / 点赞 → 现有硬登录，`entry_source` 新值 `guest_real_pets`；登录后**不**自动补做（沿用按钮现状）。

### 4.4 新手任务 7 项（FR-176）

- **OR-1（续）任务键与判定**（`NewbieTask` 改为 7 值，旧 6 值整体替换；旧逻辑挪到 `LegacyNewbieTaskCompat`，见 OR-9）：

| # | key | 记录时机（监听 → `NewbieMarkService.markIfAbsent`） | 条件 |
|---|---|---|---|
| ① | `FIRST_DIARY` | `ContentPublishedEvent` | `type == GROWTH_MOMENT`（公开 / 私密都算）→ 记 `authorId` |
| ② | `FIRST_LIKE_RECEIVED` | `ContentLikedEvent` | `likerId != authorId` → 记 `authorId`（**不排除虚拟号 / 自动点赞**） |
| ③ | `FIRST_FOLLOW` | `UserFollowedEvent`、`PetFollowedEvent` | 记 `followerId`（手动关注人或追更宠物；系统自动关注不发事件，天然不算） |
| ④ | `FIRST_LIKE_GIVEN` | `ContentLikedEvent` | `likerId != authorId` → 记 `likerId` |
| ⑤ | `FIRST_COMMENT` | `ContentCommentedEvent` | `parentCommentId == null && commenterId != contentAuthorId` → 记 `commenterId` |
| ⑥ | `FIRST_REPLY` | `ContentCommentedEvent` | `parentCommentId != null` 且被回复人 `target = replyToAuthorId != null ? replyToAuthorId : parentAuthorId`，`target != null && target != commenterId` → 记 `commenterId` |
| ⑦ | `ONE_WEEK` | 每日任务 + 读进度时 | WIB 注册日 + 7 ≤ 今天（WIB） |

  - 监听写法：`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`，写库在另一个 bean 的 `REQUIRES_NEW` 方法（`NewbieMarkService`）；`INSERT … ON CONFLICT DO NOTHING`，返回 1（真正新写）才：① 同步上报 `newbie_task_completed {task: 1–7, days_since_signup}`（`AnalyticsClient`；补算不报）；② 调 `NewbieGraduationService.checkAndGraduate(userId)`。
  - 只给参与新手任务的账号记：`role=USER`（兽医 / 管理员事件本就不会进来）；虚拟号也照记（事实，无副作用，避免判断开销）。
  - ⑦ 每日任务 `NewbieWeekJob`：`@Scheduled(cron = "${petgo.newbie.week-cron:0 5 0 * * *}", zone = "Asia/Jakarta")`（00:05 WIB），扫 WIB 注册日 ∈ [今天−8, 今天−7]（两天窗口容漏跑）且未有 `ONE_WEEK` 的 REAL USER ACTIVE 账号 → 写 `ONE_WEEK` + `checkAndGraduate`。🔴 **必须早于 19:00 WIB 跑完**（推送与召回 D7 依赖，PRD §6）；召回计划器另按「标记 ∪ 注册日」算毕业态（OR-23），不单靠本任务时序。
  - 读进度时 ⑦ 按注册日直接判定（不依赖标记），保证「不打开 App 也当天完成」与读到的一致。
- **接口** `GET /api/v1/me/newbie-tasks`（≥ 1.5.0 客户端）：没宠物也返回 200。
  ```json
  { "items": [{"key":"FIRST_DIARY","done":false}, …7 项固定顺序],
    "completedCount": 2, "total": 7, "lulusPemulaUnlocked": false,
    "hasPet": false,                       // 账号下有 ACTIVE 或只读宠物
    "oneWeekDaysLeft": 4,                  // ⑦ 未完成时剩余天数（≥1），完成为 0
    "replyGoPostId": 123 }                 // ⑥ 未完成且有人（非本人）评论过我的帖子 → 最近一条被评论的帖子 id；否则 null
  ```
  - `lulusPemulaUnlocked = true`（含按旧 6 项解锁的老用户）时 App 一律显示完成横幅（PRD）。
  - `replyGoPostId`：我作为作者、未删、`PUBLISHED` 的帖子里，最近一条「别人发的、未删」评论所在帖子 id（一条 SQL）。帖子 id 是现有对外口径（帖子详情 `/content/{id}` 本就按 id）。
- **OR-9 旧版兼容（< 1.5.0）**：旧包把未知 key 原样显示。控制器读 `X-App-Version`：旧版走 `LegacyNewbieTaskCompat`——**照搬现有 6 项只读聚合逻辑与无宠物 404**，只把 `lulusPemulaUnlocked` 改为读账号 flag（与新口径一致）。新旧两套只在这一个接口分流。

### 4.5 Lulus Pemula 账号级（FR-177）

- **OR-6 并入账号级 flag**：`AccountMilestoneFlag` 加 `LULUS_PEMULA(MilestoneAutoEvent.LULUS_PEMULA)`；`MilestoneAutoEvent` 加 `LULUS_PEMULA`；`MilestoneAutoCompleteMap` 加 `LULUS_PEMULA → C-S16 / D-S16 / G-S9`。由此自动获得：解锁时扇到全部 ACTIVE 宠物（只读不写）、之后新建 / 恢复的宠物直接「已完成且已庆祝」不发事件、庆祝回报按账号置位。
- **`NewbieGraduationService.checkAndGraduate(userId)`**：7 项全满（①–⑥ 有标记、⑦ 按注册日）且 flag 未存在 → `AccountMilestoneService.onFlagTriggered(userId, LULUS_PEMULA)`。调用点：每次新写标记后、`NewbieWeekJob`、健康记录创建时（`MilestoneAutoCompleteListener.onHealthRecordCreated` 那一行保留，被调方法换成 `checkAndGraduate(ownerId)`——PRD「录健康记录时的徽章检查保留不动，只把判定换成新 7 项」）。
- 删除按宠物判定：`MilestoneCompletionService.checkAndUnlockLulusPemula` 与 `complete()` 里 `isNewbiePrereq` 分支删除；`maybeUnlockLulusPemulaForOwner/ForPet` 改为委托 `checkAndGraduate`（保留方法名，减少调用方改动）。`MilestoneCatalog.newbiePrereqCodes / isNewbiePrereq` 若再无调用方则删除（测试同步）。
- **成长值**（OR-8）：`RuleCode` 加 `NEWBIE_GRADUATED`（加在 `ADMIN_ADJUST` 之后，不改既有顺序）；`GrowthActivityListener.onAccountMilestone`：`flagKey == LULUS_PEMULA` → `NEWBIE_GRADUATED`，否则仍 `MILESTONE_S`；幂等键不变 `MILESTONE_ACCT:{u}:LULUS_PEMULA`。后台 `AdminGrowthConfigService.RULE_ORDER` 与 App 等级页 `GrowthPageQuery.RULE_ORDER` 在 `MILESTONE_L` 后加它；后台四语 i18n `admin.v140.growth.rule.NEWBIE_GRADUATED` / `.source`；App `growth_labels.dart` 明细文案 + 图标（旧包落 `growthExpGeneric`，可接受）。
- **通知**：`MilestoneNotifyListener.onAccountMilestone`：`flagKey == LULUS_PEMULA` 时**无 ACTIVE 宠物也发**（PRD：照常解锁、发通知）；`targetRef` = 事件 `targetCode`，为空（无 ACTIVE 宠物）时取账号最早一只宠物（含只读）的 Lulus 编码，再没有则 `C-S16`。类型仍 `MILESTONE_SM_NODE`、`sendWithoutPush`、文案不写宠物名。
- **庆祝（账号级补弹）**：「已庆祝」按账号记——
  - 有宠物：照旧看宠物行 `celebrated_at`；回报 `POST /pet-profiles/me/milestones/celebrations` 含 Lulus 编码时，除全部宠物行外**同时置 `user_milestone_flags.celebrated_at`**。
  - 无宠物：账号级里程碑页按 `flag.celebrated_at IS NULL` 补弹；回报新端点 `POST /api/v1/me/account-milestones/celebrations {codes}`（同时置 flag 与该账号所有宠物的同 flag 行）。
- 补算（FR-179）解锁的 → 一律 `celebrated_at` 已填，永不补弹。

### 4.6 账号成就区与计数口径（FR-178）

- **OR-10 账号级里程碑接口（新）** `GET /api/v1/me/account-milestones`：
  ```json
  { "items": [ {"flagKey":"FIRST_FOLLOW_USER","code":"C-S19","completed":true,"completedAt":"…","pendingCelebration":false},
               {"flagKey":"FIRST_COLLECT_CONTENT","code":"C-S20",…},
               {"flagKey":"LULUS_PEMULA","code":"C-S16","completed":false,…} ],
    "completedCount": 1, "totalCount": 3 }
  ```
  - `code` 取账号默认宠物物种的编码；无宠物取 CAT 编码（App 标题 / 徽章表三物种同名同图，按 code 查即可）。
  - `pendingCelebration` 只对 `LULUS_PEMULA` 可能为 true（flag 存在且 `celebrated_at IS NULL`）。
- **OR-11 宠物里程碑列表新口径（≥ 1.5.0）**：`MilestoneListResponse` 加 `accountGroup`（结构同 `MilestoneGroupResponse`，level 用 `"ACCOUNT"`，3 项，顺序 Teman pertama / Simpan pertama / Lulus Pemula），**S 组去掉这 3 个**，顶层与 S 组 `completedCount / totalCount` 都不含它们（猫狗 35 → 32，其他 20 → 17）。旧版（< 1.5.0）保持旧形状（`LegacyMilestoneCompat.forClient` 扩一档：1.4.2 ≤ v < 1.5.0 原样含 3 个在 S 组，< 1.4.2 仍按现有规则再去掉 1.4.2 编码）。
  - 同一口径用于：作者态 Diary 统计（`ArchiveStatsResponse` 的里程碑数，按版本：≥ 1.5.0 新口径、旧版旧口径，与该版本列表页一致）；访客视图 `VisitorStats` 与 H5 名片（`CardPageController`）**一律新口径**（他人宠物、服务端直出，不分版本）。实现收口在一个 `MilestoneCounting`（`isAccountLevel(code)` 判定 + 分子分母计算），各处调用，不各写一套。
  - 未庆祝角标 `countUncelebrated` 不变（含账号级行，补弹仍走它）。

### 4.7 存量补算（FR-179，迁移 ④）

- **OR-12 一条纯 SQL 迁移**（照 `V20261104_2040` 写法：不经 Java、不发事件、单条数据修改 CTE 或多条幂等语句；账号范围 = 未注销 `deleted_at IS NULL` 且无 `account_deletions` 行）：
  1. 写 `user_newbie_marks`（`source='BACKFILL'`，`occurred_at` = 来源最早时间，`ON CONFLICT DO NOTHING`）：
     - ① 账号任一宠物（含只读）已完成 S2（`C-S2 / D-S2 / G-S2`，以 `MilestoneAutoCompleteMap.GROWTH_MOMENT_FIRST` 实际编码为准）**或**存在本人作者的 `GROWTH_MOMENT` 帖（`status='PUBLISHED'`，含软删）；
     - ② 任一宠物已完成「第一次获赞」（猫狗 S15 / 其他 G-S8，以映射表为准）**或** `content_likes` 中他人给本人帖子的赞；
     - ③ 已有 `FIRST_FOLLOW_USER` flag **或** `user_follows.source='USER'` **或** `pet_follows` 有行；
     - ④ `content_likes` 本人给他人帖子；⑤ `comments` 一级、本人、帖子作者非本人；⑥ `comments` 二级、本人、被回复评论（`COALESCE(reply_to_comment_id, parent_id)`）作者非本人；（④⑤⑥ 含软删评论——「删评不撤」，有就算）
     - ⑦ `(created_at AT TIME ZONE 'Asia/Jakarta')::date + 7 <= 今天(WIB)`。
  2. 已有按宠物 Lulus 完成行的账号 → 写 `LULUS_PEMULA` flag（`occurred_at = celebrated_at = MIN(completed_at)`）；补到账号其他 ACTIVE 宠物（完成行 `completed_at = celebrated_at = GREATEST(flag.occurred_at, 宠物建档时间)`，roster 缺失的跳过）；**不发成长值**。
  3. 未解锁且 7 项全满 → 写 flag（`occurred_at` = 7 项里最晚的那个时间，`celebrated_at` 同值）+ 全部 ACTIVE 宠物完成且已庆祝 + 成长值 `LAUNCH_BACKFILL +20`（写死分值），`idem_key = 'BACKFILL150:' || user_id || ':LULUS_PEMULA'`，排除已有运行时键 `MILESTONE_ACCT:{u}:LULUS_PEMULA` 的账号；eligible 与 `V20261018_1503` 逐字一致；`growth_accounts` 累加 + 重算 level，**不写** `celebrate_from_level / celebrate_to_level`、不碰 `backfill_popup_pending`（不弹升级庆祝）。`GrowthIdemKeys` 加 `backfillNewbie(userId)` 同格式常量供测试比对。
  4. 不写通知、不弹庆祝；可重复执行不重复发放。
- 迁移在部署启动时、监听开始工作之前跑完；部署窗口内的增量由运行时监听补上（`markIfAbsent` 幂等）。

## 5. 推送与召回（FR-180 ~ FR-186）

### 5.1 关注的人发帖推送（FR-180）+ 上限（FR-181）

- **OR-13 新监听 `UserPostNotifyListener`**（`notify/service/`，形状同 `PetDiaryNotifyListener`：`@Async @TransactionalEventListener(AFTER_COMMIT)`，无外层事务，分批 500，每人一次 `REQUIRES_NEW` 写入）：
  - 只处理 `visibility == PUBLIC`（Diary / Moment / Tips 都算）；
  - **不触发作者**：`accounts.isOfficialAccount(author)`（ADMIN）、`accounts.isVirtual(author)`、`AutoFollowTargets.contains(author)`，作者非 `isActive` 也不发；
  - 收件人 = 作者全部关注者（`user_follows.followee_id = author`，不分 source），排除：作者本人、非 `isActive`、隐藏了作者（`holdersHiding`）、被作者拉黑（`blockedByHolder`）——与 `PET_DIARY_POSTED` 同口径；
  - **同帖去重（FR-181）**：`petFollows.followersOfAny(e.petIds())` 的 key 集合（追更了帖子里任一宠物的人）**整体跳过**——这些人只走 `PET_DIARY_POSTED`，即使那边因「今天已提醒过这只宠物」没发，也不改发本类（PRD 拍板）。私密帖 / Moment 无 petIds 时集合为空。
- **写入 `UserPostNotifyWriter.write(follower, post, author, wibDate)`**（`REQUIRES_NEW`）：每次触发都写通知（不合并）——`NotificationService.sendUserPostPublished(recipient, postId, authorId, authorNickname, firstPetName, isDiary)`：标题按收件人语言渲染（Diary：`notify.tpl.userPostPublished.diary` `{0}` 昵称 `{1}` 宠物名；无宠物名 / Moment / Tips：`notify.tpl.userPostPublished.post`），`body=""`，`deepLinkType = USER_POST_PUBLISHED`、`targetRef = postId`、`variant = null`；角标 +1；同事务插 `user_post_notify_log`（初值 outcome 暂记 `MUTED`，提交后按推送决策 UPDATE）。
- **推送决策（提交后，按序，任一命中即停并记 outcome）**：虚拟号 / 官方号收件人 → `NO_PUSH_ACCT`；分类关闭（`PET_DIARY`）→ `MUTED`；`AppVersionGate.reportedV150OrLater == false` → `OLD_APP`（旧版只写通知中心）；`user_post_push_daily` 抢位失败 → `AUTHOR_DUP`；`FollowUpdatePushQuota.tryClaim` 失败 → `CAP_REACHED`；推送异常 → `FAILED`；成功 → `PUSHED`。
- **OR-14 每人每天上限（两类合计）**：`FollowUpdatePushQuota.tryClaim(userId, wibDate)`：
  ```sql
  INSERT INTO follow_update_push_quota (user_id, wib_date, used) VALUES (:u, :d, 1)
  ON CONFLICT (user_id, wib_date) DO UPDATE SET used = follow_update_push_quota.used + 1
   WHERE follow_update_push_quota.used < :cap
  RETURNING used
  ```
  有返回行 = 抢到。`cap` = `petgo.follow-update-push.daily-cap`（默认 3，env `FOLLOW_UPDATE_PUSH_DAILY_CAP`）。**只在确定要推的那一刻抢**（关了分类、旧版、虚拟号不占名额），抢到后推送失败不退还（at-most-once）。
  - `NotificationService.sendPetDiaryPosted` 提交后推送段在 `pushAllowed` 之后加 `tryClaim`，失败只留通知中心（`PET_DIARY_POSTED` 不加版本门槛——PRD 只对 FR-180 / FR-183 设门槛）。
- **OR-15 回滚开关**：`petgo.follow-update-push.enabled`（默认 `true`，env `FOLLOW_UPDATE_PUSH_ENABLED`）——关掉后本监听整条 no-op（不写通知、不推）；`PET_DIARY_POSTED` 不受影响。
- 扇出规模：排除自动关注目标与官方号后单作者粉丝 ≤ 百级，不需要额外限速（全局 `PushRateLimiter` 25 条 / 秒照常生效）。

### 5.2 纪念日催发布（FR-182）+ 生日改时（FR-186）

- **OR-16 两个日扫**（`ScheduledPushJob` 拆方法，planner 按类型分开）：
  - `runBirthdayScan`：`@Scheduled(cron = "${petgo.scheduled-push.birthday-cron:0 0 4 * * *}", zone = "UTC")`（11:00 WIB），只产出 `PET_BIRTHDAY` + 第一个生日 `MILESTONE_NODE`，规则、文案、去重、落点不变；
  - `runAnniversaryScan`：`@Scheduled(cron = "${petgo.scheduled-push.anniversary-cron:0 0 12 * * *}", zone = "UTC")`（19:00 WIB），只产出 `COMPANION_ANNIVERSARY`；受 `petgo.scheduled-push.anniversary-enabled`（默认 `true`，env `ANNIVERSARY_PUSH_ENABLED`）控制。
  - 旧配置键 `petgo.scheduled-push.cron` 废弃（application.yml 里删掉；env 若有残留无害）。
  - 🔴 `ScheduledPushPlanner.plan(LocalDate, List<PetProfileSnapshot>, Set<String>)` 签名保留（健康提醒线调它判生日占用），实现改为两个新方法的并集；`PetProfileSnapshot` 加字段时保留原 5 参构造器。
  - **日期一律 WIB**：`today = LocalDate.now(ScheduleWindow.WIB)`；`PetProfileSnapshot` 加 `createdDateWib`（建档时间按 WIB 折算）与 `status`（ACTIVE / READ_ONLY）、`ownerActive`（owner `isActive`）。生日比较的是 `birthday`（日期本身），只是 today 改 WIB（11:00 WIB 时 UTC 与 WIB 同一天，行为不变）。纪念日改按 WIB 建档日计天数：00:00–07:00 WIB 建档的宠物节点日比原来晚一天，`scheduled_push_marks` 按 `(宠物, 类型, 天数)` 去重，切换当周不会重复也不会漏。
- **纪念日计划规则（纯函数 `AnniversaryPlan.compute(snapshots, wibDate)`，planner 与 `RetentionPushCalendar` 共用）**：
  - 候选 = 当天满 30 / 100 / 365 天（WIB）的宠物；**只读宠物、owner 非 active 的剔除**（不推也不写通知，PRD AC12）；已删宠物本就不在快照里；
  - 同一 owner 当天多只 → 建档最早（`created_at, id` 升序）的那只 `push=true`，其余 `push=false`（只写通知中心）；
  - 输出 `List<AnniversaryPush(pet, owner, days, push)>`。
- **dispatcher**：`push=true` → `NotificationService.sendRendered(…, push=true)`；`push=false` → 同方法 `push=false`（只写通知）；两种都写 `scheduled_push_marks`（去重）。`targetRef = petProfileId`（不变）；不带 `milestoneCode`。
- **OR-19 通知中心存渲染后文案**：`NotificationService` 新增 `sendRendered(recipient, type, copyKey, args, fallbackTitle, fallbackBody, targetRef, boolean push)`：按收件人 `localeOf` 用 `messages_*` 渲染 title / body，**落库存渲染结果**（超长按列宽截断，title 120 / body 255，按码点不劈 emoji），推送用同一句；`push=false` 时只写通知 + 角标。纪念日与生命周期（D1/D3/D7）改用它；生日、第一个生日**不改**（仍 `sendWithCopy`，PRD 不要求，App 也不把它们放进后端文案类型）。`sendWithCopy` 签名与行为不动（健康线可能在用）。
- 文案键：`notify.COMPANION_ANNIVERSARY.title=🎉 Hari ke-{1} bareng {0}!`、`.body=Cheers buat {1} hari kalian! Abadikan gaya {0} hari ini, satu foto aja cukup 📸`（EN 同附录 A；`{0}` 宠物名 `{1}` 天数；含撇号写 `''`）。
- **OR-20 `RetentionPushCalendar.anniversaryPushPets(petIds, wibDate)` 替换方法体**：对入参宠物所属的 owner 取当天全部宠物快照（一次查询，含同 owner 未在入参里的宠物——「建档最早」要在 owner 维度比），跑 `AnniversaryPlan.compute`，返回 `push=true` 且在入参里的 petId。签名、包名不动；健康线测试里 mock。`recallNodeUsers` 节点规则（D1/D3/D7、两天窗口、WIB 注册日）本线不变，**方法体不改**（保守判定仍成立：D3 的 E 层不推、当天来过的不推都只会让实际发送更少）。

### 5.3 召回序列（FR-183）+ 开关放量（FR-185）

- **OR-21 快照扩展**：`UserLifecycleSnapshot` 改为 `(userId, registeredDateWib, lastActiveDate, publishedCount, petStatus)`（注册日按 WIB 折算；`lastActiveDate` 仍 UTC 只供召回节点用）；新增只读端口 `LifecycleContextPort`（profile + social 侧实现，**批量**，与候选数无关的查询次数）一次给出：
  - `defaultPet`：owner → `(petName, breed, petType)`（`DefaultPetPolicy`，含只读——推送只用名字 / 品种）；有任一宠物行即「有宠物」；
  - `newbieMarks`：owner → `Set<task_key>`（`user_newbie_marks` 一次 `IN` 查询）；
  - `activeToday`：`user_active_days` 中 `active_date = 今天(WIB)` 的用户集合；
  - `graduated`：有 `LULUS_PEMULA` flag 的用户集合。
  - 候选先按节点窗口（WIB 注册日 ∈ 今天 − {1,2,3,4,7,8}）在 SQL 里收窄，再取上下文（不再对全量 REAL 用户扫宠物表）；召回节点（WINBACK）开着时才额外取全量。
- **OR-22 分层**（发送当时按账号判定，自上而下取第一个）：A 没宠物（无宠物且 `petStatus != HAS_PET`，含 `petStatus` 为空）/ B 待建档（无宠物且 `HAS_PET`）/ C 待发帖（有宠物、`publishedCount == 0`）/ D 待互动（发过帖、③④⑤ 标记都没有）/ E 已互动（发过帖、③④⑤ 至少一个）。
- **OR-23 节点 × 分层 → (变体, 文案键)**（`LifecyclePushPlanner` 纯逻辑；D1 / D3 当天 `activeToday` → 不推且作废（**照样写 `lifecycle_push_marks`**，variant 记 `SKIPPED_ACTIVE`，防第二天窗口补推；不推送、不写通知）；D7 照发）：

| 节点 | A 没宠物 | B 待建档 | C 待发帖 | D 待互动 | E 已互动 |
|---|---|---|---|---|---|
| D1 | `DISCOVER`（③④⑤ 任一完成 → `FEED`）· `D1.NO_PET` | `CREATE_PROFILE` · `D1.CREATE_PROFILE`（现有） | `RECORD` · `D1.RECORD`（现有） | `DISCOVER` · `D1.SQUAD` | `RECORD` · `D1.ACTIVE` |
| D3 | 同 D1（`D3.NO_PET`） | `CREATE_PROFILE` · `D3.CREATE_PROFILE`（现有） | `DISCOVER` · `D3.SQUAD`（句子同 `D1.SQUAD`） | `DISCOVER` · `D3.BESTIE` | **不推**（不写 mark，窗口第二天仍是 E 照样不推） |
| D7 | 毕业 → `GRADUATED` · `D7.GRADUATED`；否则 `NEWBIE_TASKS` · `D7.TASKS_LEFT` | `CREATE_PROFILE` · `D7.CREATE_PROFILE`（现有） | 同 A | 同 A | 同 A |

  - 毕业判定（D7）：`graduated` 集合 **或**（①–⑥ 标记齐全，⑦ 此时按注册日必然满足）——不依赖 `NewbieWeekJob` 是否已跑；N = 7 − 已完成项（⑦ 计为完成）。
  - 文案键 `notify.LIFECYCLE_<节点>.<copy>.title/body`，参数固定 `{0}` 宠物名、`{1}` 品种词、`{2}` N；品种词 = 默认宠物 `breed`（trim 非空），否则物种词 `notify.species.<PetType>`（ID：CAT Kucing / DOG Anjing / OTHER Anabul；EN：cat / dog / pet——OTHER 两条是本文件补的默认值，见 epics「待拍板」）。附录 A 的定稿句逐字写入 `messages_id.properties` / `messages_en.properties`（`messages.properties` 默认同 EN、`messages_zh_CN.properties` 同 EN 或中文意思，照现有 `notify.LIFECYCLE_*` 的做法）。
  - 现有 REVIEW / FEED（D3 已建档）分支取消；`LifecycleVariant` 枚举**保留旧值**（旧通知 targetRef 仍可能是它们）、新增 `DISCOVER / NEWBIE_TASKS / GRADUATED / SKIPPED_ACTIVE`（`SKIPPED_ACTIVE` 只入 mark，不下发）。
  - `lifecycle_push_marks.variant` 存 `<层>_<变体>`（如 `D_DISCOVER`、`D7_GRADUATED` 记为 `E_GRADUATED` 等，≤ 32 字符）供复盘；通知 `targetRef` 只放变体名。
  - 落点（App，OR-26）：`DISCOVER` → `/pet-recommendations`；`NEWBIE_TASKS` / `GRADUATED` → 有宠物 `/profile/milestones`（默认宠物）、无宠物账号级里程碑页；`GRADUATED` 进页后由补弹机制弹 Lulus 庆祝。
  - `LifecyclePushCopyTest`：D7 `GRADUATED` / `TASKS_LEFT` 两句加例外并注释「账号级内容」；A 层与 B 层不带宠物名；其余带宠物名的规则与「禁止回来看看类空话」不变。
  - 通知中心：生命周期改 `sendRendered`（OR-19），落库即推送同一句。
- **OR-24 开关与放量**（`LifecyclePushProperties` + application.yml）：
  - `enabled`（现有，env `LIFECYCLE_PUSH_ENABLED`，默认 false）= D1 / D3 / D7 开关；
  - 新增 `winback-enabled`（env `LIFECYCLE_WINBACK_ENABLED`，**默认 false**）：false 时计划器不产出 `LIFECYCLE_WINBACK`；
  - `daily-cap` 默认 200 → **2000**（env `LIFECYCLE_PUSH_DAILY_CAP`）；上线第 1 天运维设 300，第 3 天起删 env 回默认（写进上线清单）；
  - **版本门槛**：计划结果在投递前过 `AppVersionGate.usersOnV150OrLater`（一次批量）；旧版用户本节点**跳过且不写 mark**（升级到 1.5.0 后若仍在窗口内还能收到；不写通知中心——PRD「旧版能显示后端存好的标题」只对 FR-180 成立，召回旧版不发）；
  - 节点窗口 2 天 + `(用户, 类型, 节点)` 去重 + 一人一天最多 1 条，均不变。
- 跑批时序（WIB）：00:05 新手 ⑦ → 10:00 健康提醒（① 线）→ 11:00 生日 / 第一个生日 → 19:00 纪念日 + 召回（同一时刻两条日扫并行，全局限速共用；召回一人一天 1 条，纪念日与召回同人同天可各收一条，PRD 未禁止）。

### 5.4 直达与发布页按宠物预选（FR-184，App）

- **OR-25 直达**：`kPushDirectTypes` 追加 `USER_POST_PUBLISHED`、`PET_DIARY_POSTED`、`COMPANION_ANNIVERSARY`、`LIFECYCLE_D1`、`LIFECYCLE_D3`、`LIFECYCLE_D7`（只加本线的值；健康线、运营活动线会往同一集合加，合并取并集）。点推送照常标已读（现有）。
- **OR-26 落点**（`deep_link_routes.dart` `pushPayloadToLocation`）：
  - `USER_POST_PUBLISHED` → `/content/{targetRef}`（非数字 → 通知中心）；通知中心点行先探帖子 `_postGone`（加入新类型）；
  - `COMPANION_ANNIVERSARY` → `/publish?preset=growth-calendar&petId={targetRef}`（新旧通知都是这个，旧通知 targetRef 同为宠物 id）；
  - 生命周期新变体：`DISCOVER` → `/pet-recommendations`；`NEWBIE_TASKS` / `GRADUATED` → `/me/milestones-entry`（新的轻量分发路由：有宠物 → `/profile/milestones`，否则账号级里程碑页 `/me/achievements`）；`GRADUATED` 附带 `?celebrate=lulus` 让目标页优先补弹；
  - `MILESTONE_SM_NODE` 且 targetRef 是 Lulus 编码 → `/me/milestones-entry`（其余维持现状进通知中心）。
- **发布页 `petId`**：`/publish` 解析 `petId`（int），`PublishLandingPage` 透传为 `initialPetIds: [petId]`；`PublishComposePage` 校验——不在本账号 ACTIVE 宠物里（别人的 / 已删 / 只读）→ 忽略，回到现有默认宠物逻辑，不报错。`from=onboarding` 同路径透传（OR-7）。
- 发布成功埋点 `diary_published` 加 `entry`（`push_anniversary` / `push_lifecycle`；来自 `/publish?entry=` 透传，落点 URL 由深链生成时带上），其余入口不带。

## 6. API 契约汇总

| 方法 | 路径 | 变化 | 鉴权 |
|---|---|---|---|
| GET | `/api/v1/me/pet-recommendations` | 排序三段；响应追加 `breed / coverPostId / coverLikeCount / coverLikedByViewer / match`；游标追加 tier（兼容旧串） | USER |
| GET | `/api/v1/public/pet-recommendations?limit=` | **新**；全站段第一页，10 分钟进程内快照，`coverLikedByViewer` 恒 false | 匿名 |
| GET | `/api/v1/me/newbie-tasks` | ≥ 1.5.0：7 项 + `hasPet / oneWeekDaysLeft / replyGoPostId`，无宠物 200；< 1.5.0：旧 6 项旧逻辑 | USER |
| GET | `/api/v1/me/account-milestones` | **新** | USER |
| POST | `/api/v1/me/account-milestones/celebrations` | **新** `{codes:[...]}` → `{marked}` | USER |
| GET | `/api/v1/pet-profiles/me/milestones` | ≥ 1.5.0：加 `accountGroup`，S 组与计数不含账号级 3 项；旧版不变 | USER |
| POST | `/api/v1/pet-profiles/me/milestones/celebrations` | 含 Lulus 编码时同时置 flag `celebrated_at` | USER |
| POST | `/api/v1/me/onboarding-marks` | 接受新键 `first_day_recommend` | USER |

错误一律 RFC 9457（`AppException`）；对外不新增自增 id 以外的标识（`coverPostId` / `replyGoPostId` 是现有帖子详情口径）。

## 7. 旧版 App 兼容（< 1.5.0）

| 接口 / 推送 | 旧版行为 |
|---|---|
| 新手任务 | 旧 6 项旧逻辑（OR-9），`lulusPemulaUnlocked` 读账号 flag |
| 里程碑列表 / Diary 统计 | 旧形状旧口径（OR-11） |
| 访客视图 / H5 名片里程碑数 | 新口径（分母变小，可接受） |
| 推荐接口 | 多出的字段旧包忽略；封面换成「最近带图公开帖」 |
| 成长值 `NEWBIE_GRADUATED` 流水 | 旧包显示通用文案 |
| Lulus 通知（`MILESTONE_SM_NODE`） | 照旧进通知中心，旧包按 code 本地化标题 |
| 关注更新 `USER_POST_PUBLISHED` | 只写通知中心不推送（`OLD_APP`）；旧包未知类型显示通用系统文案 |
| 纪念日 | 照推；旧包点开落 `/profile`（PRD 接受）；通知中心显示旧固定文案 |
| 召回 D1/D3/D7 | 不发、不写通知、不写 mark |
| 生日 | 只改时间 |

## 8. 配置项汇总（全部 env 注入，`.env.example` 只放占位与默认）

| 键 | env | 默认 | 用途 |
|---|---|---|---|
| `petgo.lifecycle-push.enabled` | `LIFECYCLE_PUSH_ENABLED` | false | D1/D3/D7 总开关（上线时运维打开） |
| `petgo.lifecycle-push.winback-enabled` | `LIFECYCLE_WINBACK_ENABLED` | false | 召回节点（本版不开） |
| `petgo.lifecycle-push.daily-cap` | `LIFECYCLE_PUSH_DAILY_CAP` | 2000 | 每日上限（第 1 天设 300） |
| `petgo.follow-update-push.enabled` | `FOLLOW_UPDATE_PUSH_ENABLED` | true | FR-180 回滚开关 |
| `petgo.follow-update-push.daily-cap` | `FOLLOW_UPDATE_PUSH_DAILY_CAP` | 3 | FR-181 两类合计每人每天 |
| `petgo.scheduled-push.anniversary-enabled` | `ANNIVERSARY_PUSH_ENABLED` | true | FR-182 回滚开关 |
| `petgo.scheduled-push.birthday-cron` | `BIRTHDAY_PUSH_CRON` | `0 0 4 * * *`（UTC） | 11:00 WIB |
| `petgo.scheduled-push.anniversary-cron` | `ANNIVERSARY_PUSH_CRON` | `0 0 12 * * *`（UTC） | 19:00 WIB |
| `petgo.newbie.week-cron` | `NEWBIE_WEEK_CRON` | `0 5 0 * * *`（Asia/Jakarta） | ⑦ 每日判定 |
| `petgo.public-pet-recommendations.ttl` | `PUBLIC_PET_RECOMMEND_TTL` | `PT10M` | 游客快照有效期 |

## 9. 护栏核对

- 不引 MQ / 通用缓存 / 新中间件：调度用原生 `@Scheduled`；游客快照是进程内单值（OR-5，PRD 拍板的唯一缓存）；计数用 PostgreSQL UPSERT。
- `ddl-auto=validate`：新表无实体或实体字段与列一致；`user_milestone_flags.celebrated_at` 若映射进 `UserMilestoneFlag` 实体须同步加字段。
- 写库监听一律 `AFTER_COMMIT` + 另一 bean 的 `REQUIRES_NEW`（2026-07 事务吞写事故）。
- 日志不记昵称 / 宠物名 / 文案 / 评论原文；只记 id、计数、异常类名。
- 注销：`user_newbie_marks` 显式删；`user_milestone_flags` 已有显式删；三张推送日志表无 PII、30 天清理。
- 安全攸关节点（红色态零变现、安全规则层、注销级联）本线不触碰。

## 10. 跨线约定（同时写进 `cloud-rules-onboarding-recall.md`）

1. `ck_notifications_type` 由地基定义，本线**不重建**；`NotificationType` / `PushCategory` 只用不改。
2. 版本门槛只走 `AppVersionGate`。
3. `RetentionPushCalendar`：本线替换 `anniversaryPushPets` 方法体（OR-20），`recallNodeUsers` 不改；签名 / 包名不动。
4. App `kPushDirectTypes` 只追加本线 6 个值，合并取并集。
5. 推送设置「Hewan yang kamu ikuti」那行改名归本线（arb `petFollowPushTitle / petFollowPushDesc` 改值，key 不改）。
6. Feed 双击点赞（⑤ 线）在本线首日部分之后合并，会复用 `LikeButton` 并作用到本线推荐卡——本线推荐卡点赞**只用现有 `LikeButton` 的公开参数**，不 fork、不改它的内部状态机。
7. 自动点赞（⑤ 线）新增 `content_likes.is_automated` 与 `ContentLikedEvent.automated`（保留 4 参构造器），自动点赞同样发该事件、**计入**新手任务 ②；本线监听不得按 `automated` 过滤，补算 SQL 不按 `is_automated` 过滤。
11. 守卫测试 `PushExitStructureTest.PUSHER_CALLER_METHODS`：本线新增 `sendUserPostPublished`、`sendRendered`，健康线也会加，合并取并集。
8. 物种扩展（④ 线，最后合）：推荐同物种匹配收口在 `speciesKeyOf` + SQL 过滤列一处；新手任务已与物种无关；Lulus 编码仍按大类 `PetType`。
9. 成长值 `NEWBIE_GRADUATED` 用独立迁移插一行；运营活动线也往 `growth_rules` 插各自的行，互不冲突。
10. 健康提醒线（①）调用 `RetentionPushCalendar` 两个方法、共用 `AppVersionGate`；生日改 11:00 后健康提醒 10:00「当天有生日推送就让开」规则不变。
