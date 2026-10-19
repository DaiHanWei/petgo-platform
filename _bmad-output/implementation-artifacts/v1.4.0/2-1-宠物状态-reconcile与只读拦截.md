# Story 2.1: 宠物状态、reconcile 与只读拦截

Status: review

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

- [x] **T0 开工核对**：确认 Epic 1 已合入（`PetResolver`、`findDefault`、`quotaOf`、`deleteByPetId`、1603 迁移存在）；按 AC7 表的方法名重新 grep 接入点并更新行号
- [x] **T1 迁移 1604**（AC1）+ `PetStatus` 枚举 + `PetProfile` 字段（AC2.1）
- [x] **T2** `findDefault` ACTIVE 优先、`/me/list` 带真实 `status` / `keepActive`（AC2.2 / AC2.3）
- [x] **T3** `profile.slot.PetSlotLock` / `PetSlotTrigger` / `PetSlotStateService.reconcile` + `ReconcileResult`（AC3）
- [x] **T4** 改选接口 + DTO + `ErrorTypes.PET_SELECTION_NOT_NEEDED`（AC4）
- [x] **T5** `PetSlotLevelChangedListener`（AC5）；`deleteByPetId` 后 reconcile（AC6）
- [x] **T6** `PetWriteGuard` + `ErrorTypes.PET_READ_ONLY` + `AppException.petReadOnly` + 逐点接入（AC7）
- [x] **T7 测试**：L0 全部；L1 写好能编译；`mvn -B clean package`；Completion Notes 标「L1 待本地验收」

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

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- 云端 locale 为 POSIX，所有 mvn 命令加 `LC_ALL=C.UTF-8`（同 1.1 ~ 1.4）。
- `List.of(..).contains(null)` 会 NPE → 改选的空值校验改用 `stream().anyMatch(Objects::isNull)`。

### Completion Notes List

