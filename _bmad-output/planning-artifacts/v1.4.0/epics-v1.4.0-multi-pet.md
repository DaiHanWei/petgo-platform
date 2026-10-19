---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md                         # ② 节
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-admin-1-4-0-multi-pet-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md  # ① 契约
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md                  # 「2026-10-18」节
version: v1.4.0
theme: multi-pet
status: complete
updated: '2026-10-18'
---

# petgo-platform V1.4.0 multi-pet - Epic Breakdown

## Overview

本文件把 V1.4.0 ②「多宠物（名额 · 月租 · 多宠关联）」PRD（FR-145 / FR-146 / FR-147 · AB-34）、UI 稿与架构增量拆成可实现的 story。范围：后端 + 运营后台 + App。分支 `feat/1.4.0-multi-pet`（从 `dev_1.4.0` 切出）。

> 🔴 **口径优先级**：`decision-log.md` ② 节 > PRD > `architecture-v1.4.0-multi-pet-delta.md`（AD-1 ~ AD-17、P-1 ~ P-4、V-1 / V-2）> UI 稿（定稿）。跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18」节为准。
> 🔴 **改造顺序**：Epic 1 先把全部读口改为多宠安全，**1.4 才删唯一约束**；此前任何 story 都在「库里仍是单宠物」下独立可上线。

## Requirements Inventory

### Functional Requirements

**FR-145 多宠物账号与切换**
- **FR-145.1** 名额内建多只宠物；名额满提示升级 / 月租两条路；已达 5 只单独提示。
- **FR-145.2** 按只删除（物理删除范围沿用 FR-39A，Diary 保留但解除关联），名额释放；不再给身份证误打「档案已删」；删最后一只沿用现状（`pet_status` 不改）。
- **FR-145.3** Diary Tab 顶部切换器，时间线 / 日历 / 统计 / 里程碑 / 健康记录 / 了解入口跟随；记住上次查看的宠物；首页推荐不跟随。
- **FR-145.4** 「我的」页宠物列表（圆形头像一排 + 添加），他人主页宠物列表（> 4 只横滑），访客视图统计 / 徽章墙按宠物。
- **FR-145.5** 按宠物计算的功能：里程碑 / 健康 / 名片 H5 / 年龄卡 / 护照 / 登机牌 / Tailsonality 跟随当前宠物；KTP 创建选宠物（只列正常、默认当前宠物）；打卡选宠物（默认全不选、只一只时自动选、今日已打卡按宠物）；AI 问诊与兽医咨询多宠时先单选（只读可选不可存档），AI 回答参考所选宠物物种 / 年龄 / 体重（P-1）。
- **FR-145.6** 分享奖励每只宠物各一次（年龄卡按账号，P-3），受账号月度上限；新手任务按账号任意一只达成（P-2）；生日 / 纪念日按宠物分别推送。
- **FR-145.7** 旧版 App 兼容：`/me/*` 不带 petId 返回默认宠物（最早创建的正常宠物），不强制更新。

**FR-146 宠物名额与月租**
- **FR-146.1** 名额 = min(5, 1 + [Lv≥5] + [Lv≥10] + 生效月租)，实时计算；降级时已建宠物保留但不能新建。
- **FR-146.2** 月租 Rp5,000 / 名额 / 月（后台可配），QRIS / PawCoin，无 QRIS 最低额；一次租 1 ~（5 − 名额）个；按名额分别 30 天有效、续租顺延；不自动续费；按数字服务档返成长值（由 ① 自动处理）。
- **FR-146.3** 到期前 3 天提醒、到期当天通知（仅有宠物因此只读时发），通知深链名额管理页，后端生成文案。
- **FR-146.4** 到期后超出名额的宠物只读（用户选择优先，否则最早创建优先保留），可在名额管理页改选；只读禁止所有新增记录类操作（发 Diary、被选为打卡宠物、里程碑打卡、健康记录、编辑档案、按宠物的付费购买），查看 / 分享 / 收礼照常；续租立即恢复。
- **FR-146.5** 名额管理页：构成（默认 / 等级 / 月租）、每个月租名额到期日、租用、续租（勾选多个一次付款）、选择保持正常的宠物。
- **FR-146.6** 升级获得永久名额且有生效月租时，升级庆祝页显示正向提示（不出现闲置 / 浪费 / 不退款）。

**FR-147 内容多宠关联与推荐物种**
- **FR-147.1** Diary 必须至少选一只、可多选；多宠 Diary 在 Feed 只出现一次、各宠物时间线都收录；Moment / Tips 不关联宠物；只读宠物不可选；删宠物只解除关联。
- **FR-147.2** 内容物种：Diary 按关联宠物（多物种 → 通用池）；Moment / Tips 按作者物种（多物种 → 通用池）；运营覆写优先级不变。
- **FR-147.3** 用户主物种按宠物数比例分配权重与槽位（余数给多的物种），含只读、不含已删；首页不跟随切换。

