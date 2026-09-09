---
baseline_commit: 5a9ccb7
---
# Story 3.8: Konsultasi UI 定稿与封禁挂起逃生

Status: review

> V1.1 **Epic 3 收尾** story（第八个，**安全攸关 H-5**）。后端为主（封禁挂起 + 自动退款 + scanner）+ 前端逃生入口/文案定稿。承接 5.7（`interruptByVetBan` 免费会话即时中断）+ 3-4（付费订单 `consult_orders`）+ 3-7（订单完成/`markCompleted` 范式）+ 1-2（PawCoin `credit`/`ledger`）。本 story：兽医被封禁若有**进行中付费会话** → **挂起 15min**（不再像免费会话即时无痕中断）+ 用户端**逃生入口「立即结束/上报」** + `@Scheduled` 15min 或用户主动 → **强制结束 + 按支付方式退款 + 中断**（FR-43G/H-5，安全规则「只升不降不可绕过」）。
> 源：`epics-v1.1.md` Story 3.8（兽医「待封禁挂起」态 → 挂起 ≤15min 或会话超时 → 强制结束 + 按支付方式退款 + 封禁生效、用户端恢复「结束/上报」入口 L1；用户端移除「结束会话」按钮、文案定稿 L0；真机逃生路径 L2）· FR-43G（Konsultasi 文案定稿 + 移除结束会话按钮 + 封禁挂起 15min 逃生通道）· H-5（封禁挂起硬上限逃生，架构 §继承护栏）· 架构 `architecture-v1.1-delta.md`（§调度⑥ 封禁挂起 15min 强制结束 + 自动退款 line 213；退款方向铁律 PawCoin→PawCoin·QRIS→真钱 line 208；ledger `REFUND_OUT` line 11）。
> **⚠️ 现状（已核实，务必理解 brownfield）**：
> ① **移除「结束会话」按钮已完成**：`consult_conversation_page.dart:413` 明注「用户侧无『结束会话』入口——会话结束由兽医发起（5.6），用户只能离开/评分」。**FR-43G 的「移除结束会话按钮」已满足**，本 story 前端主体 = 逃生入口 + 文案定稿（非移除按钮）。
> ② **免费会话封禁流是即时无退款**（5.7）：`AdminVetService.setBanned` → `setStatus(BANNED)` + `presence.goOffline` + `ConsultInterruptService.interruptByVetBan`（所有 IN_PROGRESS/PENDING_CLOSE → INTERRUPTED，IM 发「重新匹配/结束，无退款」）。**付费会话不能走这条无退款路**——本 story 让 `interruptByVetBan` 区分：**有付费订单的会话 → 挂起（不即时中断）**；免费会话 → 保持即时中断不变。
> ③ **退款原语已具备**：`PawCoinWalletService.credit(userId, coins, PawCoinTxnType.REFUND, refType, refId, idempotencyKey)`（退回 PawCoin + `FLOAT_LIABILITY` CREDIT 分录 + 幂等）；`LedgerService.post(group, lines, idempotencyKey)` + `LedgerAccount.REFUND_OUT`（退款流出）；`ConsultOrderStatus.REFUNDING/REFUNDED` + `ConsultStageEvent.REFUND_REQUESTED/REFUND_COMPLETED`。**QRIS 实际打款走 Midtrans Iris = Epic 4 未建**（本 story QRIS 只记账 REFUNDING + REFUND_OUT 负债，实际打款留 Epic 4）。
> ④ **VetStatus 无挂起态**（仅 ACTIVE/BANNED）；**不新增 VetStatus 枚举**（避免动全局过滤器/迁移，用户已定选「挂起挂在会话级」）——兽医封禁即 BANNED（踢线），**挂起态挂在 session 级**（`suspend_deadline_at`）。
> **范围边界（用户已拍板）**：本 story = ① `consult_sessions` 加 `suspend_deadline_at`（V69 小迁移）；② 封禁分流（付费会话挂起、免费会话即时中断不变）；③ 强制结束 + 退款原语（**PawCoin 立即全额退**；**QRIS 记 REFUNDING + REFUND_OUT 负债、实际打款留 Epic 4**）；④ `@Scheduled` 15min 挂起超时扫描强制结束；⑤ 用户逃生入口（立即结束→强制结束+退款 / 上报）；⑥ 会话状态 DTO 暴露 `suspendDeadlineAt`；⑦ 前端挂起横幅 + 逃生 CTA + 文案定稿 + i18n。**不做**：QRIS 实际 Midtrans 打款执行（Epic 4）、用户主动退款两段审批流（Epic 4，本 story 是**系统自动退款**非用户申请）、SIPDH 到期触发挂起（本 story 只接封禁；SIPDH 到期挂起属 9-x/后续，机制可复用）、既有免费会话中断流改动、既有会话状态机 SessionStatus 加值（用 `suspend_deadline_at` 列不加 SUSPENDED 态）、兽医端 UI（封禁后兽医已踢线）。

## Story

As a 用户,
I want 会话文案定稿、被封兽医不再劫持我的付费会话（挂起 15 分钟内我可立即结束或上报、超时系统强制结束并退款）,
so that 会话安全、钱不被卷走（FR-43G/H-5）。

## Acceptance Criteria

1. **后端：`consult_sessions` 加挂起截止列（V69/顺延）（L0/L1）**
   **Given** 付费会话需挂起态
   **When** 建列
   **Then** `ALTER TABLE consult_sessions ADD COLUMN suspend_deadline_at TIMESTAMPTZ`（nullable，**非空=挂起中**，服务端权威 15min 截止）；`ConsultSession` 加字段 + `suspend(Instant deadline)`（置 `suspend_deadline_at`，**保持 IN_PROGRESS**——挂起不改状态机，IM 仍可用，用户在控制）；无 CHECK/无 SUSPENDED 枚举（用户已定）（**L0** 迁移/实体 / **L1** validate 一致）
   **And** Flyway **V69**（当前文件 max V68）——移动靶：merge 时按当时全局 max 顺延（决策 E2）；ALTER 加列不动旧迁移（**L0**）

