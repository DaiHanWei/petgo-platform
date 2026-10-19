---
title: "V1.4.0 multi-pet 主题 · 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
completedAt: '2026-10-18'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-admin-1-4-0-multi-pet-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ② 节
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md  # ① 契约
  - _bmad-output/planning-artifacts/architecture.md                                 # 跨版本基线
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md                  # 「2026-10-18」节
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-10-18'
version: v1.4.0
theme: multi-pet
status: complete
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.0 @ 8ca69f9f（= 地基 Story 0.1 + 等级线规划文档；等级线代码在 feat/1.4.0-tailrent-level 开发中）
---

# V1.4.0 multi-pet 主题 · 架构增量

> 只写本主题新增或改变的技术决定，均对照代码。**权威顺序**：`decision-log.md`（② 节）> `1-4-0多宠物prd.md` > 本文件 > UI 稿。
> 地基 Story 0.1 已落地的（`PET_SLOT_RENT`、`PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED`、`GrowthQueryService.getLevel`、`GrowthLevelChangedEvent`、抽屉「宠物与名额」页签容器）只引用、不重复设计。

## 0. 项目上下文分析（2026-10-18 核实 dev_1.4.0 @ 8ca69f9f）

### 0.1 范围与复杂度
- **FR-145** 多宠物账号与切换（名额内建档、按只删除、Diary Tab 切换器、「我的」页 / 他人主页宠物列表、按宠物计算的功能、旧版 App 兼容）；**FR-146** 名额与月租（实时计算、Rp5,000 / 月、按名额分别有效期、不自动续费、到期提醒、超额只读 + 改选、升级永久名额提示）；**FR-147** 内容多宠关联（关联表）与推荐物种（内容物种 + 用户物种按比例）；**AB-34** 后台（全部宠物、宠物与名额页签、运营补发、月租价格）。埋点 7 个。UI 稿 App 64 屏 + 后台 8 屏。
- **复杂度：很高**——拆除「一账号一宠物」全局假设：后端约 60 处调用 + 8 个跨模块读口 + 约 30 个 `/me/*` 端点 + App 19 个文件。

### 0.2 已具备（地基 Story 0.1）
`PaymentPurpose.PET_SLOT_RENT` + DB CHECK · `PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` · 抽屉「宠物与名额」空页签（`data-utab-panel="petSlots"`）· `GrowthQueryService.getLevel` · `GrowthLevelChangedEvent`。