**AB-34 宠物与名额管理（后台）**
- **AB-34.1** 用户抽屉「宠物档案」显示全部宠物；「宠物与名额」页签显示名额构成、宠物状态、月租明细、购买记录。
- **AB-34.2** 运营补发 N 天名额（原因必填、原生二次确认，购买记录标「运营补发」+ 操作人），权限 `user.pet_slot_adjust`（预授 OPS_MANAGER），写审计。
- **AB-34.3** 配置页新增「宠物名额月租价格」（默认 Rp5,000），只影响之后的新订单与续租。

**埋点（FR-AN）**：`pet_added`、`pet_switched`、`pet_slot_full_shown`、`pet_slot_rent_started` / `pet_slot_rent_paid`、`pet_readonly_banner_shown`、`pet_readonly_renew_tapped`、`diary_published.pet_count`。

### NonFunctional Requirements
- **NFR1 回归**：单宠物账号全部既有功能不变，既有测试全绿（不删、不 skip）——本线最大风险。
- **NFR2 改造安全**：唯一约束删除前全仓无「按账号取唯一宠物」（源码扫描守卫）。
- **NFR3 幂等**：月租回调与轮询只生效一次；到期扫描可重放。
- **NFR4 一致性**：宠物状态只经 `reconcile`、取宠物只经 `PetResolver`、可写校验只经 `PetWriteGuard`。
- **NFR5 安全**：不属于本人的 petId 一律 404；分诊安全规则层不动（只升不降不可绕过）。
- **NFR6 护栏**：不引 MQ / 缓存；`ddl-auto=validate`；Flyway 时间戳号（预分配 1600 ~ 1604）。
- **NFR7 本地化**：App 不渲染后端显示串（名额到期两类通知例外）；新增文案取对照表。

### Additional Requirements
（取自 `architecture-v1.4.0-multi-pet-delta.md`）AD-1 改造顺序 · AD-2 / AD-3 寻址与 `?petId=` · AD-4 状态与 `reconcile` · AD-5 名额 / 购买表 · AD-6 支付 · AD-7 到期扫描 · AD-8 只读拦截 · AD-9 删除 · AD-10 `content_pets` · AD-11 推荐物种 · AD-12 里程碑按宠物 · AD-13 按宠物计算 · AD-14 App 状态 · AD-15 后台 · AD-16 与 ① 衔接 · §6.4 迁移号。

### UX Design Requirements
- **UX-DR1（A0 ~ A6）**「我的」页宠物列表 / 名额满抽屉 / 已达 5 只 / 第 N 只建档。
- **UX-DR2（B1 ~ B6）** Diary Tab 切换器（含只 1 只、无宠物、切换失败、只读）。
- **UX-DR3（C1 ~ C6）** 删除入口 / 确认（非最后一只新正文）/ 删除中 / 成功 / 失败 / 删最后一只后。
- **UX-DR4（D1 ~ D15）** 名额管理页、租用抽屉（`PayChannelPicker` 加数量步进器 + 总价）、余额不足、QRIS、成功 / 失败 / 过期（仅名额流程补 SnackBar）、续租勾选、改选保持正常。
- **UX-DR5（E1 ~ E3）** 只读拦截抽屉（仅续租）、只读里程碑页、健康记录页标题「Catatan Kesehatan · {name}」。
- **UX-DR6（F1 ~ F6c）** 发布页选宠物（只 1 只自动选）、Moment / Tips 无选择、发布成功（分享取第一只）、详情页关联宠物胶囊（有图 / 无图 / ≥ 3 只头像 + dkk.）。
- **UX-DR7（G1 ~ G4）** 打卡选宠物、多只盖章、「Rekam Momen Ini」默认选中本次打卡宠物。
- **UX-DR8（H1 ~ H4）** 问诊 / 兽医咨询选宠物、只读存档拦截（黄色结果同样拦截）。
- **UX-DR9（I1 ~ I3）** KTP 预填当前宠物（只读则取最早正常宠物）、换宠物抽屉。
- **UX-DR10（J0 ~ J3）** 他人主页宠物列表、访客视图按宠物。
- **UX-DR11（K1 ~ K3）** 通知列表两类到期、升级庆祝永久名额提示（并入「+1 slot hewan」行内说明）。
- **UX-DR12（L0 ~ L7）** 后台宠物档案全部宠物、宠物与名额页签、补发、无权限、无记录、月租价格配置。

### FR Coverage Map
- FR-145.1 → 1.4 / 1.5 / 2.4 · FR-145.2 → 1.3 / 1.5 · FR-145.3 → 1.1 / 1.5 · FR-145.4 → 1.3 / 1.5 · FR-145.5 → 1.3 / 3.3 / 4.1 / 4.2 · FR-145.6 → 1.2 / 1.3 · FR-145.7 → 1.1
- FR-146.1 → 1.4 / 2.1 · FR-146.2 → 2.2 / 2.4 · FR-146.3 → 2.3 · FR-146.4 / FR-146.5 → 2.1 / 2.4 · FR-146.6 → 4.4
- FR-147.1 → 3.1 / 3.3 · FR-147.2 / FR-147.3 → 3.2
- AB-34 → 4.3 · FR-AN → 1.5 / 2.4 / 3.3

## Epic List

