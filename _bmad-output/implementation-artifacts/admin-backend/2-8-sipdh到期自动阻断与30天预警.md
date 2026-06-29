# Story 2.8: SIPDH 到期自动阻断与 30 天预警

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **范围域**：管理后台 V1.0.0 · Epic 2 · Story 2.8（AB-2H 定时部分）。**依赖 Story 2.1**（`vet_qualifications.sipdh_expiry` + 6 态）。
> **产物归属**：仅后端 `petgo-backend`（**无 Flutter 侧**）。`@Scheduled` 定时任务 + 后台预警 + 列表筛选联动（2.2）。
> **brownfield 关键**：`@Scheduled` 范式 **已存在**（notify `ScheduledPushDispatcher`/`@Scheduled` + DB 去重标记；consult `ConsultCloseScanner`）。本故事新增 SIPDH 到期扫描器，沿用既有定时范式，**禁中间件（F5）**。

## Story

As a **平台**,
I want **每日检查兽医 SIPDH 有效期，自动阻断到期者并对临近到期者预警**,
so that **杜绝证件过期仍接诊，并提醒运营提前续期**。

## Acceptance Criteria

> 验证层：**L0** 单测（mock repo/clock）· **L1** 集成（Docker postgres+redis，验状态切换 + 列表筛选）。

1. **AC1（到期自动阻断，L1）**：`@Scheduled` 每日扫描资质 `CERTIFIED`/`EXPIRING_SOON` 的兽医 SIPDH 有效期；**到期当日（sipdh_expiry < 今日）**→ 状态切 `EXPIRED`（证件已过期）+ **停止接单**（2.1 `canTakeConsult` 对 EXPIRED 返 false，自动联动）+ 向运营发后台预警。
2. **AC2（30 天预警，L1）**：距到期 **≤ 30 天且未过期** 的 `CERTIFIED` → 状态切 `EXPIRING_SOON`（证件即将到期，**仍可接单**）；详情页橙色预警标识；列表（2.2）可按「即将到期」筛选。
3. **AC3（@Async + DB 去重，禁中间件，L1）**：扫描用 `@Scheduled` + `@Async` 逐条处理；**去重靠状态机幂等**——已 `EXPIRED` 不重复切、已 `EXPIRING_SOON` 不重复预警；**绝不引入 MQ/调度/缓存中间件（F5）**。
4. **AC4（无人工操作 → 不写操作审计，L1）**：到期/预警是系统行为、无 admin 操作人 → **不写 `admin_audit_logs`**；系统行为经 SLF4J JSON 日志（不含证件 PII）可观测；预警以后台可见形式呈现（见防坑）。
5. **AC5（回归绿，L0/L1）**：既有 notify/consult 定时任务不回归；`mvn -B compile` + 单测全绿；本地 L1 全量回归绿。

## Tasks / Subtasks

- [ ] **T1 扫描器 + 状态切换（AC1/AC2/AC3）**
  - [ ] 新建 `admin/vetqual/service/SipdhExpiryScanner`（`@Component`，`@Scheduled(cron=...)` 每日一次，cron 可 env 配，沿用 `ConsultCloseScanner` 的 try/catch + 日志风格）
  - [ ] `VetQualificationService` 新增 `scanExpiry(LocalDate today)`：查 `CERTIFIED`/`EXPIRING_SOON` 且有 `sipdh_expiry` 的资质 → 逐条：`expiry < today` → `EXPIRED`；`today <= expiry <= today+30` → `EXPIRING_SOON`（若当前 CERTIFIED）；状态机幂等（已目标态跳过）；返回 (expired数, warned数)
  - [ ] `VetQualificationRepository` 加到期查询（`findByStatusInAndSipdhExpiryNotNull(...)` 或按 expiry 范围）
  - [ ] 实体加 `markExpired()`/`markExpiringSoon()` 状态机方法（合法迁移校验）
- [ ] **T2 后台预警（AC1/AC4）**
  - [ ] 预警呈现：列表（2.2）「证件即将到期/已过期」筛选 + 详情页橙色标识为主；另在 dashboard 或资质相关页展示「到期/即将到期数量」徽标（轻量，读资质状态统计）。**不发 IM/邮件**（V1.0.0 无外部通知）
- [ ] **T3 回归（AC5）**
  - [ ] `VetQualificationServiceTest`（mock repo + 固定 today/clock）：过期→EXPIRED、≤30 天→EXPIRING_SOON、未到期保持 CERTIFIED、幂等（已 EXPIRED 不再切）、边界（恰好 30 天 / 恰好今日到期）；`SipdhExpiryScanner` 调度方法异常吞掉不崩；`mvn -B compile` + 单测全绿

## Dev Notes