2. **后端：封禁分流——付费会话挂起、免费会话即时中断不变（L1）**
   **Given** `AdminVetService.setBanned` 封禁兽医（既有 `interruptByVetBan`）
   **When** 兽医有活跃会话（IN_PROGRESS/PENDING_CLOSE）
   **Then** `interruptByVetBan` 逐会话分流：**有付费订单**（`consult_orders (user_id,vet_id,IN_PROGRESS)` 存在，3-7 `findFirstByUserIdAndVetIdAndStatus` 复用）→ **挂起**（`session.suspend(now+15min)` + IM 发挂起告知「兽医已被封禁，本次问诊将在 15 分钟内结束并全额退款，你可立即结束或上报」+ **不置 INTERRUPTED**）；**无付费订单**（免费直连流）→ 保持既有即时 `INTERRUPTED` + 无退款 IM（**不改**）（**L1**）
   **And** 兽医仍 `setStatus(BANNED)` + `goOffline`（踢线、拒接新单，封禁即生效）；挂起是 session 级、不新增 VetStatus 态（**L1**）

3. **后端：强制结束 + 按支付方式退款（PawCoin 立即全退 / QRIS 记账留 Epic 4）（L1）**
   **Given** 挂起会话到期 或 用户主动逃生结束
   **When** 强制结束
   **Then** 单一原语 `forceEndSuspended(session)`：**订单 CAS `IN_PROGRESS→REFUNDING`**（幂等单点，scanner 与用户逃生并发只一方生效）→ 按 `pay_channel` 退款：
   - **PawCoin** → `wallet.credit(userId, order.amount, REFUND, "CONSULT_ORDER", orderId, idempotencyKey)`（退回 PawCoin + `FLOAT_LIABILITY` CREDIT 分录，幂等）→ 订单 `REFUNDING→REFUNDED` + 追加 `REFUND_COMPLETED` 节点
   - **QRIS** → 记 `LedgerService.post`（`REFUND_OUT` DEBIT / `CASH_IN` 或对应科目 CREDIT，承认退款负债）+ 订单**留 `REFUNDING`** + 追加 `REFUND_REQUESTED` 节点（**实际 Midtrans Iris 打款留 Epic 4**，用户已定）
   → 会话 `INTERRUPTED`（`VET_BANNED`，终态不评分不存档，照 5.7）+ 发 `ConsultInterruptedEvent`（推送/历史）（**L1**）
   **And** 幂等：订单状态 CAS（`WHERE status=IN_PROGRESS`）保证退款恰一次；`wallet.credit`/`ledger.post` 幂等键去重；已 REFUNDING/REFUNDED 订单不重复退（**L1**）
   **And** 退款方向铁律（架构 line 208）：PawCoin 付→退 PawCoin；QRIS 付→退真钱（本 story 记账、Epic 4 打款）；**未交付全额退**（无溢价、无 bonus——bonus 只挂「系统故障未交付+转 PawCoin」单一分支 C-1，封禁挂起不属该分支）（**L1**）

4. **后端：15min 挂起超时扫描（@Scheduled，无 MQ）（L1）**
   **Given** `suspend_deadline_at`（服务端权威）
   **When** 到期仍挂起（未被用户提前结束）
   **Then** `@Scheduled`（照 `ConsultCloseScanner`/`ConsultRequestTimeoutScanner` 范式，**禁 MQ**）扫 `suspend_deadline_at < now AND status=IN_PROGRESS` → 逐条 `forceEndSuspended`；try/catch 不 crash；幂等可重扫（已 INTERRUPTED/已退款不重复）（**L1**）

5. **后端：用户逃生端点 + 会话状态暴露挂起（L1）**
   **Given** 挂起会话
   **When** 用户逃生
   **Then** ① `POST /api/v1/consultations/sessions/{sessionId}/escape`（JWT USER，owner 校验）→ `forceEndSuspended`（立即强制结束+退款，不等 15min）；非本人/非挂起态 → 409/404 防枚举；② 会话状态查询（用户 poll）DTO 加 `suspendDeadlineAt`（`ConsultSessionResponse` +字段，非空=挂起中，前端据此显逃生入口）（**L1**）
   **And** 「上报」= 记录封禁投诉（本 story 最简：复用既有举报/反馈入口或落一条投诉记录；完整客服工单 FR-52 属 Epic 4）——不阻塞主逃生（立即结束）路径（**L1**，上报可 [OPEN] 最简实现）

6. **前端：Konsultasi 挂起横幅 + 逃生入口 + 文案定稿（L0/L2）**
   **Given** 会话 poll 返回 `suspendDeadlineAt != null`
   **When** 用户在会话页
   **Then** `consult_conversation_page.dart` 加**挂起横幅**（醒目：兽医已被封禁、剩余 MM:SS 倒计时 + 全额退款说明，服务端权威 `suspendDeadlineAt`）+ **逃生 CTA「立即结束」**（→ `POST /escape` → 强制结束+退款 → 转终态/退出）+ **「上报」**（→ 投诉入口）；**确认「结束会话」按钮仍不存在**（既有，勿加回）；文案定稿（挂起/退款/逃生印尼语文案，`microcopy_rules_test` 守卫）（**L0** / 模拟器 **L2**）
   **And** 挂起态输入区可保留（用户在控制，不劫持）；到期/结束后照既有 `INTERRUPTED` 终态渲染（横幅→终态标签）；倒计时纯显示、跃迁靠 poll 服务端（照 3-5/3-6 范式）（**L0**）