### 0.3 现状要点（与 PRD 不一致或高风险）
1. **唯一约束 `uq_pet_profiles_owner_id`（V3:17）一删即全局 500**：`PetProfileRepository.findByOwnerId`（返回 `Optional`）全部抛 `IncorrectResultSizeDataAccessException`；`PetProfileQueryService.findIdentitiesByOwners`（:111）`toMap` 无合并函数 → 兽医工作台整页 500。**约束必须最后删，先改完全部读口。**
2. 按账号取宠物：模块侧约 60 处（profile 核心 ~25、milestone 8、passport 6、consult 5、shop/repurchase 5、idcard 4、content 3、tailsonality 3、share 3、admin 3 + 原生 SQL ~9、auth/notify 各 2、place/social 各 1）。`PetProfileQueryService` 读口 **8 个**（PRD 写 6）。
3. **删除缺陷**：`ProfileDeletionService.deleteByUserId`（:68）只删唯一一只；`idCards.markProfileDeleted(userId)`（:100）给该账号**全部**身份证打标；`AccountDeletionService`（:153）注销时多宠会被 `fk_pet_profiles_owner`（无级联）挡住。7 张表外键指向 `pet_profiles` 且无 ON DELETE（content_posts、health_events、pet_milestones、milestone_shares、place_checkin_pets、pet_passports、tailsonality_results）。
4. **按账号、多宠会串**：`VisitorProjectionService.stats`（:261）/ `completedMilestones`（:296）；`MilestoneAutoCompleteListener` 全部 `completeForOwner`（含带 petId 的 `HealthArchivedEvent` :102）；打卡「今日已打卡」`existsForUserOnDay`（`PlaceVisitRepository`:41）、提交时盖章 / 新章只按 `petIds.get(0)`（`PlaceCheckinService`:122）；里程碑打卡不校验帖子宠物（`MilestoneCheckInService`:84）；`LifecyclePushJob.petNameByOwner`（:93）顺序不确定。
5. **已有可复用**：`MilestoneCompletionService.completeForPet`（:95）；按 petId 访客端点 `/api/v1/pets/{petId}/visitor/*`（`InAppVisitorPetController`:54）；`place_checkin_pets` 多对多 + `uq_place_checkin_pets_pet_place_day`；生日 / 纪念日按宠物去重（`ScheduledPushJob` + `scheduled_push_marks`）；分享奖励已按宠物（年龄卡除外，按账号）；「我的」页 / 他人主页宠物区已按列表渲染（数据单只）。
6. **内容**：只有 Diary 写 `pet_id`（`ContentService.publish`:492-516，其他类型 :515 置 null）；按 `(authorId, petId)` 的查询约 10 处在 `ContentService`（时间线 / 日历 / 当天 / 最近 / 统计 / 推荐池 / 封面 / 打卡关联）；`ContentPublishedEvent` 构造点 3 处（:347 / :603 / :682）；`detachPet`（`ContentPostRepository`:264）。
7. **推荐物种**：`ContentSpeciesResolver`（:74）后一只覆盖前一只；`FeedRecommendationService.viewerMainSpecies`（:450）单值；`FeedRankEngine.Input.mainSpecies`（:48）单值、配额 MAIN / OTHER 两桶（:141 / :243）；`species_override` / `account_species`（`V20260824_1346`）。
8. **支付与到期先例**：`KeepsakePurchaseService`（业务行 + 购买行 + 幂等键；PawCoin 同步 `debit` / QRIS 建意图 + PENDING 行）；到账 `*PaidHandler`（`@EventListener` + `@Transactional(MANDATORY)`，按 purpose 过滤）；扫描 `SipdhExpiryScanner`（`@Scheduled` + DB 状态机、无分布式锁，互斥用 `pg_advisory_xact_lock`）；价格照 TS_MATCH 改 7 处（迁移、`PricingConfig`、Form、`AdminConfigService`、Controller、模板、i18n）。
9. **App**：`petProfileProvider`（`profile_repository.dart`:143，`FutureProvider<PetProfile?>`）**19 个**文件使用；Diary Tab（`growth_archive_page.dart`）依赖的 provider 均非 petId family；发布页 / 打卡 / AI 问诊 / 兽医咨询均无宠物选择；支付先例 `runKeepsakePurchase` + `PayChannelPicker`；本地存储 `AppPrefs`。
10. **PRD 写了、代码没有**：新手任务 N-3 / N-4 不存在（现有 S1–S5 + 健康记录）；AI 问诊不读任何宠物信息（`TriageSubmitRequest.petId` 预留未用）。

### 0.4 横切关注点
改造顺序（读口先、约束后）· petId 寻址 + 旧版 App 默认宠物（最早创建的正常宠物）· 只读校验统一拦截所有新增类接口 · 名额实时计算不落库 · 到期扫描幂等 · 按宠物级联删除 / 注销删全部 · 跨线（② 合并后 ① 流水改取 `ContentPublishedEvent.petIds`；① 升级庆祝页加「永久名额」提示块）· **单宠物账号全量回归**（最大风险）。

### 0.5 待产品拍板（第 4 步给建议）
1. AI 问诊是否真正把宠物物种 / 年龄 / 体重喂给 AI（改 prompt，需回归分诊安全规则）？
2. 现有新手任务多宠后按「任意一只达成即算」？
3. 年龄卡分享奖励维持按账号，还是改为每只宠物各领一次？

### 0.6 技术基线（沿用，不引入新依赖）
Spring Boot 4 / Java 21 / PostgreSQL + Flyway（时间戳号）；`@Async` + DB 状态机，不加 MQ / 缓存；到期扫描 `@Scheduled`，需要互斥时 `pg_advisory_xact_lock`。后台 Thymeleaf + htmx。App Flutter / Riverpod / go_router。名额与月租新代码放 `profile.slot` 包；App 改造 `features/profile`、新增 `features/pet_slot`。

