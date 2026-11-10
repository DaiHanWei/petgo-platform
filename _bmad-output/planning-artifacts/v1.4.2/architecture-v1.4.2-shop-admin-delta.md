---
title: "V1.4.2 shop-admin 主题（电商管理后台）· 架构增量"
status: complete
completedAt: '2026-11-06'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商后台prd.md
  - _bmad-output/planning-artifacts/v1.4.2/电商/ui-admin-1-4-2-shop-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md
  - _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md
  - _bmad-output/planning-artifacts/v1.4.2/决策日志-shop-admin.md
version: v1.4.2
theme: shop-admin
code_baseline: origin/feat/1.4.2-shop（e819b02f1，Epic 1~9 完成）+ dev_1.4.2（b089cb1d）
---

# V1.4.2 shop-admin 主题（电商管理后台）· 架构增量

> 只写本主题新增或改变的技术决定。**权威顺序**：`决策日志-shop-admin.md` > 后台 PRD > 本文件 > UI 稿。电商线架构 `architecture-v1.4.2-shop-delta.md` 的 D-x / P-x 继续有效，本文件用 AD-x / AP-x 编号。
>
> **范围**：AB-24、AB-25、AB-27、AB-28、AB-29、AB-38、AB-39 的后台页面、Controller，以及电商线 service 层**未覆盖的缺口**。App 只有一处改动：确认收货 409 `return-inbound-confirmed` 的提示（O-16）。
>
> **基线（O-2）**：代码在电商线合入 `dev_1.4.2` 后从 dev 切 `feat/1.4.2-shop-admin`。本文件的电商线方法签名取自 `e819b02f1`，联调若有变动，以合并后的代码为准。

## 1. 已核实的代码现状

路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`T/` = `petgo-backend/src/main/resources/templates/admin/`，`R/` = `petgo-backend/src/main/resources/`。

### 1.1 后台框架

1. **目录单一事实源** `B/admin/shared/AdminPageCatalog.java`：`page(...)` / `shared(...)` / `legacy(...)` + `.nav(...)`。banner、库存周转、对账是 `legacy` 项，仍在侧栏。`AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate` 双向比对侧栏与 `@PreAuthorize`。
2. **现有 `/admin/shop/orders` 就是「订单履约」**，新页「订单」须用新路由，不能占此路径。
3. **角标** `B/admin/shared/nav/NavBadgeService.java`：只有 6 个待办队列，按 `visible()` 过滤，**商城无计数**。
4. **权限码** `B/admin/account/domain/AdminPermissions.java`：新增要加常量 + `GROUPS` + Catalog 归属页 + 四份 properties；种子迁移已冻结，新码默认不预授。超管靠表达式放行。
5. **审计** `AdminAuditService.record(actor, action, targetType, targetId, summary)`：无前后值字段，`SUMMARY_MAX_LENGTH = 500`，超长截断。配置类另写 `config_change_logs`。
6. **确认弹层** `T/fragments/confirm-modal.html` + `B/admin/shared/web/AdminConfirmModal.java`：服务端渲染，先 POST `…/confirm` 再以 `HX-Retarget #confirm-host` 打开。已有标题、警示条、facts、diffs、note、输入确认、hidden 字段、危险按钮；**无金额块、无单选 / 下拉**。
7. **商城页二次确认全是原生** `data-confirm`：`shop-shipping.html:109`、`merchant-purchase-order-detail.html:163`、`shop-exception-panel.html:88,106`、`shop-banners-list.html:83`、`shop-products-list.html:76`、`drawer-shop-inventory.html:124,151`、`drawer-shop-order.html:202`、`shop-return-panel.html:283`。
8. **提醒通道**：`AdminAlertService` 只写日志；`LarkWebhookClient.send(payload)`（`B/admin/dailyreport/`，带 HMAC）只服务日报；`LarkMessageClient.sendText`（`B/shop/order/notify/`，应用机器人）单接收方。**均不支持多群**。
9. **三语**：`R/i18n/messages{,_zh_CN,_en,_id}.properties`，每份约 4600 行。
10. **测试**：无 `@WebMvcTest`；页面测试基类 `ApiIntegrationTest`（真库，L1）；`AdminTemplateShellRenderTest` 是纯模板渲染（L0 可跑）。
11. **用户详情**是 `/admin/users` 上的抽屉 `T/fragments/drawer-users.html`，9 个页签；「收送礼」页签 + `AdminUserGiftsController` 是加页签的范本。
12. **运营配置** `AdminConfigController` + `T/config.html`；分享奖励保存 `POST /admin/config/share-reward`，权限 `config.share_reward_edit`，存 `pawcoin_config.share_reward_enabled`（默认 true，`V20260825_0236`）；确认现为前端 `window.confirm`。

