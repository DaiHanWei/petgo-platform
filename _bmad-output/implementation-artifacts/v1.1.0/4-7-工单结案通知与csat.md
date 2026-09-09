---
baseline_commit: 4ecc3e7
---
# Story 4.7: 工单结案通知与 CSAT

Status: review

> V1.1 **Epic 4 第 7 story**，**全栈**（客服工单结案闭环：admin 结案处理 + 结案/CSAT 通知 + 用户 CSAT 问卷 + 7 天自动关闭，L1/L2）。承接 4-1（`feedback_tickets` 表 + CSAT 预留列 V70 + `support` 模块 + 建单/查单原语）与 4-2（用户端投诉 UI）。**无 Flyway 迁移**（V70 已备 `csat_score`/`csat_comment`/`csat_deadline`/`resolved_at`/`contacted_customer`/`handled_by` 列）。
> 源：`epics-v1.1.md` Story 4.7（客服勾「已联系+已解决」结案→发结案通知 + 触发 CSAT 调查 1-5 分+评论 L1；前端问卷 L0/L2）· FR-52A/52B · 架构 `architecture-v1.1-delta.md` §3.6（CSAT 字段）· §5.3 定时任务⑦（工单已解决 7 天自动关闭）· 通知扩枚举 `TICKET_RESOLVED`/`CSAT_SURVEY`（V72 4-4 已加进 CHECK）。
> **⚠️ 承接现状（已就位，勿重建）**：`support` 模块（4-1）——`FeedbackTicket`（含 CSAT/status/resolved 全字段但**无流转方法**）+ `TicketStatus`(OPEN/IN_PROGRESS/RESOLVED/CLOSED) + `SupportTicketService`（建单/查单/加内部备注，**无结案/CSAT 方法**）+ `SupportTicketController`（用户建/查，`/api/v1/support-tickets/**` 已 USER 门控）+ `SupportTicketView`（已含 csatScore/csatComment/csatDeadline 字段）+ `FeedbackTicketRepository`（findByTicketToken/findByUserIdOrderByCreatedAtDesc）。**无 admin 工单控制器**（4-4 本应做工单处理却做了退款，故 admin 结案入口本 story 建）。`NotificationService.send`(REQUIRES_NEW) + `NotificationType.TICKET_RESOLVED`/`CSAT_SURVEY`（枚举 + V72 CHECK 已就位，本 story 首次**发**）。前端 `features/support/`（4-2）——`ticket_detail_page`/`my_tickets_page`/`support_repository`/`support_l10n`/`ticket_status_badge`（本 story 加 CSAT 问卷 + 详情展示结案态/CSAT 入口/结果）。
> **决策（本轮已与用户对齐）**：① **新增 `support.handle` 权限点**（AdminPermissions 加常量 + ALL 白名单，纯 Java 无迁移）——admin 结案端点门控 `hasRole('SUPER_ADMIN') or hasAuthority('support.handle')`（照 refund.*/consult.handle）。② **全栈完整**（admin 工单列表/详情/结案 Thymeleaf + 后端结案/CSAT/scanner + 用户 CSAT 端点 + 前端 CSAT 问卷 + L0/L1/L2）。
> **范围边界**：本 story = 结案（客服勾已联系+已解决）+ TICKET_RESOLVED/CSAT_SURVEY 通知 + CSAT 收集（1-5 分+评论）+ 7 天自动关闭 + 前端问卷。**不做**：`cs_rating`（AB-5G 后续版本，V70 预留不动）；工单接手 IN_PROGRESS 中间态（可选，本 story 结案直接 OPEN→RESOLVED，dev 定是否经 IN_PROGRESS）；举报处理回告（4-8）。**无迁移**。

## Story

As a 用户,
I want 工单结案时收到通知并能给客服打分评价,
so that 我知道处理结果、平台能收集满意度（FR-52A/52B）。

## Acceptance Criteria

