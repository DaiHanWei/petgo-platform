# Story 2.1: 宠物状态、reconcile 与只读拦截

Status: ready-for-dev

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`
> 口径优先级：`decision-log.md` ② 节 > `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md` > UI 稿（均在 `_bmad-output/planning-artifacts/v1.4.0/`）；跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。
> 前置：**Epic 1 全部完成**（1.1 `PetResolver` / `findAllByOwnerIdOrderByCreatedAtAscIdAsc` / `findDefault` / `requireOwned` / `GET /pet-profiles/me/list`；1.2 删除 `Optional findByOwnerId` + 源码守卫；1.3 `ProfileDeletionService.deleteByPetId`；1.4 `PetSlotService.quotaOf`（默认 1 + 等级，封顶 5）、建档名额校验、`V20261018_1603` 已删唯一约束）；地基 0.1 的 `GrowthLevelChangedEvent`。
> 范围层：**只后端**。迁移 **`V20261018_1604`**（预分配，不得另取）。不碰两条共享 CHECK（`ck_notifications_type` / `ck_payment_intents_purpose`）。
> 🔴 本 story 只用测试**直接调用** `reconcile(RENTAL_EXPIRED)` 验证降级；真实到期触发在 **2.3**；月租名额计入 `quotaOf` 在 **2.2**；App 侧只读体验在 **2.4**。

## Story

As a 名额不足的多宠物用户,
I want 超出名额的宠物进入只读、并能自己选哪几只保持正常,
so that 我知道哪些宠物暂停了记录，也能决定保留谁。

## Acceptance Criteria

**AC1 — 迁移 `V20261018_1604__pet_profiles_status_keep_active.sql`** `[L0]` `[L1]`（AD-4、§6.4）
1. 号已预分配，不得另取；提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
2. `ALTER TABLE pet_profiles ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`，`ADD CONSTRAINT ck_pet_profiles_status CHECK (status IN ('ACTIVE','READ_ONLY'))`；`ADD COLUMN keep_active BOOLEAN NOT NULL DEFAULT false`。
3. 索引 `ix_pet_profiles_owner_status (owner_id, status)`（`/me/list`、reconcile、`findDefault` 都按它查）。
4. 列 `COMMENT`：`status` = 只经 `PetSlotStateService.reconcile` 改；`keep_active` = 用户在名额管理页「Ubah pilihan」选为保持正常。
5. 不回填（存量全部默认 `ACTIVE` / `false`，等价于现状）。

**AC2 — 实体与读口** `[L0]`
1. `PetProfile` 加 `status`（新枚举 `profile.domain.PetStatus { ACTIVE, READ_ONLY }`，`@Enumerated(STRING)`、`length = 16`，**不要** `length=1`）与 `keepActive`；只给包内可见的 setter（`markReadOnly()` / `markActive()` / `setKeepActive`），由 `PetSlotStateService` 调用，其他类不得改状态（§5 单一入口）。
2. `findDefault(ownerId)` 改为「最早创建且 `status = ACTIVE`，无则最早创建」（AD-2 原文；1.1 实现时 status 列还不存在，本 story 补上 ACTIVE 优先）。
3. `GET /api/v1/pet-profiles/me/list` 每项的 `status` 改读真实列（1.1 先写死 `ACTIVE`），并加 `keepActive`。
4. 对 ③ 提供的 `PetQueryService.get / listByOwner`（若 3.1 前已存在）返回状态；本 story 不新建该类（AD-10 归 3.1）。

**AC3 — `PetSlotStateService.reconcile(userId, Trigger)`** `[L0]` `[L1]`（AD-4）
1. 新包 `com.tailtopia.profile.slot`：`PetSlotStateService`、`PetSlotTrigger { RENTAL_EXPIRED, RENTAL_ADDED, RENEWED, LEVEL_CHANGED, SELECTION_CHANGED, PET_DELETED }`、`PetSlotLock`。
2. **加锁**：进入即 `PetSlotLock.lockUser(userId)` → `SELECT pg_advisory_xact_lock(:ns, :uid)`，写法照 `CardNumberService.lockOwnerForCardCreation`（`@Transactional(propagation = MANDATORY)`，命名空间常量如 `PET_SLOT_LOCK_NS = 0x5045_5453 // "PETS"`，不得与现有 `0x4943_4352` / `0x4147_4543` / `0x5050_5352` / `0x5453_5352` 相撞）。🔴 若 1.4 建档的「按账号串行化」已建了同类锁，**复用同一把**（同 ns），让建档、reconcile、2.2 发放三者互斥；Completion Notes 记一句。
3. 读取：账号全部未删除宠物，排序 `keep_active DESC, created_at ASC, id ASC`；名额 `quota = PetSlotService.quotaOf(userId).total()`（只经它，不自算）。
4. **`RENTAL_EXPIRED`**（唯一会降级的自动触发）：若 `ACTIVE` 数 > quota，从 `ACTIVE` 宠物中按上述顺序保留前 quota 只，其余置 `READ_ONLY`；返回 `ReconcileResult(List<PetRef> demoted, List<PetRef> restored)`（`PetRef(id, name)`，2.3 发通知用）。
5. **只恢复不降级**（`RENTAL_ADDED` / `RENEWED` / `LEVEL_CHANGED` / `PET_DELETED`）：若 `ACTIVE` 数 < quota 且有 `READ_ONLY`，按同一顺序把只读宠物恢复为 `ACTIVE`，直到 `ACTIVE` 数 = quota；**任何情况下不把 ACTIVE 改成 READ_ONLY**（PRD：降级 / 名额减少时已建宠物照常用，只是不能新建）。
6. **`SELECTION_CHANGED`**：见 AC4，按用户选择重排（总 `ACTIVE` 数 ≤ quota）。
7. 无变化不写库；状态变更后 `saveAll`。日志只记 `userId`、触发、变更数（不记宠物名）。
8. 同一用户并发两次 reconcile 串行执行，结果确定（L1 两线程测试）。