### 1.2 商城各页

13. **banner**：`AdminShopBannerController` / `AdminShopBannerService` / `shop-banners.html`。上传由 `AdminSeedImageService` 代传并**已读出宽高**（`imageW` / `imageH`）。两个小 bug：`shop-banners-list.html:67` 用错 key；`shop-banners.html:41` 标题固定。
14. **异常订单**：`AdminShopOrderExceptionController` + `shop-exception-{queue,panel,done}.html`；队列只取 `PENDING_SHIPMENT` + `shortage_flagged_at` 非空。
15. **订单履约**：`AdminShopOrderController` + `shop-orders.html` + 抽屉 `drawer-shop-order.html`（订单 / 商品行 / 收货信息 / 包裹 / 吸底操作）；`AdminShopOrderService.search/page/summary/searchByPhone` 可复用。
16. **对账**：`AdminShopFinanceController` → `ShopFinanceDashboardService.reconciliation`，时间维度 from / to，模板 `cards-shop-reconciliation.html`。
17. **运费**：`AdminShippingController` + `shop-shipping.html`，权限 `config.view` / `config.edit`。
18. **商品**：`AdminShopProductController` + `shop-product-form.html`；SKU 保存 `upsertSku`；列表筛选只有 category / active。
19. **复购看板**：`AdminRepurchaseDashboardController`、`RepurchaseDashboardService`、`shop-repurchase-dashboard.html`、`cards-shop-repurchase.html`、Catalog 一项；文案 `admin.shop.form.feedingWarning`、`admin.shop.form.hint.feedingConsequence`、`admin.roles.matrix.footnote`。
20. **入库入口**：`POST /admin/shop/inventory/return-inbound`（skuId、qty、originalOrderNo、日期），走 `InventoryMovementService` `RETURN_INBOUND`。`originalOrderNo` 只是文本，**与订单没有关联**。

### 1.3 电商线已提交、后台线直接调用

| 能力 | 入口 |
|---|---|
| 建券 / 停券 / 兑换码 | `CouponAdminService.create` / `stop` / `createUniversalCode` / `generateSingleUseCodes` |
| 下运单 / 重提 / 取消 | `BiteshipShipmentService.createWaybill` / `retryWaybill` / `reshipForCourierFault` / `cancelWaybill` |
| 揽收失败处置 | `ShopLogisticsExceptionService.resolvePickupFailureByRetry` / `ByManualWaybill` / `ByCancel` |
| 物流异常 | `confirmReturned` / `reshipAfterCourierFault` / `markLostOrDamaged` / `setClaimPending` / `clearException` / `listOpen` / `listClaimPending` |
| 取消退款 | `ShopOrderRefundService.cancelWhole` / `cancelLine` / `confirmReturnInbound` / `compensate` / `markPaidOut` / `markPayoutFailed` / `refundViewsOf` / `findAwaitingDestinationOverdue` |
| 迟到款 | `LatePaymentService.reorderFromLatePayment` / `refundLatePayment` / `linksOf` |
| 履约 | `ShopOrderFulfillmentService.shipManualWithAudit` / `markShippedWithoutWaybill` / `markDeliveredByAdmin` |
| 评价 | `AdminShopReviewService.hide` / `unhide` / `setDisplayEnabled`；开关在 `shipping_settings.review_display_enabled` |
| 包裹参数 | `AdminShopSkuPackageService.updatePackage` |
| banner | `AdminShopBannerService`（已支持排期、COUPON）；App 侧取数 `ShopBannerQueryService` |