### Epic 1: 一个账号能养多只宠物
把全部「按账号取唯一宠物」改为按宠物寻址，按只删除、按宠物计算，名额按默认 + 等级计算，最后删除唯一约束；App 支持宠物列表、Diary Tab 切换、他人主页列表、建档与删除。
**FRs covered:** FR-145.1 ~ .4、FR-145.6、FR-145.7、FR-146.1（默认 + 等级）· **迁移:** `V20261018_1603`

### Epic 2: 月租名额与只读
用户按月租用名额、续租、到期提醒；名额不足时超出的宠物只读并可改选；只读宠物被统一拦截。
**FRs covered:** FR-146.1 ~ .5 · **迁移:** `V20261018_1604`、`V20261018_1601`、`V20261018_1602`

### Epic 3: 多宠内容与推荐
一条 Diary 关联多只宠物，内容与用户物种按多宠规则进推荐；发布与打卡选择宠物。
**FRs covered:** FR-147.1 ~ .3、FR-145.5（打卡）· **迁移:** `V20261018_1600`

### Epic 4: 按宠物的服务与后台
AI 问诊 / 兽医咨询 / KTP 选宠物（AI 参考宠物信息）；后台宠物与名额管理；升级庆祝的永久名额提示（与 ① 衔接）。
**FRs covered:** FR-145.5（问诊 / 咨询 / KTP）、FR-146.6、AB-34.1 ~ .3

**依赖：** Epic 1 → 2 → 3 → 4（只向后）；**4.4 不在本功能分支做**，①② 都合入 `dev_1.4.0` 后从 dev 切对齐分支补做（含 ① 流水改取 `petIds`）。

## Epic 1: 一个账号能养多只宠物

### Story 1.1: 宠物寻址改造（一）——profile 核心、内容与 Diary 系列

As a 多宠物用户,
I want 「我的宠物」相关接口都能指定是哪只宠物,
So that 每只宠物的档案、时间线、日历和统计互不串。

**范围：** 后端。AD-2 / AD-3：`PetResolver`、仓储新增 `findAllByOwnerIdOrderByCreatedAtAscIdAsc` / `findDefault` / `requireOwned`；`ProfileApiController` 全部 `/me/*`（档案、timeline、calendar、day、archive-stats、card-shares、身份证与分享奖励组）、`HealthRecordController`、`MilestoneController`、`TailsonalityController`、`BoardingPassController`、`PetPassportController` 加可选 `petId`；新增 `GET /pet-profiles/me/list`；`ProfileService` / `TimelineService` / `HealthRecordService` / `HealthEventTimelineSourceImpl` / `ContentService` 按 `(authorId, petId)` 的读口改为接收 petId。本 story **不删**唯一约束。

**Acceptance Criteria:**

**Given** 单宠物用户（库里仍有唯一约束）
**When** 不带 petId 调用任一 `/me/*`
**Then** 返回与改造前完全一致（AC6 旧版兼容），既有测试全绿

**Given** 带 petId 调用 `/me/timeline?petId=X`
**When** X 属于本人
**Then** 返回 X 的数据；X 不属于本人或不存在 → 404（不泄露存在性）

**Given** 用户调用 `GET /pet-profiles/me/list`
**When** 有 1 只宠物
**Then** 返回 1 项（含 id、名字、头像、物种、创建时间；状态字段在 2.1 加入前默认 ACTIVE）

**Given** 测试夹具绕过约束为同一账号造两只宠物（单元 / 切片测试层）
**When** 分别用两个 petId 调用时间线、日历、统计、健康记录、里程碑列表
**Then** 两只宠物的数据不串；不带 petId 时返回默认宠物（最早创建）

**And** 本 story 改造的读口均经 `PetResolver`；`mvn -B clean package` 绿。

### Story 1.2: 宠物寻址改造（二）——其余模块与跨模块读口

As a 多宠物用户,
I want 里程碑、护照、问诊、身份证、分享、推送、后台等所有模块都能正确处理多只宠物,
So that 账号有第二只宠物时不会出现报错、白屏或数据串错。

**范围：** 后端。`PetProfileQueryService` 8 个读口改造（`findIdentitiesByOwners` 改为按宠物 id 批量）、milestone（8 处）、passport（6）、consult（5，改用 `consult_requests.pet_profile_id`）、shop/repurchase（5）、idcard（4）、tailsonality（3）、share（3）、notify / lifecycle（`LifecyclePushJob` 固定取默认宠物）、auth / onboarding（判有无宠物）、admin 原生 SQL（约 9 处，按列表）、social（`FollowSuggestionService`）。**V-1**：`ContentSpeciesResolver`、`FeedRecommendationService.viewerMainSpecies` 在多宠下确定性取值、不抛异常（语义完善在 3.2）。分享奖励：每只宠物各一次、年龄卡按账号（P-3）。删除 `Optional findByOwnerId` 并加源码扫描守卫测试（AD-1 / NFR2）。

**Acceptance Criteria:**

**Given** 测试夹具为同一账号造两只宠物
**When** 调用兽医工作台列表、问诊身份展示、Tailsonality、护照、登机牌、身份证、复购推荐、后台用户详情
**Then** 均不抛异常，按各自规则取对的宠物（问诊按单号上的宠物）