### 0.7 产品拍板（2026-10-18，Dai）
- **P-1** AI 问诊**参考所选宠物的物种 / 年龄 / 体重**：作为只读上下文段拼入分诊 prompt，**不动系统指令与 `SafetyRuleLayer`**（安全规则层只升不降不可绕过），配套分诊安全回归测试。兽医咨询不涉及 AI。
- **P-2** 现有新手任务（S1–S5 + 健康记录）多宠后**按账号、任意一只宠物达成即算**；PRD 所称 N-3 / N-4 不存在，不新建。
- **P-3** 年龄卡分享奖励**维持按账号**（表结构不动）；其余分享奖励维持每只宠物各领一次，均受 `share_reward_monthly_cap` 约束。
- **P-5** 只读宠物上的「续租」按钮：有生效中月租 → 弹续租；没有 → 弹新租（数量默认 = 缺的名额数），文案仍为「续租」，付款后立即恢复。过期名额不可续（AD-6 不变）。
- **P-6** 推荐物种按比例**只拆「主物种」配额**（现行 6 / 其他 2 / 通用 2 结构保留）；运营可通过配额配置改为整列按比例，无需改代码。
- **P-7** 内容触发的里程碑（第一次被赞 / 被评论等）**归帖子关联的宠物**：Diary → 其关联宠物；Moment / Tips → 默认宠物；问诊类 → 问诊单上的宠物（无单回落默认宠物）。**取代 AD-12「账号级触发对全部宠物逐只完成」的写法。**
- **P-8** AI 问诊**单宠物用户也传宠物上下文**（不弹选择器，但带上唯一那只的物种 / 年龄 / 体重）；安全判定不变由回归测试保证。
- **P-4** 「我的宠物」接口在现有 `/pet-profiles/me/*` 上**加可选查询参数 `?petId=`**，不传 = 默认宠物（最早创建的正常宠物），旧版 App 零改动兼容；不复制端点。

## 1. 改造顺序与寻址

**AD-1 · 读口先改、约束最后删。** 删除 `uq_pet_profiles_owner_id` 的迁移放在本主题**最后一个后端 story**；之前所有 story 在「库里仍是单宠物」下可独立上线与回归。删除前须全仓无 `Optional findByOwnerId` 残留（加一个源码扫描守卫测试）。

**AD-2 · 宠物寻址（`PetProfileRepository` / `PetProfileQueryService`）。**
- 新增：`findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId)`、`findDefault(ownerId)`（最早创建且 `status = ACTIVE`，无则最早创建）、`requireOwned(ownerId, petId)`（不属于本人 → 404，不泄露存在性）。删除 `Optional findByOwnerId`。
- 约 60 处调用按用途分三类改：**按宠物操作** → 接收 petId（缺省走 `findDefault`）；**只判有无宠物** → `existsByOwnerId`；**问诊** → 用 `consult_requests.pet_profile_id`（兽医端身份展示按单号的宠物取，`findIdentitiesByOwners` 改为按宠物 id 批量）。后台原生 SQL 按「列表」改写。
- 统一解析：`PetResolver.resolve(ownerId, Long petIdOrNull)` → 有 petId 校验归属，无 petId 取默认宠物；所有 `/me/*` 控制器只经它取宠物。

**AD-3 · 接口形态（P-4）。** `/pet-profiles/me/*` 全部接受可选 `petId`；新增：`GET /api/v1/pet-profiles/me/list`（账号全部宠物 + 状态）、`DELETE /api/v1/pet-profiles/me?petId=`（按只删除；不带 petId 时仍删默认宠物，兼容旧版）、`PATCH /api/v1/pet-profiles/me?petId=`。`POST /api/v1/pet-profiles` 建档改为名额校验（满 → 409 `pet-slot-full`，含 `canRent` / `nextLevelSlot` 供 App 显示两条路；已达 5 → 409 `pet-limit-reached`）。他人主页 `GET /users/{userId}/pets`（列表，新增）；旧 `/users/{userId}/pet` 保留返回默认宠物。