**AC4 — 改选接口 `PUT /api/v1/pet-profiles/me/active-selection`** `[L0]` `[L1]`（D13 / D14、AC13c）
1. 请求 `{ "petIds": [Long...] }`；逐只 `PetResolver` / `requireOwned`（非本人或不存在 → 404，不泄露存在性）；去重。
2. 校验：账号宠物数 ≤ quota（无需选择）→ 409 `pet-selection-not-needed`；`petIds.size() != quota` → 422 `validation`（D13「勾满名额数后其余行不可再勾」，必须恰好选满）。
3. 生效：选中者 `keep_active = true` + `ACTIVE`，其余 `keep_active = false` + `READ_ONLY`；经 `reconcile(SELECTION_CHANGED)` 同一把锁内完成；返回最新列表（同 `/me/list` 形状）。
4. 立即生效（D14 Toast 由 App 出）；`MilestoneCheckIn`、发 Diary 等随后按新状态拦截。

**AC5 — `GrowthLevelChangedEvent` 监听** `[L0]` `[L1]`（AD-4、AC9）
1. `PetSlotLevelChangedListener`：`@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`，方法内调 `reconcile(userId, LEVEL_CHANGED)`（方法自身 `@Transactional`，锁 MANDATORY 才有事务可挂）。
2. 升级（名额增加）→ 只读宠物按序恢复；**降级 → 不转任何宠物为只读**（AC9）。
3. ① 未合入前全仓无发布方（地基只定义事件）；测试里直接 `publishEvent` 验证。异常只记日志、不冒泡（AFTER_COMMIT + `@Async`）。

**AC6 — 删除后释放名额** `[L0]` `[L1]`
1. 1.3 的 `ProfileDeletionService.deleteByPetId` 删除成功后（同一事务内）调 `reconcile(userId, PET_DELETED)`（只恢复）。例：名额 2、A/B 正常、C 只读，删 A → C 恢复正常。
2. 删除只读宠物照常允许（不经 `PetWriteGuard`）；账号注销路径（遍历删全部）不需要 reconcile，可跳过以免无意义加锁。
3. ⚠️ 本条是对 AD-4 触发清单的**补充**（AD-4 未列 `PET_DELETED`），理由：PRD「删除 → 名额释放」，否则用户删一只后只读宠物不恢复、要等下次升级 / 续租。见 Dev Notes「待确认」。