1. **后端：admin 结案处理（客服勾「已联系+已解决」）+ support.handle 权限（L1）**
   **Given** 工单 `status=OPEN`（或 IN_PROGRESS）
   **When** 客服结案 `POST /admin/support-tickets/{ticketToken}/resolve`（`support.handle` 权限，Thymeleaf redirect+flash）
   **Then** `contacted_customer=true` + `status=RESOLVED` + `resolved_at`(now) + `handled_by`(adminId) + `csat_deadline`(resolved_at + 7d) + 审计 `TICKET_RESOLVED`（**L1**）
   **And** `AdminPermissions` 加 `SUPPORT_HANDLE`(`support.handle`)（ALL 白名单）；无权限→403；已 RESOLVED/CLOSED 重复结案→幂等拒（409 或跳过，dev 定）（**L1**）

2. **后端：结案 + CSAT 通知（AB-5B 承接 send 范式）（L1）**
   **Given** 工单结案
   **When** resolve 编排
   **Then** 发 `TICKET_RESOLVED` 通知（deep link 工单详情，`targetRef=ticketToken`，非随机 token）+ 发 `CSAT_SURVEY` 通知（deep link CSAT 问卷，`targetRef=ticketToken`）给 `ticket.user_id`；均走 `NotificationService.send`（REQUIRES_NEW）；文案本地化 + **无 PII**（**L1**）
   **And** 通知落库给工单发起人；批准不影响（本 story 只发这两类，`REFUND_REJECTED` 等不涉）（**L1**）

3. **后端：用户提交 CSAT（1-5 分 + 评论）（L1）**
   **Given** 工单 `status=RESOLVED`、未评价、未过期
   **When** 用户 `POST /api/v1/support-tickets/{ticketToken}/csat`（JWT role=USER，仅 owner；body `score`(1-5)/`comment`(≤100 可空)）
   **Then** `csat_score`/`csat_comment` 落库 + `status=CLOSED`（评价即闭环）（**L1**）
   **And** 非 owner→404（照既有 viewForUser owner 校验）；非 RESOLVED 态（OPEN/已 CLOSED/已评）→409；`score∉[1,5]`→400 Bean 校验（**L1**）

4. **后端：7 天自动关闭 scanner（§5.3 ⑦）（L1）**
   **Given** 工单 `status=RESOLVED` 且 `csat_deadline` 已过（用户未评价）
   **When** `@Scheduled` 扫描（DB 状态机驱动，禁 MQ/延迟队列，enforcement 护栏）
   **Then** `status=CLOSED`（无 CSAT，静默关闭）；幂等（仅 RESOLVED 可转，重扫不重复）（**L1**）

5. **前端：CSAT 问卷 + 工单详情结案态（`features/support/`）（L0/L2）**
   **Given** 用户从通知深链 / 工单详情进入已结案工单
   **When** 展示
   **Then** 工单详情（4-2）显示 `RESOLVED` 态 + 「评价服务」CTA（未评价时）→ CSAT 问卷屏（**1-5 星 + 评论 ≤100 可空** + 提交，调 AC3）；已评价显示 CSAT 结果（分数+评论只读）；`CLOSED` 无 CTA；`ticket_status_badge` 覆盖 RESOLVED/CLOSED（**L0/L2**）
   **And** 提交成功回详情刷新（状态→CLOSED）；深链类型 `CSAT_SURVEY`/`TICKET_RESOLVED` 路由到工单详情（问卷经详情 CTA 进）；无死链/死控件（**L0/L2**）

6. **后端：admin 工单管理 UI（列表 + 详情 + 结案）（L1）**
   **Given** 客服需在后台处理工单
   **When** `GET /admin/support-tickets`（列表）/ `GET /admin/support-tickets/{token}`（详情）
   **Then** Thymeleaf SSR：列表（工单 + 状态 + 联系方式）+ 详情（正文/附件数/内部备注[4-1 addInternalNote 可加]/结案按钮）；`support.handle` 门控；结案按钮 POST resolve；layout 侧栏加「客服工单」入口（**L1**）
   **And** 无 `support.handle`→403（**L1**）

7. **契约 + 静态/集成/端到端验收（L0/L1/L2）**
   **Given** 全部实现
   **When** 验证
   **Then** `DB_NAME=petgo_l1 mvn -B test` 绿：L0（DTO 契约、路由、门控）+ L1（结案→RESOLVED+resolved_at+csat_deadline+2 通知落库；CSAT 提交→CLOSED+分数落库；非法态 409/400；scanner 自动关闭；support.handle 门控 403）+ `flutter analyze` 零警告 + `flutter test` 绿（**L0/L1**）
   **And** 不破坏 4-1 建单/查单/内部备注 / 4-2 用户投诉 UI / 通知既有类型（回归绿）；模拟器：通知→详情→CSAT 问卷提交（**L2 留本地**）