### 1.4 电商线与后台 PRD 的缺口

| # | PRD 要求 | 电商线现状 | 处置 |
|---|---|---|---|
| G1 | 券编辑、加总量、列表、详情数据、导出 | 只有建 / 停 / 生成码 | AD-6 |
| G2 | 券状态变化记录 | 无流水 | AD-6 |
| G3 | 标记已打款填流水号、传截图 | `markPaidOut(token, actor)` 无此参数 | AD-10 |
| G4 | 打款失败选原因 | 只有自由文本 `note` | AD-10 |
| G5 | 操作人不能是发起人 | 未校验（`created_by_admin_id` 已有） | AD-10 |
| G6 | 退款明细试算 | 只有落库动作 | AD-9 |
| G7 | 用户原因先入库后取消 | 先取消后入库，且入库再回补一次库存 | AD-9 |
| G8 | 部分缺货可重新下单 | 要求全部有货 | AD-11 |
| G9 | 原快递不可用改选最便宜 | 未处理 | AD-11 |
| G10 | 索赔结案填结果与金额 | 只有 `claim_pending` 布尔 | AD-14 |
| G11 | 「已发货」的操作人 | 只有 `shipped_source` | AD-14 |
| G12 | 隐藏评价记原因 | `hide` 无原因 | AD-16 |
| G13 | Biteship 预估余额 | 无 | AD-13 |
| G14 | 飞书提醒客服 / 运营 / 技术 | 7 天未选只写 WARN；技术告警走单一接收方 | AD-5 |
| G15 | 处置后离开队列 | 取消后标记保留，`findLogisticsExceptionOpen` 不滤状态 | AD-9 |
| G16 | 退回原因 | `confirmReturned` 不落库，只写 confirmed 与 `claim_pending` | AD-9 |
| G17 | 实际保价费 | `insurance_amount` 是**申报保价额**；Biteship 快照无保价费 | AD-13 |
| G18 | 已发货揽收失败重提 | `requirePickupFailed` 与 CREATE 模式只收待发货 | AD-14 |
| G19 | 重发运单标识 | 无列 | AD-14 |

> **`shop_orders.shipping_discount` 存负数或 0**（`ck_shop_orders_total_formula`：`total = goods − coupon + shipping_fee + shipping_discount + insurance_fee`）。凡计算用户实付运费都用 `+`。

## 2. 架构决策

迁移时间戳排在电商线 `V20261105_1650` 之后；金额 `BIGINT`；不用 `SMALLINT`。

**AD-1 分层**：Controller 放 `B/admin/shop/web/`，后台专用查询放 `B/admin/shop/service/`；业务规则改动落回电商线原类（如 `ShopOrderRefundService`），不在 Controller 或后台 service 里写第二份。金额仍只经 `ShopPricingCalculator` / `ShopRefundCalculator`（P-1），券状态只经 `CouponLedgerService`（P-2）。

**AD-2 导航**（`AdminPageCatalog`）：

| 页 | 路由 | 位置 | 查看 | 操作 |
|---|---|---|---|---|
| 订单（新） | `/admin/shop/order-list` | 「订单履约」前 | `shop.order_view` | `shop.order_fulfill` |
| 退款打款（新） | `/admin/shop/refund-payouts` | 「退货审核」后 | `refund.view` 或 `refund.payout` | `refund.payout` |
| 优惠券（新） | `/admin/shop/coupons` | 「顶部 Banner」后 | `shop.coupon_view` | `shop.coupon_edit` |
| 复购引擎效果 | — | 删除 | — | — |

待办中心组末尾加小标题「商城待办」和两项跳转（不新增 Catalog 页，只是指向商城页的导航项），计数并入 `/admin/nav/badges` 与待办中心组角标，无页面权限不渲染。计数口径：异常订单 = 待处理队列条数；退款打款 = 待打款 + 打款失败 + 待用户选择中超 7 天未选。

