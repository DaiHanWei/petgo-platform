# 充值二维码 60 分钟窗口 + 复用同一 QR — 实施规格

> 产出：2026-07-16 · 分支 `v1.1-dev` · 依据代码实读
> 状态：**待确认 1 个边界决策（§4），其余可直接实施**

---

## 1. 需求（用户 2026-07-16）

1. 充值 Coin 付款等待时间 = **60 分钟**（原无时间过期）
2. 60 分钟后订单变**过期**
3. 60 分钟内**重复打开 = 同一个二维码**，不重复申请新 QR、不重复下单

> 兽医问诊付款窗（接单后）已单独改为 **5 分钟**（`PAY_WINDOW_SECONDS=90→300` + 前端两处倒计时 + 测试断言），已 L0 绿，本文档不含。

## 2. 关键前提（已核实）

- **GemPay QR 有效期 = 65 分钟**（用户确认，GemPay 侧，不可调）。
- **60 < 65 → 无冲突**：60 分钟窗口内 GemPay 的码始终存活，「同一个 QR」严格成立；且我方窗口比 GemPay 码早死 5 分钟，**永不给用户一张 GemPay 即将失效的码**（安全余量）。

## 3. 现状（代码实读）

| 事实 | 位置 |
|---|---|
| **后端已支持同 QR 复用**——同 `Idempotency-Key` → `createIntent` 返回既有意图 → `gatewayRef!=null` 走幂等分支返回 `gateway_meta.payload` 里存的同一个 QR，不再调 GemPay | `PawCoinTopupService.create` |
| **「重复申请新 QR」根因 = 纯前端 bug**：每次提交生成新时间戳键 | `recharge_page.dart:75` `idemKey: 'topup-${微秒时间戳}'` |
| **完全无时间过期**：`payment_intents` 无 `expires_at`；意图变 EXPIRED 只靠 GemPay 回调/对账 | `PaymentIntent`（列：无 expires_at）；`PaymentIntentService.applyCallback` |
| 已有对账定时批 `reconcilePending`（轮询 GemPay 推进 PENDING） | `PaymentReconciliationService` |
| `payment_intents` 列 | `V60`：id/public_token/user_id/purpose/channel/amount/currency/status/gateway_ref/gateway_meta/version/created_at/updated_at |
| 复用范围键：purpose=`PAWCOIN_TOPUP` | `PaymentPurpose` |

## 4. 边界决策 ✅ D-b（用户 2026-07-16 拍板）

**60 分钟内有 PENDING 充值、用户改选不同档位（金额）时**：
- **同档位（同金额+同渠道）重开 → 复用同一个 QR**（不重复下单）
- **换档位 → 新建一笔**（旧的留到 60min 过期自然消亡）
- 允许同时存在多笔不同档位的 PENDING（各自独立 QR + 独立 60min 窗口）

> 复用匹配键 = `(userId, PAWCOIN_TOPUP, PENDING, 未过期, amount, channel)`。

## 5. 实施（按 D-a）

### 5.1 后端

| # | 改动 | 层级 |
|---|---|---|
| B1 | **`V85__add_payment_intents_expires_at.sql`**：`payment_intents` 加 `expires_at TIMESTAMPTZ`（**可空**——仅 topup 填，其余 purpose 留 null=无时间过期，不改既有行为） | L1 |
| B2 | `PaymentIntent` 加 `expiresAt` 字段 + `isExpiredAt(now)` 判定 + `markExpired` 保持（PENDING→EXPIRED） | L0 |
| B3 | 建 topup 意图时 `expiresAt = now + 60min`（`createIntent` 加可选 `Duration ttl` 参数，topup 传 60min，其余 purpose 传 null） | L0 |
| B4 | **复用同 QR（服务端权威，D-b）**：`PawCoinTopupService.create` 先查该用户 PENDING 未过期 **且同 amount+channel** 的 PAWCOIN_TOPUP 意图——有则返回它 + `gateway_meta.payload`（**不调 GemPay**），无则新建。换档位（amount/channel 不同）→ 无匹配 → 新建。查询用新方法 `findFirstByUserIdAndPurposeAndChannelAndAmountAndStatusAndExpiresAtAfter(...)` | L1 |
| B5 | **懒过期**：`statusOf` + B4 复用查询命中时，若 `now>expiresAt && PENDING` → `markExpired` 返回 EXPIRED（重开超 60min 即见「已过期」） | L0/L1 |
| B6 | **定时过期扫描**：`@Scheduled` 把 `expires_at<now` 的 PENDING topup 意图批量置 EXPIRED（复用/仿 `reconcilePending` 批范式，逐笔独立事务）。防过期意图滞留挡新充值 | L1 |
| B7 | `TopupResponse` + `TopupStatusView` 加 `expiresAt`（服务端权威，前端倒计时/过期判定据此，不靠客户端计时） | L0 |

### 5.2 前端

| # | 改动 | 层级 |
|---|---|---|
| F1 | `recharge_page.dart:75` 停用「每次新时间戳键」。改为**服务端权威复用**：POST 后端按 pending 查回同一笔，前端渲染返回的 QR + 用 `expiresAt` 起 60min 倒计时（非客户端自计 90s 类） | L0 |
| F2 | 充值页进入时若已有 pending 充值 → 直接恢复该 QR + 剩余倒计时（不重新下单）。`status=EXPIRED` → 过期态 UI（可重新发起） | L0 + L2 |
| F3 | i18n：过期态文案（印尼语主 + en），`flutter gen-l10n` + `microcopy_rules_test` | L0 |

### 5.3 不做

VET_CONSULT/AI_UNLOCK/ID_HD 的 QRIS 付款窗（本次仅 PAWCOIN_TOPUP；兽医问诊付款窗是 consult_requests 的 5min，已单独改）；GemPay 传 expiry 参数（GemPay 不可调，且 65>60 无需）；取消充值入口（除非选 D-b 外的换档位需求）。

## 6. 验证

| 层 | 内容 | 环境 |
|---|---|---|
| **L0** | `mvn compile/test-compile`；`flutter analyze` 零警告 + `flutter test`（含 topup 复用/过期 model 测试 + microcopy） | 云端可跑 |
| **L1** | V85 迁移 + `validate` 过；同用户重复 POST topup 返回**同一 intent + 同一 payload**（不新增行/不重复 charge）；`now>expires_at` 懒过期 + 定时扫描置 EXPIRED；60min 边界 | 本地 scratch 库（先 flush Redis DB0） |
| **L2** | 真机：发起充值→拿 QR→退出重进→**同一个 QR + 倒计时接续**（非重置/新码）；等过 60min（或造数据）→重开见「已过期」 | 本地 Android 模拟器 + stag（GemPay sandbox） |

### 云端执行须知
- 云端只到 L0 绿灯，Completion Notes 标 L1/L2 待本地。
- 改迁移后 `test-compile` 重拷资源。
- Flyway **V85** 按当前 v1.1-dev 最大号（V84）顺延；并行工作线开工前重核号。

## 7. 影响面
- 服务端权威复用改变 POST topup 语义（同用户 60min 内幂等返回既有）——需确认无其他调用方依赖「每次 POST 建新意图」。
- 定时扫描是新 `@Scheduled`——遵守护栏「异步只用 @Async/@Scheduled + DB 状态机，不引 MQ」。
