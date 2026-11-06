# Story 6.1：履约抽屉 Biteship 下单与失败处置

Status: review

> V1.4.2 · 主题 shop-admin · 分支 `feat/1.4.2-shop-admin`（电商线合入 `dev_1.4.2` 后切出）。
> 口径优先级：`决策日志-shop-admin.md` > `决策日志-shop.md` > 后台 PRD > 架构增量 > UI 稿。

## Story

As a 运营,
I want 在抽屉里下运单并处理失败,
So that 不再手工录单.

## Acceptance Criteria

1. 待发货订单 **Then** 吸底「发货」换成「Biteship 下单」，显示快递、运费、保价（H2）；吸底判定是有单测的集中函数〔L0 / L1〕
2. 下单成功回填运单与 `insurance_cost`，订单仍待发货，出现「标记已发货」（H3）〔L1〕
3. 已发货显示来源、时间、操作人，只在后台可见（H4）〔L1〕
4. 运单失败显示原因，三选一（H5）；「联系用户后取消」跳异常订单该单〔L1〕
5. 揽收失败同三选一，执行前先取消旧运单，已发货且无活动运单也可重提，状态不变（H6）〔L1〕
6. 结果未知时重提须先核实，confirm-modal 确认〔L1〕
7. 手工填写运单复用线上表单，按钮「保存并发货」（H7）；手工单只有手工送达（H13）〔L1〕
8. 列表加「异常标记」筛选、徽标、摘要（H1）〔L1〕
9. 下单、重提、取消运单、手工运单、标记已发货写审计，手工运单记承运成本〔L1〕

## Tasks