### 架构约束（必须遵守）
- 定时只用 `@Scheduled` + `@Async` + DB（状态机幂等去重），**禁 MQ/调度/缓存中间件（F5）**。[Source: CLAUDE.md 强制护栏；architecture.md#API & Communication Patterns 定时任务]
- `ddl-auto=validate`、schema 归 Flyway；本故事不加列（用 2.1 字段）。
- 系统行为不写操作审计（审计=后台**写操作**=有 admin 操作人）；日志 JSON 严禁证件 PII。[Source: architecture.md#Communication Patterns；CLAUDE.md 日志护栏]

### 既有代码基线（READ 过，brownfield）
- **现状（@Scheduled 范式可抄）**：`consult/service/ConsultCloseScanner`（`@Scheduled(fixedDelayString=...)` + try/catch + `closeService.closeExpiredGates()`）；`notify/schedule/ScheduledPushDispatcher`+`ScheduledPushJob`（每日扫描 + `@Async` 逐条 + DB 去重表 `scheduled_push_marks` 唯一约束当去重源，禁 Redis/MQ）。2.1 的 `vet_qualifications.sipdh_expiry`(date) + `QualificationStatus`(6 态) + `VetQualificationService`。
- **本故事改什么**：新增 `SipdhExpiryScanner` + `VetQualificationService.scanExpiry` + 实体 markExpired/markExpiringSoon + repository 到期查询 + 后台预警呈现。
- **不可破坏**：① 既有 notify/consult 定时任务不动；② 2.1 的 `canTakeConsult`（{CERTIFIED,EXPIRING_SOON} 可接单）不改——本故事切 EXPIRED 即自动停接单、切 EXPIRING_SOON 仍可接单，**门控逻辑零改动**；③ 资质其它字段/录入审核（2.7）不动。

### 关键边界 / 防坑
- **去重靠状态机幂等，不另建去重表**：与 `scheduled_push_marks` 不同，到期/预警是**状态转换**（CERTIFIED→EXPIRING_SOON→EXPIRED 单向），天然幂等——已 EXPIRED 不再切、已 EXPIRING_SOON 不重复处理。**无需新建去重表/列**（保持最小、不加 Flyway）。若产品要求「每日重复发预警提醒」才需去重表（V1.0.0 不要求，预警靠状态/列表呈现即可）。
- **EXPIRING_SOON 不回切 CERTIFIED**：扫描只单向收紧（CERTIFIED→EXPIRING_SOON→EXPIRED）；若 SIPDH 续期（2.7 `renew`）才回 CERTIFIED——**扫描器不负责回切**，避免与续期逻辑打架。
- **边界判定按日（date）**：`sipdh_expiry` 是 date，用 `LocalDate today`（UTC 当日或运营时区当日——统一口径，建议 UTC 日，标注）；「到期当日」= `expiry < today` 视为已过期（PRD「到期当日切已过期」可解读为到期日次日失效或当日失效，dev 取一并在 Completion 记录；推荐 `expiry < today` = 过了有效期末日才阻断，避免有效期最后一天误杀）。30 天：`today <= expiry <= today.plusDays(30)`。
- **clock 可注入便于测试**：`scanExpiry(LocalDate today)` 收 today 入参（scanner 传 `LocalDate.now(ZoneOffset.UTC)`），单测可固定边界日期。
- **预警不刷屏**：V1.0.0 用列表筛选 + 详情橙标 + dashboard 计数徽标即可，**不发 IM/邮件/推送**（PRD 无外部通知）。

### Flyway
- 本故事**无新迁移**（用 2.1 的 `sipdh_expiry`/`status`；状态机幂等不需去重表）。如产品后续要求重复预警去重表，从 **V38 之后**顺延并说明（V1.0.0 不做）。

### 范围边界（不做）
- 不做去重标记表（状态机幂等已足）、不做外部通知（IM/邮件/推送）、不做资质录入审核（→ 2.7）、不做列表筛选 UI（筛选项在 2.2 已建，本故事确保「即将到期/已过期」可被筛）、不做双语外化（→ 1.6）。
- 本故事**只**交付：`@Scheduled` 每日 SIPDH 扫描（到期→EXPIRED 停接单、≤30 天→EXPIRING_SOON 仍可接单）+ 后台预警呈现（列表/详情/徽标）。

### 测试标准
- L0：`VetQualificationServiceTest.scanExpiry`（固定 today：过期/≤30天/未到期/边界/幂等）；`SipdhExpiryScanner`（异常被 try/catch 吞、不崩）。
- L1（本地 docker）：造数据（不同 expiry）→ 跑扫描 → 验状态切换 + EXPIRED 不可接单（2.1 门控）+ EXPIRING_SOON 可接单 + 列表「即将到期」筛得到。云端只 L0。

### Project Structure Notes
- 新增 `admin/vetqual/service/SipdhExpiryScanner`；扩 `admin/vetqual/service/VetQualificationService`、`admin/vetqual/repository/VetQualificationRepository`、`admin/vetqual/domain/VetQualification`（markExpired/markExpiringSoon）。

### References
- [Source: admin-backend/epics.md#Story 2.8 SIPDH 到期自动阻断与 30 天预警]
- [Source: admin-backend/PRD.md#AB-2H（SIPDH 到期自动阻断 + 30 天预警 + 即将到期仍可接单）]
- [Source: petgo-backend/.../consult/service/ConsultCloseScanner.java（@Scheduled 范式）]
- [Source: petgo-backend/.../notify/schedule/ScheduledPushDispatcher.java + db/migration/V22__init_scheduled_push_dedup.sql（@Async + DB 去重范式参考）]
- [Source: 2-1-兽医资质数据模型与接单门控.md（sipdh_expiry / 6 态 / canTakeConsult）]
- [Memory: [[gemini-pro-vs-flash-thinkingbudget-incident]] 之外——本故事核心 [[hibernate6-char1-and-cloud-l0-gap]] 云端只 L0]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
