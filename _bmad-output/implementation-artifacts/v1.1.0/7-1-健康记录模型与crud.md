---
baseline_commit: db553fca2005b06f39fd55524e3d571ed029a970
---

# Story 7.1: 健康记录模型与 CRUD

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **所属**：V1.1 Epic 7 第一个 Story（**纯后端建模 + CRUD**）。为 7-2（列表混排 + 里程碑第四路径）、7-3（新手任务）铺数据地基。交付：新表 `health_records`（结构化健康记录：疫苗/驱虫/月经/绝育/自定义）+ CRUD 接口 + 校验（event_date 不可未来、字段长度）+ 档案删除级联硬删（PDP）。
> ⚠️ **区别于既有 `health_events`**（V5，问诊存档 + 健康图，来自 FR-16）——本表是**用户手动录入的结构化记录**；问诊类条目**不入本表**，7-2 只读混排。**健康数据 = PII**：日志严禁落明文（type/name/note/date 均不入日志正文）。

## Story

As a **用户**,
I want **录入结构化健康记录**,
so that **我能系统记录宠物健康（FR-45/45A）**。

## Acceptance Criteria

> **验证层**：**L0**（编译/DTO 校验/service 单测，无 DB）· **L1**（Docker pg：落库、校验、级联删除、schema validate 一致）。本 Story 无 L2（纯后端）。

### AC1 — `health_records` 表 + schema validate 一致

**Given** `health_records`（类型枚举 + event_date，**Flyway V76**——非架构旧稿 V56，见 Dev Notes「Flyway 号」）
**When** 上下文启动
**Then** `ddl-auto=validate` 与实体一致（列/类型/约束）
> 验证层：**L0**（迁移 DDL + 实体编译）+ **L1**（Spring 上下文启动 validate 过）。

### AC2 — CRUD + 字段校验

**Given** 结构化健康记录（`type`∈{VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM} + `event_date`）
**When** 增/删/改/查
**Then**
- **event_date 不可未来**（无下限）；`custom_name`≤20（CUSTOM 必填）、`vaccine_name`≤30（VACCINE 可选）、`note`≤100
- 增删改查**仅本人**（记录归属当前用户的宠物档案；越权 → 404 防枚举，非 403）
- 无档案 → 404
> 验证层：**L1**（真 pg：创建落库、未来日期 422、超长 422、他人记录 404、列表倒序）+ **L0**（DTO Bean 校验 + service 归属/校验分支单测）。

### AC3 — 档案删除级联硬删（PDP）

**Given** 档案删除 / 注销（`ProfileDeletionService`）
**When** 删档
**Then** 该宠物 `health_records` **硬删**（个人健康数据不保留，PDP）；与既有 `health_events`/里程碑级联同事务原子
> 验证层：**L1**（删档后 health_records 该宠物行清零；FK/删除不阻断既有级联测试）+ **L0**（`deleteByPetId` 布线编译）。

---

## Tasks / Subtasks

> **纯后端**。顺序：迁移 → 实体/枚举/repo → CRUD service + 校验 → 接口 → 级联删除布线 → 测试。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [x] **B1. Flyway 迁移 `V76__init_health_records.sql`** (AC: 1)
  - [x] 表 `health_records`：`id` BIGINT IDENTITY PK；`pet_profile_id` BIGINT NOT NULL；`type` VARCHAR(16) NOT NULL CHECK in (`VACCINE`,`DEWORM`,`MENSTRUATION`,`NEUTER`,`CUSTOM`)；`custom_name` VARCHAR(20)；`vaccine_name` VARCHAR(30)；`event_date` DATE NOT NULL；`note` VARCHAR(100)；`created_at` TIMESTAMPTZ NOT NULL DEFAULT now()；`updated_at` TIMESTAMPTZ NOT NULL DEFAULT now()。
  - [x] 索引 `(pet_profile_id, event_date DESC)`（列表倒序）。
  - [x] FK `pet_profile_id`→`pet_profiles(id)` **ON DELETE CASCADE**（PDP 级联硬删 + 不阻断删档；⚠️ 对照 memory：新表 FK→pet_profiles 须想清 ON DELETE，CASCADE 避免阻断既有删除测试）。
  - [x] ⚠️ 迁移号 **V76**（当前最大 V75=6-3；架构旧稿 V56 作废，决策 E2 顺延）。改迁移后 `mvn test-compile` 重拷资源。

- [x] **B2. `HealthRecordType` 枚举 + `HealthRecord` 实体** (AC: 1, 2)
  - [x] `profile/domain/HealthRecordType`（VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM）。
  - [x] `profile/domain/HealthRecord`（petProfileId/type/customName/vaccineName/eventDate/note/created/updated + `@PrePersist/@PreUpdate`）。照 `HealthEvent` 范式。

- [x] **B3. `HealthRecordRepository`** (AC: 2, 3)
  - [x] `findByPetProfileIdOrderByEventDateDescIdDesc(long)`（列表倒序）；`findByIdAndPetProfileId(long,long)`（归属校验取单条）；`deleteByPetProfileId(long)`（级联删除布线）。