**AD-3 confirm-modal 扩展**：`AdminConfirmModal` 加三项可选能力，默认不渲染：`amounts`（标签、金额、灰字附注、合计行）、`choices`（单选组）、`select`（下拉）。所有商城页 `data-confirm`（§1.1 第 7 条全部 10 处，含 `drawer-shop-inventory.html` 里由 JS 改写的那处，需单独处理）改为 `…/confirm` 端点。`admin.css` 新增 `.kv.amt`、汇总格语义色、页签红色计数、队列来源徽标、金额列不换行；全局字体抗锯齿。

**AD-4 审计**（O-11）：`AuditActions` 按 PRD 文档头表新增动作码。摘要写 `字段: 旧→新`，只写有变化的字段；涉钱写全金额；兑换码只记数量。配置类沿用 `config_change_logs`。

**AD-5 运营提醒**（O-10）：新增 `B/admin/alert/OpsAlertService.send(channel, dedupeKey, text)`，`channel ∈ CS | OPS | TECH`，复用 `LarkWebhookClient`（改为可传 URL + secret）。env：`LARK_SHOP_{CS,OPS,TECH}_WEBHOOK_URL` / `_SECRET`，空则 WARN 日志。事务提交后发送，失败只记日志，绝不回滚业务。去重表 `ops_alert_log(dedupe_key PK, channel, sent_at)`，同键只发一次。

| 事件 | 群 | 去重键 |
|---|---|---|
| 取消后到账 | CS | `late-paid:{orderToken}` |
| 打款失败 | CS | `payout-failed:{refundToken}:{payoutFailedAt}` |
| 超 7 天未选 | CS | `refund-overdue:{refundToken}` |
| 物流停滞 | OPS | `stalled:{orderToken}:{exceptionAt}` |
| 用户确认收货清除异常 | OPS | `receipt-cleared:{orderToken}` |
| 预估余额低于阈值 | OPS | `biteship-balance:{snapshotId}` |
| 待索赔产生 | OPS | `claim:{orderToken}` |
| 重新提交运单激增、查单连续失败等技术告警 | TECH | 沿用现有节流 |
| `ShopPaymentAlerts` 中的运营类（确认收货、运单取消失败） | OPS | 按事件 |

文案不含手机号、地址、收款账户。

**AD-6 优惠券**：
- `CouponAdminService` 增 `update`：发放期未开始可改全部字段；已开始只能加总量（只增不减）。增 `listForAdmin`、`detailStats`（已领 / 占用中 / 已使用 / 带来的订单金额 = 用该券且已付款订单的 `total_amount` 合计）、`exportCodes`。
- 列表状态现算：已停用（`STOPPED`）/ 未开始 / 进行中 / 已结束（按发放期）。
- 校验：固定有效期结束日 ≥ 发放期结束日；免运券最高减免必填；新人券发放期不重叠（已有）。面额 ≥ 门槛继续硬拒（O-14）。防误配（门槛为 0、总量不限且每人不限）走 confirm-modal，服务端以 `acknowledgeRisk` 参数放行。
- 新表 `user_coupon_events(id, user_coupon_id, event, order_id NULL, created_at)`，`event ∈ ISSUED | LOCKED | RELEASED | USED | VOIDED`，**只在 `CouponLedgerService` 内写**，与状态变更同事务。
- 用户页签只读；锁定倒计时读 Redis `rl:coupon:redeem-lock:<uid>` 的 TTL（前缀常量改为包内可见复用，不另写字面量）；优惠券页签权限沿用用户详情查看权限。
- 导出 CSV 要求 `shop.coupon_edit`，审计只记数量。批量码券加总量时同一事务补生成差额兑换码。
- `user_coupon_events.user_coupon_id` 外键 `ON DELETE CASCADE`（注销级联）。

