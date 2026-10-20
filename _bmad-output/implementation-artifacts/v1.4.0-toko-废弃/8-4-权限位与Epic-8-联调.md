# Story 8.4: 模块 10–13 权限位与 Epic 8 联调

Status: **review**（L0 + L1 全绿）

## 交付物
`AdminPermissions` 新增 4 个权限码（49 → 53）：
`shop.order_view` · `shop.order_fulfill` · `shop.order_phone_search` · `shop.finance_view`。
每次新增都走全四个同步点：常量 → `GROUPS` → 三份 i18n → `AdminPermissionsTest.listStableSize`。
`Epic8ChainIntegrationTest`（7 例，L1）。

## AC 与验证

| AC | 层级 | 验证 |
|---|---|---|
| 🔴 模块 10–13 独立角色权限，不默认授予既有运营角色 | **L0** | `模块 10–13 的权限码一个都不在默认授予集合里（NFR-11）` |
| 🔴 进货价 / 毛利 / 对账单独权限位，默认仅财务与管理层 | **L0** | `毛利/库存周转/对账三页都要 shop.finance_view` |
| 进货价与毛利是两个不同的权限位 | **L0** | `看得到单个进货价 ≠ 看得到整盘生意` |
| 走一笔完整交易后三个看板数字互相对得上 | **L1** | `🔴🔴 一笔完整交易（下单 → 履约 → 部分退货）后，三个看板的数字互相对得上` |
| 销售额 − 退款 = 净额 | L1 | 同上（`netRevenue()`） |
| 库存变化与订单一致 | L1 | 同上 |
| 对账两段之和 = 订单实付 | L1 | 同上（`segmentsBalance()`） |

## 为什么「进货价」与「毛利」要拆成两个权限位
看得到单个 SKU 的进货价，和看得到整盘生意的毛利率，是**两种不同的敏感度**：
前者是采购同事的日常，后者是管理层的经营数字。合成一个权限位，
要么让采购看到全盘毛利，要么让财务看不到进货价 —— 两种都不对。

## 一处测试环境坑（已修，写在这里避免复现）
`AdminShopOrderExceptionIntegrationTest` 与 `RefundExecutionIntegrationTest` 会改
**共享的 `pawcoin_config`**（`premium_rate` / `compensation_premium_rate`）来验 C-9，
于是 `PlatformConfigIntegrationTest` 与 `MeRefundIntegrationTest` **只在全量跑时**红。
已加 `@AfterEach restoreSharedPawcoinConfig()` 三列复位。
🔴 **「单跑绿、全类跑红」就是共享状态没复位**，不要去改被污染的那一侧的断言。