**AC7 — `PetWriteGuard.requireWritable(petId)` 接入全部新增类接口** `[L0]` `[L1]`（AD-8、AC10）
1. 新类 `com.tailtopia.profile.service.PetWriteGuard`：查宠物状态，`READ_ONLY` → `AppException.petReadOnly(petId)`：409，type `https://petgo/errors/pet-read-only`，ProblemDetail 扩展成员 `petId`（异常实现 `ProblemExtensions`，照 `CheckoutUnavailableException`）。新增 `ErrorTypes.PET_READ_ONLY`、`PET_SELECTION_NOT_NEEDED`。
2. 调用顺序固定：**先** `PetResolver` / `requireOwned`（归属，404）**再** `requireWritable`（409），不让只读校验泄露他人宠物存在性。
3. 接入点（逐一在 Service 写方法入口调用；Epic 1 改造后行号会变，开工时按方法名重新 grep，Completion Notes 列最终 file:line）：

   | # | 操作 | Service 入口（dev_1.4.0 @ 8ca69f9f 现状） | 校验对象 |
   |---|---|---|---|
   | 1 | 发 Diary | `ContentService.publish`（:452；Diary 绑宠物 :492-516） | Diary 的 petId（3.1 改为 `petIds` 后逐只） |
   | 2 | 场所打卡 | `PlaceCheckinService.checkIn`（:95；校验 `ownedPetIds` :187-200） | 请求 `petIds` 逐只 |
   | 3 | 里程碑打卡 | `MilestoneCheckInService.checkIn`（:67，`requireProfile` :126） | 所打卡宠物 |
   | 4 | 健康记录新增 / 修改 / 删除 | `HealthRecordService.create`（:70）/ `update`（:85）/ `delete`（:109） | 记录所属宠物 |
   | 5 | 编辑档案 | `ProfileService.update`（:137，含头像 :154-157；`PATCH /pet-profiles/me` 在 `ProfileApiController`:125） | 被编辑宠物 |
   | 6 | AI 问诊 / 兽医咨询结论存档 | `HealthEventService.recordDecision`（:54；ARCHIVED :81，SKIPPED :85） | **仅 `decision = ARCHIVED`** 时校验 `req.petId()`；SKIPPED 照常写（App 的 H2 拦截在 4.1，后端兜底不应让「跳过」报错） |
   | 7 | Tailsonality 结果解锁 / 配型解锁 | `TailsonalityUnlockService.unlock`（:43）/ `unlockMatch`（:52） | `lockOwned` 取到的宠物 |
   | 8 | Tailsonality 测试提交（新增一条结果） | `TailsonalityResultService.submit`（:65） | 所测宠物（见「待确认」） |
   | 9 | 护照快照购买 | `PassportSnapshotService.start`（:83） | 护照所属宠物 |
   | 10 | 登机牌购买 | `BoardingPassService.unlock`（:100） | 护照所属宠物 |
   | 11 | KTP 创建 / 生成身份证号 | `IdCardService.createCard`（:92）/ `createCardV2`（:102）/ `generateSerial`（:188） | **仅当 Epic 1 改造后这些方法接收 petId 时**校验；卡不挂宠物（AD-8：KTP 创建只列正常宠物，由 App 4.2 过滤），若仍按默认宠物取则默认宠物恒为 ACTIVE（AC2.2），无需拦 |

   - **另加一道统一兜底**：`KeepsakePurchaseService.start`（:109）入口对 `ref.petProfileId() != null` 调 `requireWritable`（覆盖 #7 / #9 / #10 三类经它付款的路径，防以后新增 SKU 漏接）。`purchase` 包现禁止 import `profile`（`KeepsakeSingleConsumerSourceGuardTest.purchasePackageDoesNotDependOnSkuModules`）→ **不在 purchase 包里加**，改为只在 #7 / #9 / #10 各自 Service 入口调用；守卫测试不改。
   - KTP 高清购买（`IdCardHdService.purchaseCard`:86，按卡）**不拦**：卡不挂宠物，属于已存在卡片的付费下载。
4. **不拦**（查看 / 分享 / 收礼）：里程碑分享 `MilestoneShareService.createOrRefresh`、庆祝上报 `MilestoneCelebrationService.reportCelebrated`、卡片分享上报、各分享奖励、Tailsonality 徽章佩戴 / 摘下、全部 GET、删除宠物。
5. 每个接入点一条「只读 → 409 `pet-read-only`、无副作用（不扣币、不写行）」测试；对应「正常宠物照常成功」已由既有测试覆盖。