7. **前端：repository + api_paths + i18n + model（L0）**
   **Given** 逃生
   **When** 接线
   **Then** `ConsultRepository` 加 `escapeSuspended(sessionId)`；`ApiPaths` 加 `consultationSessionEscape(id)`；会话 model 加 `suspendDeadlineAt`（解析 `DateTime.parse().toUtc()`，nullable）；`app_id.arb`+`app_en.arb` 加挂起/逃生/退款文案（印尼语主）→ `flutter gen-l10n`（**L0**）

8. **后端 + 前端：静态验收（L0）**
   **Given** 全部实现
   **When** 验证
   **Then** 后端 `mvn -B test` 绿：L0（V69 建列、退款金额/幂等 DTO）+ L1（封禁分流付费挂起·免费即时中断、PawCoin 全退+订单 REFUNDED、QRIS 记账+订单 REFUNDING、15min scanner 强制结束、用户 escape、幂等不双退、非本人 409）；schema `validate` 一致（**L0/L1**）
   **And** 前端 `flutter analyze` **零警告** + `flutter test` 全绿（挂起横幅/倒计时/逃生 CTA/终态过渡 widget 测试 + `microcopy_rules_test`）；真机双端逃生路径（封禁→挂起→用户立即结束/超时强制结束+退款到账）留本地（**L2**，云端 headless 只跑 L0）

## Tasks / Subtasks

> 后端 → 前端 → 联调。先 V69 迁移 + 实体挂起字段 → 封禁分流（付费挂起/免费不变）→ 强制结束+退款原语（PawCoin 全退/QRIS 记账）→ 15min scanner → 用户 escape 端点 + 会话 DTO 挂起字段 → 前端挂起横幅/逃生/文案 → i18n → analyze/test。**先读**：后端 `consult/service/{ConsultInterruptService,ConsultCloseService,ConsultBillingService}`(中断/收尾/append 范式 + 3-7 `completeBillingOrder`)、`consult/domain/{ConsultSession,ConsultOrder,ConsultOrderStatus,ConsultStageEvent,InterruptReason}`、`consult/repository/{ConsultOrderRepository,ConsultSessionRepository}`(3-7 `findFirstByUserIdAndVetIdAndStatus`)、`admin/service/AdminVetService`(setBanned 调用点)、`pay/service/{PawCoinWalletService,LedgerService}`(credit/post + `LedgerLine`/`LedgerAccount.REFUND_OUT`)、`consult/dto/ConsultSessionResponse`、`consult/web/ConsultSessionController`(用户 poll/端点范式)；前端 `features/consult/presentation/consult_conversation_page.dart`(会话页状态机/INTERRUPTED/leave/banner)、`features/consult/data/consult_repository.dart`、`core/network/api_paths.dart`、`features/consult/presentation/vet_timed_pay_page.dart`(倒计时范式)、`l10n/app_id.arb`（Dev Notes 已附锚点）。

- [x] **T1 后端：V69 迁移 + 实体挂起字段**（AC1）
  - [x] `db/migration/V69__add_consult_session_suspend.sql`（`ALTER ADD suspend_deadline_at TIMESTAMPTZ` + 部分索引 WHERE NOT NULL；注释挂起语义 + 移动靶顺延）。**L0** ✅ validate 绿
  - [x] `ConsultSession` +`suspendDeadlineAt` + `suspend(Instant)`（保持 IN_PROGRESS，仅 IN_PROGRESS 可挂起）+ getter；`interrupt` 终态清挂起锚。**L0** ✅
- [x] **T2 后端：封禁分流（付费挂起 / 免费即时中断不变）**（AC2）
  - [x] `ConsultInterruptService.interruptByVetBan`：`orders.findFirstByUserIdAndVetIdAndStatus(...,IN_PROGRESS)` 存在 → `suspend(now+15min)` + IM 挂起告知（不 interrupt/不发中断事件）；否则既有 `interrupt(VET_BANNED)` + IM + 事件（5.7 不变）。注入 `ConsultOrderRepository`；`SUSPEND_SECONDS=900`。`ConsultInterruptServiceTest` mock 同步。**L1** ✅
  - [x] 用户停用/SIPDH 挂起 → [OPEN-3] 后续（本 story 只接封禁，D-5 默认；机制 `forceEndSuspended` 可复用）。
- [x] **T3 后端：强制结束 + 退款原语**（AC3）
  - [x] `ConsultSuspensionService.forceEndSuspended(s)`（`@Transactional`）：status!=IN_PROGRESS 直返（幂等）→ 找订单 → **CAS `markRefunding` 返 1 才退款**（唯一闸防双退）→ 按渠道（PawCoin `wallet.credit(REFUND)`+`markRefunded`+`REFUND_COMPLETED`；QRIS 留 REFUNDING+`REFUND_REQUESTED`，**不记 ledger 避免与 Epic 4 实际打款双记**）→ `s.interrupt(VET_BANNED)`+save+`ConsultInterruptedEvent`。幂等键 `"consult-refund-"+orderId`。**L1** ✅
  - [x] `ConsultOrderRepository` +`markRefunding`（`WHERE status=IN_PROGRESS`）/`markRefunded`（`WHERE status=REFUNDING`）单列 CAS。**L0/L1** ✅