**AD-7 banner**：
- 六种列表状态由一个纯函数算出：待上线 / 生效中 / 目标失效 / 超出展示上限 / 已过期 / 未上架。与 `ShopBannerQueryService` **共用同一套「生效集合」判定**，后台与 App 不得各写一份。
- 拖动排序 `POST /admin/shop/banners/reorder {ids[]}`：按新顺序重写 `sort_weight`（N..1），列与初始顺序不变，无需迁移。
- 表单去掉权重输入；下拉去掉品类；存量 CATEGORY 编辑时类型留空、未改选不能保存（服务端同样拒绝）。COUPON 目标只列 `acquire_type = CLAIM`。
- 图片比例：`|w/h − 352/148| / (352/148) > 3%` 时提示，不拦截；纯函数单测。
- 修 §1.2 第 13 条两个小 bug。

**AD-8 分享奖励**（O-1）：迁移只 `ALTER COLUMN share_reward_enabled SET DEFAULT false`，**不 UPDATE 现有行**。卡片顶部常驻警示。保存走 confirm-modal：列新旧值 + 估算 `min(月度上限, Σ(每次发放 × 每日上限) × 当月天数)`，当月天数按 WIB；估算是纯函数。改奖励金额或月度上限才弹估算。

**AD-9 异常订单**：
- **队列**（G15）：缺货（现状）；`waybill_failed_at` 非空且订单待发货或已发货；`PICKUP_FAILED`；已发货的 `RETURNED`；`LOST` / `DAMAGED`（已发货，或已签收 / 已完成且尚无赔付单）。CANCELLED / REFUNDING / REFUNDED 一律出队；取消时清 `waybill_failed_at`。来源由一个函数算出，优先级：退回 > 丢件破损 > 揽收失败 > 运单失败 > 缺货（重发失败的已发货单来源为退回）。「联系用户后继续」只对缺货来源显示。
- **退回原因落库**（G16）：`shop_orders.return_cause`（`COURIER_FAULT | USER_FAULT`），`confirmReturned` 写入，判定后不可改；`reshipAfterCourierFault` 要求 `COURIER_FAULT`。
- **类别由服务端绑定**：平台原因按来源映射 `OUT_OF_STOCK` / `WAYBILL_FAILED` / `PICKUP_FAILED` / `COURIER_FAULT`；用户原因只接受 `return_cause = USER_FAULT`；丢件破损只接受 `LOST` / `DAMAGED`。类别决定退款、补偿、退券，运营不能单独开关。退回待判定的订单不能取消。
- **揽收失败取消**走 `resolvePickupFailureByCancel`（先取消旧运单，失败则中止，不回补库存），不直接调 `cancelWhole`，满足 PRD「先取消」。库存回补沿用 `restocksOnCancel`。
- **试算**（G6）：`previewWhole` / `previewLine` / `previewCompensate` 只读，与落库同一计算。
- **按行入库**（O-3、O-15、G7）：`shop_order_lines.return_inbound_qty INTEGER NOT NULL DEFAULT 0`，`shop_orders.return_inbound_at`、`return_inbound_by`。`confirmReturnedInbound(orderToken, {lineId: qty}, actor)` 要求 `return_cause = USER_FAULT`，累计不超过 `qty − refunded_qty`，同事务调 `movements.receiveReturn`，可分多次，全部收齐写 `return_inbound_at`。`cancelWhole(USER_FAULT_RETURNED)` 要求该列非空，否则 409；通过后直接出退款单、**不回补库存**。短收的不放行，改走丢件破损。`confirmReturnInbound` 删除，stag 入口改调新方法。
- **已入库拒收**（O-16）：任一行 `return_inbound_qty > 0` 时 `confirmReceipt` 返回 409 `return-inbound-confirmed`，App 提示联系客服。
- **赔付**（O-6）：已签收 / 已完成的丢件破损在 D5 表单走 `compensate`，试算同 `previewCompensate`。
- **部分取消**（O-21）：有活动运单时拒绝。最后一行转整单时类别只能是平台原因。
- **联系用户后继续**：清缺货标记，语义不变。