- **L1/L2 待本地验收**：云端只跑 L0（`./mvnw -B clean package -DskipTests` 绿 + 纯单元测试）。
- L1（已写好、编译通过，**未运行**）：`profile/slot/PetSlotReconcileIntegrationTest`（迁移 1604 形态：`ck_pet_profiles_status` / `ix_pet_profiles_owner_status` / `keep_active` 默认 false、直插默认 ACTIVE；AC13c 四只降级 + `PUT /me/active-selection` 改选 A、D；改选 422 / 404 / 409 `pet-selection-not-needed`；游客 401、兽医 403；两线程并发 reconcile 串行（demoted 数 {2, 0}、终态一致）；删 A 后只读 C 恢复（AC6）；`GrowthLevelChangedEvent` 升级恢复 / 降级不转只读（AC5 / AC9））、`profile/slot/PetReadOnlyGuardIntegrationTest`（编辑档案 / 新增健康记录 / 发 Diary / 问诊存档 ARCHIVED / Tailsonality 提交打只读宠物 → 409 `pet-read-only` 带 `petId` + `traceId` 且库无新增行；SKIPPED 照常；他人只读宠物 404；GET / 删除不受影响；默认宠物 = 最早的 ACTIVE）。本地另需：空 scratch 库全量迁移启动、`ddl-auto=validate` 通过、既有单宠物集成测试全绿（AC9）。
- L0 单测（新增）：`profile/slot/PetSlotStateServiceTest`（15，3 个 `@Nested`：到期降级 5 / 只恢复不降级 6 / 改选 4）、`profile/slot/PetSlotLevelChangedListenerTest`（4）、`profile/service/PetWriteGuardTest`（4）、`profile/web/ActiveSelectionValidationTest`（5）、`db/migration/PetStatusMigrationTextTest`（2）、`profile/PetStatusSingleEntryGuardTest`（3，含正则正 / 反例自检）。
- L0 单测（补用例 / 改构造）：`DefaultPetPolicyTest`（+3：跳过只读取最早 ACTIVE、全只读回落最早、`pickPerOwner` 同口径）、`PetListItemResponseTest`（+1：status / keepActive 读列、默认 = 最早 ACTIVE）、`ProfileDeletionServiceTest`（+3：删后 `reconcile(PET_DELETED)` 在 delete 之后、删最后一只 / 注销不 reconcile、他人宠物不 reconcile）、各接入点「只读 → 409 且无副作用」：`ContentServiceTest`（+2）、`PlaceCheckinServiceTest`（+3，含「全部已打卡只算 ACTIVE」）、`MilestoneCheckInServiceTest`（+1）、`HealthRecordServiceTest`（+4）、`ProfileServiceTest`（+1）、`HealthEventServiceTest`（+3：ARCHIVED 拦、SKIPPED→ARCHIVED 补存拦、SKIPPED 放）、`TailsonalityUnlockServiceTest`（+2）、`TailsonalityResultServiceTest`（+1）、`PassportSnapshotServiceTest`（+1）、`BoardingPassUnitTest`（+1）、`IdCardServiceTest`（+1）。`MentionEventPublishTest` / `HealthMilestoneCheckInRefusedTest` 只改构造。既有断言均未改语义（只加构造参数 / 一处跟随桩 `findActivePetIdsByOwner → findPetIdsByOwner`）。
- **全量 L0 回归**：`gen_unit.py` 重新筛出的 493 个纯单元测试类（含本 story 新增 6 个）一次跑完：**3662 例，0 失败 / 0 错误（7 个原有 skip）**，BUILD SUCCESS。单宠物既有测试未删、未 skip，断言语义未改。
- 迁移：`V20261018_1604__pet_profiles_status_keep_active.sql`（预分配号）；`bash scripts/ci/check-flyway-versions.sh origin/main` OK（239 个迁移）；未改 `ck_notifications_type` / `ck_payment_intents_purpose`；不回填。
- **锁复用**：`PetSlotStateService.reconcile` / `changeSelection` 进入即调 1.4 的 `PetSlotLock.lockUser`（两参 `pg_advisory_xact_lock(ns, uid)`，ns `0x5045_5453` "PETS"），与建档 `ProfileService.create`、2.2 月租发放是同一把账号锁，三者互斥。未另建锁类。
- T0 核对（与 1.1 ~ 1.4 实际代码）：`DefaultPetPolicy.pick(List)` / `pickPerOwner`、`PetProfileRepository.findDefault`（default 方法委托 `pick`）/ `findAllByOwnerIdOrderByCreatedAtAscIdAsc` / `findByIdAndOwnerId`、`PetResolver.resolve/resolveOptional/listOwned`、`PetListService.listMine`、`PetListItemResponse.STATUS_ACTIVE`、`profile.slot.PetSlotService.quotaOf(long).total()` / `PetSlotLock.lockUser(long)`（`@Transactional(MANDATORY)`）、`ProfileDeletionService.deleteByPetId(ownerId, petId)` / `deleteAllForAccount`、`growth.event.GrowthLevelChangedEvent(long userId, int oldLevel, int newLevel)`（只监听，未改）均与 story 一致；行号漂移以代码为准。`PetQueryService`（③ 3.1）尚不存在 → AC2.4 无需改。`IdCardService.createCard / createCardV2` 仍按 ownerId、不收 petId → 不拦；`generateSerial(ownerId, petId)` 收 petId → 接入（见出入 6）。
- AC7 接入点最终位置（均在归属校验之后）：① `ContentService.publish` `content/service/ContentService.java:511`（`ownsPet` 422 之后）；② `PlaceCheckinService.checkIn` `place/service/PlaceCheckinService.java:113`（`ownedPetIds` 422 / 403 之后逐只）；③ `MilestoneCheckInService.checkIn` `profile/service/MilestoneCheckInService.java:84`（健康类 422、`requireProfile` 404 之后）；④ `HealthRecordService.create / update / delete` `profile/service/HealthRecordService.java:84 / 105 / 134`（经 `requireWritablePet` :147）；⑤ `ProfileService.update` `profile/service/ProfileService.java:182`（409 抛出 → 控制器不执行 `scheduleRerender`）；⑥ `HealthEventService.recordDecision` `profile/service/HealthEventService.java:70`（SKIPPED→ARCHIVED 补存）/ `:86`（新 ARCHIVED）；⑦ `TailsonalityUnlockService.unlock / unlockMatch` `tailsonality/service/TailsonalityUnlockService.java:49 / 59`（`lockOwned` 404 之后、`purchases.start` 之前）；⑧ `TailsonalityResultService.submit` `tailsonality/service/TailsonalityResultService.java:72`；⑨ `PassportSnapshotService.start` `passport/service/PassportSnapshotService.java:94`（冻结快照之前）；⑩ `BoardingPassService.unlock` `passport/service/BoardingPassService.java:107`（`insertIfAbsent` 之前）；⑪ `IdCardService.generateSerial` `profile/service/IdCardService.java:201`。`KeepsakePurchaseService` / purchase 包未改，守卫测试未动；`IdCardHdService.purchaseCard` 及分享 / 庆祝 / 徽章 / 删除 / 全部 GET 未接。
- AC6：`ProfileDeletionService.deleteByPetId` 删除后（同一事务）若账号仍有宠物 → `reconcile(PET_DELETED)`（`profile/service/ProfileDeletionService.java:92`）；删最后一只（照旧打标）与注销 `deleteAllForAccount` 不 reconcile、不加锁。