**Given** 两只宠物各分享一次宠物身份证
**When** 领取奖励
**Then** 各领一次；当月累计达到 `share_reward_monthly_cap` 后不再发放（AC7）；年龄卡仍按账号一次

**Given** 有 2 只宠物的账号请求首页推荐
**When** 组装用户物种与内容物种
**Then** 不抛异常、不退回时间倒序（AC5 的安全前提）

**Given** 源码扫描守卫测试
**When** 运行
**Then** 全仓不存在返回单个 `Optional<PetProfile>` 的按 owner 查询，原生 SQL 无按 owner 取单行写法

**And** 单宠物账号既有测试全绿；`mvn -B clean package` 绿。

### Story 1.3: 按宠物删除、按宠物计算

As a 多宠物用户,
I want 删除一只宠物不影响其他宠物，且访客统计、里程碑、打卡都按宠物算,
So that 每只宠物的记录准确独立。

**范围：** 后端。AD-9：`ProfileDeletionService.deleteByPetId`（先清 7 张无级联表、`detachPet` 只解除该宠物、不调用 `markProfileDeleted`）；`DELETE /pet-profiles/me?petId=`（不带 = 默认宠物）；账号注销遍历删除全部宠物后按账号 `markProfileDeleted`。AD-12 / AD-13：`MilestoneAutoCompleteListener` 按宠物（`HealthArchivedEvent` 用其 petId；账号级触发对全部宠物逐只）；`MilestoneCheckInService` 校验帖子关联该宠物；`VisitorProjectionService.stats` / `completedMilestones` 按 petId；打卡「今日已打卡」逐宠物、提交时每只宠物各自盖章 / 新章 / 计数；新手任务按账号任意一只（P-2）。

**Acceptance Criteria:**

**Given** 账号有 A、B 两只宠物和两张身份证
**When** 删除 A
**Then** A 的健康 / 里程碑 / 分享记录 / 打卡 / 护照 / Tailsonality 被删，A 的 Diary 保留但解除关联；B 的数据与两张身份证均不受影响、不被打「档案已删」（AC3）

**Given** 多宠账号注销
**When** 注销执行
**Then** 全部宠物被删除，注销不被外键挡住，身份证按账号打标

**Given** 访客查看 B 的访客视图 / 名片 H5
**When** 加载统计与徽章
**Then** 只统计 B（AC4）

**Given** A、B 今天已在场所 P 打卡过 A
**When** 查询 P 的打卡状态
**Then** 返回逐宠物结果（A 已打、B 未打），全部正常宠物都打过才视为「今日已打卡」；同时选 A、B 打卡时两只各盖一个章（AC3a 后端部分）

**Given** 只关联 A 的帖子
**When** 用 B 做里程碑打卡
**Then** 拒绝

**And** 新手任务任意一只宠物达成即完成；`mvn -B clean package` 绿。

### Story 1.4: 名额计算与解除单宠物限制

As a Lv5 及以上的用户,
I want 用等级解锁的名额建第二、第三只宠物,
So that 我的每只宠物都有地方记录。

**范围：** 后端。`PetSlotService.quotaOf(userId)`（本 story 只算默认 1 + [Lv≥5] + [Lv≥10]，封顶 5；月租部分在 2.2 接入）；`ProfileService.create` 改为名额校验（409 `pet-slot-full` 带 `canRent` / `nextLevelSlot`；已 5 只 → 409 `pet-limit-reached`）；迁移 `V20261018_1603` 删除 `uq_pet_profiles_owner_id`（V-2：先跑源码扫描守卫）；`GET /users/{userId}/pets` 列表（旧 `/pet` 保留返回默认宠物）。

**Acceptance Criteria:**

**Given** Lv1 无月租用户已有 1 只
**When** 建第二只
**Then** 409 `pet-slot-full`（AC1 / AC8：名额 1）

**Given** Lv5 用户（名额 2）
**When** 建第二只、再建第三只
**Then** 第二只成功；第三只 409 `pet-slot-full`；Lv10 用户名额 3（AC8）

**Given** Lv10 用户有 3 只，降到 Lv9
**When** 查询与建第 4 只
**Then** 3 只照常；建第 4 只 409（AC9）

**Given** 已有 5 只
**When** 建第 6 只
**Then** 409 `pet-limit-reached`

**Given** 并发两次建档请求
**When** 名额只剩 1
**Then** 只成功一只（按账号串行化）

**And** 他人主页 `GET /users/{userId}/pets` 返回全部宠物（按创建时间）；虚拟号 / 官方号名额按「不参与」= 默认 1；`mvn -B clean package` 绿；L1 在 scratch 库验证迁移。

### Story 1.5: App——宠物列表、Diary Tab 切换、他人主页、建档与删除

As a 多宠物用户,
I want 在「我的」页看到所有宠物、在 Diary Tab 切换、在他人主页看到对方所有宠物,
So that 我能分别记录和浏览每只宠物。