## Tasks / Subtasks

> 全栈，后端 → 前端 → 联调。**先读**：`support/domain/FeedbackTicket.java`（加流转方法）、`support/domain/TicketStatus.java`、`support/service/SupportTicketService.java`（加 resolve/submitCsat）、`support/repository/FeedbackTicketRepository.java`（加 scanner 查询）、`support/web/SupportTicketController.java`（加 CSAT 端点）、`support/dto/SupportTicketView.java`（已含 CSAT 字段，确认暴露）、`notify/service/NotificationService.java`（send REQUIRES_NEW）+`notify/domain/NotificationType.java`（TICKET_RESOLVED/CSAT_SURVEY）、`admin/account/domain/AdminPermissions.java`（加 SUPPORT_HANDLE）、`admin/refund/web/AdminRefundController.java`+`templates/admin/refund-detail.html`（admin 列表/详情/结案范式，4-6 刚建）、`consult/service/ConsultRequestTimeoutScanner.java`（@Scheduled scanner 范式）、`admin/audit/service/AuditActions.java`。前端读：`features/support/`（4-2 全模块）、`core/network/api_paths.dart`、`core/router/app_router.dart`、`features/refund/`（4-5 问卷/表单范式）。

- [x] **T1 后端：FeedbackTicket 流转方法 + support.handle 权限**（AC1）
  - [x] `FeedbackTicket` 加 `markResolved(handledBy, csatDeadline)`/`submitCsat(score, comment)`/`autoClose()`；`AdminPermissions` 加 `SUPPORT_HANDLE`("support.handle") + ALL 白名单。**无迁移**。**L0/L1**
- [x] **T2 后端：SupportTicketService 结案/CSAT + 通知**（AC1/AC2/AC3）
  - [x] `resolveTicket(ticketToken, adminId)`：仅 OPEN/IN_PROGRESS（重复 409）；`markResolved(adminId, now+csatWindowDays)` + 发 `TICKET_RESOLVED` + `CSAT_SURVEY` 通知（targetRef=ticketToken，REQUIRES_NEW）+ 审计 `TICKET_RESOLVED`。注入 NotificationService/AdminAuditService + `@Value csat-window-days:7`。**L1**
  - [x] `submitCsat(userId, ticketToken, score, comment)`：owner(非本人 404) + 仅 RESOLVED（否则 409）+ `submitCsat`→CLOSED。**L1**
- [x] **T3 后端：7 天自动关闭 scanner**（AC4）
  - [x] `FeedbackTicketRepository` 加 `findByStatusAndCsatDeadlineBefore`；`SupportTicketCloseScanner`（`@Scheduled` fixedDelay 属性化 `close-scan-ms:300000`，仅 RESOLVED→CLOSED，幂等）。照 `ConsultRequestTimeoutScanner`。**L1**
- [x] **T4 后端：用户 CSAT 端点 + admin 工单控制器/UI**（AC1/AC3/AC6）
  - [x] `SupportTicketController` 加 `POST /api/v1/support-tickets/{token}/csat`（`SubmitCsatRequest` @Valid score 1-5/comment ≤100）；`admin/support/web/AdminSupportTicketController`（GET 列表/详情 + POST /{token}/resolve，`@PreAuthorize support.handle`，redirect+flash）+ `AdminSupportTicketQueryService`+`AdminTicketView`；`templates/admin/support-tickets.html`+`support-ticket-detail.html` + layout 侧栏 + i18n nav 键。**L1**
- [x] **T5 前端：CSAT 问卷 + 详情结案态**（AC5）
  - [x] `features/support/`：`csat_page`（1-5 星 + 评论 + 提交，调 submitCsat + invalidate 详情/列表）；`ticket_detail_page` 加 CSAT 区块（RESOLVED 未评→「评价服务」CTA 进问卷；已评→只读分数+评论）；`support_repository.submitCsat` + `api_paths.supportTicketCsat` + 路由 `/me/support-tickets/:token/csat`；`ticket_status_badge` 已覆盖 RESOLVED/CLOSED；arb EN/ID 各 +8 键。**L0/L2**
