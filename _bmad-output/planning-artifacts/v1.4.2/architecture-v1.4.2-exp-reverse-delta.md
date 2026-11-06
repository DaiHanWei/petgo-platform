---
title: "V1.4.2 exp-reverse 主题（问诊挂起退款扣回成长值）· 架构增量"
status: complete
completedAt: '2026-11-06'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2退款扣回成长值修复prd.md
  - _bmad-output/planning-artifacts/v1.4.2/决策日志-exp-reverse.md
version: v1.4.2
theme: exp-reverse
code_baseline: dev_1.4.2（ad0d8f33a）
---

# V1.4.2 exp-reverse · 架构增量

> 修复 1.4.0 架构 P-10 留下的缺口（问诊 QRIS 分支）。只动后端与 Thymeleaf 后台，App 无改动。**权威顺序**：`决策日志-exp-reverse.md` > PRD > 本文件。

## 1. 已核实的代码现状

路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`T/` = `petgo-backend/src/main/resources/templates/admin/`。

| # | 现状 | 位置 | 影响 |
|---|---|---|---|
| 1 | PRD §2 属实：现金分支只留 `REFUNDING` + `REFUND_REQUESTED` 节点，不发退款事件 | `B/consult/service/ConsultSuspensionService.java` `refundByChannel` | 本次在此打标 |
| 2 | `REFUNDING` 有两个来源：挂起 `markRefunding`、常规 `markRefundingFromCompleted` | `B/consult/repository/ConsultOrderRepository.java` | 需要 E-2 |
| 3 | 问诊实际只有 `QRIS` / `PAWCOIN`，不产生 `MIXED` | `ConsultBillingService` 及全仓 `PayChannel.MIXED` 用点 | 现金分支即 QRIS |
| 4 | 入账流水键：有支付意图 `SPEND:PI:{intentId}`；纯币 `SPEND:COIN:vet-consult-order:{orderId}` | `B/growth/service/GrowthIdemKeys.java`、`GrowthRefundListener.locate` | 必须按幂等键定位并校验同一用户，不能按 ref 反查 |
| 5 | `reverse` 已保证幂等、累计不超入账、行锁、等级重算；返回 boolean，且按用户累计截断 | `B/growth/service/GrowthLedgerService.java:152` | 需要带结果的变体 |
| 6 | 降级不通知：`GrowthNotifyListener` 仅升级发 | `B/growth/listener/GrowthNotifyListener.java` | 满足 PRD，无需改 |
| 7 | 注销物理删流水，停用保留 | `GrowthAccountDeletionService` | 注销用户走 E-4 |
| 8 | 兽医订单页无筛选参数，`list()` 全表内存映射；页面文案写明「本页暂无筛选」 | `AdminConsultOrderService.list`、`T/consult-orders.html` | E-7 |
| 9 | `OPS_MANAGER` 不在 `admin_roles` 表，权限在枚举；新码无需迁移 | `B/admin/account/domain/AdminRole.java` | E-1 |

## 2. 决定

### AD-1 挂起退款标记（E-2、E-3）

- 迁移 `V20261106_2310__add_consult_orders_suspension_refund_at.sql`：`ALTER TABLE consult_orders ADD COLUMN suspension_refund_at timestamptz NULL` + 列注释，**不回填**。
- `ConsultOrder` 映射字段；`ConsultOrderRepository` 加 JPQL：`set suspensionRefundAt = :at, updatedAt = CURRENT_TIMESTAMP where id = :id and status = REFUNDING and suspensionRefundAt is null`。`refundByChannel` 现金分支在写 `REFUND_REQUESTED` 的同一事务里调用，返回非 1 抛异常回滚。PawCoin 分支不写。
- 🔴 **禁止用实体 setter 打标**：`markRefunding` 是 bulk update，一级缓存里 `order.status` 仍是 `IN_PROGRESS`，`ConsultOrder` 无 `@DynamicUpdate`，全列 UPDATE 会把状态写回去。
- 不新增订单状态，订单继续停在 `REFUNDING`（PRD OQ-2）。

### AD-2 扣回状态单点