- [x] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [x] 后端：迁移（如有）、服务、Controller
- [x] 页面与三语文案
- [x] 测试：L0 单测跑绿，L1 测试写好能编译
- [x] 审计与提醒逐项核对
- [x] （核对补）`ShopOrderFulfillmentService.ship` 不清 `waybill_failed_at`，全仓只有运单建成时清。运单失败后转手工，标记会留着，订单一直挂在异常队列：手工运单成功时调 `clearWaybillFailure()`
- [x] （核对补）`courier_code` 为空的存量待发货单走 Biteship 必 409（`requireWaybillData`「存量订单请走手工运单」），吸底判定函数要把这类单归到手工运单
- [x] （核对补）放开已发货重提（O-18）要改三处：`resolvePickupFailureByRetry` 第 97 行的前置、第 112-114 行收尾校验、`BiteshipShipmentService.precheck` 与 `stillValid` 的 CREATE 分支
- [x] （核对补）`BiteshipOrderSnapshot` 加保价费字段时同步构造调用方：`LiveBiteshipClient:442`、`StubBiteshipClient:91,146`、`BiteshipShipmentServiceTest:119`、`BiteshipTrackingServiceTest:80,310`
- [x] （核对补）`shipped_by_admin_id`：`ShopOrderFulfillmentService.ship` 现在没有操作人入参，`markShipped` 也没有；一单多包时第二个包裹不改写

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-14
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` H1~H7、H13；每屏 `frame-cap` 即实现说明
- **迁移**：V20261106_1035
- **代码入口**：`AdminShopOrderController`、`drawer-shop-order.html`、`BiteshipShipmentService`（`:144` 结果未知、`:311` CREATE 模式）、`ShopLogisticsExceptionService.requirePickupFailed/resolvePickupFailureBy*`、`ShopOrderFulfillmentService.markShippedWithoutWaybill/shipManualWithAudit`、`LiveBiteshipClient`（`:435` 起解析订单快照）、`StubBiteshipClient`、`BiteshipOrderSnapshot`、`BiteshipProperties`、`AdminShopOrderService.ship`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`DrawerActionsResolverTest`

- Biteship 调用不在持锁事务内
- `courier.insurance.fee` 字段待沙箱确认；解析不到按 0，记 WARN

### 代码核对（2026-11-06，基线 `fd94b3ffa`，电商线 PR#49 已合入）

- `insurance_cost` 已在 4.1 的 `V20261106_1020` 建，本 story 的 `V20261106_1035` **不要再建**，只写入值。架构 AD-14 那段把它和 `is_reship` 写在一起，以架构 §4 和 sprint-status 为准。
- 🔴 `BiteshipShipmentService`、`ShopLogisticsExceptionService` 自带短事务，Controller 不得加 `@Transactional`。它们的入参是内部 `long orderId`，`ShopOrderFulfillmentService` 用 token。
- `waybill_fail_reason` 前缀：`REJECTED:`、`UNKNOWN_RESULT:`、`NOT_CONFIGURED:BITESHIP`，以及组装缺数据的原因；AC6 按 `UNKNOWN_RESULT:` 判断。
- AC9「手工运单记承运成本」：`AdminShopOrderService.ship` 与 `shipManualWithAudit` 的审计摘要现在只有承运商和单号，要补成本。
- 「标记已发货」现成：`markShippedWithoutWaybill`，要求待发货且有活动 Biteship 运单，审计 `SHOP_ORDER_MARKED_SHIPPED` 已写。

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：AC1（L1 部分）~ AC9 为 〔L1〕，云端未起容器、`BITESHIP_MODE=stub` 未连沙箱。Biteship 沙箱 `courier.insurance.fee` 字段是否存在、`price` 是否已含保价（4.1 开关）L2 待本地验收；抽屉 / 列表还原度 L2 待本地验收。新列表 JPQL（`searchPageFlagged`）只在 Spring 启动时校验，L1 待本地验收。
- 迁移 `V20261106_1035__shipped_by_claim_close_reship.sql`：`shop_orders.shipped_by_admin_id`、`claim_result`（CHECK PAID / PARTIAL / REJECTED）、`claim_amount`、`claim_closed_at`、`claim_closed_by_admin_id`（按 `<单数>_id` 命名，AD-14 原写 `claim_closed_by`）；`shipments.is_reship`。未重复建 `insurance_cost`。索赔结案列本条只建表，实体映射与写入在 6.2。
- 吸底判定 `DrawerActionsResolver`（纯函数，`DrawerActionsResolverTest` 13 例）：待发货无运单 → Biteship 下单（无快递编码的存量单 → 手工运单）；有活动 Biteship 运单 → 标记已发货；运单失败三选一（重新提交 / 手工填写 / 联系用户后取消，结果未知 `UNKNOWN_RESULT:` 加核实警示 + 弹层黄色警示）；揽收失败（待发货 / 已发货）三选一；已发货无活动 Biteship 运单可再登记手工包裹；已发货手工送达；已发货 / 已签收 / 已完成显示发货来源（运营标记 · 操作人 · 时间 / Biteship 回传）。
- 端点（Controller 不开事务）：`/waybill/confirm`（H2 复述快递、用户实付运费 = `shipping_fee + shipping_discount` 下限 0、保价）、`/waybill`、`/waybill/retry`（只给待发货且非揽收失败，防止绕过揽收失败流程，复审修）、`/pickup/retry`、`/pickup/manual`（先取消旧运单）、`/mark-shipped`（复用 `markShippedWithoutWaybill`，写操作人）；「联系用户后取消」跳 `/admin/shop/order-exceptions?open=<token>`（5.1 已支持揽收失败 / 运单失败取消）。揽收重提失败（旧运单已取消、新运单未建成）时重渲染抽屉而不是只报错（复审修）。
- 下单成功：`shipments.insurance_cost` 回写 `courier.insurance.fee`（解析不到按 0，已保价时记 WARN），重发运单 `is_reship = true`；订单仍待发货。
- O-18：`BiteshipShipmentService` CREATE 模式放开「已发货 ∧ 揽收失败」，`resolvePickupFailureByRetry` 前置与收尾校验放开已发货；有手工包裹时在取消旧运单之前 409（复审修）。按设计更新 `ShopLogisticsExceptionServiceTest.retryRequiresPendingShipment` → `retryAllowedForShippedPickupFailure`。
- 手工运单（H7）：按钮改「保存并发货」，`ShopOrderFulfillmentService.ship(…, actor)` 首次转已发货写 `shipped_by_admin_id`（一单多包不改写），清 `waybill_failed_at`（结果未知时记 WARN 提示核实孤儿运单）；审计摘要补承运成本（`AdminShopOrderService.ship` 与 `shipManualWithAudit` 两处），按设计更新 `ShopOrderFulfillmentManualShipTest` 断言。
- 列表（H1）：「异常标记」筛选（有异常标记 / 缺货 / 运单失败 / 揽收失败 / 退回 / 丢件破损 / 物流停滞 / 待索赔，一律只看待发货 / 已发货 / 已签收 / 已完成，复审修）、摘要「有异常标记」、订单号旁徽标，翻页带筛选。
- 「待确认」：① H3 成功横幅「运单已创建 · {time} · 预约取件 {pickupSlot}」未做（取件时段不在快照里），只给 toast；② 包裹状态文案沿用 `admin.v130.shopOrders.status.*`（CREATED = 已下运单（待揽收）），未改为「待揽收 / 运输中」；③ H5 横幅里的原因直接显示 `waybill_fail_reason` 原文（如 `REJECTED:xxx`）。
- 文案取三语表 H1 ~ H7 / H13；自拟（待设计确认）：`admin.v142.ful.waybill.title / insuranceNo / unknownWarn / flash.failed / flash.pickupRetried / flash.manualSaved / flash.markedShipped`、`admin.err.v142.ful.*`。
- L0：`DrawerActionsResolverTest`、`BiteshipInsuranceFeeParseTest`、`ShopLogisticsExceptionServiceTest`、`ShopOrderFulfillmentManualShipTest`、`BiteshipShipmentServiceTest`、`BiteshipTrackingServiceTest`、`AdminMessagesParityTest`、`AdminMessagesExternalizedTest`、`AdminTemplateStructureTest`、`AdminNoNestedFormTest` 全绿；shop / admin / shared / pay 非集成单测 2861 例 0 失败（68 例 Spring 上下文 L1）。

### File List

- petgo-backend/src/main/resources/db/migration/V20261106_1035__shipped_by_claim_close_reship.sql（新）
- petgo-backend/src/main/java/com/tailtopia/admin/shop/web/DrawerActionsResolver.java（新）、AdminShopOrderController.java
- petgo-backend/src/main/java/com/tailtopia/admin/shop/service/AdminShopOrderService.java
- petgo-backend/src/main/java/com/tailtopia/shop/order/domain/{ShopOrder,Shipment}.java
- petgo-backend/src/main/java/com/tailtopia/shop/order/repository/ShopOrderRepository.java
- petgo-backend/src/main/java/com/tailtopia/shop/order/service/ShopOrderFulfillmentService.java
- petgo-backend/src/main/java/com/tailtopia/shop/logistics/{BiteshipShipmentService,ShopLogisticsExceptionService}.java
- petgo-backend/src/main/java/com/tailtopia/shared/logistics/biteship/{LiveBiteshipClient,dto/BiteshipOrderSnapshot}.java
- petgo-backend/src/main/resources/templates/admin/{shop-orders.html,fragments/drawer-shop-order.html,fragments/shop-orders-list.html}
- petgo-backend/src/main/resources/i18n/messages{,_en,_id,_zh_CN}.properties
- petgo-backend/src/test/java/com/tailtopia/admin/shop/web/DrawerActionsResolverTest.java（新）、shared/logistics/biteship/BiteshipInsuranceFeeParseTest.java（新）
- petgo-backend/src/test/java/com/tailtopia/shop/logistics/ShopLogisticsExceptionServiceTest.java、shop/order/service/ShopOrderFulfillmentManualShipTest.java