## 2. 名额、月租与只读

**AD-4 · 宠物状态与 `reconcile`。**
- `pet_profiles` 加 `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK IN ('ACTIVE','READ_ONLY')`、`keep_active BOOLEAN NOT NULL DEFAULT false`（用户在 D13 选择保持正常）。
- 唯一状态变更入口 `PetSlotStateService.reconcile(userId, Trigger)`：
  - `RENTAL_EXPIRED`：若正常宠物数 > 名额 → 按（`keep_active` 优先，其次 `created_at` 最早）保留前「名额」只，其余转 `READ_ONLY`；返回被转只读的宠物（发通知用）。
  - `RENTAL_ADDED` / `RENEWED` / `LEVEL_CHANGED` / `SELECTION_CHANGED`：**只恢复不降级**——名额允许时按同一顺序把只读宠物恢复为 `ACTIVE`；改选时按新选择重排（总正常数 ≤ 名额）。
  - **降级导致名额减少不转只读**（PRD：已建宠物照常用，只是不能新建）。
- `pg_advisory_xact_lock(userId)` 串行化同一用户的 reconcile。监听 `GrowthLevelChangedEvent` 触发 `LEVEL_CHANGED`。

**AD-5 · 名额与购买表。**
- `pet_slot_rentals(id, user_id, source VARCHAR(16) CHECK IN ('RENT','ADMIN_GRANT'), starts_at, expires_at, status CHECK IN ('ACTIVE','EXPIRED'), reminded_at, expired_at, purchase_id, created_at)`，索引 `(status, expires_at)`、`(user_id, status)`。
- `pet_slot_purchases(id, user_id, kind CHECK IN ('RENT','RENEW','ADMIN_GRANT'), quantity, unit_price, total, channel, payment_intent_id UNIQUE NULL, idem_key UNIQUE, status CHECK IN ('PENDING','PAID','FAILED','EXPIRED'), rental_ids BIGINT[], days INT, operator_admin_id, reason, created_at, paid_at)`（照 `keepsake_purchases`）。
- **补充（Story 2.2 落地，2026-10-18）**：两表都加 `public_token VARCHAR(32) NOT NULL UNIQUE`（项目强制「对外标识不可枚举」——续租接口按名额寻址、App 轮询按购买单寻址，不外露自增 id）；迁移 `V20261018_1601`。`pet_slot_purchases.status` 另加 `ORPHAN_PAID`（已到账、发放失败，交人工）与 `OVERPAID`（已到账、发放会超 5 个名额而未发，交人工退款）；QRIS 行 `idem_key` 带 `:{intentId}` 后缀（同键重开另起一行、不改挂）；`pet_slot_rentals` 加 `purchase_id` 索引（Story 2.2 code review）。
- **名额 = min(5, 1 + [Lv≥5] + [Lv≥10] + 生效月租数)**，实时计算（`PetSlotService.quotaOf(userId)`，读 `GrowthQueryService.getLevel`；虚拟号 / 官方号按「不参与」= 只算默认 + 月租）。可租数 = 5 − 名额。

**AD-6 · 支付。**
- PawCoin：`PawCoinWalletService.debit(SPEND, refType=PET_SLOT_RENT, refId=purchaseId)` 同步发放（① 的 `PawCoinSpentEvent` 自动按数字服务档返成长值，② 不写成长值逻辑）。
- QRIS：建意图 + PENDING 购买行，`PetSlotPaidHandler`（`@EventListener` + `@Transactional(MANDATORY)`，按 `purpose = PET_SLOT_RENT` 过滤，照 `KeepsakePaidHandler`）到账发放；`payment_intent_id` 唯一保证回调与轮询只生效一次（AC12）。不设 QRIS 最低额。
- 新租：建 N 行 rentals，到期 = 付款成功时刻 + 30 天。续租：所选**生效中**名额 `expires_at += 30 天`；已过期名额不可续，走新租。发放后 `reconcile(RENTAL_ADDED / RENEWED)`。
- 价格读 `pricing_config.pet_slot_monthly_price`（下单时快照到购买行）。