**范围：** App。AD-14：`petListProvider`、`currentPetIdProvider`（`AppPrefs` 键 `petgo.currentPetId`）、`petProfileProvider` 改派生兼容层；Diary Tab 相关 provider 改 petId family；`ApiPaths` 带 petId。UI 稿 A0 ~ A6（名额满抽屉 A4 的「月租」入口本 story 先显示、点击跳名额管理页占位，2.4 接通）、B1 ~ B6、C1 ~ C6、J0 ~ J3。埋点 `pet_added`、`pet_switched`、`pet_slot_full_shown`。

**Acceptance Criteria:**

**Given** 有 2 只宠物
**When** 打开「我的」页
**Then** Hewanku 显示 2 个圆形头像 + 「添加」，标题右侧「Kelola ›」（A1）；点某只进入其 Diary Tab

**Given** Diary Tab
**When** 切换宠物
**Then** 时间线、日历、统计、里程碑、健康记录都只显示该宠物（AC2）；切换中 / 失败重试（B2 / B3）；退出再进停在上次查看的宠物；只 1 只时显示 1 个头像 +「+」（B5）；无宠物显示紧凑建档条 + 推荐网格（B6）

**Given** 名额已满点「添加」
**When** 后端返回 `pet-slot-full`
**Then** 弹名额已满抽屉（升级 / 月租两条路，A4）；已 5 只显示 A4b；上报 `pet_slot_full_shown`

**Given** 删除非最后一只
**When** 确认删除
**Then** 新正文确认抽屉（C2）→ 删除中 → 成功（C4），当前宠物切到剩余宠物；失败可重试（C5）；删最后一只沿用原文并进入无宠物态（C6）

**Given** 他人主页有 6 只宠物
**When** 查看
**Then** 圆形头像一排、超过 4 只横滑（J2）；点进访客视图统计按该宠物（J3，AC4）

**And** 新增文案取对照表（EN / ID），不渲染后端显示串；`flutter analyze` 与相关测试绿；Android 模拟器演示 A1 / B1 / C2 / J2。

## Epic 2: 月租名额与只读

### Story 2.1: 宠物状态、reconcile 与只读拦截

As a 名额不足的多宠物用户,
I want 超出名额的宠物进入只读、并能自己选哪几只保持正常,
So that 我知道哪些宠物暂停了记录，也能决定保留谁。

**范围：** 后端。迁移 `V20261018_1604`（`pet_profiles.status` / `keep_active`）；AD-4 `PetSlotStateService.reconcile`（`pg_advisory_xact_lock(userId)`；`RENTAL_EXPIRED` 才降级，其余只恢复；监听 `GrowthLevelChangedEvent` 触发 `LEVEL_CHANGED`）；改选接口 `PUT /pet-profiles/me/active-selection`；AD-8 `PetWriteGuard` 接入全部新增类接口（409 `pet-read-only`）；`/me/list` 带状态。本 story 用测试直接调用 `reconcile(RENTAL_EXPIRED)` 验证（真实到期在 2.3）。

**Acceptance Criteria:**

**Given** A、B、C、D 四只、名额剩 2、未做选择
**When** `reconcile(RENTAL_EXPIRED)`
**Then** A、B 保持正常，C、D 只读（AC13c）；用户改选 A、D 后即时生效

**Given** 只读宠物
**When** 发 Diary / 被选为打卡宠物 / 里程碑打卡 / 新增或编辑健康记录 / 编辑档案 / 购买 Tailsonality / 护照快照 / 登机牌 / AI 问诊结论存档
**Then** 均返回 409 `pet-read-only`；查看、分享、收礼不受影响（AC10）

**Given** 用户降级导致名额减少
**When** `GrowthLevelChangedEvent`（降）
**Then** 不转任何宠物为只读（AC9）

**Given** 名额增加（升级 / 新租 / 续租）
**When** reconcile
**Then** 只读宠物按顺序恢复为正常，恢复数不超过名额

**And** 每个新增类接口有「只读 → 409」测试；同一用户并发 reconcile 串行；`mvn -B clean package` 绿。

### Story 2.2: 月租名额购买、续租与价格配置

As a 想多养宠物的用户,
I want 按月租用名额、勾选多个名额一次续租,
So that 不升级也能多养几只。

**范围：** 后端 + 后台配置。迁移 `V20261018_1601`（`pet_slot_rentals` / `pet_slot_purchases`）、`V20261018_1602`（`pricing_config.pet_slot_monthly_price` 默认 5,000、≥ 100）；`PetSlotPurchaseService`（新租数量 1 ~ 5 − 名额；续租所选生效中名额 +30 天；PawCoin 同步 / QRIS 意图 + `PetSlotPaidHandler`）；`quotaOf` 接入月租；发放后 `reconcile`；接口 `GET /pet-slots/me`（构成、名额列表与到期日、价格、可租数）、`POST /pet-slots/me/rentals`、`POST /pet-slots/me/renewals`；后台配置页新增月租价格（L7，照 TS_MATCH）。

**Acceptance Criteria:**