**AD-10 退款打款**：
- 迁移加 `payout_txn_no VARCHAR(64)`（不设唯一，批量转账可共用；重复时提示不拦截）、`payout_proof_key VARCHAR(255)`、`paid_out_by_admin_id`、`payout_fail_reason`（`ACCOUNT_INVALID | TRANSFER_RETURNED | OTHER`）、`payout_failed_by_admin_id`、`offline_closed_at`、`offline_closed_by`、`offline_close_note`。
- `markPaidOut(token, txnNo, proofKey, actor)`；`markPayoutFailed(token, reason, note, actor)`；stag 入口同步改。
- 职责分离（O-12）：`actor == created_by_admin_id` → 403，`REQUIRES_NEW` 写违规审计，超管不豁免。`created_by_admin_id` 为空（系统生成）时不拦截。
- 列表取 `cash_refund > 0` 的退款单，页签：待用户选择 / 待打款 / 打款失败 / 已打款 / 已退为 PawCoin；不含退货单（PRD）。
- 账号：列表只显示末 4 位、户名显示，抽屉给 `refund.payout` 显示完整账号（O-8、O-22），响应不进日志；审计只记末 4 位。
- 截图走私有对象 + 短时效签名 URL（O-9），限图片、≤10MB，查看限 `refund.payout`。
- **注销用户**（O-17）：退款单的用户已注销时单独归入「注销用户·线下处理」页签，不计角标；`closeOffline(token, note, actor)` 受同样的职责分离约束，转 `COMPLETED`，写审计，成长值不处理。
- `RefundDestinationReminderJob` 改发 CS 提醒；7 天未选的 `AWAITING_DESTINATION` 行标记。

**AD-11 取消后到账**：
- 列表筛选：`late_paid_amount IS NOT NULL`；待处理 = `late_payment_resolution IS NULL`。迟到款落库时发 CS 提醒。
- 重复付款单（订单未取消）只显示「退还到账款」（O-19）。
- 核对清单服务端算：券（原券未被占用 / 使用 / 作废；过期不算问题）、PawCoin 按 `reorderSettlement` 算出的实际需扣额、逐行库存；「用户已同意」「地址有效」客服手勾。提交时**服务端重查**，不信任勾选结果。
- **部分缺货**（O-4，G8）：至少一行有货即可。`shop_order_lines` 新增 `stock_unlocked BOOLEAN NOT NULL DEFAULT false`。锁不到库存的行照常复制、置 true；转已付款时跳过这些行的库存确认；新单自动标缺货（说明列出缺货行）。**所有发货入口**（`createWaybill`、`retryWaybill`、手工 `ship`、`resolvePickupFailureBy*`）统一校验：有未处置的 `stock_unlocked` 行就拒绝。`cancelLine` 与 `restockRemaining` 都跳过这类行的回补。「联系用户后继续」对这类行做 lock + commit 并清标记，锁不到则拒绝、提示走部分取消。
- **原快递不可用**（O-5，G9）：仅 `checkout_source = REORDER` 的订单，下运单前询价一次；原快递 / 服务不在报价内 → 选最便宜一家写入运单，订单快照不改，抽屉提示。
- 金额沿用 S-6 多退少补。退还到账款调 `refundLatePayment`，无补偿。

**AD-12 订单页**：`/admin/shop/order-list` 复用 `AdminShopOrderService` 查询与履约 7 列；摘要待支付 / 待发货 / 待收货 / 退款中 / 取消后到账待处理；「异常标记」筛选与履约页共用一个枚举。抽屉继续用 `drawer-shop-order.html`，页面上下文决定吸底操作。抽屉新增「退款与成长值」节：退款来自 `refundViewsOf`；成长值入账 / 已扣回 / 待扣回取自 `GrowthLedgerEntry`，待扣回 = 去向 `TO_BANK` 且未打款的部分按同规则估算，标明为预计。