**AC8 — 验收（Epic AC 逐条）** `[L0]` `[L1]`
- Given A、B、C、D 四只（按创建先后）、名额 2、未做选择，When `reconcile(RENTAL_EXPIRED)`，Then A、B `ACTIVE`，C、D `READ_ONLY`；返回 demoted = [C, D]（AC13c）。When 改选 A、D，Then A、D `ACTIVE`，B、C `READ_ONLY`，即时生效。
- Given 只读宠物，When 发 Diary / 被选为打卡宠物 / 里程碑打卡 / 新增或编辑或删除健康记录 / 编辑档案 / 购买 Tailsonality / 护照快照 / 登机牌 / AI 问诊结论存档，Then 均 409 `pet-read-only`（ProblemDetail 带 `petId`）；查看、分享、收礼不受影响（AC10）。
- Given Lv10 用户 3 只全部正常，When 发 `GrowthLevelChangedEvent(10 → 9)`，Then 3 只仍 `ACTIVE`（AC9）。
- Given 2 只只读、名额 +1（升级 / 新租 / 续租），When reconcile，Then 按顺序恢复 1 只（恢复数不超过名额）。
- Given 同一用户并发两次 reconcile，Then 串行、终态一致。

**AC9 — 回归** `[L0]` `[L1]`
1. 单宠物账号全部既有测试全绿（不删、不 skip）：单宠物恒 `ACTIVE`，`PetWriteGuard` 对其恒通过。
2. `mvn -B clean package` 绿。

## Tasks / Subtasks