**Given** Lv1 用户（名额 1）
**When** 租 2 个（PawCoin，Rp10,000）
**Then** 即时名额 3、两个名额到期 = 付款时刻 + 30 天；再租时最多 2 个；租满后名额 5 不能再租（AC8）

**Given** QRIS 租用
**When** 回调与轮询同时到达 / 付款失败 / 过期
**Then** 只生效一次；失败或过期不增加名额（AC12）

**Given** 名额 10/31 到期
**When** 10/28 续租
**Then** 新到期日 11/30（AC11 续租部分）；勾选多个名额一次付款

**Given** 月租付款成功 Rp5,000
**When** ① 的消费返成长值处理
**Then** 入账 25（AC13，本线不写成长值逻辑，① 未合并时为预期不触发）

**Given** 运营在配置页把价格改为 Rp7,000
**When** 之后下单
**Then** 新订单按 7,000；已生成的待支付单不受影响

**And** 价格下单时快照进购买行；`mvn -B clean package` 绿。

### Story 2.3: 名额到期扫描与通知

As a 月租用户,
I want 名额到期前收到提醒、到期时知道哪只宠物进入只读,
So that 我能及时续租不丢记录。

**范围：** 后端。AD-7 `PetSlotExpiryScanner`（每小时，照 `SipdhExpiryScanner`）；`PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` 文案由后端按账号语言生成（同 `OPS_ANNOUNCEMENT`），深链名额管理页；逐用户事务、异常隔离、状态列幂等。

**Acceptance Criteria:**

**Given** 某名额 3 天后到期
**When** 扫描
**Then** 发一条 `PET_SLOT_EXPIRING`（含到期日），同一名额不重复发（AC11）

**Given** 名额到期且导致宠物只读
**When** 扫描
**Then** 名额置 EXPIRED，`reconcile(RENTAL_EXPIRED)`，发一条 `PET_SLOT_EXPIRED`（含转只读的宠物名）；无宠物因此只读时不发

**Given** 扫描中途失败或重复执行
**When** 再次扫描
**Then** 不重复通知、不重复处理

**And** 推送分类归「订单与账号」由 ④ 映射，本线不处理；`mvn -B clean package` 绿。

### Story 2.4: App——名额管理、租用续租与只读体验

As a 多宠物用户,
I want 在名额管理页看清名额构成、租用续租、选择保留哪几只，并在只读宠物上看到清楚的提示,
So that 名额与只读状态对我是透明的。

**范围：** App。`features/pet_slot/`：名额管理页 D1 ~ D4、D13 / D14、D15；租用抽屉 D5 ~ D10b（`PayChannelPicker` 加可选数量步进器 + 总价行；QRIS 过期仅名额流程补 SnackBar）；续租 D11 / D12；只读：切换器锁标、Diary Tab 只读横幅（仅续租）、统一拦截抽屉 E1、E2 / E3（健康记录标题「Catatan Kesehatan · {name}」）；通知中心两类到期（K1，读后端 title / body）；接通 1.5 的 A4「月租」入口。埋点 `pet_slot_rent_started` / `pet_slot_rent_paid` / `pet_readonly_banner_shown` / `pet_readonly_renew_tapped`。

**Acceptance Criteria:**

**Given** 名额管理页
**When** 打开
**Then** 显示构成（默认 / 等级 / 月租）与每个月租名额到期日；已到上限显示 D4；加载失败可重试（D15）

**Given** 租用抽屉
**When** 选数量与渠道
**Then** 显示「N × Rp5,000 = 总价」，PawCoin 余额不足显示 D6；QRIS 成功（D9）/ 失败（D10）/ 过期（D10b SnackBar）

**Given** 续租
**When** 勾选两个名额支付
**Then** 一次付款，成功后两个到期日各 +30 天（D12）

**Given** 有只读宠物
**When** 进入其 Diary Tab 或点新增类入口
**Then** 切换器锁标、顶部横幅「名额已到期，续租后可继续记录」+ 续租；新增类入口弹统一拦截抽屉 E1（只提供续租）；改选只在名额管理页「Ubah pilihan ›」（D13 / D14）

**Given** 通知中心有到期通知
**When** 查看
**Then** 显示后端文案，点击进名额管理页（K1）

**And** `flutter analyze` 与相关测试绿；Android 模拟器演示 D2 / D5 / E1。

## Epic 3: 多宠内容与推荐

### Story 3.1: 内容多宠关联

As a 多宠物用户,
I want 一条 Diary 关联多只宠物,
So that 同时记录几只宠物的时刻只需发一次。

**范围：** 后端。迁移 `V20261018_1600`（`content_pets` + 回填存量 Diary）；发布接口 `petIds`（Diary ≥ 1、逐只归属 + 可写；Moment / Tips 忽略）；`content_posts.pet_id` 同步为第一只、删宠物时顺延或置空；约 10 处按宠物查询改走 `content_pets`；`ContentPublishedEvent` 末尾追加 `List<Long> petIds`；`ContentPetQuery.petsOf`、`PetQueryService.listByOwner / get`（供 ③）；内容详情响应带关联宠物列表（供 F6）。

**Acceptance Criteria:**

**Given** 发 Diary 不带 petIds
**When** 提交
**Then** 400 校验失败（AC14）