`AdminConsultOrderExpService`（`B/admin/consult/service/`）提供 `state(ConsultOrder)`，返回 `ConsultExpState(Integer accrued, long reversed, long pending, boolean reversible)`：

- 入账：按现状 4 定位，校验 `userId` 相同，`delta > 0`；否则 `accrued = null`。
- 已扣回：`sumReversed(原流水 id)`。
- `reversible = status == REFUNDING && suspensionRefundAt != null && accrued != null && reversed == 0 && 用户累计 > 0`（E-9）。
- `pending = reversible ? min(accrued, 用户累计) : 0`。

抽屉、弹层、列表筛选、执行校验**都只用这一处**。入账键选择抽成 `GrowthIdemKeys.consultSpend(orderId, paymentIntentId)` 纯静态方法，本服务调 `findByIdemKey` 后自行校验 userId。**`GrowthRefundListener` 不动**（其测试 mock `findByIdemKey`，且 `locate` 第一分支不只问诊用）。

### AD-3 执行（E-5、E-6）

- `GrowthLedgerService` 新增 `reverseDetailed(ReverseCommand)` 返回 `ReverseOutcome(Status status, int amount, int levelBefore, int levelAfter)`（`DONE` / `ALREADY_REVERSED` / `NOTHING_TO_REVERSE`），账户锁内取等级；`reverse` 改为委托它，返回值语义不变。
- `AdminConsultOrderExpService.reverse(orderToken, adminId)` 单事务：取 `state` → 不可扣回则 422 → `reverseDetailed(new ReverseCommand(原幂等键, REFUND, refundSource("consult-refund-" + orderId), null))` → 非 `DONE` 则 422（`ALREADY_REVERSED` 用 PRD 重复扣回文案，`NOTHING_TO_REVERSE` 用通用状态已变文案）→ 同事务写审计。
- 重复提交（双击、两人并发）靠账户行锁 + 幂等键，第二次返回 422「这单的成长值已经扣回过了」。
- 成功提示取**实扣额**。

### AD-4 权限与审计

- 权限码 `order.reverse_exp`（`AdminPermissions.ORDER_REVERSE_EXP`），照 `user.grant_pawcoin` 全部登记点：常量、`perm.group.edit` 分组、`AdminPageCatalog` consult-orders 第三列、`perm.*` 三语、`OPS_MANAGER` 枚举预授（E-1）。
- 审计动作 `CONSULT_ORDER_EXP_REVERSED`，target `consult_order` / orderToken，summary 含入账、此前已扣回、本次扣回、前后等级，不含 PII。

### AD-5 页面

- 抽屉 `T/fragments/drawer-consult-order.html` 新增 `data-section="order-growth"`：入账、已扣回；`reversible` 时加「待扣回」行与按钮（`sec:authorize` 门控）。
- 两个端点，门控都用 `REVERSE_EXP_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.reverse_exp')"`（超管不注入权限码，只写 hasAuthority 超管会 403），模板 `sec:authorize` 逐字一致：`POST /admin/consult-orders/{orderToken}/exp-reverse/preview` 回 `confirm-modal`；`POST .../exp-reverse` 执行。
- 成功路径不用 `successTarget`，走 `HX-Trigger-After-Swap: admin:confirm-close` + `afterAction`（范式 `AdminGrowthBatchController`），错误落 `#confirm-modal-err`。预览按钮 `hx-target` 指向 `#order-drawer-err`。`consult-orders.html` 必须加 `#confirm-host`。
- `/verify` 与 `/exp-reverse` 共用一个方法算摘要与行：用 `HxRequest.currentUrl()` 解析 filter（范式 `AdminSupportTicketController`）；行不在筛选结果里时输出 `hx-swap-oob="delete"`，**不得用 `orElseThrow` 找行**（扣回已提交后抛 500）。未知 filter 值按全部。
- 列表：`?filter=exp-reverse` → 查 `REFUNDING + suspension_refund_at IS NOT NULL` 候选，再用 AD-2 过滤；摘要用过滤后的 rows。此类订单极少，无需联表 SQL。

## 3. 不做

不改退款流程与订单状态、不做资金出款、不发通知、不补存量、不接入「退款管理」、不改 App。