**AD-13 运费页与 Biteship 预估余额**：
- 发货地址卡读写 `shipping_settings.origin_*`；未配置时页顶报错。
- 门槛 > 0 时 `free_shipping_cap` 必填，服务端校验。
- 区域列表去运费列，`fee` 列保留不读。新增 / 编辑区域表单需能填 `postal_code`（电商线要求运营补齐）。
- 新表 `biteship_balance_snapshots(id, amount, estimate_before BIGINT NULL, recorded_by, recorded_at)`；`shipping_settings` 加 `balance_alert_threshold BIGINT NOT NULL DEFAULT 0`（0 = 不预警）。
- 预估 = 最新快照 `amount` − Σ(此后创建、`provider = BITESHIP`、未取消运单的 `carrier_cost + insurance_cost`)。`shipments.insurance_cost BIGINT NOT NULL DEFAULT 0` 由 Biteship 订单响应 `courier.insurance.fee` 写入（G17）；**`price` 是否已含保价须在沙箱实测**，配置 `biteship.price-includes-insurance` 默认 false，含保价时不重复加。`insurance_amount` 不得用于算钱。纯函数 + 单查询；从未录入时显示「未录入」，不预警。运单创建、运单取消、录入余额、改阈值后各检查一次，低于阈值发 OPS 提醒（按快照去重）。
- 全部写操作走 confirm-modal。

**AD-14 履约与物流异常**：
- 抽屉吸底按订单状态 + 异常标记切换，判定函数集中一处并有单测。
- 迁移加 `shop_orders`：`shipped_by_admin_id`、`claim_result`（`PAID | PARTIAL | REJECTED`）、`claim_amount BIGINT`、`claim_closed_at`、`claim_closed_by`；`shipments`：`insurance_cost`、`is_reship BOOLEAN NOT NULL DEFAULT false`（G10、G11、G17、G19）。
- 「标记已发货」用现有 `markShippedWithoutWaybill`（语义相符），补写操作人。
- 重新提交运单、手工填写运单在**运单失败**时直接做；在**揽收失败**时走 `resolvePickupFailureBy*`（先取消旧运单）。放开到已发货且无活动运单，状态不变（O-18、G18）。手工运单保存即已发货（O-20）。
- 重发失败的已发货单，「重新提交运单」走重发模式。
- `UNKNOWN_RESULT`（Biteship 结果未知）时重新提交前须先去 Biteship 控制台核实，抽屉显示警示，confirm-modal 确认，防止出两张运单。
- 标记丢件破损：已发货、已签收、已完成都可标，均加待索赔（O-6）。
- 索赔结案：写结果与金额，清 `claim_pending`，进对账；任何订单状态都能结案。
- 清除物流停滞：`clearException` 不限类型，后台入口新增校验只允许 `STALLED`。

**AD-15 对账**：新增「优惠与物流成本」区块，口径（时间范围 from / to，WIB）：

| 项目 | 取数 | 时间基准 |
|---|---|---|
| 满减券抵扣 | `discount_coupon_amount` | `paid_at` |
| 满额免运 / 免运券减免 | `abs(shipping_discount)` 按 `shipping_discount_source` 分列 | `paid_at` |
| 用户实付运费 | `shipping_fee + shipping_discount`（后者 ≤ 0） | `paid_at` |
| Biteship 实际运费 | 未取消且 `is_reship = false` 运单 `carrier_cost`；手工运单取录入值 | 运单 `created_at` |
| 重发运费 | `is_reship = true` 运单 `carrier_cost` | 运单 `created_at` |
| 运费净成本 | Biteship 实际运费 + 重发运费 − 用户实付运费 | 派生 |
| 保价费收入 / 实际成本 | `insurance_fee` / 运单 `insurance_cost` | `paid_at` / `created_at` |
| 转 PawCoin 补偿 | 退款单 `compensation_amount` | `destination_chosen_at` |
| 待索赔 / 已索赔到账 | 待索赔笔数 / `claim_amount` | 当前 / `claim_closed_at` |
| 退款打款 | 退款单现金按当前状态分四项 | `created_at` |
| 取消后到账 | `late_paid_amount` 分已转新订单 / 已退 / 待处理 | `late_paid_at` |