- [x] **B4. `HealthRecordService`（CRUD + 校验 + 归属）** (AC: 2)
  - [x] `create/list/update/delete`：owner 取自 JWT → 取其档案（无→404）；写/改/删校验记录归属该档案（越权→404 防枚举）。
  - [x] 校验：`event_date` 不可未来（>今日 422）；`type=CUSTOM` 须 `custom_name`；字段长度（DTO Bean 校验 + service 兜底）；`type` 创建后可改（简单更新）。

- [x] **B5. DTO + 接口** (AC: 2)
  - [x] `HealthRecordCreateRequest`（type/customName/vaccineName/eventDate/note，Bean 校验 `@NotNull`/`@Size`/`@PastOrPresent`）、`HealthRecordUpdateRequest`（部分更新）、`HealthRecordResponse`。
  - [x] `HealthRecordController` `@RequestMapping("/api/v1/pet-profiles/me/health-records")`：`POST`（创建 201）、`GET`（列表倒序）、`PATCH /{id}`、`DELETE /{id}`（204）。owner 取自 JWT，`{id}` 经归属校验（非本人→404）。
  - [x] > 归属说明：`{id}` 为记录主键，**仅在鉴权 owner 作用域内可寻址**（记录须归属当前用户宠物，否则 404）——单宠物 owner 子资源，不跨用户枚举出数据；对外**不**暴露宠物档案顺序 id（沿用 token 规则于跨用户资源）。

- [x] **B6. 档案删除级联布线** (AC: 3)
  - [x] `ProfileDeletionService.deleteByUserId`：删 `health_events` 附近加 `healthRecords.deleteByPetProfileId(petId)`（同 `@Transactional`，PDP 硬删）。DB FK ON DELETE CASCADE 为兜底，显式删更清晰。

### 🟨 联调验收子任务（L1 ⏳ 待本地 Docker）

- [x] **J1. CRUD + 校验 L1**：创建落库（type/date）、未来日期 422、超长字段 422、CUSTOM 缺 custom_name 422、列表 event_date 倒序、他人记录 PATCH/DELETE 404、无档案 404。
- [x] **J2. 级联删除 L1**：删档后该宠物 health_records 清零；删档不被 FK 阻断（既有级联/注销测试仍绿）。
- [x] **J3. schema validate L0/L1**：`mvn compile` + 上下文启动 validate 过（scratch 库 flush Redis 后跑）。

### 🟩 前端子任务

- ❌ **无**。健康记录列表/录入 UI 属 **Story 7.2**（消费本 Story CRUD 接口 + 与问诊存档只读混排）。

---

## Dev Notes

### 关键约定

- **目录/分层**：后端 `com.tailtopia.profile.{web,service,domain,repository,dto}`（health_records 归 profile 域，与 pet_profiles/health_events 同域）。
- **命名映射链**：DB snake_case(`health_records`/`event_date`/`custom_name`) ↔ camelCase ↔ JSON；枚举 UPPER_SNAKE varchar+CHECK；时间戳 UTC；`event_date` 为 DATE（无时区，用户本地日期语义）。
- **健康数据 = PII（红线）**：日志**严禁**记录 type/vaccineName/customName/note/eventDate 明文（架构 §Enforcement + memory）。service/controller 日志只记 record id + 动作，不记内容。
- **错误**：无档案/越权 → 404（防枚举，非 403）；校验失败 → 422 ProblemDetail（不外泄堆栈）。

### 区别既有 health_events（勿混淆）

- `health_events`（V5，Story 2.5）：问诊存档承接（症状/AI 级别/健康图 imageKeys），`source_ref` 幂等键，**非用户手动 CRUD**。
- `health_records`（本 Story，V76）：用户**手动录入结构化记录**（疫苗/驱虫/月经/绝育/自定义）。**问诊类不入本表**；7-2 只读混排两源（`editable` 标志区分）。

### Flyway 号

- 当前最大迁移 **V75**（6-3 id_card_hd_purchases）。架构旧稿「V56 init_health_records」已作废。本 Story = **V76**（决策 E2 单调顺延）。

### 强制护栏（违反即返工）

- `ddl-auto=validate`；schema 归 Flyway；CHECK 用 varchar+UPPER_SNAKE。
- 日志红线：健康数据明文严禁入库/入日志。
- `event_date` 不可未来（DTO `@PastOrPresent` + service 兜底）；字段长度 DB + Bean 双校验。
- 档案删除级联**硬删** health_records（PDP，D1/D2 注销级联延伸——健康记录硬删，architecture-v1.1-delta §9）。

### Project Structure Notes

- 后端新增：`db/migration/V76__init_health_records.sql`、`profile/domain/{HealthRecordType,HealthRecord}.java`、`profile/repository/HealthRecordRepository.java`、`profile/service/HealthRecordService.java`、`profile/dto/{HealthRecordCreateRequest,HealthRecordUpdateRequest,HealthRecordResponse}.java`、`profile/web/HealthRecordController.java`。
- 后端修改：`profile/service/ProfileDeletionService.java`（+deleteByPetProfileId 布线）。

### References