#### 与 story 文字的出入（按代码现状 / 最保守口径实现）

1. **AC2.1「只给包内可见的 setter」**：`PetProfile`（`profile.domain`）与 `PetSlotStateService`（`profile.slot`）不在同一包，包内可见则状态服务调不到。改为 `markReadOnly()` / `markActive()` / `setKeepActive(boolean)` 设为 public、Javadoc 标明仅 `PetSlotStateService` 可调，并新增源码守卫 `PetStatusSingleEntryGuardTest`（主代码中这三者的调用、以及 `pet_profiles` 的 `SET status / keep_active` 原生写，只允许出现在 `PetSlotStateService.java`）。实体另加只读口 `getStatus()` / `isReadOnly()` / `isKeepActive()`。
2. **`DefaultPetPolicy.pickPerOwner`** 也改为「每账号最早的 ACTIVE，没有再取最早」（与 `pick` 同口径；story 只点名 `findDefault`，但批量版是同一个「默认宠物」定义，不同步会让生命周期推送 / 兽医端默认身份与 `/me` 不一致）。
3. **改选入口**：`reconcile(userId, Trigger)` 的签名带不了选择集合 → 另设 `PetSlotStateService.changeSelection(userId, petIds)`：同一把锁内 归属 404 → 宠物数 ≤ 名额 409 → 去重后只数 ≠ 名额 422 → 写 `keep_active` → 内部按 `SELECTION_CHANGED` 重排（前「名额」只 ACTIVE、其余 READ_ONLY）。`petIds` 含 null / 请求体缺失按 422。直接调 `reconcile(SELECTION_CHANGED)` 也按同一顺序键重排。
4. **`RENTAL_EXPIRED` 在正常数 < 名额时也按序补足恢复**（AC3.4 只写了降级分支；两分支互斥，补足恢复与 AD-4「名额允许时恢复」一致，且 `ReconcileResult.restored` 才有意义）。
5. **端点放在新控制器** `profile/web/PetActiveSelectionController`（同前缀 `/api/v1/pet-profiles`），不改 `ProfileApiController` 构造（既有单测不动）；`SecurityConfig` 加精确路径 `PUT /api/v1/pet-profiles/me/active-selection` → `hasRole("USER")`。无限流（story 未要求）。
6. **`IdCardService.generateSerial`（AC7 #11）只在要分配新号时拦**：已有流水号的幂等回读不写库，照常 200（避免只读宠物进身份证页时报错）。
7. **`HealthEventService`（AC7 #6）只在 ARCHIVED 的两条写路径拦**（新建 ARCHIVED、SKIPPED→ARCHIVED 补存）；已决策的幂等重放（不写库）不拦，SKIPPED 照常。
8. **`PlaceCheckinService.checkedInToday`**：按 1.3 story 移交给 2.1 的口径，`allCheckedIn` 改为「本人有正常宠物且全部 ACTIVE 宠物都已打」（新读口 `PetProfileQueryService.findActivePetIdsByOwner`）；单宠物账号恒等于改造前。
9. **`ReconcileResult.PetRef`** 为 `ReconcileResult` 的嵌套 record（story 未定位置）；另有 `ReconcileResult.none()` / `changed()`。
10. **`AppException.petReadOnly(petId)`** 返回新子类 `shared.error.PetReadOnlyException`（实现 `ProblemExtensions`，扩展成员 `petId`）；另加 `AppException.petSelectionNotNeeded(detail)`。detail 为中文服务端文案（App 按 type 映射，同既有先例）。
11. **`PetWriteGuard`** 除 `requireWritable(long petId)` 外加了 `requireWritable(PetProfile)` 重载（调用方已取到实体时不再查库）；宠物查不到时放行（归属 / 存在性由调用方在前面判 404）。
12. **L1 名额设定**：story 写 `@MockitoBean PetSlotService` 返回名额 2；实现改为 `@MockitoBean GrowthQueryService`（Lv5 = 名额 2、Lv10 = 3），走真实 `quotaOf`，覆盖更完整。
13. 监听器方法本身不加 `@Transactional`（Spring 6.1+ 禁止 `@TransactionalEventListener` 上挂默认传播的事务，启动即失败）；事务由 `reconcile` 自己开，锁 MANDATORY 挂在其上（单测断言了注解形态）。

