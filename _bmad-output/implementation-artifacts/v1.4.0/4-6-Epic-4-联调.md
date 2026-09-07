# Story 4.6: Epic 4 联调

Status: **review**（L1 全绿；**L2 模拟器走通待本地人工**）

## 交付物

`petgo-backend/src/test/java/com/tailtopia/shop/Epic4ChainIntegrationTest.java`（7 例，L1）。

全程经真实 service，不用 JDBC 抄近路 —— 抄近路就测不到各段之间的接缝，而接缝正是本类要看的东西。

## AC 与验证

| AC | 层级 | 验证 |
|---|---|---|
| 后台发货 → App 显示单号可复制可跳转 → 用户确认收货 → COMPLETED | **L1** | `fullChainShipToCompleted`（含 `ShopOrderDetailView` 真下发 `trackingUrl`、发货通知落行） |
| 🔴 **单独验证 SPEC-2 三条出口各自都能让订单脱离 SHIPPED** | **L1** | `exitOneAdminMarkLeavesShipped` · `exitTwoUserConfirmLeavesShipped` · `exitThreeAutoTimeoutLeavesShipped` |
| 无死锁（最坏路径 D14 也能到终态） | **L1** | `noDeadlockOnTheWorstPath` |
| S-2 一单多包全链路 | **L1** | `multiPackageChain` |
| 未动 Epic 3 的边 | **L1** | `epic3EdgesUntouched` |
| L2 模拟器走通 | **L2** | ⏳ **待本地人工**（需 GUI） |

后端基线：`mvn -B test` → **1868 通过 / 0 失败 / 6 跳过**（Epic 4 前 1802）。

## 一处刻意的测试设计

**三条出口必须靠 `deliverySource` 区分，不能只看最终状态。**
三条边在代码里共用同一个 `markDelivered` —— 只断言 `status == DELIVERED` 的话，
验一条和验三条看起来一模一样，而 SPEC-2 说的恰恰是「只有一条出口就会死锁」。
所以每条出口的用例都额外断言 `deliverySource` 落在预期的那一个值上。