**Given** 选 A、B 发 Diary
**When** 发布成功
**Then** A、B 的时间线 / 日历 / 统计都有这条，Feed 只出现一次（AC14）；事件 `petIds = [A, B]`、`petId = A`

**Given** 选了只读宠物
**When** 发布
**Then** 409 `pet-read-only`

**Given** 发 Moment / Tips 带了 petIds
**When** 发布
**Then** 忽略，不建关联（AC15 后端）

**Given** 删除 A
**When** 关联 A、B 的帖子
**Then** 只解除 A；`content_posts.pet_id` 改为 B；只关联 A 的帖子 `pet_id` 置空、内容保留

**And** 存量 Diary 回填后时间线与改造前一致；`mvn -B clean package` 绿。

### Story 3.2: 推荐物种按多宠规则

As a 多宠物用户,
I want 首页推荐按我所有宠物的物种比例来,
So that 养猫又养狗的我两类内容都能看到。

**范围：** 后端。AD-11：`ContentSpeciesResolver`（Diary 按关联宠物物种、多物种 → 通用池；Moment / Tips 按作者全部宠物物种）；用户主物种改权重（按宠物数，含只读、不含已删）；`FeedRankEngine.Input` 权重化、槽位按权重分配、余数给多的物种；同步更新 `2.数据算法/首页推荐算法.md`。

**Acceptance Criteria:**

**Given** 关联一猫一狗的 Diary
**When** 解析物种
**Then** 进通用池；只养猫的作者发的 Moment 进猫池；猫狗都养的作者发的 Moment 进通用池（AC16）

**Given** 2 猫 1 狗的用户
**When** 首页 10 个推荐槽位分配
**Then** 猫 7、狗 3（2/3、1/3，余数给猫）（AC17）；推荐仍按排序、不退回时间倒序（AC5）

**Given** 单宠物用户
**When** 推荐
**Then** 行为与改造前一致

**And** `species_override` / `account_species` 优先级不变；推荐既有测试全绿；`mvn -B clean package` 绿。

### Story 3.3: App——发布选宠物、详情关联宠物、打卡选宠物

As a 多宠物用户,
I want 发 Diary 和打卡时选择宠物、在详情页看到这条关联了哪些宠物,
So that 记录准确归到每只宠物。

**范围：** App。UI 稿 F1 ~ F6c、G1 ~ G4；`diary_published` 增加 `pet_count`。

**Acceptance Criteria:**

**Given** Diary 发布页有 3 只正常宠物
**When** 不选宠物
**Then** 不能发布（F1）；选 2 只发布成功（F2 / F3，分享成长册取第一只）；只 1 只正常时自动选中（F4）；只读宠物不在可选列表

**Given** Moment / Tips 发布页
**When** 打开
**Then** 没有宠物选择（F5，AC15）

**Given** 多宠 Diary 详情页
**When** 查看
**Then** 有图：图片左下半透明胶囊（环扣头像 + 名字 + ›）；无图：正文与评论分隔线之间浅色胶囊（F6b）；≥ 3 只：最多 3 个头像（超出 +N）+ 第一只名字 + dkk.（F6c）；Feed 卡片不显示

**Given** 打卡有 3 只正常宠物
**When** 打开选择器
**Then** 默认全不选、不选不能打卡；今天已在此打过的宠物置灰；选 2 只后两只各盖一章（G1 ~ G3，AC3a）；只 1 只时不显示选择器（G4）；「Rekam Momen Ini」默认选中本次打卡的宠物

**And** `flutter analyze` 与相关测试绿；Android 模拟器演示 F2 / F6 / G2。

## Epic 4: 按宠物的服务与后台

### Story 4.1: AI 问诊与兽医咨询选宠物

As a 多宠物用户,
I want 问诊前选是哪只宠物、AI 参考它的物种年龄体重,
So that 回答更准确、结论存到对的宠物。

**范围：** 后端 + App。P-1：分诊 prompt 拼入所选宠物的只读上下文（物种、按生日换算的年龄、体重），**不动系统指令与 `SafetyRuleLayer`**，补分诊安全回归测试；`TriageSubmitRequest.petId` 校验归属；兽医咨询 `createRequest` 接收 petId；存档经 `PetWriteGuard`。App：H1 ~ H4（多宠先单选、只 1 只不弹、只读可选、存档拦截提示「续租后可存档」、黄色结果同样拦截）。

**Acceptance Criteria:**

**Given** 有 2 只宠物
**When** 开始 AI 问诊 / 点「Mulai konsultasi」
**Then** 先单选宠物（H1 / H4）；只 1 只时不弹（H3）

**Given** 选了一只 3 岁、5kg 的猫
**When** 提交 AI 问诊
**Then** prompt 含该宠物物种 / 年龄 / 体重上下文；安全规则层判定结果与改造前同输入一致（回归测试）

**Given** 选了只读宠物
**When** 问诊完成后存档
**Then** 提示「续租后可存档」，健康记录无新增（AC3b，H2）

**Given** petId 不属于本人
**When** 提交问诊 / 咨询
**Then** 404