- [x] **T4 后端：15min 挂起超时扫描**（AC4）
  - [x] `ConsultSessionRepository.findByStatusAndSuspendDeadlineAtBefore(IN_PROGRESS, now)`；`ConsultSuspensionService.scanExpiredSuspensions()` 逐条 `forceEndSuspended`；`ConsultSuspensionScanner @Scheduled(fixedDelayString ":30000")` + try/catch。**L1** ✅
- [x] **T5 后端：用户逃生端点 + 会话 DTO 挂起字段**（AC5）
  - [x] `ConsultSessionController` +`POST /api/v1/consult-sessions/{id}/escape`（`/consult-sessions/**` 已 hasRole USER；`escapeByUser` owner 校验 + 仅挂起态，否则 404 防枚举）→ `forceEndSuspended`。**L1** ✅
  - [x] `ConsultSessionResponse` +`suspendDeadlineAt`（`of` 映射 `s.getSuspendDeadlineAt()`；`ConsultSessionContractTest` 契约同步）。用户 poll 自动带出。**L1** ✅
  - [x] 「上报」→ [OPEN-2] 最简/复用既有（完整工单 FR-52 属 Epic 4）；不阻塞主逃生。
- [x] **T6 前端：挂起横幅 + 逃生入口 + 文案定稿**（AC6）
  - [x] `consult_conversation_page.dart`：`suspendDeadlineAt != null && active` → `_suspendBanner`（封禁告知 + MM:SS 服务端权威倒计时[1s ticker 仅挂起启用] + 全额退款说明）+ 逃生 CTA「立即结束」（→ `_escape` → escapeSuspended → INTERRUPTED 终态 + Toast）+「上报」（`_report` 最简提示）；**确认无「结束会话」按钮**（既有，未加回）；文案定稿。**L0** ✅
- [x] **T7 前端：repository + api_paths + model + i18n**（AC7）
  - [x] `consult_repository.dart` +`escapeSuspended(id)`；`api_paths.dart` +`consultSessionEscape(id)`（`/consult-sessions/{id}/escape`）；`consult_session.dart` model +`suspendDeadlineAt`（`DateTime.parse().toUtc()` nullable）+`isSuspended`；`app_id.arb`+`app_en.arb` +挂起/逃生/退款文案（consultSuspendTitle/Body/EndNow/Report/Escaped/EscapeFailed/Reported，印尼语主）→ `gen-l10n`。**L0** ✅
- [x] **T8 测试 + 静态**（AC8）
  - [x] 后端 L0（V69 建列 validate、`ConsultSessionContractTest` 契约 +suspendDeadlineAt、退款 CAS）+ L1（`ConsultSuspensionIntegrationTest` 8 例：封禁付费→挂起非中断/免费→即时中断；PawCoin 全退+REFUNDED+余额恢复；QRIS 留 REFUNDING+余额不变；15min scanner 强制结束;用户 escape 提前结束;**幂等不双退**;非本人 404）；`DB_NAME=petgo_l1 mvn -B test` 全量 1081、唯一失败 `NotificationControllerEndpointTest.targetRef`（既有 baseline flake，notify 未碰）；consult 模块 196 绿。**L0/L1** ✅
  - [x] 前端 `test/consult/consult_suspend_test.dart`（挂起横幅+倒计时+逃生 CTA→escape+INTERRUPTED 终态+Toast，3 例）；`flutter analyze` **零警告** + `flutter test` 413 绿（唯一失败 `story_1_5_gating` 为 baseline flake）。**L0** ✅
- [ ] **T9 真机双端逃生路径（L2，留本地）**
  - [ ] 封禁兽医（后台）→ 用户会话挂起横幅 → 立即结束/等 15min 超时 → 强制结束 + PawCoin 退款到账验证。**⚠️ 需本地起本分支后端 + 后台封禁动作 + 用户端**（端点仅本分支）。**L2 待本地**

## Dev Notes

> 后端为主（安全攸关：挂起分流 + 自动退款 + scanner，幂等/事务是红线）+ 前端逃生入口/文案。**不重复造轮子**：退款用现成 `wallet.credit(REFUND)`/`ledger.post`；中断/IM 照 `ConsultInterruptService`；订单完成/append 照 3-7；@Scheduled 照 `ConsultCloseScanner`；倒计时照 3-5/3-6；会话页终态照既有 `consult_conversation_page`。**移除结束会话按钮已完成**（现状①），前端主体是逃生横幅。

### 承接现状（已核实，照此复用/勿改）

