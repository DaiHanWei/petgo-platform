# Story 6.1：履约抽屉 Biteship 下单与失败处置

Status: ready-for-dev

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

- [ ] 对照当前代码核对下方引用的类名与方法，有出入先改本文件
- [ ] 后端：迁移（如有）、服务、Controller
- [ ] 页面与三语文案
- [ ] 测试：L0 单测跑绿，L1 测试写好能编译
- [ ] 审计与提醒逐项核对
- [ ] （核对补）`ShopOrderFulfillmentService.ship` 不清 `waybill_failed_at`，全仓只有运单建成时清。运单失败后转手工，标记会留着，订单一直挂在异常队列：手工运单成功时调 `clearWaybillFailure()`
- [ ] （核对补）`courier_code` 为空的存量待发货单走 Biteship 必 409（`requireWaybillData`「存量订单请走手工运单」），吸底判定函数要把这类单归到手工运单
- [ ] （核对补）放开已发货重提（O-18）要改三处：`resolvePickupFailureByRetry` 第 97 行的前置、第 112-114 行收尾校验、`BiteshipShipmentService.precheck` 与 `stillValid` 的 CREATE 分支
- [ ] （核对补）`BiteshipOrderSnapshot` 加保价费字段时同步构造调用方：`LiveBiteshipClient:442`、`StubBiteshipClient:91,146`、`BiteshipShipmentServiceTest:119`、`BiteshipTrackingServiceTest:80,310`
- [ ] （核对补）`shipped_by_admin_id`：`ShopOrderFulfillmentService.ship` 现在没有操作人入参，`markShipped` 也没有；一单多包时第二个包裹不改写

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

### File List