**AD-7 · 到期扫描 `PetSlotExpiryScanner`（每小时，照 `SipdhExpiryScanner`）。**
- 提醒：`status = ACTIVE AND expires_at - 3 天 ≤ now AND reminded_at IS NULL` → 每个名额一条 `PET_SLOT_EXPIRING`，置 `reminded_at`。
- 到期：`expires_at ≤ now` → 置 `EXPIRED` → `reconcile(RENTAL_EXPIRED)` → 有宠物转只读才发 `PET_SLOT_EXPIRED`（含宠物名）。
- 两类通知 title / body 由后端按账号语言生成（同 `OPS_ANNOUNCEMENT`，决策日志）；深链到名额管理页。逐用户事务、异常隔离；靠状态列幂等，无需分布式锁。

**AD-8 · 只读拦截 `PetWriteGuard.requireWritable(petId)` → 409 `pet-read-only`。** 适用：发 Diary（逐只校验）、打卡（所选宠物）、里程碑打卡、健康记录增改删、编辑档案、AI 问诊结论存档、按宠物的付费购买（Tailsonality / 护照快照 / 登机牌）。KTP 创建只列正常宠物（卡不挂宠物）。查看、分享、收礼不拦。

## 3. 删除、内容、推荐、按宠物计算

**AD-9 · 删除。** `ProfileDeletionService.deleteByPetId(petId)`：先删 7 张无级联表中该宠物的行（`content_posts` 走 AD-10 解除关联）、再删宠物，**不再调用 `idCards.markProfileDeleted`**；名额自然释放。账号注销：遍历全部宠物 `deleteByPetId`，然后按账号 `markProfileDeleted`（AC3）。

**AD-10 · 内容多宠关联 `content_pets`。**
- `content_pets(post_id BIGINT FK content_posts ON DELETE CASCADE, pet_profile_id BIGINT FK pet_profiles ON DELETE CASCADE, PRIMARY KEY (post_id, pet_profile_id))`，索引 `(pet_profile_id, post_id)`；迁移回填存量 Diary 的 `pet_id`。
- `content_posts.pet_id` 保留为「第一只关联宠物」兼容字段：写入时同步；删宠物时若还有其他关联宠物则改为其中最早的一只，否则置空。新代码一律读 `content_pets`。
- 发布接口 `petIds`（Diary 必填 ≥ 1、逐只校验归属与可写；Moment / Tips 传了也忽略）。约 10 处按宠物查询改走关联表；Feed 一帖只出一次（不因多宠重复）。
- `ContentPublishedEvent` 末尾追加 `List<Long> petIds`（`petId` 保留 = 第一只）。对 ③ 提供 `ContentPetQuery.petsOf(postId)`；对 ③ 提供 `PetQueryService.listByOwner(userId)` / `get(petId)`（含状态）；对 App / 后台提供 `PetSlotService.quotaOf(userId)`。

**AD-11 · 推荐物种。** `ContentSpeciesResolver`：Diary 按关联宠物物种（多物种 → 通用池）；Moment / Tips 按作者全部宠物物种（多物种 → 通用池）；`species_override` / `account_species` 优先级不变。用户主物种改为权重 `Map<Species, Double>`（按宠物数，含只读、不含已删），`FeedRankEngine.Input` 由单值改为权重，槽位按权重分配、余数给宠物数多的物种（AC17）；无宠物时行为不变。同步更新 `2.数据算法/首页推荐算法.md`。

**AD-12 · 里程碑按宠物完成。** 发帖 → 对每只关联宠物 `completeForPet`；`HealthArchivedEvent` → 用其 petId；打卡已按宠物；~~与具体宠物无关的账号级触发 → 对账号下全部未删除宠物逐只完成~~（**被 P-7 取代**：内容触发归帖子关联宠物，Moment / Tips 归默认宠物，问诊类归单上宠物）。`MilestoneCheckInService` 校验帖子关联了该宠物。`MilestoneCompletedEvent.petId` 由 ① Story 1.1 追加，本线直接使用。