- [Source: epics-v1.1.md#Story 7.1] — health_records 类型枚举+event_date；event_date 不可未来、字段长度、档案删除级联硬删；schema validate 一致。
- [Source: architecture-v1.1-delta.md §3.7] — health_records 字段（pet_profile_id/type[VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM]/custom_name≤20/vaccine_name≤30/event_date 不可未来/note≤100/created_at）；问诊类不入本表；档案删除级联硬删。
- [Source: architecture-v1.1-delta.md §9] — 注销级联延伸：健康记录硬删（PDP）。
- [Source: architecture-v1.1-delta.md #迁移表] — 旧稿 V56（作废，改 V76，决策 E2）。
- [Source: HealthEvent.java / ProfileDeletionService.java] — 既有健康事件实体 + 级联删除编排（本 Story 加 health_records 布线）。
- [Memory: petgo-i18n-model-and-debt] — 类型 code 本地化在前端（7-2）；后端只落枚举 code。
- [Memory: hibernate6-char1-and-cloud-l0-gap] — length=1 别建 VARCHAR(1)（本表无此列，注意即可）；schema 契约等 CI/L1 绿才算数。
- [Memory: shared-dev-db-and-parallel-flyway-collision] — scratch 库跑 L1 + flush Redis + 改迁移后 test-compile 重拷资源。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（本地 dev-story，L0+L1 全绿）

### Debug Log References

- L0：`mvn compile test-compile` exit=0；`HealthRecordServiceTest` **6 绿**（无档案 404/非法类型 422/CUSTOM 缺名 422/valid 落库 captor/越权 PATCH·DELETE 404）。
- L1（scratch `petgo_scratch`，flush Redis 后重建）：`HealthRecordEndpointIntegrationTest` **8 绿**（创建落库+列表 event_date 倒序、未来日期 422、CUSTOM 缺名 422、note 超长 422、无档案 404、他人记录 PATCH/DELETE 404、改删自己记录、删档级联硬删清零）。V76 干净应用（validate 过）。
- 回归（scratch）：`com.tailtopia.profile.**` + `com.tailtopia.account.**` **154 绿 0 失败**（含删档/注销级联——ProfileDeletionService 构造签名变更 + 健康记录级联布线零回归）。

### Completion Notes List

- **迁移 V76** `health_records`（架构旧稿 V56 作废，顺延；当前最大 V75=6-3）。字段照架构 delta：`type`(VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM CHECK)、`custom_name`≤20、`vaccine_name`≤30、`event_date` DATE、`note`≤100。索引 `(pet_profile_id, event_date DESC, id DESC)` 支撑列表倒序。FK→pet_profiles **ON DELETE CASCADE**（PDP 硬删 + 不阻断删档）。
- **CRUD** `HealthRecordService` + `HealthRecordController`（`/api/v1/pet-profiles/me/health-records` POST/GET/PATCH{id}/DELETE{id}）：owner 取自 JWT；记录归属当前用户宠物，越权→404 防枚举；无档案→404。校验：event_date `@PastOrPresent`（未来 422）+ 字段 `@Size` + CUSTOM 须 custom_name（service 兜底）+ 非法 type 422。
- **级联硬删布线**：`ProfileDeletionService.deleteByUserId` 在删 health_events 后加 `healthRecords.deleteByPetProfileId(petId)`（同 @Transactional，PDP）。DB FK CASCADE 兜底。
- **健康数据 PII 红线**：service/controller 不记 type/name/note/date 明文。
- **区别既有 health_events**（问诊存档，非手动 CRUD）：问诊类不入本表，7-2 只读混排。
- 纯后端，无前端（列表/录入 UI 留 7-2）。

### File List

**后端（新增）：**
- `petgo-backend/src/main/resources/db/migration/V76__init_health_records.sql`
- `petgo-backend/src/main/java/com/tailtopia/profile/domain/HealthRecordType.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/domain/HealthRecord.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/repository/HealthRecordRepository.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/dto/HealthRecordCreateRequest.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/dto/HealthRecordUpdateRequest.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/dto/HealthRecordResponse.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/service/HealthRecordService.java`
- `petgo-backend/src/main/java/com/tailtopia/profile/web/HealthRecordController.java`

**后端（修改）：**
- `petgo-backend/src/main/java/com/tailtopia/profile/service/ProfileDeletionService.java`（注入 HealthRecordRepository + 级联硬删布线）

**测试（新增）：**
- `petgo-backend/src/test/java/com/tailtopia/profile/service/HealthRecordServiceTest.java`（L0，6）
- `petgo-backend/src/test/java/com/tailtopia/profile/web/HealthRecordEndpointIntegrationTest.java`（L1，8）

**规划产物（修改）：**
- `_bmad-output/implementation-artifacts/7-1-健康记录模型与crud.md`、`sprint-status-v1.1.yaml`。

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-07-14 | create+dev-story | 后端结构化健康记录 CRUD：V76 health_records（5 类型+event_date 不可未来+字段长度+FK CASCADE PDP 硬删）、CRUD 接口（owner 归属 404 防枚举）、ProfileDeletionService 级联硬删布线。L0 6 + L1 8 + 回归 154 绿。纯后端，UI 留 7-2。baseline=db553fc。 |