**And** 后端 `mvn -B clean package` 绿；App `flutter analyze` 与相关测试绿；Android 模拟器演示 H1 / H2。

### Story 4.2: App——KTP 创建选宠物

As a 多宠物用户,
I want 创建身份证时选择是哪只宠物并自动预填,
So that 每只宠物都能有自己的身份证。

**范围：** App。I1 ~ I3：默认预填 Diary Tab 当前宠物（只读则取最早创建的正常宠物）；「换宠物」抽屉只列正常宠物；只 1 只正常时预填且可不换。卡片仍只挂账号（不改后端结构）。

**Acceptance Criteria:**

**Given** 当前宠物为正常宠物 B
**When** 进入 KTP 创建
**Then** 预填 B 的资料（I2）；点「换宠物」只列正常宠物（I1）

**Given** 当前宠物只读
**When** 进入 KTP 创建
**Then** 预填最早创建的正常宠物

**And** `flutter analyze` 与相关测试绿；Android 模拟器演示 I2。

### Story 4.3: 后台——宠物档案全部宠物与「宠物与名额」页签

As a 运营,
I want 在用户抽屉看到用户全部宠物与名额情况，并能补发名额,
So that 能处理名额相关客诉。

**范围：** 后台。`AdminUserService` 宠物档案取全部宠物（L0）；填充地基 `data-utab-panel="petSlots"` 容器（L1：构成、宠物状态、月租明细、购买记录）；补发 N 天名额（L2 ~ L4，原因必填、原生确认，购买记录 `ADMIN_GRANT` + 操作人，补发名额与月租一样到期提醒）；权限 `user.pet_slot_adjust`（`AdminPermissions` 入 `GROUPS`，**在 `AdminRole.OPS_MANAGER` 枚举预授**，不写迁移），无权限置灰（L5）；无记录空态（L6）；审计。

**Acceptance Criteria:**

**Given** 用户有 3 只宠物
**When** 打开抽屉「宠物档案」
**Then** 显示全部 3 只（L0）

**Given** 「宠物与名额」页签
**When** 打开
**Then** 显示名额构成、每只宠物状态（正常 / 只读）、每个月租名额生效与到期时间、购买记录（时间、数量、金额、渠道、payment_intent）（L1）；无记录显示 L6

**Given** 有权限的运营补发 7 天
**When** 填原因、原生确认
**Then** 用户名额即时 +1，7 天后到期并照常提醒；购买记录显示「运营补发」与操作人（AC13b，L4）；写审计

**Given** 无 `user.pet_slot_adjust`
**When** 打开页签
**Then** 补发卡置灰并提示（L5）

**And** 页签头不改；`AdminPageCatalogTest` 等守卫绿；`mvn -B clean package` 绿。

### Story 4.4: 升级庆祝的永久名额提示（与 ① 衔接）

As a 有月租名额的用户,
I want 升级获得永久名额时看到正向提示,
So that 我知道以后可以少租一个名额。

**范围：** 后端 + App。**前置：①② 均已合入 `dev_1.4.0`；不在 `feat/1.4.0-multi-pet` 上做（功能分支不反向合入 dev，拿不到 ① 代码），而是从 dev 切对齐分支补做**（① 的 `/api/v1/me/growth/popups` 与升级庆祝页）。后端：`celebration` 增加 `petSlotHint`（本次升级跨过 Lv5 / Lv10 且有生效月租 → true）；App：升级庆祝页「+1 slot hewan」行内正向说明（K2；无月租时不出现，K3）；名额管理页显示「永久名额 +1，下次续租默认少勾一个」。① 合并后 ① 写成长值流水的关联宠物改取 `ContentPublishedEvent.petIds`（如 ① 尚未改，本 story 一并改）。

**Acceptance Criteria:**

**Given** Lv4 用户默认 1 + 月租 1
**When** 升到 Lv5
**Then** 升级庆祝页出现永久名额正向提示，文案无「闲置 / 浪费 / 不退款」（AC13a，K2）

**Given** 没有生效月租的用户升到 Lv5
**When** 升级
**Then** 不出现该提示（K3）

**Given** 发布关联 A、B 的 Diary
**When** ① 入账 `POST_PUBLISHED`
**Then** 流水关联宠物为 [A, B]

**And** `mvn -B clean package` 绿；`flutter analyze` 绿；Android 模拟器演示 K2。

## 最终校验（2026-10-18）

- **覆盖**：FR-145.1 ~ .7、FR-146.1 ~ .6、FR-147.1 ~ .3、AB-34.1 ~ .3、FR-AN、UX-DR1 ~ UX-DR12、PRD AC1 ~ AC17（含 AC3a / AC3b / AC13a ~ c）均落在至少一个 story。
- **依赖**：只向后。1.5 的 A4「月租」入口先占位、2.4 接通；4.4 前置 ① 合入。
- **表按需创建**：1603（1.4）、1604（2.1）、1601 / 1602（2.2）、1600（3.1）。
- **最大风险**：单宠物回归（NFR1）——每个后端 story 的 AC 都含「单宠物既有测试全绿」。
