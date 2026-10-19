# Story 1.1: 宠物寻址改造（一）——profile 核心、内容与 Diary 系列

Status: review

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`（从地基 Story 0.1 合入之后的 `dev_1.4.0` 切出）
> 口径优先级：`decision-log.md` ② 节 > `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md`（AD-1 ~ AD-17、P-1 ~ P-4、V-1 / V-2）> UI 稿（均在 `_bmad-output/planning-artifacts/v1.4.0/`）；跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。
> 前置：**地基 Story 0.1**（`implementation-artifacts/v1.4.0/0-1-四条线共用地基.md`，已合入 `dev_1.4.0` @ 81005da7）。本 story 是 multi-pet 线第一条，**不依赖** ① 等级线代码。
> 范围层：**只后端**。本 story 把「我的宠物」入口统一为 `PetResolver` + 可选 `petId`，并把 profile 核心 / Diary 系列（档案、时间线、日历、当天、统计、健康记录、里程碑列表、名片分享信号）的服务层改为按宠物取数。其余模块的服务层（身份证、分享奖励、Tailsonality、护照、登机牌、里程碑打卡 / 分享 / 庆祝、问诊、推送、后台……）在 **Story 1.2**；**唯一约束 `uq_pet_profiles_owner_id` 本 story 不删**（Story 1.4 删）。

## Story

As a 多宠物用户,
I want 「我的宠物」相关接口都能指定是哪只宠物,
so that 每只宠物的档案、时间线、日历和统计互不串。

## Acceptance Criteria

**AC1 — 仓储寻址方法与 `PetResolver`** `[L0]`（AD-2、§5「单一入口」）
1. `PetProfileRepository` **新增**（本 story 不删 `Optional<PetProfile> findByOwnerId`，删除在 Story 1.2）：
   - `List<PetProfile> findAllByOwnerIdOrderByCreatedAtAscIdAsc(long ownerId)`
   - `Optional<PetProfile> findByIdAndOwnerId(long id, long ownerId)`
   - `boolean existsByIdAndOwnerId(long id, long ownerId)`
   - `default Optional<PetProfile> findDefault(long ownerId)` = `DefaultPetPolicy.pick(findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId))`
2. 新类 `profile.service.DefaultPetPolicy`（final，静态方法）：`Optional<PetProfile> pick(List<PetProfile> orderedByCreatedAsc)`——**本 story 口径 = 列表第一只（最早创建，`created_at` 相同按 `id` 升序）**。`pet_profiles.status` 在 Story 2.1 才有，届时 2.1 只改本方法为「最早创建的 ACTIVE，没有 ACTIVE 再取最早创建」；「默认宠物」的定义**全仓只此一处**，任何地方不得自写 `get(0)` / `min(createdAt)`。
3. 新类 `profile.service.PetResolver`（`@Service`）：
   - `PetProfile resolve(long ownerId, Long petId)`：`petId != null` → `findByIdAndOwnerId`，查不到（不存在或不属于本人）→ **404**（`AppException.notFound("宠物档案不存在")`，与不存在同一响应，不泄露存在性）；`petId == null` → `findDefault`，无宠物 → 404「尚未创建宠物档案」（与现状同文案）。
   - `Optional<PetProfile> resolveOptional(long ownerId, Long petId)`：`petId != null` 且不属于本人 → 404；`petId == null` 且无宠物 → `Optional.empty()`（给「无档案时返回 0 / 空而不是 404」的接口用，如分享奖励）。
   - `List<PetProfile> listOwned(long ownerId)` = `findAllByOwnerIdOrderByCreatedAtAscIdAsc`。
   - 类 Javadoc 写：「`/pet-profiles/me/*` 控制器取宠物**只经本类**（架构 §5）；默认宠物定义见 `DefaultPetPolicy`」。
4. `PetResolverTest` / `DefaultPetPolicyTest`（L0，Mockito）覆盖：本人 petId → 该宠物；他人 petId → 404；不存在 petId → 404（两种 404 的 type / detail 完全相同）；null + 两只 → 最早创建；null + `created_at` 相同 → id 小的；null + 无宠物 → 404 / `resolveOptional` 返回 empty。

**AC2 — 旧版兼容：不带 petId 与改造前完全一致** `[L0]` `[L1]`（PRD AC6、FR-145.7、P-4）
- Given 单宠物用户（库里仍有唯一约束），When 不带 `petId` 调用本 story 改造的任一端点（清单见 Dev Notes「端点清单」），Then 响应体、状态码、错误 type 与改造前逐字一致；既有测试**全绿、不删、不 skip**（被签名变更打破的测试只改调用方式，不改断言语义）。

**AC3 — 带 petId 寻址与 404** `[L0]` `[L1]`（AD-2 / AD-3、NFR5）
- Given 带 `petId` 调用 `/pet-profiles/me/timeline?petId=X`（及清单中其他「加 petId」的端点），When X 属于本人，Then 返回 X 的数据；When X 不属于本人或不存在，Then **404**，两种情况响应完全相同；`petId` 非数字 → 400（沿用 `GlobalExceptionHandler` 的类型不匹配处理，:101-108）。

**AC4 — 新增 `GET /api/v1/pet-profiles/me/list`** `[L0]` `[L1]`（AD-3、epics 1.1 AC3）
1. 返回本人全部宠物，按 `created_at` 升序（`id` 升序兜底），无宠物 → `200 []`（**不是 404**，列表接口空即空）。
2. 每项 `PetListItemResponse`：`id`、`name`、`avatarUrl`、`petType`、`breed`、`birthday`、`createdAt`、`status`（本 story 恒为 `"ACTIVE"`，常量来源写在 DTO 里，Story 2.1 改为读列）、`isDefault`（= `DefaultPetPolicy.pick` 选中的那只）、`tailsonalityBadge`（同 `ProfileService.badgeOf`）、`diaryCount`（= `contentService.countGrowthMoments(ownerId, petId)`，与 Diary Tab 统计同一实现）。App「我的」页头像一排（UI 稿 A1 / A2）需要生日、diary 角标、性格小标，故列表一次带齐，App 不为每只宠物再拉 `/me`。
3. 有 1 只宠物 → 返回 1 项；`diaryCount` 等于 `GET /me/archive-stats` 的 `happyMomentCount`。
4. 鉴权同其他 `/me/*`（仅 `ROLE_USER`）；查询逐只算 diaryCount，宠物数上限 5，可接受 N 次计数查询，**不做缓存**。

**AC5 — 两只宠物数据不串（测试夹具绕过约束）** `[L0]` `[L1]`（epics 1.1 AC4）
- Given 测试夹具为同一账号造 A（先建）、B（后建）两只宠物（做法见 Dev Notes「造双宠物的测试夹具」），When 分别用 `petId=A`、`petId=B` 调用时间线、日历、当天详情、统计、健康记录列表（含新增 / 编辑 / 删除）、里程碑列表、档案 `GET/PATCH /me`、名片分享信号，Then 每次只返回 / 只写该宠物的数据；When 不带 `petId`，Then 返回默认宠物 A。
- 健康记录：用 `petId=A` 编辑 / 删除 B 的记录 → 404（`findByIdAndPetProfileId(recordId, petId)` 已有此语义，补测试）。

**AC6 — 入口统一与 1.2 衔接标记** `[L0]`（AD-2、§5）
1. 本 story 改造的控制器方法取宠物**只经 `PetResolver`**；控制器内不得出现 `PetProfileRepository`。
2. 服务层在 Story 1.2 范围内的端点（身份证 / 分享奖励 / Tailsonality / 护照 / 登机牌 / 里程碑打卡·分享·庆祝），本 story 只在控制器加 `petId` 参数并调用 `petResolver.resolve(...)`（或 `resolveOptional`）**做归属校验（404）**，仍调用原服务方法；在该行上方写固定注释 `// TODO(v1.4.0-multi-pet-1.2): 把 pet.getId() 传入服务层`。单宠物库里校验通过的 petId 必然就是唯一那只，行为与改造前一致。Story 1.2 的源码守卫测试会断言此标记全部清零。
3. 不在本 story 改的 `existsByOwnerId` 调用（`ProfileService.create`:61 → 1.4、`deleteMyProfile`:125 → 1.3、`hasProfile`:247）保持原样。

**AC7 — 构建与回归** `[L0]` `[L1]`
1. `mvn -B clean package` 绿（云端只验 L0，见「测试 · 验证层级」）。
2. 不改 `ck_notifications_type` / `ck_payment_intents_purpose`；本 story **无迁移**。

## Tasks / Subtasks

- [x] **T0 开工核对**：确认基线 `dev_1.4.0` 含地基 0.1；把 Dev Notes「现状」表与「调用点改造清单」的 file:line 逐一对一遍（行号漂了以代码为准，Completion Notes 记一句）
- [x] **T1 仓储 + 策略 + 解析器**（AC1）：`PetProfileRepository` 4 个方法、`DefaultPetPolicy`、`PetResolver` + 两个 L0 测试
- [x] **T2 ProfileService**（清单 #1 ~ #5）：`getMyProfile` / `update` / `recordCardShared` 加 `Long petId`；`ownsPet` 改 `existsByIdAndOwnerId`；`findByOwnerId` 包装方法本 story 保留（`AdminUserService`:398 仍在用，1.2 删）
- [x] **T3 TimelineService + 健康时间线源**（清单 #6 ~ #9）：`requireProfile(ownerId, petId)`；四个公开方法加 `Long petId` 重载、旧签名保留并委托 `null`；`HealthEventTimelineSource` 接口与实现改为按 `petId`
- [x] **T4 HealthRecordService**（清单 #10）：四个方法加 `Long petId`
- [x] **T5 MilestoneService.getMilestones**（清单 #11）：加 `(ownerId, Long petId)` 重载，旧签名委托 `null`（`VisitorProjectionService`:300 仍调旧签名，1.3 改）
- [x] **T6 控制器**（AC3 / AC6、端点清单）：逐端点加 `@RequestParam(name = "petId", required = false) Long petId`；新增 `GET /me/list` + `PetListItemResponse`
- [x] **T7 测试**（AC1 ~ AC5）：L0 单测全部；L1 集成测试写好能编译（云端不跑）
- [ ] **T8** `mvn -B clean package`；Completion Notes 标「L1 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 8ca69f9f）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/profile/repository/PetProfileRepository.java` | `Optional<PetProfile> findByOwnerId`（:12）、`findByOwnerIdIn`（:15）、`existsByOwnerId`（:17） | 新增 AC1 的 4 个方法 | 三个旧方法本 story 不删 |
| `…/profile/service/ProfileService.java` | `create`（:60，`existsByOwnerId` :61）、`deleteMyProfile`（:124，:125）、`update`（:137，`findByOwnerId` :138）、`getMyProfile`（:199，:200）、包装 `findByOwnerId`（:210-212）、`recordCardShared`（:220，:221）、`hasProfile`（:246，:247）、`ownsPet`（:255，按唯一宠物比对 id :256-258） | 见调用点清单 | `badgeOf`、送审事件、`create` / `deleteMyProfile` 不动 |
| `…/profile/service/TimelineService.java` | `getTimeline`（:116 / :122 / :135）、`getCalendarMonth`（:461 / :470）、`getDayDetail`（:568 / :574）、`getStats`（:616），都经私有 `requireProfile(ownerId)`（:678-681，走 `profileService.findByOwnerId`）；健康源按 `ownerId` 调（:255 / :497 / :584 / :620）；内容读口已按 `(ownerId, petId)`（:234 / :479 / :577 / :618 / :656） | `requireProfile` 改走 `PetResolver`；四个公开方法加 petId 重载；健康源改传 petId | 游标格式、排序、合并逻辑一字不改 |
| `…/profile/service/HealthEventTimelineSource.java`（接口 :18）/ `HealthEventTimelineSourceImpl.java` | 四个方法都收 `ownerId`，实现里 `profileService.findByOwnerId(ownerId)` 再取 petId（:31 / :43 / :53 / :59） | 接口参数改为 `long petId`，实现直接按 petId 查 `healthEvents` | 唯一使用方是 `TimelineService`（已核） |
| `…/profile/service/HealthRecordService.java` | `timeline` / `create` / `update` / `delete`（:50 / :70 / :85 / :109）都调私有 `requirePet(ownerId)`（:116-120） | 改为 `requirePet(ownerId, petId)` → `petResolver.resolve(...).getId()` | 记录按 `findByIdAndPetProfileId` 校验归属 |
| `…/profile/service/MilestoneService.java` | `getMilestones(ownerId)`（:89，`findByOwnerId` :90），roster 缺失 lazy 物化 | 加 `getMilestones(long ownerId, Long petId)`，旧签名委托 `null` | lazy 物化逻辑不动 |
| `…/profile/service/HealthEventService.java` | `recordDecision` 用 `profileService.ownsPet(ownerId, req.petId())`（:56），不属于 → 403 | 不改（`ownsPet` 改实现后自然多宠安全） | 403 语义（存量契约） |
| `…/content/service/ContentService.java` | `publish` 校验 `profileService.ownsPet(authorId, petId)`（:500）；Diary 读口 `findGrowthMoments*` / `countGrowthMoments` / `findCheckinIdsWithTimelinePost` / `findRecentGrowthMomentsByEventDate` 等（:442 / :695 / :723 / :755 / :767 / :783 / :799 / :819 / :835）**已经按 `(authorId, petId)`** | **不改**（只要调用方传对 petId 即多宠安全；`content_pets` 关联表在 Story 3.1） | 全部签名 |
| `…/profile/web/ProfileApiController.java` | 类级 `/api/v1/pet-profiles`（:50），`/me/*` 全部只取 `currentUserId(jwt)`，无 petId | 见端点清单 | 限流键、`cardRerenderService.scheduleRerender(updated.id())`（:131） |
| `…/profile/web/HealthRecordController.java`（:34）/ `MilestoneController.java`（:33）/ `tailsonality/web/TailsonalityController.java`（:35）/ `passport/web/BoardingPassController.java`（:23）/ `passport/web/PetPassportController.java`（:53 起） | 均无 petId | 见端点清单 | 路径、限流 |
| `…/shared/error/GlobalExceptionHandler.java` | 参数类型不匹配 → 400（:101-108）；`AppException` → ProblemDetail（:52-64） | 不改 | — |

### 调用点改造清单（本 story 部分；其余全部在 Story 1.2 清单，两份合起来覆盖全仓）

归类：**按宠物操作** = 接收 petId（缺省走默认宠物）；**只判有无** = `existsByOwnerId` 保留；**本 story 不动** = 由后续 story 处理。

| # | 位置 | 所在方法 | 现调用 | 归类 | 改法 |
|---|---|---|---|---|---|
| 1 | `ProfileService.java:138` | `update` | `profiles.findByOwnerId` | 按宠物操作 | `update(long ownerId, Long petId, req)` → `petResolver.resolve` |
| 2 | `ProfileService.java:200` | `getMyProfile` | 同上 | 按宠物操作 | `getMyProfile(long ownerId, Long petId)` |
| 3 | `ProfileService.java:221` | `recordCardShared` | 同上 | 按宠物操作 | `recordCardShared(long ownerId, Long petId)`；`CardSharedEvent` 已带 petId，不改事件 |
| 4 | `ProfileService.java:256` | `ownsPet` | `findByOwnerId(...).map(id equals)` | 按宠物操作（归属校验） | `return profiles.existsByIdAndOwnerId(petId, ownerId);`（调用方 `ContentService`:500、`HealthEventService`:56 不改） |
| 5 | `ProfileService.java:211` | 包装 `findByOwnerId` | `profiles.findByOwnerId` | — | 本 story 去掉 #6 ~ #9 的使用；方法本身留到 Story 1.2（还剩 `AdminUserService`:398） |
| 6 | `TimelineService.java:679` | `requireProfile` | `profileService.findByOwnerId` | 按宠物操作 | `requireProfile(long ownerId, Long petId)` → `petResolver.resolve` |
| 7 | `HealthEventTimelineSourceImpl.java:31` | `recentHealthEvents` | 同上 | 按宠物操作 | 签名改 `(long petId, …)`，直查 `healthEvents.findBeforeAnchor(petId, …)` |
| 8 | `HealthEventTimelineSourceImpl.java:43`（:53 委托） | `healthEventsInRange` / `healthEventsOnDay` | 同上 | 按宠物操作 | 同上 |
| 9 | `HealthEventTimelineSourceImpl.java:59` | `countHealthEvents` | 同上 | 按宠物操作 | 同上 |
| 10 | `HealthRecordService.java:117` | `requirePet` | `profiles.findByOwnerId` | 按宠物操作 | `requirePet(long ownerId, Long petId)` |
| 11 | `MilestoneService.java:90` | `getMilestones` | 同上 | 按宠物操作 | 新重载 `(ownerId, Long petId)` |
| 12 | `ProfileApiController.java:97` | `myProfile` | `profileService.getMyProfile` | 按宠物操作 | 传 `petId` |
| 13 | `ContentService.java:500` | `publish` | `profileService.ownsPet` | 按宠物操作（已是） | 不改（随 #4 自然多宠安全） |
| 14 | `HealthEventService.java:56` | `recordDecision` | `profileService.ownsPet` | 按宠物操作（已是） | 不改 |
| — | `ProfileService.java:61` | `create` | `existsByOwnerId` | — | **本 story 不动** → Story 1.4 改名额校验 |
| — | `ProfileService.java:125` | `deleteMyProfile` | `existsByOwnerId` | — | **本 story 不动** → Story 1.3 改按 petId 删 |
| — | `ProfileService.java:247` | `hasProfile` | `existsByOwnerId` | 只判有无 | 保留（全仓无调用方，不删不改） |

> 两份清单的计数口径：`findByOwnerId` / `existsByOwnerId` / `findByOwnerIdIn` 在 `src/main/java` 共 **51** 处调用（不含仓储声明）：本 story 改 10 处（#1 ~ #4、#6 ~ #11）+ 3 处 `existsByOwnerId`「不动 / 保留」= 13；其余 38 处（含 #5 包装方法内的 :211）在 Story 1.2。`PetProfileQueryService` 读口调用方 16 处、原生 SQL 11 处全部在 Story 1.2。本 story 另改 `ProfileService` 包装方法的 4 个调用方（#6 ~ #9）与 `ownsPet` 实现。

### 端点清单（本 story 加 `petId` 的范围）

所有「加」的端点：`@RequestParam(name = "petId", required = false) Long petId`。「1.1 服务层」= 本 story 把 petId 传进服务；「入口校验」= 本 story 只 `resolve` 做 404 校验并加 `TODO(v1.4.0-multi-pet-1.2)` 标记，服务层在 1.2。

| 控制器 | 端点 | petId | 本 story 改法 |
|---|---|---|---|
| `ProfileApiController` | `GET /me` | 加 | 1.1 服务层（#2） |
| | `PATCH /me` | 加 | 1.1 服务层（#1）；`cardRerenderService.scheduleRerender(updated.id())` 不变 |
| | `DELETE /me` | **不在本 story** | Story 1.3 |
| | `POST /me/card-shares` | 加 | 1.1 服务层（#3） |
| | `GET /me/timeline`、`/me/calendar`、`/me/day`、`/me/archive-stats` | 加 | 1.1 服务层（#6 ~ #9） |
| | `GET /me/list` | 新增 | AC4 |
| | `GET /me/id-card`、`POST /me/id-card`、`POST /me/id-card/hd-download` | 加 | 入口校验（`resolve`）→ 1.2 `IdCardService` / `IdCardHdService` |
| | `GET /me/id-cards`、`/me/id-cards/pricing`、`/me/id-cards/{cardId}`、`POST /me/id-cards`、`POST /me/id-cards/v2`、`POST /me/id-cards/{cardId}/hd-download` | **不加** | 身份证卡只挂账号（PRD §2「按账号存的数据」），`createCard` 不读宠物档案（已核 `IdCardService` 只在 :179 / :189 读档案） |
| | `POST /me/id-cards/{cardId}/share-rewards`、`GET /me/id-cards/share-reward` | 加 | 入口校验（**`resolveOptional`**：无档案时仍返回 coins 0，不是 404）→ 1.2 `IdCardShareRewardService` |
| | `POST /me/age-cards/share-rewards` | **不加** | P-3：年龄卡按账号 |
| | `POST /me/tailsonality/share-rewards`、`POST /me/passport/share-rewards` | 加 | 入口校验（`resolveOptional`）→ 1.2 `PetCardShareRewardChannel` |
| `HealthRecordController` | `GET`、`POST`、`PATCH /{id}`、`DELETE /{id}` | 加 | 1.1 服务层（#10） |
| `MilestoneController` | `GET` | 加 | 1.1 服务层（#11） |
| | `GET /checkin-candidates`、`POST /{code}/check-in`、`POST /{code}/shares`、`POST /celebrations` | 加 | 入口校验 → 1.2 `MilestoneCheckInService` / `MilestoneShareService` / `MilestoneCelebrationService` |
| `TailsonalityController` | `POST /results`、`GET /results`、`DELETE /badge` | 加 | 入口校验 → 1.2 |
| | `GET /results/{token}`、`POST /results/{token}/unlock`、`POST /results/{token}/match-unlock`、`PUT /badge`（按 `resultToken`） | **不加** | 宠物由 token 行反查 + 归属校验（1.2 实现）；token 本身已定位到宠物，再传 petId 只会制造「对不上」的分支 |
| `BoardingPassController` | `GET`、`GET /{placeToken}`、`POST /{placeToken}/unlock` | 加 | 入口校验 → 1.2（`placeToken` 是场所，不定位宠物） |
| `PetPassportController` | `GET /passport`、`POST /passport/snapshots`、`GET /passport/snapshots` | 加 | 入口校验 → 1.2 |
| | `GET /passport/snapshots/{token}` | **不加** | 同 token 反查（1.2） |
| `NewbieTaskController` `/api/v1/me/newbie-tasks` | — | **不加** | P-2：按账号（Story 1.3） |
| `HealthEventController` `/api/v1/health-events/archive-decisions` | — | 已在请求体带 `petId` | 不改 |

### 造双宠物的测试夹具（库里仍有唯一约束）

- **L0（单元，云端可跑）**：Mockito mock `PetProfileRepository` / `PetResolver`，直接返回两只 `PetProfile`。新建测试工具 `src/test/java/com/tailtopia/profile/support/PetFixtures.java`：`static PetProfile pet(long ownerId, long id, PetType type, Instant createdAt)`，用 `PetProfile.create(...)` 后 `ReflectionTestUtils.setField(p, "id", id)`、`setField(p, "createdAt", createdAt)`（现有测试用 `Field.setAccessible` 设 id，见 `TimelineServiceTest.java:90-100`，照此收拢成公共工具，**不改**既有测试的私有 helper）。服务测试按 `(ownerId, petId)` 断言仓储被以哪个 petId 调用、返回体只含该宠物数据。
- **L1（集成，需 Docker，云端不跑）**：新建 `src/test/java/com/tailtopia/profile/support/MultiPetDbFixture.java`（`@Component` 不需要，纯静态工具接收 `JdbcTemplate`）：
  ```java
  /** 返回本次是否真的删了约束（1.4 迁移之后约束已不存在 → false，restore 自然 no-op）。 */
  static boolean dropOwnerUniqueIfPresent(JdbcTemplate jdbc) {
      Integer n = jdbc.queryForObject(
          "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_pet_profiles_owner_id'", Integer.class);
      if (n != null && n > 0) {
          jdbc.execute("ALTER TABLE pet_profiles DROP CONSTRAINT uq_pet_profiles_owner_id");
          return true;
      }
      return false;
  }
  /** 先删掉本测试造的多余宠物（每个 owner 只留最早一只），再按原定义加回约束。 */
  static void restoreOwnerUnique(JdbcTemplate jdbc, boolean dropped, Collection<Long> extraPetIds) { … }
  static long insertPet(JdbcTemplate jdbc, long ownerId, String name, String petType, Instant createdAt) { … } // card_token 用 SecureRandom 唯一串
  ```
  - 在测试类 `@BeforeEach` 调 drop、`@AfterEach` 在 `finally` 里调 restore（先删本测试插入的第二只宠物及其派生行，再 `ALTER TABLE pet_profiles ADD CONSTRAINT uq_pet_profiles_owner_id UNIQUE (owner_id)`）；**只有 drop 返回 true 时才 restore**，保证 Story 1.4 迁移落地后这些测试照常可跑且不会把约束加回来。
  - 第二只宠物用 `insertPet` 直插（不走 `POST /pet-profiles`，那条在 1.4 前仍 409）；`created_at` 显式给，让「默认 = 最早创建」可断言。
  - 同库顺序依赖风险：surefire 单 fork 串行执行测试类，约束在类内即删即恢复；Completion Notes 记录本地全量跑的结果。

### 架构约束

- AD-1：本 story 不删约束、不删 `Optional findByOwnerId`；库里仍单宠物时本 story 可独立上线。
- AD-2：默认宠物 = 最早创建（2.1 起加 ACTIVE 优先），**只在 `DefaultPetPolicy`**；不属于本人一律 404。
- AD-3 / P-4：在现有 `/pet-profiles/me/*` 上加可选 `?petId=`，**不复制端点**、不新增 `/pet-profiles/{petId}/*`。
- §5：`/me/*` 控制器只经 `PetResolver`；ProblemDetail 照 `GlobalExceptionHandler`；不新增 ErrorType（404 复用 `notFound`）。
- 日志不记宠物名、体重、健康数据；`petId` 可记。
- 护栏：不引 MQ / 缓存；`ddl-auto=validate`；本 story 无迁移。

### 本 story 不做

- 不删 `Optional<PetProfile> findByOwnerId` / `findByOwnerIdIn` / `ProfileService.findByOwnerId` 包装、不写源码扫描守卫 → **Story 1.2**。
- 不改身份证、分享奖励、Tailsonality、护照、登机牌、里程碑打卡 / 分享 / 庆祝 / 自动完成、问诊、推送、复购、后台、关注推荐、推荐物种的服务层 → **Story 1.2**。
- 不做按宠物删除、访客统计按宠物、打卡按宠物、新手任务 → **Story 1.3**；`VisitorProjectionService`（:262 / :300）继续调旧签名（= 默认宠物），1.3 改。
- 不做名额、不改 `ProfileService.create` → **Story 1.4**；不加 `pet_profiles.status` → **Story 2.1**；不做 `content_pets` → **Story 3.1**。
- 不改 App → **Story 1.5**。

### 待确认（实现按默认做法，不阻塞）

- **身份证分享奖励归到哪只宠物**：`id_cards` 只挂账号、没有宠物外键，「各宠物各领一次」只能按请求里的 `petId`（App 传 Diary Tab 当前宠物，缺省 = 默认宠物）判定。默认按此实现（入口在本 story，发放逻辑在 1.2）；账号月度上限 `share_reward_monthly_cap` 兜住总量。

### 测试

**验证层级**
- **L0（云端可跑，纯单元，无 DB / Redis / Spring 上下文）**：
  - `profile/service/DefaultPetPolicyTest`、`profile/service/PetResolverTest`（AC1.4）。
  - `profile/service/ProfileServiceTest` 补：`getMyProfile` / `update` / `recordCardShared` 带 petId 走 `resolve`；`ownsPet` 改为 `existsByIdAndOwnerId`（他人 petId → false）。
  - `profile/service/TimelineServiceTest`、`TimelineCursorMergeTest`、`CalendarAndDayDetailStory34Test`、`TimelinePlaceCheckinTest`、`TimelineTailsonalityTest`、`TimelinePaginationRegressionTest`：改为 mock `PetResolver`；新增「双宠物：petId=B 时内容读口、健康源、打卡 / 性格 banner 读口都以 B 调用」「不带 petId = A」用例。
  - `profile/service/HealthEventTimelineSourceImplTest`（新增）：按 petId 直查。
  - `profile/service/HealthRecordServiceTest` 补：petId=A 编辑 B 的记录 → 404。
  - `profile/service/MilestoneServiceTest`（若无则新增）：`getMilestones(owner, B)` 只读 B 的 roster。
  - `profile/web/PetListItemResponseTest`（新增，纯映射）：`status = ACTIVE`、`isDefault` 只有一只为 true。
- **L1（需 Docker postgres + redis，云端不跑，待本地验收）**：
  - `profile/web/ProfileApiControllerEndpointTest` 补：不带 petId 响应不变；他人 petId / 不存在 petId → 同一 404；非数字 → 400；`GET /me/list` 单宠 1 项、无宠 `[]`。
  - `profile/MultiPetAddressingIntegrationTest`（新增，`extends ApiIntegrationTest`，用 `MultiPetDbFixture`）：AC5 全部端点双宠不串、缺省 = A；健康记录跨宠编辑 404；`/me/list` 两项按创建时间。
  - 本地跑 L1：新建空 scratch 库 + flush Redis DB0 后 `DB_NAME=<scratch> mvn -B clean package`。
- 云端只跑 L0；Completion Notes 标「L1 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 1.1]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#§0.3 第 1、2、9 条 · AD-1 / AD-2 / AD-3 · §5 · §6.1 / §6.2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.1 旧版本 App 兼容 · AC6]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② 多宠物]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- 云端 locale 为 POSIX，`mvn` 编译中文嵌套测试类名报 `bad filename`；所有 mvn 命令加 `LC_ALL=C.UTF-8` 后正常（与代码无关，本地 macOS 不受影响）。

### Completion Notes List

- **L1/L2 待本地验收**：云端只跑 L0（`./mvnw -B clean package -DskipTests` + 下列 L0 单测）。`MultiPetAddressingIntegrationTest`（新增）与 `ProfileApiControllerEndpointTest` 新增 3 例为 L1，已写好、编译通过，**未运行**。
- L0 通过：`DefaultPetPolicyTest`、`PetResolverTest`、`ProfileServiceTest`、`TimelineServiceTest`、`TimelineCursorMergeTest`、`CalendarAndDayDetailStory34Test`、`TimelinePlaceCheckinTest`、`TimelineTailsonalityTest`、`TimelineMultiPetTest`（新增）、`HealthEventTimelineSourceImplTest`（新增）、`HealthRecordServiceTest`、`MilestoneServiceTest`（新增）、`PetListItemResponseTest`（新增）、`ProfileApiControllerTest`、`CardPageControllerTest`；另跑全部纯单元测试类做回归（见下）。`TimelinePaginationRegressionTest` 是 `@SpringBootTest`，属 L1，未跑。
- T0 核对：Dev Notes 现状表行号与 `feat/1.4.0-multi-pet` @ 688f9bd5 一致，无漂移。
- 与 story 文字的出入（已按代码现状实现，记于此）：
  1. `GET /me/list` 的组装放在新类 `profile.service.PetListService`（不在 `ProfileService`）：`ContentService` 经 `ProfileService.ownsPet` 依赖 `ProfileService`，若 `ProfileService` 再注入 `ContentService` 会成环。
  2. `TimelineService` / `HealthRecordService` / `MilestoneService` 的构造参数由 `ProfileService` / `PetProfileRepository` 换成 `PetResolver`（服务内取宠物只经 `PetResolver`）；`HealthEventTimelineSourceImpl` 不再依赖 `ProfileService`。相应单测只改了构造与桩（`findByOwnerId` → `petResolver.resolve(owner, null)` / `findDefault`；健康源桩由 ownerId 改为 petId），断言未改。
  3. 「入口校验」端点（1.2 才改服务层的那些）一律用 `petResolver.resolveOptional(...)` 而不是 `resolve(...)`：不带 petId 时与改造前完全一致（含无档案账号——原服务对无档案各有自己的处理，`resolve` 会提前抛 404 改变行为）；带他人 / 不存在的 petId 仍 404。`TODO(v1.4.0-multi-pet-1.2)` 标记照写（共 20 处），1.2 清零。
- 回归：全部纯单元测试类（自动筛出不含 `@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest` / Testcontainers、不继承 `ApiIntegrationTest`、非 `*IntegrationTest` 的 467 个类）共 3454 例，0 失败 / 0 错误（7 个原有 skip）。单宠物既有测试未删、未 skip。
- **code review（bmad-code-review）**：无行为回归类发现。CONFIRMED 1 条已修：新端点 `GET /me/list` 未显式限定 `ROLE_USER`，兽医 token 落 `anyRequest().authenticated()` 可按同号用户读宠物列表（AC4.4 要求仅 `ROLE_USER`）→ `SecurityConfig` 按惯例加精确路径 `hasRole("USER")`，`ProfileApiControllerEndpointTest.listRejectsNonUserRole`（L1）覆盖。PLAUSIBLE（未修，存量问题）：`GET /me`、`/me/timeline` 等其他 `/pet-profiles/me/*` 同样只靠 `authenticated()`，非本 story 引入。
- 本 story 无迁移；未改 `ck_notifications_type` / `ck_payment_intents_purpose`。

#### 待确认

- 身份证分享奖励归属（story「待确认」原文）：按请求 `petId` 判定，缺省 = 默认宠物；本 story 只做入口校验，发放在 1.2。
- 时间线源⑤「身份证首次生成」仍按账号取（`id_cards` 不挂宠物），多宠后每只宠物的时间线都会出现同一条「名片已生成」。story 未要求改，保持现状。

### File List

- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/service/DefaultPetPolicy.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/service/PetResolver.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/service/PetListService.java`
- 新增 `petgo-backend/src/main/java/com/tailtopia/profile/dto/PetListItemResponse.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/repository/PetProfileRepository.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/service/{ProfileService,TimelineService,HealthEventTimelineSource,HealthEventTimelineSourceImpl,HealthRecordService,MilestoneService}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/profile/web/{ProfileApiController,HealthRecordController,MilestoneController}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/tailsonality/web/TailsonalityController.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/passport/web/{BoardingPassController,PetPassportController}.java`
- 新增测试 `profile/support/{PetFixtures,MultiPetDbFixture}.java`、`profile/service/{DefaultPetPolicyTest,PetResolverTest,MilestoneServiceTest,HealthEventTimelineSourceImplTest,TimelineMultiPetTest}.java`、`profile/web/PetListItemResponseTest.java`、`profile/MultiPetAddressingIntegrationTest.java`
- 修改测试 `profile/service/{ProfileServiceTest,HealthRecordServiceTest,TimelineServiceTest,TimelineCursorMergeTest,CalendarAndDayDetailStory34Test,TimelinePlaceCheckinTest,TimelineTailsonalityTest}.java`、`profile/web/{ProfileApiControllerTest,ProfileApiControllerEndpointTest}.java`

### Change Log

- 2026-10-18：实现 Story 1.1（PetResolver / DefaultPetPolicy / `/me/*` 可选 petId / `GET /me/list`），状态 → review（L1/L2 待本地验收）。