- [ ] **T0 开工核对**：确认 Epic 1 已合入（`PetResolver`、`findDefault`、`quotaOf`、`deleteByPetId`、1603 迁移存在）；按 AC7 表的方法名重新 grep 接入点并更新行号
- [ ] **T1 迁移 1604**（AC1）+ `PetStatus` 枚举 + `PetProfile` 字段（AC2.1）
- [ ] **T2** `findDefault` ACTIVE 优先、`/me/list` 带真实 `status` / `keepActive`（AC2.2 / AC2.3）
- [ ] **T3** `profile.slot.PetSlotLock` / `PetSlotTrigger` / `PetSlotStateService.reconcile` + `ReconcileResult`（AC3）
- [ ] **T4** 改选接口 + DTO + `ErrorTypes.PET_SELECTION_NOT_NEEDED`（AC4）
- [ ] **T5** `PetSlotLevelChangedListener`（AC5）；`deleteByPetId` 后 reconcile（AC6）
- [ ] **T6** `PetWriteGuard` + `ErrorTypes.PET_READ_ONLY` + `AppException.petReadOnly` + 逐点接入（AC7）
- [ ] **T7 测试**：L0 全部；L1 写好能编译；`mvn -B clean package`；Completion Notes 标「L1 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 8ca69f9f；Epic 1 合入后行号会变）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/profile/domain/PetProfile.java` | 字段 id(:28)…weightKg(:90)、neuterStatus(:95)、createdAt(:98)、updatedAt(:101)；**无任何状态字段** | 加 `status` / `keepActive` | 既有字段与 `ddl-auto=validate` 对齐 |
| `…/profile/repository/PetProfileRepository.java` | `findByOwnerId`(:12)（1.2 删）、`findByOwnerIdIn`(:15)、`existsByOwnerId`(:17)、`findByCardToken`(:19) | `findDefault` ACTIVE 优先；可加 `countByOwnerIdAndStatus` | — |
| `PetResolver` / `PetSlotService` / `PetSlotStateService` / `PetWriteGuard` | **不存在**（前两者由 1.1 / 1.4 新建，后两者需本 story 新建） | 新建后两者 | §5 单一入口 |
| `…/growth/event/GrowthLevelChangedEvent.java:13` | `record (long userId, int oldLevel, int newLevel)`；全仓**无发布方、无监听者** | 新增监听 | 不改字段 |
| `…/profile/service/CardNumberService.java:97-104` | `pg_advisory_xact_lock(:ns, :uid)` 两参写法 + `@Transactional(MANDATORY)` + `(int)(userId % Integer.MAX_VALUE)` | 照抄做 `PetSlotLock` | — |
| 其他 advisory 锁先例 | `SerialAllocationService`:40（单参）、`AgeCardShareRewardService`:162、`PetCardShareRewardChannel`:154、`AdminConfigService`:444（`hashtext`）、`DashboardMaterializer`:144（JdbcTemplate） | 不改 | ns 不相撞 |
| `…/shared/error/ErrorTypes.java:11` | `BASE = "https://petgo/errors/"`；先例 `PROFILE_EXISTS`(:25)、`CHECKIN_ALREADY_TODAY`(:49)、`KEEPSAKE_ALREADY_UNLOCKED`(:61) | 加 `PET_READ_ONLY`、`PET_SELECTION_NOT_NEEDED` | — |
| `…/shared/error/AppException.java:164-166` | `keepsakeAlreadyUnlocked` 工厂（409 + type）写法 | 加 `petReadOnly` | — |
| `…/shared/error/ProblemExtensions.java:20` | 扩展成员接口；先例 `shop/order/dto/CheckoutUnavailableException.java:20`；`GlobalExceptionHandler.handleApp`(:52-65) 统一产出信封 | `petId` 扩展走它 | traceId / instance 由 handler 注入 |
| `…/content/service/ContentService.java` | `publish`:452；Diary `ownsPet` 校验 :500（→ `ProfileService.ownsPet`:255）；非 Diary petId 置 null :515 | 接入 guard | Moment / Tips 不关联宠物 |
| `…/place/service/PlaceCheckinService.java` | `checkIn`:95；`ownedPetIds`:187-200（422 `checkin-no-pet` / 403 `checkin-pet-forbidden`） | 归属后逐只 guard | 既有错误码顺序 |
| `…/profile/service/MilestoneCheckInService.java` | `checkIn`:67，`requireProfile`:126-128 | 接入 guard | — |
| `…/profile/service/HealthRecordService.java` | `create`:70 / `update`:85 / `delete`:109，`requirePet`:116-119 | 接入 guard | 记录归属校验 `findByIdAndPetProfileId` |
| `…/profile/service/ProfileService.java` | `update`:137（`ProfileApiController.patch`:125-131 之后还调 `cardRerenderService.scheduleRerender`） | 接入 guard | 409 时不触发重渲染 |
| `…/profile/service/HealthEventService.java` | `recordDecision`:54；`ownsPet` 校验 :56；ARCHIVED :81、SKIPPED :85；唯一调用方 `HealthEventController`:44-49 | 仅 ARCHIVED 接 guard | FR-16 幂等 / SKIPPED→ARCHIVED 补存 |
| `…/tailsonality/service/TailsonalityUnlockService.java` | `unlock`:43 / `unlockMatch`:52 → `KeepsakePurchaseService.start`:109；`lockOwned`:59-61 | 接入 guard | 补差价逻辑 |
| `…/tailsonality/service/TailsonalityResultService.java` | `submit`:65（写结果 :70），`requirePet`:114 | 接入 guard（待确认项） | — |
| `…/passport/service/PassportSnapshotService.java` | `start`:83（`ensureIssuedForOwner`:84、冻结快照 :102、`purchases.start` :106） | guard 放在冻结快照之前 | 不留半截快照行 |
| `…/passport/service/BoardingPassService.java` | `unlock`:100（`insertIfAbsent`:111 在 `purchases.start`:116 之前） | guard 放在 `insertIfAbsent` 之前 | 同上 |
| `…/profile/service/IdCardService.java` | `createCard`:92 / `createCardV2`:102 / `generateSerial`:188，按 ownerId | 视 Epic 1 改造结果（AC7 #11） | 卡不挂宠物 |
| `…/purchase/service/KeepsakePurchaseService.java:109` | 统一付款入口，`KeepsakeRef(sku, refId, refToken, petProfileId, alreadyUnlocked, priceOverride)` | **不改**（purchase 包不得依赖 profile） | 源码守卫测试 |

### reconcile 规则速查

- 顺序键（保留 / 恢复同一把）：`keep_active DESC, created_at ASC, id ASC` —— 未做选择时 =「最早创建优先」（PRD 2026-10-13 拍板）。
- 只有 `RENTAL_EXPIRED` 与 `SELECTION_CHANGED` 会把宠物改成 `READ_ONLY`；其余触发只恢复。
- 用户「至少有一只可编辑」（decision-log ② 第一轮拍板）：默认名额 1 永久，quota ≥ 1，故 `ACTIVE` 数恒 ≥ 1（有宠物时）；加一条断言测试。
- `keep_active` 只由改选写；新建宠物默认 false；宠物数 ≤ quota 时 `keep_active` 不影响结果。

### 架构约束

- §5 单一入口：状态只经 `reconcile`、取宠物只经 `PetResolver`、可写校验只经 `PetWriteGuard`、名额只经 `PetSlotService.quotaOf`。
- 不引 MQ / 缓存；`@Async` + DB 状态机；`ddl-auto=validate`；Flyway 时间戳号 1604。
- ProblemDetail：不属于本人的 petId 一律 404；`pet-read-only` 带 `petId`；绝不外泄堆栈。
- 日志不记宠物名 / 健康数据。

### 待确认（默认按括号内做，Completion Notes 记一句）

1. **删除后 reconcile（AC6）**：AD-4 未列 `PET_DELETED`（默认加，只恢复不降级）。
2. **Tailsonality 测试提交是否拦（AC7 #8）**：AD-8 只列「购买」；PRD「只读禁止所有新增记录」且 UI 稿 E1 把 Kenali Hewanmu 付费项列为拦截入口（默认拦；不拦则只拦解锁购买）。

### 与前后 story 的边界（本 story 不做）

- 月租名额表、购买、`quotaOf` 计入月租 → **2.2**（本 story 的 `quotaOf` 仍只有默认 + 等级；`RENTAL_ADDED` / `RENEWED` 触发由 2.2 调用）。
- 到期扫描、`PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` 通知 → **2.3**（2.3 调本 story 的 `reconcile(RENTAL_EXPIRED)` 并用其返回的 demoted 列表）。
- App 锁标 / 横幅 / 拦截抽屉 / 改选抽屉 → **2.4**。
- 发 Diary 多宠（`petIds` 逐只校验）→ **3.1** 复用本 story 的 `PetWriteGuard`；问诊选宠物与存档拦截 App 侧 → **4.1**；KTP 只列正常宠物 → **4.2**。

### 测试

**验证层级**
- **L0（云端可跑，纯单元 / Mockito）**：
  - `profile/slot/PetSlotStateServiceTest`：AC13c 四只降级 + 改选；降级触发不降级（`LEVEL_CHANGED` 名额变小）；恢复按序且不超名额；`keep_active` 优先；无变化不写库；单宠物恒 ACTIVE。
  - `profile/slot/PetSlotLevelChangedListenerTest`：升 / 降两条事件都只调 `reconcile(LEVEL_CHANGED)`；监听异常不冒泡。
  - `profile/service/PetWriteGuardTest`：ACTIVE 通过；READ_ONLY 抛 409、type `pet-read-only`、扩展成员含 `petId`。
  - `profile/web/ActiveSelectionValidationTest`：数量不等 → 422；宠物数 ≤ 名额 → 409 `pet-selection-not-needed`。
  - 各接入点 Service 单测补「只读 → 409 且未调写仓储 / 未扣币」：`ContentServiceTest`、`PlaceCheckinServiceTest`、`MilestoneCheckInServiceTest`、`HealthRecordServiceTest`、`ProfileServiceTest`、`HealthEventServiceTest`（ARCHIVED 拦、SKIPPED 放）、`TailsonalityUnlockServiceTest`、`TailsonalityResultServiceTest`、`PassportSnapshotServiceTest`、`BoardingPassServiceTest`（类名以仓库现有为准，没有就新建）。
- **L1（需 Docker postgres + redis，云端不跑，待本地验收）**：
  - `profile/slot/PetSlotReconcileIntegrationTest`（`extends ApiIntegrationTest`）：迁移 1604 在 scratch 库从空跑通、`ddl-auto=validate`；四只 + `@MockitoBean PetSlotService` 返回名额 2 → AC13c；`PUT /me/active-selection` 端到端；两线程并发 reconcile 串行；删除 A 后只读 C 恢复（AC6）。
  - `profile/slot/PetReadOnlyGuardIntegrationTest`：每个新增类端点打只读宠物 → 409 `pet-read-only` 且库无新增行、PawCoin 余额不变；GET / 分享端点照常 200。
  - 既有单宠物集成测试全绿（AC9）。
- 云端只跑 L0；Completion Notes 标「L1 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 2.1]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#AD-2、AD-4、AD-8、§5、§6.4]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.2 月租到期后的只读状态 / AC9 / AC10 / AC13c]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② 多宠物（D13 改选只在名额管理页；用户至少有一只可编辑）]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/0-1-四条线共用地基.md（GrowthLevelChangedEvent）]

### 拍板（2026-10-18，Dai；优先于本文件正文与「待确认」）

- P-5：过期名额不可续；只读宠物的「续租」入口由 App 按情况分流（见 2.4），后端无需支持续过期名额。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log