- **封禁流（5.7，本 story 改分流）**：`AdminVetService.setBanned(vetId,banned,actor)` → `setStatus(BANNED)`+`presence.goOffline`+`interruptService.interruptByVetBan(vetId)`+审计。`interruptByVetBan` 现把所有 IN_PROGRESS/PENDING_CLOSE → `interrupt(VET_BANNED)`（INTERRUPTED 终态、无退款、IM「重新匹配/结束」）。**本 story 让它分流付费会话 → 挂起**（不 INTERRUPTED）。`setBanned` 本身不改（仍 BANNED + goOffline + 调 interruptByVetBan）。
- **付费会话识别**：付费会话 = `consult_sessions(source=DIRECT, IN_PROGRESS)` + 有 `consult_orders(user_id,vet_id,IN_PROGRESS)`（3-4 支付建）。用 **3-7 既有** `ConsultOrderRepository.findFirstByUserIdAndVetIdAndStatus(userId,vetId,IN_PROGRESS)` 判定（存在=付费待完成）。免费会话无订单。
- **退款原语（1-2/pay，现成）**：`PawCoinWalletService.credit(userId, coins, PawCoinTxnType.REFUND, refType, refId, idempotencyKey)`——`coins>0` 退回 PawCoin + `insertIfAbsent` 建钱包 + `applyDelta(+coins)` + `ledger.post(FLOAT_LIABILITY CREDIT + 借方对冲)` + 幂等键短路。`LedgerService.post(entryGroup, List<LedgerLine>, idempotencyKey)` + `LedgerLine.debit/credit(LedgerAccount, amount, userId, refType, refId)` + `LedgerAccount.REFUND_OUT`。**订单金额 `order.getAmount()`（成交额=用户实付，PawCoin 1 koin=Rp1 同额退回）。**
- **订单/节点（3-1/3-7）**：`ConsultOrderStatus.REFUNDING/REFUNDED`；`ConsultStageEvent.REFUND_REQUESTED/REFUND_COMPLETED`；`ConsultBillingService.appendStageEvent(orderId, type, at, note)`（append-only）；3-7 `ConsultOrder.markCompleted` 范式（本 story 加 `markRefunding`/`markRefunded` 或 repo CAS）。
- **中断/事件（5.7）**：`ConsultSession.interrupt(InterruptReason.VET_BANNED)`（→ INTERRUPTED 终态、不评分不存档）；`ConsultInterruptedEvent(sessionId,userId,vetId,reason)`（推送/历史）；`TencentImClient.sendSystemMessage(convId, text)`（挂起/中断 IM 告知）。
- **@Scheduled（`ConsultCloseScanner`/`ConsultRequestTimeoutScanner`）**：`@Scheduled(fixedDelayString="${...ms:30000}")` → service 方法 → try/catch 不 crash。**禁 MQ**（架构护栏）。
- **用户会话 poll（`ConsultSessionController` + `ConsultSessionResponse`）**：用户会话页轮询会话状态（status/vetId/imConversationId/closedReason/interruptedReason）；本 story +`suspendDeadlineAt`。
- **前端会话页（`consult_conversation_page.dart`）**：状态机 `_status`（IN_PROGRESS/PENDING_CLOSE/INTERRUPTED/CLOSED）+ 轮询 + `_leave`（回 /triage）+ INTERRUPTED 终态标签 + `PopScope`。**无「结束会话」按钮**（现状①，勿加）。挂起横幅叠加在 IN_PROGRESS 之上。

### 关键设计决策（dev 照此，安全攸关勿走样）

- **D-1 挂起挂 session 级、不新增 VetStatus（用户已定）**：兽医封禁即 BANNED（踢线、拒接新单，封禁即生效）；「待封禁挂起」是**会话**的态（`suspend_deadline_at` 非空 + 仍 IN_PROGRESS），不加 `VetStatus.SUSPENDED`（不动 `BannedVetFilter`/全局）。会话保持 IN_PROGRESS（IM 可用、用户在控制、不劫持）。
- **D-2 分流铁律**：`interruptByVetBan` 仅对**有付费订单**的会话挂起；**免费会话保持既有即时 INTERRUPTED + 无退款**（不回归 5.7 行为）。判定用订单存在性（D-1 承接）。
- **D-3 退款幂等单点（安全红线）**：强制结束可由「15min scanner」与「用户逃生」并发触发——**用订单状态 CAS `IN_PROGRESS→REFUNDING` 作唯一闸**（`@Modifying UPDATE ... WHERE status=IN_PROGRESS` 返回行数，0 行=已被另一路处理 → 直接返回、不重复退）。`wallet.credit`/`ledger.post` 幂等键（`"consult-refund-"+orderId`）二次兜底。**绝不双退**。
- **D-4 按支付方式退款（用户已定，架构 line 208）**：PawCoin → `wallet.credit(order.amount, REFUND)` 立即全额退回 + 订单 `REFUNDED` + `REFUND_COMPLETED` 节点（用户即时到账）。QRIS → `ledger.post(REFUND_OUT DEBIT / 对应 CREDIT)` 记退款负债 + 订单**留 `REFUNDING`** + `REFUND_REQUESTED` 节点；**实际 Midtrans Iris 打款执行留 Epic 4**（未建）。**全额退、无溢价无 bonus**（bonus 只挂「系统故障未交付+转 PawCoin」单一分支 C-1，封禁挂起不属之）。
- **D-5 用户停用对称性**：架构 line 120「封禁/SIPDH 到期/用户停用遇进行中付费会话 → 挂起+退款」。本 story 主接**封禁**；`interruptByUser`（用户停用，`AdminUserService` 调）遇付费会话是否同挂起——**建议对齐**（同 `forceEndSuspended` 退款，但用户停用是用户侧问题、退款语义可能不同）。dev 与产品确认：最简可**本 story 只接封禁**，用户停用/SIPDH 留 [OPEN]（机制复用，接入点后续）。**默认：本 story 只封禁触发挂起**，其余标 [OPEN]。
- **D-6 会话状态机不加值**：不加 `SessionStatus.SUSPENDED`（避免状态机改动，安全规则层「只升不降不可绕过」——状态机是安全敏感）。挂起=IN_PROGRESS + `suspend_deadline_at` 非空。强制结束 → `INTERRUPTED`（既有终态，VET_BANNED）。
- **D-7 移除结束会话按钮=已完成**：现状① 用户页无该按钮。AC「移除」是核对不加回 + 文案定稿；前端主体是**加逃生横幅**。
- **Flyway V69**：ALTER 加列（nullable，additive 安全）。当前 max V68；移动靶顺延（决策 E2）。

### Project Structure Notes