**AD-13 · 改为按宠物计算。** 访客统计 / 徽章墙（`VisitorProjectionService.stats` / `completedMilestones`）按 petId；打卡「今日已打卡」逐宠物判定（全部正常宠物都打过才置已打卡），提交时每只宠物各自盖章 / 新章 / 计数；`LifecyclePushJob` 固定取默认宠物；新手任务按账号任意一只达成（P-2）；AI 问诊上下文（P-1）由所选宠物生成（物种、年龄按生日换算、体重），无宠物或未选时不带。

## 4. App、后台、跨线

**AD-14 · App 宠物状态。** 新增 `petListProvider`（列表 + 状态）、`currentPetIdProvider`（持久化在 `AppPrefs`，键 `petgo.currentPetId`，失效时回落默认宠物）；**原 `petProfileProvider` 改为派生「当前宠物」**，19 个使用文件多数语义不变；Diary Tab 的 timeline / stats / calendar / milestone provider 改为按 petId 的 family；`ApiPaths` 的 `/me/*` 带 `petId`；发布 / 打卡 / AI 问诊 / 兽医咨询 / KTP 加宠物选择器（UI 稿 F / G / H / I 组规则）。

**AD-15 · 后台。** 用户抽屉「宠物档案」取全部宠物（`AdminUserService`:398）；「宠物与名额」页签（地基容器）显示名额构成、宠物状态、月租明细、购买记录；运营补发（L2 ~ L4，原生确认）权限码 `user.pet_slot_adjust`，**在 `AdminRole.OPS_MANAGER` 枚举中预授**（同 ① 4.2 发现，不写迁移）；`pricing_config.pet_slot_monthly_price`（默认 5,000，≥ 100）照 TS_MATCH 改 7 处（L7）。审计照惯例。

**AD-16 · 与 ① 的衔接。** ① 的 `/api/v1/me/growth/popups` 的 `celebration` 增加 `petSlotHint: boolean`（本次升级跨过 Lv5 / Lv10 且用户有生效中月租时为 true），由本线填充；App 升级庆祝页据此显示「永久名额」正向提示块（K2 / K3）。本线合并后，① 写成长值流水的关联宠物改取 `ContentPublishedEvent.petIds`。

**AD-17 · 迁移预分配。** 见 §6.4（按 story 先后重排）。

## 5. 实现模式与一致性规则

- **单一入口**：`/me/*` 控制器取宠物只经 `PetResolver.resolve(ownerId, petId)`；新增类接口先 `PetWriteGuard.requireWritable(petId)`；宠物状态只经 `PetSlotStateService.reconcile`；名额只经 `PetSlotService.quotaOf`。
- **禁止按账号取唯一宠物**：删除 `Optional<PetProfile> findByOwnerId`，加源码扫描守卫测试（全仓不得出现）；原生 SQL 中 `WHERE owner_id = ?` 取单行的写法同样禁止。
- **ProblemDetail 类型**：`pet-slot-full`（带 `canRent`、`nextLevelSlot`）、`pet-limit-reached`、`pet-read-only`（带 `petId`）；不属于本人的 petId 一律 404。
- **命名**：表 `content_pets` / `pet_slot_rentals` / `pet_slot_purchases`；状态值 UPPER_SNAKE；包 `profile.slot`（名额 / 月租 / 扫描 / reconcile）、`profile.service.PetResolver` / `PetWriteGuard`；App `features/pet_slot/`。
- **App 状态单一来源**：当前宠物只从 `currentPetIdProvider` 读，页面不自存 petId；切换宠物时 petId family 的 provider 整组失效重取；`petProfileProvider` 只作派生兼容层，新代码不再引用。
- **测试**：每个改造过的读口补「两只宠物不串」测试；每个新增类接口补「只读 → 409」测试；单宠物账号既有测试全绿（不删、不 skip）；推荐物种按 AC16 / AC17 写分配测试；到期扫描写幂等重放测试。
- **埋点**：沿用 PRD §4 的 7 个事件名与属性，按 App 埋点守卫登记。
- **文案**：App 不渲染后端显示串（名额到期两类通知例外：同 `OPS_ANNOUNCEMENT` 读后端 title / body）；新增 arb 取 `多宠物/1-4-0多宠物-新增文案对照表.md`。