- [x] **T6 测试**（AC7）
  - [x] L1 `SupportTicketResolveCsatIntegrationTest`（scratch，6 用例）：结案→RESOLVED+contacted+resolved_at+csat_deadline+handled_by+TICKET_RESOLVED/CSAT_SURVEY 通知落库；重复结案 409；CSAT 提交→CLOSED+分数+评论；非 owner 404；非 RESOLVED 409；scanner 死线过期→CLOSED（无 CSAT）。+ L0 `AdminSupportAccessControlTest`（3 用例：support.handle / SUPER_ADMIN / 无权 403）。**L0/L1**
  - [x] `DB_NAME=petgo_l1 mvn -B test` 绿（1140 tests，唯一失败为已知 baseline flake `NotificationControllerEndpointTest`）；`flutter analyze` 零警告 + `flutter test` 419 pass（唯一失败为已知 baseline flake `story_1_5_gating AC2`）。L2 模拟器留本地。**L0/L1/L2**

## Dev Notes

> 全栈，在 4-1 support 模型 + 4-2 前端上加**结案闭环**（结案 + 通知 + CSAT + 自动关闭）。**无迁移**（V70 列已备）。

### 承接现状（已核实，照此复用/勿改）

- **工单态流**：`OPEN --(客服结案 markResolved)--> RESOLVED --(用户 CSAT submitCsat / 或 7 天 scanner autoClose)--> CLOSED`。V70 列全备（csat_score/comment/deadline/resolved_at/contacted_customer/handled_by）。**无迁移**——加列已在 4-1 预埋（避二次迁移）。
- **通知（notify）**：`NotificationType.TICKET_RESOLVED`/`CSAT_SURVEY` 枚举 + V72 CHECK 已就位（4-4 加），本 story 首次**发**。`NotificationService.send(userId, type, title, body, deepLinkType, targetRef)`（REQUIRES_NEW，[[notify-after-commit-tx-swallow-bug]]）。`targetRef=ticketToken`（非随机，App 据此定位工单详情）；`deepLinkType=TICKET_RESOLVED`/`CSAT_SURVEY`。文案本地化占位 + 无 PII。
- **权限（AdminPermissions）**：加 `SUPPORT_HANDLE="support.handle"` + ALL 白名单（STAFF 可勾选授予）；SUPER_ADMIN 隐式全权。纯 Java 常量，**无迁移**（权限码不落表，是 authority 字符串）。
- **admin UI**：Thymeleaf slice（`@Controller` + redirect + flash + `@PreAuthorize` + `AdminUserDetails.getAdminAccountId()`）。照 4-6 `AdminRefundController` + `refunds.html`/`refund-detail.html`（body 中文字面量，nav 走 `#{admin.nav.*}` i18n；债与 4-6 一致）。
- **scanner**：`@Scheduled(fixedDelayString="${...}")` + DB 状态机（**禁 MQ/延迟队列**，enforcement）。照 `ConsultRequestTimeoutScanner`。仅 `RESOLVED` 且 `csat_deadline < now` → `CLOSED`（幂等：状态守卫）。
- **用户端点**：`/api/v1/support-tickets/**` 已 USER 门控（SecurityConfig，4-1）——CSAT 端点落此前缀**无需改 SecurityConfig**。owner 校验照 `viewForUser`（非本人 404）。
- **前端 support（4-2）**：`ticket_detail_page` 已展示工单；本 story 加结案态 + CSAT CTA/结果。`support_repository` 加 submitCsat。深链路由：`CSAT_SURVEY`/`TICKET_RESOLVED` → 工单详情（问卷经详情 CTA 进，非独立深链目标，避免多入口）。

### 关键设计决策（dev 照此）

