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

## Dev Notes

- **架构**：`architecture-v1.4.2-shop-admin-delta.md` AD-14
- **UI 屏**：`电商/ui-admin-1-4-2-shop-integrated-v1.html` H1~H7、H13；每屏 `frame-cap` 即实现说明
- **迁移**：V20261106_1035
- **代码入口**：`AdminShopOrderController`、`drawer-shop-order.html`、`BiteshipShipmentService`（`:144` 结果未知、`:311` CREATE 模式）、`ShopLogisticsExceptionService.requirePickupFailed/resolvePickupFailureBy*`、`ShopOrderFulfillmentService.markShippedWithoutWaybill/shipManualWithAudit`、`LiveBiteshipClient`、`BiteshipOrderSnapshot`
- **文案**：`shop-admin-文案三语表.md`
- **验证层级 L0 测试类**：`DrawerActionsResolverTest`

- Biteship 调用不在持锁事务内
- `courier.insurance.fee` 字段待沙箱确认；解析不到按 0，记 WARN

## Dev Agent Record

### Completion Notes

### File List