## 6. 项目结构与 story 边界

**6.1 后端新增**：`profile.service.PetResolver` / `PetWriteGuard`；`profile.slot.{PetSlotService, PetSlotStateService, PetSlotPurchaseService, PetSlotPaidHandler, PetSlotExpiryScanner}` + 实体 / 仓储；`content.service.ContentPetQuery`；后台「宠物与名额」页签片段与服务。
**6.2 后端修改**：约 60 处调用 + 8 读口、`ProfileDeletionService`、`AccountDeletionService`、`ContentService`（发布 + 约 10 处查询）、`ContentSpeciesResolver`、`FeedRecommendationService`、`FeedRankEngine`、里程碑监听 / 打卡、`PlaceCheckinService`、`VisitorProjectionService`、`LifecyclePushJob`、`NewbieTaskService`、分诊 prompt 组装。
**6.3 App**：`petProfileProvider` 改派生兼容层 + `petListProvider` / `currentPetIdProvider`；`features/pet_slot/`；Diary Tab / 「我的」/ 他人主页 / 发布 / 打卡 / 问诊 / 兽医咨询 / KTP；`ApiPaths`。

**6.4 迁移（本主题预分配，云端不得另取号）**

| 号 | 内容 | Story |
|---|---|---|
| `V20261018_1603` | 删除 `uq_pet_profiles_owner_id`（所有读口改造之后） | 1.4 |
| `V20261018_1604` | `pet_profiles.status` / `keep_active` | 2.1 |
| `V20261018_1601` | `pet_slot_rentals` / `pet_slot_purchases` | 2.2 |
| `V20261018_1602` | `pricing_config.pet_slot_monthly_price` | 2.2 |
| `V20261018_1600` | `content_pets` + 回填存量 Diary | 3.1 |

**6.5 跨线时序**：4.4（① `/popups` 加 `petSlotHint`、① 流水改取 `petIds`）**不在 `feat/1.4.0-multi-pet` 上做**——功能分支不反向合入 dev、拿不到 ① 代码；①② 均合入 `dev_1.4.0` 后，从 dev 切对齐分支 `feat/1.4.0-multipet-align` 本地补做。1.4 的名额计算依赖 ① 的 `getLevel` 真实现——① 未合并前读地基占位（真实用户 = Lv1），名额 = 默认 + 月租，行为正确。

## 7. 校验结论（2026-10-18）

- **覆盖**：AC1 → 1.4 / 2.4 · AC2 → 1.5 · AC3 → 1.3 / 1.5 · AC3a → 1.3 / 3.3 · AC3b → 4.1 · AC4 → 1.3 / 1.5 · AC5 → 1.2（物种读口多宠安全）/ 3.2 · AC6 → 1.1 · AC7 → 1.2 · AC8 → 1.4 · AC9 / AC10 / AC13c → 2.1 / 2.4 · AC11 → 2.3 · AC12 / AC13 → 2.2 · AC13a → 4.4 · AC13b → 4.3 · AC14 / AC15 → 3.1 / 3.3 · AC16 / AC17 → 3.2；埋点：`pet_added` / `pet_switched` → 1.5，`pet_slot_full_shown` / `pet_slot_rent_*` / `pet_readonly_*` → 2.4，`diary_published.pet_count` → 3.3。
- **V-1** 1.2 必须让 `ContentSpeciesResolver` / `viewerMainSpecies` / `findIdentitiesByOwners` 在多宠下不抛异常（确定性取默认或聚合），否则 1.4 删约束后 AC5 立即回归；语义完善在 3.2。
- **V-2** 1.4 删约束前跑源码扫描守卫（AD-1）。
- **一致性**：无新依赖；单一入口规则（§5）与 AD 无冲突；地基契约（`PET_SLOT_RENT`、通知类型、抽屉容器）只引用。
