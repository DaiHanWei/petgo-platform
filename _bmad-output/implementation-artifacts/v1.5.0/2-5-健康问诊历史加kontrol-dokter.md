# Story 2.5: 健康问诊历史加 Kontrol dokter（后端）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ① 健康记录提醒 · 分支 `feat/1.5.0-health-reminder`
> 口径优先级：CROSS-STORY-DECISIONS 地基段 > 架构增量 §6.3 > PRD FR-161「Kontrol dokter records are listed in "Konsultasi saya"」、§9 Consult history > UI 稿 A10。
> 前置：1.1（`VET_VISIT`）、1.2（`RecordDisplayStatus`）。
> 范围层：**只后端**。App 的 tile 与按钮归 3.5。

## Story

As a 宠物主人,
I want 在 Kesehatan 页「Konsultasi saya」里同时看到 AI / 兽医问诊和我记的 Kontrol dokter,
so that 看病记录集中在一处。

## Acceptance Criteria

### AC1 — 第三种条目 `[L0]` `[L1]`（架构 §6.3）
1. `ConsultHistoryItem` 追加字段（全部可空，NON_NULL 不下发）：`recordId`、`petId`、`petName`、`eventDate`(LocalDate)、`note`、`nextDate`、`overdue`(Boolean)、`recordStatus`；新增工厂 `vetVisit(...)`，`type = "VET_VISIT"`。
2. `ConsultHistoryService.history(userId, cursor, limit, boolean v150Client)`：`v150Client=true` 时把该账号**全部宠物**（宠物切换器里的，含只读）的 `VET_VISIT` 记录加入同一个 `all` 列表再排序 / 游标切分；`date` = `eventDate` 当天 + 该记录 `created_at` 的 WIB 时分秒，转 `Instant`（PRD「sorts by event date, creation time of day as tiebreaker」）。`nextDate` / `overdue` / `recordStatus` 用 1.2 的 `RecordDisplayStatus`（`overdue = recordStatus == OVERDUE`）。
3. `ConsultHistoryController` 读 `X-App-Version`；旧版 → 不加该类型（旧版 App 不认识第三种 type）。
4. 跨模块读健康记录：consult 模块经 profile 的只读端口（新增 `profile.service.VetVisitHistoryQuery.forOwner(userId) → List<VetVisitHistoryRow>`，内部读 `HealthRecordRepository` 与宠物名），**不在 consult 包里直接注入 profile 仓储**。
5. 用例：两只宠物各有 Vet visit、与 AI / VET 问诊交错 → 按日期混排、游标翻页不重不漏；同日两条按创建时刻；旧版请求头 → 无 VET_VISIT；空历史 + 有 Vet visit → 列表非空（App 不显示空态）。

### AC2 — 回归 `[L0]`
1. 现有 `ConsultHistoryServiceTest` / 问诊历史集成测试不红（旧调用签名保留重载，默认 `v150Client=false`）。

## Tasks / Subtasks

- [ ] T1 `VetVisitHistoryQuery`（profile 只读端口）
- [ ] T2 `ConsultHistoryItem` 字段与工厂、`ConsultHistoryService` 合并、Controller 读头
- [ ] T3 测试：`ConsultHistoryServiceTest` 补 VET_VISIT 用例（L0，mock 端口）；`ConsultHistoryVetVisitIntegrationTest`（L1）能编译
- [ ] T4 L0 跑绿，Completion Notes 标「L1 待本地验收」

## Dev Notes

- 现有合并是「内存合并两源 + 游标 = 上一页末条 epochMillis」（`ConsultHistoryService.history`），VET_VISIT 进同一个列表即可；数据量小（V1 低量），不做 SQL 层合并。
- `petId` 下发给本人，用于 App 切宠物（App 现有 `currentPetIdProvider` 就按数字 id 工作，未新增外露面）。
- 日志禁记备注。

### 验证层级（云端只跑 L0）
- L0：`ConsultHistoryServiceTest`
- L1：`ConsultHistoryVetVisitIntegrationTest`

## Dev Agent Record

### Completion Notes

### File List