- **D-1 结案 = 客服单动作「已联系+已解决」**：一个 resolve 端点置 `contacted_customer=true` + `RESOLVED` + `resolved_at` + `handled_by` + `csat_deadline`(+7d)。是否经 IN_PROGRESS 中间态：本 story 简化直接 OPEN→RESOLVED（IN_PROGRESS 可选，dev 定；不阻塞）。
- **D-2 结案发两类通知**：`TICKET_RESOLVED`（告知处理结果，深链工单详情）+ `CSAT_SURVEY`（邀请评价，深链工单详情→问卷）。均 REQUIRES_NEW + targetRef=ticketToken。
- **D-3 CSAT 提交即闭环 CLOSED**：用户评分 → csat 落库 + `CLOSED`。未评则 7 天 scanner 静默 `CLOSED`（无 csat）。**一工单一次 CSAT**（RESOLVED 未评才可提，已 CLOSED/已评 409）。
- **D-4 support.handle 权限**：新增权限点（用户拍板），STAFF 客服可授予；与 refund.*/consult.handle 同构。
- **D-5 CSAT 死线可配**：`csat_deadline = resolved_at + ${petgo.support.csat-window-days:7}d`。scanner 扫描间隔属性化。
- **D-6 无迁移**：V70 列已备；权限码是 Java 常量。**不加列**。
- **D-7 CSAT 分数校验**：`score` 1-5 Bean 校验（@Min/@Max）+ DB CHECK（V70 `ck_feedback_tickets_csat_score` 已在）双闸；comment ≤100（@Size + V70 VARCHAR(100)）。

### Project Structure Notes