- **后端新增**：`db/migration/V69__add_consult_session_suspend.sql`、`consult/service/ConsultSuspensionService.java`（`forceEndSuspended`/`scanExpiredSuspensions`）、`consult/service/ConsultSuspensionScanner.java`（@Scheduled）、测试 `ConsultSuspensionIntegrationTest`(L1) + L0。
- **后端修改**：`consult/domain/ConsultSession.java`（+`suspendDeadlineAt`/`suspend`）、`consult/service/ConsultInterruptService.java`（分流付费挂起；注入 `ConsultOrderRepository`/退款 service）、`consult/repository/{ConsultSessionRepository,ConsultOrderRepository}.java`（+挂起过期查询/退款 CAS）、`consult/dto/ConsultSessionResponse.java`（+`suspendDeadlineAt`）、`consult/web/ConsultSessionController.java`（+escape 端点）。
- **前端新增**：`test/consult/*`（挂起/逃生 widget 测试）。
- **前端修改**：`consult_conversation_page.dart`（挂起横幅+逃生 CTA）、`consult_repository.dart`（+escape）、`api_paths.dart`（+escape）、会话 model（+suspendDeadlineAt）、`l10n/app_id.arb`+`app_en.arb`。
- **不改**：免费会话中断流（5.7 `interrupt` 分支）、`AdminVetService.setBanned`（仍调 interruptByVetBan）、`SessionStatus` 枚举、`VetStatus` 枚举、`BannedVetFilter`、既有会话终态渲染、退款原语（`wallet.credit`/`ledger.post` 只调不改）。

### 前端复用范式（精确锚点）

- **倒计时**：`features/consult/presentation/vet_timed_pay_page.dart`（服务端权威 deadline MM:SS + 1s 显示 timer）——挂起横幅倒计时照此。
- **会话页状态/轮询/终态**：`features/consult/presentation/consult_conversation_page.dart`（`_status` 机 + `_poll` + INTERRUPTED 终态标签 + `_leave` + `PopScope`）。挂起横幅在 IN_PROGRESS 上叠加；结束后走既有 INTERRUPTED 渲染。
- **repository/model**：`features/consult/data/consult_repository.dart`（Dio + ApiPaths）；会话 model 手写 fromJson（照既有）；`DateTime.parse().toUtc()` nullable。
- **i18n**：印尼语主 + `microcopy_rules_test` 守卫；金额千分位 `formatIdr`（`features/triage/presentation/widgets/triage_paywall.dart`）。⚠️ 包名 `tailtopia`。
- **shared**：`app_toast.dart`（逃生结果提示）、`confirm_sheet.dart`（逃生二次确认，可选）。

### 后端复用范式（精确锚点）

- **退款**：`pay/service/PawCoinWalletService.java`（`credit` REFUND）、`pay/service/LedgerService.java`（`post`）+ `LedgerLine`/`LedgerAccount.REFUND_OUT`。
- **中断/IM/事件**：`consult/service/ConsultInterruptService.java`（`interrupt`+IM+event 范式）。
- **订单完成/append**：3-7 `consult/service/ConsultCloseService.completeBillingOrder`（订单定位+CAS 幂等范式）、`ConsultBillingService.appendStageEvent`。
- **@Scheduled**：`consult/service/ConsultCloseScanner.java`/`ConsultRequestTimeoutScanner.java`。
- **兽医端点/owner 校验**：`consult/web/ConsultSessionController.java`、3-5 `ConsultRequestService.statusOf`（owner 防枚举范式）。
- **L1 净库**：scratch 库 `petgo_l1`（`DROP/CREATE DATABASE`（`psql -U petgo -d petgo`）+ flush Redis DB0 + `DB_NAME=petgo_l1 mvn -B test`；记忆 `shared-dev-db-and-parallel-flyway-collision`）。⚠️ scratch 重建前先 flush Redis（否则 id 从 1 重启撞陈旧幂等键→假回归，退款幂等测试尤敏感）。

### References