#### code review（bmad-code-review）结果

- 已修（PLAUSIBLE，竞态）：① 按只删除先删行、后在 reconcile 里取账号锁，与并发 reconcile / 改选可互等死锁 → `deleteByPetId` 开头先 `PetSlotStateService.lockAccount(ownerId)`（xact 锁可重入）；补 `deleteByPetIdLocksAccountBeforeDeletingRows`。② `PetProfile` 无 `@DynamicUpdate`，编辑档案与 reconcile 按整行回写会互相覆盖 status / keep_active / 审核重置的名字头像 → 加 `@org.hibernate.annotations.DynamicUpdate`（只写脏列）。
- 已修（minor）：改选 `petIds` 无上限 → 超过 5 个 422（不进锁）；`PetStatusSingleEntryGuardTest` 补方法引用 `PetProfile::markReadOnly` 的命中。
- 其余（迁移、reconcile 算法与加锁、等级监听、11 个写入口拦截顺序、默认宠物口径、改选端点鉴权）复审无问题。

#### 待确认

- 删除后 reconcile（AC6 / story 待确认 1）：按默认实现（加 `PET_DELETED`，只恢复不降级）。
- Tailsonality 测试提交（AC7 #8 / story 待确认 2）：按默认实现（拦）。
- 删除路径加锁顺序：`deleteByPetId` 先删行、后取账号锁（reconcile 入口加锁）；与建档（先锁后插）并发时理论上不会互等行锁，但未做 L1 并发验证，本地验收时可留意。
- 兽医端 / 后台若有按「默认宠物」展示的地方（经 `pickPerOwner`），多宠账号在出现只读宠物后默认宠物可能换成另一只（出入 2 的直接后果，AD-2 原文口径）。

### File List

- 新增 `petgo-backend/src/main/resources/db/migration/V20261018_1604__pet_profiles_status_keep_active.sql`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/domain/PetStatus.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/slot/{PetSlotStateService,PetSlotTrigger,ReconcileResult,PetSlotLevelChangedListener}.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/service/PetWriteGuard.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/web/PetActiveSelectionController.java`、`profile/dto/ActiveSelectionRequest.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/shared/error/PetReadOnlyException.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/domain/PetProfile.java`（`status` / `keepActive`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/service/{DefaultPetPolicy,PetProfileQueryService,ProfileDeletionService,ProfileService,MilestoneCheckInService,HealthRecordService,HealthEventService,IdCardService}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/dto/PetListItemResponse.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/content/service/ContentService.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/place/service/PlaceCheckinService.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/tailsonality/service/{TailsonalityUnlockService,TailsonalityResultService}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/passport/service/{PassportSnapshotService,BoardingPassService}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/shared/error/{ErrorTypes,AppException}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java`
- 新增测试（`petgo-backend/src/test/java/com/tailtopia/` 下）：`profile/slot/{PetSlotStateServiceTest,PetSlotLevelChangedListenerTest,PetSlotReconcileIntegrationTest,PetReadOnlyGuardIntegrationTest}`、`profile/service/PetWriteGuardTest`、`profile/web/ActiveSelectionValidationTest`、`profile/PetStatusSingleEntryGuardTest`、`db/migration/PetStatusMigrationTextTest`
- 修改测试：`profile/service/{DefaultPetPolicyTest,ProfileDeletionServiceTest,ProfileServiceTest,MilestoneCheckInServiceTest,HealthMilestoneCheckInRefusedTest,HealthRecordServiceTest,HealthEventServiceTest,IdCardServiceTest}`、`profile/web/PetListItemResponseTest`、`content/service/{ContentServiceTest,MentionEventPublishTest}`、`place/service/PlaceCheckinServiceTest`、`tailsonality/service/{TailsonalityUnlockServiceTest,TailsonalityResultServiceTest}`、`passport/{PassportSnapshotServiceTest,BoardingPassUnitTest}`
- 修改 `_bmad-output/implementation-artifacts/v1.4.0/2-1-宠物状态-reconcile与只读拦截.md`（状态、任务勾选、Dev Agent Record）

### Change Log

- 2026-10-18：实现 Story 2.1（迁移 1604 `pet_profiles.status` / `keep_active`、`PetSlotStateService.reconcile` + 改选接口、等级事件监听、删除后释放名额、`PetWriteGuard` 接入 11 类写入口、默认宠物 ACTIVE 优先、`/me/list` 读真实状态），状态 → review（L1/L2 待本地验收）。