- **后端新增**：`support/dto/SubmitCsatRequest.java`；`support/service/SupportTicketCloseScanner.java`；`admin/support/web/AdminSupportTicketController.java` + `admin/support/service/AdminSupportTicketQueryService.java` + `dto`；`templates/admin/support-tickets.html`+`support-ticket-detail.html`；测试 `SupportTicketResolveCsatIntegrationTest`(L1)+`AdminSupportAccessControlTest`(L0)。
- **后端修改**：`support/domain/FeedbackTicket.java`(+3 方法)；`support/service/SupportTicketService.java`(+resolveTicket/submitCsat，注入 `NotificationService`/`AdminAuditService`)；`support/repository/FeedbackTicketRepository.java`(+scanner 查询)；`support/web/SupportTicketController.java`(+csat 端点)；`admin/account/domain/AdminPermissions.java`(+SUPPORT_HANDLE)；`admin/audit/service/AuditActions.java`(+TICKET_RESOLVED)；`templates/admin/layout.html`(侧栏)；`i18n/messages_*.properties`(nav)。
- **不改**：4-1 建单/查单内核 / 4-2 用户投诉 UI 主体 / 通知既有类型 / SecurityConfig（support-tickets 已 USER 门控，/admin/** 已门控）/ **无迁移**。
- **前端新增**：`features/support/presentation/csat_page.dart`；`api_paths` +csat；`app_router` +路由。
- **前端修改**：`ticket_detail_page.dart`(结案态+CTA/结果)；`support_repository.dart`(+submitCsat)；`ticket_status_badge.dart`(RESOLVED/CLOSED)；`support_l10n.dart`/arb(EN/ID)。

### References

- [Source: epics-v1.1.md#Story 4.7]（客服勾已联系+已解决结案→结案通知+CSAT 1-5 分+评论；前端问卷）· [#FR-52A/52B]
- [Source: architecture-v1.1-delta.md#§3.6 CSAT 字段] · [#§5.3 定时任务⑦ 工单已解决 7 天自动关闭] · [#§6 API /support-tickets/{id}/csat POST user] · [#TICKET_RESOLVED/CSAT_SURVEY 通知（extend_notification_types_v11）]
- 代码范式：`support/`(4-1 全模块)、`notify/service/NotificationService.java`(send REQUIRES_NEW)、`admin/refund/web/AdminRefundController.java`+`templates/admin/refund-detail.html`(4-6 admin 列表/详情/动作)、`consult/service/ConsultRequestTimeoutScanner.java`(@Scheduled scanner)、`admin/account/domain/AdminPermissions.java`(权限点)、`features/support/`(4-2 前端)、`features/refund/presentation/`(4-5 问卷/表单)。相关内存：[[notify-after-commit-tx-swallow-bug]]（通知 REQUIRES_NEW + targetRef 非随机）、[[shared-dev-db-and-parallel-flyway-collision]]（无迁移仍 flush Redis）、[[v1.1-epic3-implemented]]（4-1 support 模块 + 陷阱：附件返 objectKey / test 包名撞车 support 落子包）、[[petgo-i18n-model-and-debt]]（App 按 code 本地化）。

### 待澄清（不阻塞）

- **[OPEN-1] IN_PROGRESS 中间态**：结案是否先经「接手 IN_PROGRESS」再「结案 RESOLVED」，还是 OPEN 直接 RESOLVED。建议本 story 直接 OPEN→RESOLVED（单动作「已联系+已解决」），IN_PROGRESS 留后续（不阻塞）。dev 定。
- **[OPEN-2] CSAT 深链目标**：`CSAT_SURVEY` 深链跳工单详情（经 CTA 进问卷）还是直跳问卷屏。建议跳详情（单一入口，避免绕过详情上下文）。dev 与产品定；默认详情。
- **[OPEN-3] need_contact_customer=false 的工单**：若用户建单未要求联系，结案是否仍发 CSAT。建议仍发（评价与是否联系正交）。dev 定；默认发。
- **[OPEN-4] 已 CLOSED（scanner 自动）后能否补评**：建议不能（CLOSED 即终态，CSAT 仅 RESOLVED 窗口内）。dev 定；默认 CLOSED 后不可评。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（Claude Code / bmad-create-story）

### Debug Log References

- `DB_NAME=petgo_l1 mvn -B test`：1140 tests, 1 failure（唯一失败=已知 baseline flake `NotificationControllerEndpointTest.list_returnsOnlyOwnNotifications`）。**无迁移**；Redis DB0 已 flush。
- `flutter analyze`：No issues found。`flutter test`：419 pass, 1 failure（已知 baseline flake `story_1_5_gating AC2`）。microcopy 契约（EN/ID 对齐 + ≤1 emoji）通过。

### Completion Notes List

- **全栈，工单结案闭环，全 7 AC 落地，无 Flyway 迁移**（V70 列已备；`support.handle` 是 Java 权限常量不落表）。
- **后端**：`FeedbackTicket` +`markResolved`/`submitCsat`/`autoClose` 流转方法。`SupportTicketService` +`resolveTicket`（OPEN/IN_PROGRESS→RESOLVED + contacted=true + csat_deadline=now+7d + 发 TICKET_RESOLVED/CSAT_SURVEY 通知 REQUIRES_NEW targetRef=ticketToken + 审计）/`submitCsat`（owner 404 + 仅 RESOLVED 409 + →CLOSED），注入 NotificationService/AdminAuditService + `@Value csat-window-days:7`。`SupportTicketCloseScanner`（@Scheduled 属性化，RESOLVED 死线过期→CLOSED 静默，幂等）。用户 `POST /support-tickets/{token}/csat`（SubmitCsatRequest @Min1@Max5+@Size100）。
- **权限**：`AdminPermissions` +`SUPPORT_HANDLE`("support.handle") + ALL 白名单（STAFF 可授予，SUPER_ADMIN 隐式）。
- **admin UI**：`AdminSupportTicketController`（GET 列表/详情 + POST resolve，support.handle 门控）+ `AdminSupportTicketQueryService`+`AdminTicketView`（admin 见联系方式/正文以处理）；`support-tickets.html`/`support-ticket-detail.html`（结案按钮仅 OPEN/IN_PROGRESS 显）；layout 侧栏「客服工单」+ i18n nav 键。
- **前端**：`features/support/` +`csat_page`（1-5 星 + 评论 ≤100 + 提交→invalidate 详情/列表→pop）；`ticket_detail_page` 加 CSAT 区块（RESOLVED 未评→CTA 进问卷；已评→只读展示）；`support_repository.submitCsat` + api_paths + 路由 `/me/support-tickets/:token/csat`；arb EN/ID +8 键（无 emoji/感叹号）。
- **通知深链**：`TICKET_RESOLVED`/`CSAT_SURVEY` 均 targetRef=ticketToken（非随机）；App 深链导向工单详情，问卷经详情 CTA 进（单一入口，OPEN-2）。
- **[OPEN] 决议**：OPEN-1=结案直接 OPEN→RESOLVED（单动作「已联系+已解决」，不经 IN_PROGRESS）；OPEN-2=CSAT 深链跳工单详情（经 CTA 进问卷）；OPEN-3=need_contact_customer=false 仍发 CSAT（评价与联系正交）；OPEN-4=CLOSED 后不可补评（CSAT 仅 RESOLVED 窗口）。
- **未破坏**：4-1 建单/查单/内部备注（SupportTicketService 构造器加参，既有单测 Spring 注入无碍）/ 4-2 用户投诉 UI / 通知既有类型 / SecurityConfig（support-tickets 已 USER 门控）全回归绿。
- **技术债/待本地**：① admin 工单两页 body 中文字面量（未走 i18n bundle，仅 nav 走；与 4-6 一致债）。② L2 模拟器：通知→工单详情→CSAT 问卷提交端到端留本地。

### File List

**后端新增**
- `petgo-backend/src/main/java/com/tailtopia/support/dto/SubmitCsatRequest.java`
- `petgo-backend/src/main/java/com/tailtopia/support/service/SupportTicketCloseScanner.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/support/dto/AdminTicketView.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/support/service/AdminSupportTicketQueryService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/support/web/AdminSupportTicketController.java`
- `petgo-backend/src/main/resources/templates/admin/support-tickets.html`
- `petgo-backend/src/main/resources/templates/admin/support-ticket-detail.html`
- `petgo-backend/src/test/java/com/tailtopia/support/SupportTicketResolveCsatIntegrationTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/support/web/AdminSupportAccessControlTest.java`

**后端修改**
- `petgo-backend/src/main/java/com/tailtopia/support/domain/FeedbackTicket.java`（+3 流转方法）
- `petgo-backend/src/main/java/com/tailtopia/support/service/SupportTicketService.java`（+resolveTicket/submitCsat + 注入 NotificationService/AdminAuditService + csatWindowDays）
- `petgo-backend/src/main/java/com/tailtopia/support/repository/FeedbackTicketRepository.java`（+findAllByOrderByCreatedAtDesc/findByStatusAndCsatDeadlineBefore）
- `petgo-backend/src/main/java/com/tailtopia/support/web/SupportTicketController.java`（+csat 端点）
- `petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java`（+SUPPORT_HANDLE）
- `petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java`（+TICKET_RESOLVED）
- `petgo-backend/src/main/resources/templates/admin/layout.html`（侧栏客服工单）
- `petgo-backend/src/main/resources/i18n/messages_zh_CN.properties` + `messages_en.properties`（+admin.nav.supportTickets/group.support）

**前端新增**
- `petgo_app/lib/features/support/presentation/csat_page.dart`

**前端修改**
- `petgo_app/lib/features/support/data/support_repository.dart`（+submitCsat）
- `petgo_app/lib/features/support/presentation/ticket_detail_page.dart`（+CSAT 区块）
- `petgo_app/lib/core/network/api_paths.dart`（+supportTicketCsat）
- `petgo_app/lib/core/router/app_router.dart`（+csat 路由 + import）
- `petgo_app/lib/l10n/app_en.arb` + `app_id.arb`（+8 键）+ 生成产物

### Change Log

- 2026-07-14：dev-story 实现完成 Story 4.7（全栈）。**无迁移**。后端 FeedbackTicket 流转方法 + SupportTicketService resolveTicket(结案+TICKET_RESOLVED/CSAT_SURVEY 通知)/submitCsat(→CLOSED) + SupportTicketCloseScanner(7 天自动关闭) + 用户 CSAT 端点 + AdminPermissions support.handle + admin 工单列表/详情/结案 Thymeleaf。前端 csat_page(1-5 星+评论) + 工单详情 CSAT 区块 + repo/路由/arb。L1 1140 tests + flutter analyze 零 + test 419 pass(均唯一 baseline flake)。4-1/4-2 回归绿。L2 留本地。Status → review。
- 2026-07-14：create-story 定稿 Story 4.7（工单结案通知与 CSAT，全栈）。**核心发现**：4-1 已备 CSAT 全列（V70）+ support 模块但**无结案/CSAT 流转**；4-4 本应做工单处理却做了退款→admin 工单结案入口本 story 首建；`TICKET_RESOLVED`/`CSAT_SURVEY` 枚举 V72 已加本 story 首发；**无迁移**。用户拍板：新增 `support.handle` 权限 + 全栈完整。方案：FeedbackTicket 流转方法 + SupportTicketService resolveTicket(结案+2 通知)/submitCsat(→CLOSED) + 7 天 @Scheduled 自动关闭 scanner + 用户 CSAT 端点 + admin 工单列表/详情/结案 Thymeleaf + 前端 CSAT 问卷/详情结案态。4 个 [OPEN]。Status → ready-for-dev。
