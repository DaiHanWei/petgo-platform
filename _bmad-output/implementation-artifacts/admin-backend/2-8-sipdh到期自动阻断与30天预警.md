---
baseline_commit: 2bc770495356fcedf949135653dd6974ac99f824
---

# Story 2.8: SIPDH 到期自动阻断与 30 天预警

Status: review

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

- [x] **T1 扫描器 + 状态切换（AC1/AC2/AC3）**
  - [x] `SipdhExpiryScanner`（`@Component @Scheduled(cron 可 env 配，默认每日 03:00 UTC)`，try/catch 吞异常 + 日志，仿 ConsultCloseScanner）
  - [x] `VetQualificationService.scanExpiry(LocalDate today)`：查 CERTIFIED/EXPIRING_SOON 且有 sipdh_expiry → `expiry<today`→EXPIRED；`today<=expiry<=today+30` 且 CERTIFIED→EXPIRING_SOON；状态机幂等；返回 ScanResult(expired,warned)
  - [x] `VetQualificationRepository.findByStatusInAndSipdhExpiryNotNull` + `countByStatus`
  - [x] 实体 `markExpired()`/`markExpiringSoon()`（单向收紧 + 幂等）
- [x] **T2 后台预警（AC1/AC4）**
  - [x] 列表（2.2）「即将到期/已过期」筛选 + 详情/列表橙红 badge（已有）；兽医页顶部到期计数预警 banner（`expiryStats` 即将到期/已过期数）。**不发 IM/邮件**（系统行为不写审计，经 SLF4J JSON 日志可观测）
- [x] **T3 回归（AC5）**
  - [x] `VetQualificationScanTest`（过期/≤30天/恰好30天/未到期保持/已预警幂等/已过期最后一天=today仅预警/EXPIRING_SOON 过期再 EXPIRED/scanner 吞异常）；L1 `SipdhExpiryScanIntegrationTest`（过期→EXPIRED 停接单、≤30 天→EXPIRING_SOON 仍可接单、远期保持）；全量回归 734

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

claude-opus-4-8[1m]

### Completion Notes List

- **到期判定按 UTC 日**：`scanExpiry(LocalDate today)`，scanner 传 `LocalDate.now(ZoneOffset.UTC)`。`expiry < today` 才阻断（=有效期最后一天 today 仍仅预警不阻断，避免最后一天误杀）；30 天预警 `today <= expiry <= today+30`（含恰好 30 天）。L0 边界用例覆盖。
- **状态机幂等去重，无去重表**：CERTIFIED→EXPIRING_SOON→EXPIRED 单向，扫描只查 CERTIFIED/EXPIRING_SOON；已 EXPIRED 不再处理、已 EXPIRING_SOON 不重复预警。**无新 Flyway、无 MQ/调度/缓存中间件（F5）**。续期回切 CERTIFIED 由 2.7 renew 负责，扫描器不回切。
- **门控零改动联动**：切 EXPIRED → 2.1 `canTakeConsult` 自动返 false（停接单）；EXPIRING_SOON 仍 true（仍可接单）。L1 已验。
- **系统行为不写审计**（无 admin 操作人）；经 SLF4J JSON 日志可观测（不含证件 PII）。预警呈现 = 列表筛选 + 详情/列表 badge + 兽医页顶部到期计数 banner（不发外部通知）。
- **验证**：L0 `VetQualificationScanTest`(4) + scanner 吞异常；L1 `SipdhExpiryScanIntegrationTest`(1)；全量回归 **734 tests, 0 failures, 0 errors, 6 skipped**。无新迁移。

### File List

**新增（main）**
- petgo-backend/src/main/java/com/tailtopia/admin/vetqual/service/SipdhExpiryScanner.java

**修改（main）**
- petgo-backend/src/main/java/com/tailtopia/admin/vetqual/domain/VetQualification.java（markExpired/markExpiringSoon）
- petgo-backend/src/main/java/com/tailtopia/admin/vetqual/repository/VetQualificationRepository.java（findByStatusInAndSipdhExpiryNotNull + countByStatus）
- petgo-backend/src/main/java/com/tailtopia/admin/vetqual/service/VetQualificationService.java（scanExpiry + expiryStats + records）
- petgo-backend/src/main/java/com/tailtopia/admin/service/AdminVetService.java（qualificationExpiryStats）
- petgo-backend/src/main/java/com/tailtopia/admin/web/AdminWebController.java（vets 页注入 expiryStats）
- petgo-backend/src/main/resources/templates/admin/vets.html（到期预警 banner）
- petgo-backend/src/main/resources/i18n/messages_zh_CN.properties / messages_en.properties（expiryWarn 键）

**新增（test）**
- petgo-backend/src/test/java/com/tailtopia/admin/vetqual/service/VetQualificationScanTest.java
- petgo-backend/src/test/java/com/tailtopia/admin/vetqual/SipdhExpiryScanIntegrationTest.java

### Change Log

- 2026-06-29：实现 Story 2.8 SIPDH 到期每日扫描（@Scheduled，到期→EXPIRED 停接单、≤30 天→EXPIRING_SOON 仍可接单，状态机幂等去重无中间件）+ 兽医页到期预警计数。系统行为不写审计。无新迁移。L0+L1 绿，全量回归 734。