- [Source: epics-v1.1.md#Story 3.8]（挂起 ≤15min 或超时 → 强制结束+按支付方式退款+封禁生效、用户端恢复结束/上报入口 L1；移除结束会话按钮+文案定稿 L0；真机逃生 L2）· [#FR-43G]· [#H-5]
- [Source: architecture-v1.1-delta.md#§调度⑥ line 213]（封禁挂起 15min 强制结束+自动退款）· [#line 208]（退款方向铁律 PawCoin→PawCoin·QRIS→真钱）· [#line 120]（封禁/SIPDH/停用遇付费会话→挂起+退款）· [#line 164 C-1]（未交付退款不转 PawCoin 溢价）· [#ledger REFUND_OUT line 11]
- [Source: 5.7 interruptByVetBan]（免费即时中断范式）· [Source: 3-4/3-7]（订单建/完成、pay_channel、快照）· [Source: 1-2]（PawCoin credit/ledger）
- 后端代码：`consult/service/{ConsultInterruptService,ConsultCloseService,ConsultBillingService}.java`、`consult/domain/{ConsultSession,ConsultOrder,ConsultStageEvent}.java`、`consult/repository/{ConsultSessionRepository,ConsultOrderRepository}.java`、`admin/service/AdminVetService.java`、`pay/service/{PawCoinWalletService,LedgerService}.java`、`consult/dto/ConsultSessionResponse.java`、`consult/web/ConsultSessionController.java`。
- 前端代码：`features/consult/presentation/{consult_conversation_page,vet_timed_pay_page}.dart`、`features/consult/data/consult_repository.dart`、`core/network/api_paths.dart`、`l10n/app_id.arb`。

### 待澄清（不阻塞）

- **[OPEN-1] QRIS 实际打款**：本 story QRIS 只记 REFUNDING + REFUND_OUT 负债；实际 Midtrans Iris 打款 + 订单 REFUNDED 留 Epic 4（用户已定）。Epic 4 财务打款接入后消费本 story 记的 REFUNDING 订单。
- **[OPEN-2] 「上报」完整工单**：本 story 上报最简（占位/复用既有举报）；完整客服工单（FR-52）属 Epic 4。主逃生路径（立即结束+退款）不依赖上报。
- **[OPEN-3] 用户停用 / SIPDH 到期挂起**：本 story 只接**封禁**触发挂起（D-5）；用户停用（`interruptByUser`）/ SIPDH 到期遇付费会话的挂起+退款留后续（机制 `forceEndSuspended` 可复用，接入点后加）。
- **[OPEN-4] 封禁生效时点**：本 story 兽医封禁即 BANNED（D-1），挂起是会话级；若产品坚持「封禁 15min 后才生效」需 pending-ban 兽医态（用户已否，选会话级挂起）。
- **[OPEN-5] 挂起期间兽医误可见**：兽医已 goOffline + BANNED（BannedVetFilter 401），不能操作挂起会话；无需额外处理。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（Claude Code / bmad-create-story）

### Debug Log References

- 后端 L1 scratch 库 `petgo_l1`（`DROP/CREATE DATABASE` `psql -U petgo -d petgo`）——**每次重建前先 `redis-cli FLUSHDB`**（记忆 `shared-dev-db-and-parallel-flyway-collision` 坑 3：退款幂等键 `consult-refund-{orderId}` 若撞陈旧 Redis 键会短路跳过退款→余额不涨→假回归）。V69 迁移 validate 绿。
- `ConsultSuspensionIntegrationTest.clean` 首用 `deleteAll()` 撞 `ObjectOptimisticLockingFailure`（`ConsultSession` 有 `@Version`，`deleteAll` 逐行版本校验）→ 改 `deleteAllInBatch()`（单条 DELETE 绕过乐观锁）。
- 构造器变更同步 mock：`ConsultInterruptServiceTest`（+orders mock，未 stub→Optional.empty→免费流分支，既有断言不变）、`ConsultSessionContractTest`（+suspendDeadlineAt 契约字段：full 含、waiting/six-states 传 null→NON_NULL 省略）。
- 全量后端 1081 跑，唯一失败 `NotificationControllerEndpointTest.targetRef`（既有 baseline flake，3-7 已证孤立跑亦失败、notify 模块本 story 未碰）→ 非本次回归。consult 模块 196 绿。
- 前端 `flutter test` 413 绿，唯一失败 `story_1_5_gating`（3-6 已 git stash 证 baseline flake）。会话页有 poll(5s)+挂起 ticker(1s) → 测试用 `pump()` 非 `pumpAndSettle`；toast 静态计时器断言后 `pump(3s)` 排空。⚠️ 包名 `tailtopia`。

### Completion Notes List

- ✅ **AC1 V69 迁移 + 挂起字段**：`ALTER consult_sessions ADD suspend_deadline_at TIMESTAMPTZ`（+部分索引 WHERE NOT NULL）；`ConsultSession.suspend(deadline)`（仅 IN_PROGRESS，保持状态机不动）+ `interrupt` 终态清挂起锚。不加 SessionStatus/VetStatus 枚举（D-1/D-6，安全规则层不动状态机）。
- ✅ **AC2 封禁分流（D-2）**：`interruptByVetBan` 逐会话——有 IN_PROGRESS 订单（付费）→ `suspend(+15min)` + IM 告知全额退款/逃生、**不即时中断/不发中断事件**；无订单（免费）→ 既有即时 INTERRUPTED + 无退款 IM（5.7 不变）。`SUSPEND_SECONDS=900`。
- ✅ **AC3 强制结束+退款（安全红线，D-3/D-4）**：`ConsultSuspensionService.forceEndSuspended`——status!=IN_PROGRESS 直返幂等 → **订单 CAS `markRefunding`(IN_PROGRESS→REFUNDING) 返 1 才退款（唯一闸，绝不双退）** → PawCoin `wallet.credit(REFUND)` 全额退回+`markRefunded`(REFUNDED)+`REFUND_COMPLETED` 节点；QRIS 留 REFUNDING+`REFUND_REQUESTED`（**实际 Midtrans Iris 打款留 Epic 4；本 story 不记 ledger 避免与 Epic 4 实际打款双记**——落地决策，比原 spec「记 REFUND_OUT」更稳，无 cash-refund-payable 科目、防premature/double 记账）→ 会话 `interrupt(VET_BANNED)`+`ConsultInterruptedEvent`。幂等键 `consult-refund-{orderId}`。全额退无溢价无 bonus（C-1）。
- ✅ **AC4 15min scanner**：`findByStatusAndSuspendDeadlineAtBefore(IN_PROGRESS, now)` → 逐条 `forceEndSuspended`；`ConsultSuspensionScanner @Scheduled(:30000)` + try/catch，禁 MQ。
- ✅ **AC5 用户逃生 + DTO**：`POST /consult-sessions/{id}/escape`（USER 门控，`escapeByUser` owner+挂起态校验，否则 404 防枚举）→ 立即 `forceEndSuspended`；`ConsultSessionResponse` +`suspendDeadlineAt`（用户 poll 自动带出）。「上报」最简（[OPEN-2]，完整工单 Epic 4）。
- ✅ **AC6/AC7 前端逃生横幅**：`_suspendBanner`（封禁告知 + 服务端权威 MM:SS 倒计时[1s ticker 仅挂起启用] + 全额退款 + 「立即结束」→ escape→INTERRUPTED+Toast + 「上报」）；确认无「结束会话」按钮（现状①，未加回）；model/repo/api_paths/i18n（印尼语主）。
- ✅ **AC8 静态**：后端 L0+L1（挂起分流/PawCoin 全退/QRIS 留 REFUNDING/scanner/escape/**幂等不双退**/非本人 404）8+3+3 绿 + consult 196 回归；前端 analyze 零警告 + 挂起测试 3 例绿 + 无回归。
- **守住的边界**：免费会话中断流 5.7 不变；未加 SessionStatus/VetStatus 枚举；退款原语只调不改；QRIS 实际打款/上报工单/用户停用·SIPDH 挂起/封禁生效时点 = 5 个 [OPEN]（Epic 4/后续）。
- **无 L2**：`/escape` 端点仅本分支未上线 prod，真机双端逃生（封禁→挂起→立即结束/超时+退款到账）留本地。

### File List

**后端**
- 新增 `petgo-backend/src/main/resources/db/migration/V69__add_consult_session_suspend.sql`
- 新增 `petgo-backend/src/main/java/com/tailtopia/consult/service/{ConsultSuspensionService,ConsultSuspensionScanner}.java`
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/domain/ConsultSession.java`（+`suspendDeadlineAt`/`suspend`/getter；`interrupt` 清挂起锚）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/service/ConsultInterruptService.java`（封禁分流付费挂起；注入 `ConsultOrderRepository`；`SUSPEND_SECONDS`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/repository/ConsultOrderRepository.java`（+`markRefunding`/`markRefunded` CAS）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/repository/ConsultSessionRepository.java`（+`findByStatusAndSuspendDeadlineAtBefore`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/dto/ConsultSessionResponse.java`（+`suspendDeadlineAt`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/web/ConsultSessionController.java`（+`POST /{id}/escape`；注入 `ConsultSuspensionService`）
- 新增测试 `ConsultSuspensionIntegrationTest`(L1, 8)；修改测试 `service/ConsultInterruptServiceTest`（+orders mock）、`dto/ConsultSessionContractTest`（+契约字段）
- 修改 `_bmad-output/implementation-artifacts/sprint-status-v1.1.yaml`（3-8 → review；无迁移清单纠偏）

**前端（petgo_app，包名 tailtopia）**
- 修改 `lib/features/consult/presentation/consult_conversation_page.dart`（+挂起横幅/倒计时 ticker/`_escape`/`_report`/`_suspendMmss`）
- 修改 `lib/features/consult/domain/consult_session.dart`（+`suspendDeadlineAt`/`isSuspended`）
- 修改 `lib/features/consult/data/consult_repository.dart`（+`escapeSuspended`）、`lib/core/network/api_paths.dart`（+`consultSessionEscape`）
- 修改 `lib/l10n/app_id.arb`、`lib/l10n/app_en.arb`（+挂起/逃生文案）→ gen-l10n 产物
- 新增 `test/consult/consult_suspend_test.dart`（3 例）

### Change Log

- 2026-07-13：dev-story 实现 Story 3.8（Konsultasi UI 定稿与封禁挂起逃生，安全攸关 H-5）。**后端**：V69 加 `consult_sessions.suspend_deadline_at`；`interruptByVetBan` 封禁分流（付费会话→挂起 15min+IM 告知，不即时中断；免费会话→即时中断 5.7 不变）；`ConsultSuspensionService.forceEndSuspended`（订单 CAS `IN_PROGRESS→REFUNDING` 幂等唯一闸绝不双退 + PawCoin `wallet.credit(REFUND)` 全额退回+REFUNDED / QRIS 留 REFUNDING 实际打款留 Epic 4 + 会话 INTERRUPTED + 事件）；15min `@Scheduled` scanner；用户 `POST /consult-sessions/{id}/escape` 提前结束；`ConsultSessionResponse` 暴露 `suspendDeadlineAt`。**前端**：会话页挂起横幅（服务端权威 MM:SS 倒计时 + 全额退款 + 「立即结束」→escape→INTERRUPTED+Toast + 「上报」）；无「结束会话」按钮（早已完成）；model/repo/api_paths/i18n。L0+L1（8+3+3）+ consult 196 绿（全量唯一失败 notify targetRef 为 baseline flake）；前端 analyze 零警告 + 挂起 3 例绿 + test 413（唯一失败 story_1_5_gating baseline flake）。QRIS 打款/上报工单/用户停用挂起 = Epic 4/后续 [OPEN]。L2 真机逃生留本地。Status → review。
- 2026-07-13：create-story 定稿 Story 3.8（Konsultasi UI 定稿与封禁挂起逃生，安全攸关 H-5）。**核心发现**：移除「结束会话」按钮早已完成（前端主体=逃生横幅）；免费会话封禁是即时无退款（5.7），付费会话须挂起+退款；退款原语（PawCoin credit REFUND / ledger REFUND_OUT）已具备，QRIS 实际打款属 Epic 4。**用户已拍板**：① 15min 挂起+逃生入口+scanner（挂 session 级不新增 VetStatus，V69 小迁移加 `suspend_deadline_at`）；② PawCoin 立即全额退+订单 REFUNDED，QRIS 记 REFUNDING+REFUND_OUT 负债、实际打款留 Epic 4。方案：封禁分流（付费挂起/免费即时中断不变）+ `forceEndSuspended`（订单 CAS IN_PROGRESS→REFUNDING 幂等单点 + 按渠道退款 + 会话 INTERRUPTED）+ 15min @Scheduled scanner + 用户 escape 端点 + 会话 DTO 暴露 suspendDeadlineAt + 前端挂起横幅/逃生 CTA/文案定稿。幂等/事务是安全红线（绝不双退）。L0 迁移/DTO + L1 分流/退款/scanner/escape/幂等 + 前端 analyze/test；L2 真机逃生留本地。5 个 [OPEN]（QRIS 打款/上报工单/用户停用·SIPDH 挂起/封禁生效时点/兽医可见）。Status → ready-for-dev。