运费净成本是计算列，单测钉住「减免不重复计」。按下单口径，不冲减已退运费（O-24）。新区块用 `paid_at`，与原卡 `created_at` 不同，卡内注明。存量订单新列为默认值，不补算。

**AD-16 评价**：迁移加 `shop_reviews.hidden_reason`（`ABUSE | AD | PRIVACY | OTHER`）、`hidden_by_admin_id`；`hide(reviewId, reason, actor)`。商品编辑页加「商品信息 / 评价」页签，评价只列已发布（含已隐藏）并分页，操作权限 `shop.product_edit`。运营配置加「商品评价展示」卡，权限 `config.edit`，绑定 `shipping_settings.review_display_enabled`。不提供删除，不通知用户。

**AD-17 AB-29 下线**：删 Controller、Service、模板、Catalog 项、侧栏文案 key；删 §1.2 第 19 条两处警告与脚注相应句子；`RepurchaseDashboardService` 无其他调用方时一并删。喂量字段保留。

**AD-18 SKU 包裹**：SKU 表加重量、长宽高；`upsertSku` 同事务调 `AdminShopSkuPackageService`，四项必填。商品列表加「包裹数据」筛选、摘要与徽标。

## 3. 实现规则

- **AP-1 文案**：只取 `implementation-artifacts/v1.4.2/shop-admin-文案三语表.md`；四份 properties 同步；标「不可复用」的新建 key。界面用词按 PRD 文档头。
- **AP-2 迁移**：sprint-status 预分配；一件事一个文件。
- **AP-3 外部调用**：Biteship 调用不在持有行锁的事务内（P-7）。
- **AP-4 日志**：不记收款账户、手机号、地址、兑换码明文。
- **AP-5 测试**：纯函数（banner 状态、比例、分享估算、预估余额、类别映射、吸底判定、运费净成本）写 L0 单测；金额用例取 UI 稿示例数字；页面状态写 L1 集成测试，云端只编译不跑；`AdminPagesRenderSmokeTest` 同步。
- **AP-6 样式**：取现有 `admin.css` 类，新样式放 `admin.css`，不另起样式表，不量截图像素。
- **AP-7 未改动的业务页**：只替换确认方式，不顺手改其他行为。

## 4. 迁移（预分配）

| 版本 | 内容 | Story |
|---|---|---|
| `V20261106_1000` | `ops_alert_log` | 1.3 |
| `V20261106_1005` | `user_coupon_events` | 2.1 |
| `V20261106_1010` | `pawcoin_config.share_reward_enabled` 默认值 | 3.2 |
| `V20261106_1015` | `shop_reviews` 隐藏原因与操作人 | 3.3 |
| `V20261106_1020` | `biteship_balance_snapshots` + `balance_alert_threshold` + `shipments.insurance_cost` | 4.1 |
| `V20261106_1025` | `shop_orders.return_cause` / `return_inbound_*`、`shop_order_lines.return_inbound_qty` | 5.1 |
| `V20261106_1030` | `shop_order_refunds` 打款与线下结案列 | 5.2 |
| `V20261106_1035` | `shop_orders` 发货操作人、索赔结案列；`shipments.is_reship` | 6.1 |
| `V20261106_1040` | `shop_order_lines.stock_unlocked` | 7.2 |

## 5. 上线依赖

- 须与电商线**同批发布**（AB-27 除外）。
- 须在沙箱实测 Biteship 订单响应的保价费字段与 `price` 是否含保价。
- UI 稿缺两屏：确认退回入库（O-15）、注销用户线下处理（O-17）；PRD 待设计侧同步 O-14、O-15、O-20。
- env：三组飞书 webhook，群由运营指定。
- 运营：配发货地址、预警阈值、录入 Biteship 余额；补齐 SKU 包裹与区域邮编；看分享奖励开关（O-1）；过历史评价后再开展示开关。
- 新权限 `shop.coupon_view` / `shop.coupon_edit` 需管理员分配。
- 遗留 S-5 不变：门店返佣取消扣回由商家仓做。
