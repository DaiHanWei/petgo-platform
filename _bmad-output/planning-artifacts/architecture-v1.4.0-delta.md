---
name: 'TailTopia V1.4.0 架构 Delta'
type: architecture-spine
purpose: build-substrate
altitude: feature
paradigm: '模块化单体 + 分层（继承 V1.0 基线，本版本不改变形态）'
scope: 'V1.4.0 增量：精选自营电商最小闭环（商品/库存/购物车/地址/订单/履约/退货/评价）+ 复购引擎（FR-107/FR-109）+ 混合支付模型扩展'
status: draft
created: '2026-08-15'
updated: '2026-08-15'
docType: 'architecture-delta'
baseline:
  - _bmad-output/planning-artifacts/architecture.md              # V1.0 冻结基线
  - _bmad-output/planning-artifacts/architecture-v1.1-delta.md   # V1.1 已完成
  - _bmad-output/planning-artifacts/architecture-v1.1.2-delta.md # V1.1.2
sources:
  - _bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0.md
  - _bmad-output/planning-artifacts/v1.4.0/PRD-v1.4.0-后台.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md
  - _bmad-output/planning-artifacts/v1.4.0/README.md
companions:
  - _bmad-output/implementation-artifacts/v1.4.0/PARALLEL-DEV-CONTRACT.md
flywayBaseline: 'V100'
flywayRange: 'V101–V139（独占号段，见 PARALLEL-DEV-CONTRACT.md §1，待三人签字）'
---

# TailTopia V1.4.0 架构决策文档 —— Delta

> **形态说明**：本文件是 brownfield **增量 delta**，只写 V1.4.0 的**新增 / 取代 / 补齐**；未提及处一律**继承基线**。冲突序：**本 delta > V1.1.2 delta > V1.1 delta > V1.0 基线**。正确性以 `v1.4.0/PRD-v1.4.0.md` 为准。
>
> **底线继承（不重述、全部延续）**：Spring Boot 4 / Java 21 / PostgreSQL + Redis + Flyway；模块化单体；异步只用 `@Async` + DB 状态机、`@Scheduled` 定时；**禁 MQ / 禁调度中间件 / 禁分布式锁 / 禁通用缓存层**；DB snake_case ↔ Java/Dart camelCase ↔ JSON camelCase；RFC 9457 ProblemDetail；对外标识不可枚举 token；env 注入凭证不入库；日志禁 PII / 健康 / 令牌 / 签名 URL；`ddl-auto=validate`，schema 归 Flyway。
>
> **运维 envelope 零增量**：不引入任何新中间件、不新增外部依赖、不改变部署形态（德国单机）。**唯一的外部新依赖是承运商商务账号（DEP-5），属商务动作而非技术组件**——本版本的物流是「后台手工填运单号 + 外链查询」，不接承运商 API（FR-103）。
>
> ⚠️ **本版本是三人并行**。所有涉及共享物（Flyway 号 / 共享枚举 / `OrderCenterService` / App 共享壳）的决策，其**执行纪律**写在 `implementation-artifacts/v1.4.0/PARALLEL-DEV-CONTRACT.md`，本文件只写**技术裁定**。两份文件配套使用。

---

## §0 决策日志

| # | 决策 | 结论 | 来源 |
|---|------|------|------|
| **AD-1** | 混合支付模型 | **扩展既有 `PaymentIntent`**，`PayChannel` 末尾追加 `MIXED`，加 `coin_amount`/`cash_amount`/`coin_ratio` 三列（nullable）。不自建拆分表、不起两笔 intent | 产品 2026-08-15 拍板（`decision-log` **C-10**） |
| **AD-2** | 按比例退款的算术 | 🔴 **`coin_ratio` 只作展示与审计冗余，不参与计算。** 拆分用 `coin_amount`/`amount` 的**整数运算 + 累计法**，保证多次部分退款后全额退款恰好归零 | 架构裁定（**细化 C-10**，见 §2.2） |
| **AD-3** | `MIXED` 的 CHECK 放宽范围 | **只放宽 `ck_payment_intents_channel` 一处**，另外三张虚拟商品表的 CHECK **刻意保持不放宽**，作纵深防御 | 架构裁定（据代码核实） |
| **AD-4** | 电商模块的位置与命名 | 新建**单一模块 `shop/`**，与 `order/` 平级；`order/` 保持为跨类型订单中心聚合层，不承载电商业务逻辑 | 架构裁定 |
| **AD-5** | 退款单建模 | **退款单独立于订单状态机**，承载 1..N 个订单行；订单主状态仅在「全部行退款完成」时回写 `已退款` | 产品 2026-08-15 拍板（**C-12**） |
| **AD-6** | 库存并发扣减 | **纯 DB 条件原子写**（`UPDATE ... WHERE ... AND available >= qty` 判影响行数），禁分布式锁、禁 Redis 扣减 | 继承 CLAUDE.md 护栏 + V1.0 决策 **F11** 范式 |
| **AD-7** | 订单号与对外标识 | 复用 `CardTokenGenerator` 范式（`SecureRandom` + Base62 22 位）。**对用户展示的订单号即该 token，不是自增 id 也不是可推算的日期序列号** | 继承 CLAUDE.md 护栏 + PRD FR-102 |
| **AD-8** | 待支付超时（60 min） | 复用 `payment_intents.expires_at`（V85 已有）+ `@Scheduled` 扫描置 `EXPIRED` 的既有范式；**库存释放挂在同一次状态迁移的事务内** | 架构裁定（据代码核实） |
| **AD-9** | PawCoin 扣减与退回 | 全量复用 `PawCoinWalletService.debit/credit`（自带幂等键 + Redis 前置 + DB 兜底）。**`PawCoinTxnType` 四值够用，本版本不加值** | 架构裁定（据代码核实） |
| **AD-10** | 退款真钱出金渠道 | 全量复用 `pay/refund` 模块的 `PayoutChannel`——`BCA(0)` / `OVO(2500)` / `GOPAY(2500)` **与 PRD FR-105 的费率表逐字一致，零改动** | 架构裁定（据代码核实） |
| **AD-11** | `OrderCenterService` 接入 | `OrderType` 末尾追加 `ECOMMERCE`，聚合器**在 if 链末尾追加第 4 个分支 + 独立映射方法**，既有三分支一行不改 | 架构裁定 + 并行契约 §3 |
| **AD-12** | 复购引擎的触发时机 | FR-109 用 **`@Scheduled` 日扫 + 落库触发记录**，不做实时计算；**不建提醒引擎**，复用既有通知通道 | 架构裁定（守「不新建提醒引擎」护栏） |
| **AD-13** | 地址与物流的 PII 边界 | 收货地址属 PII，**订单上存地址快照而非外键**（履约凭证与地址簿解耦）；⚠️ 注销级联处置**待 OQ-41 拍板**，本版本先按「可级联删除」建模留出口 | 架构裁定 · 待 **OQ-41** 收口 |

---

## §1 代码现状核实 —— 与 PRD 工程假设的偏差

> PRD §8A 已登记 L-8 ~ L-12 五条。本节只记**在架构落笔阶段新查出、PRD 尚未记载**的三条。

### 1.1 `PayChannel` 被 **4 个实体**共用，不是 `PaymentIntent` 独有

PRD §8B 与 `decision-log` L-8 都把 `PayChannel` 描述为 `PaymentIntent` 的字段。实测它被四处映射：

| 实体 | 表.列 | CHECK 约束 |
|---|---|---|
| `pay/domain/PaymentIntent.channel` | `payment_intents.channel` | `ck_payment_intents_channel` |
| `consult/domain/ConsultOrder.payChannel` | `consult_orders.pay_channel` | `ck_consult_orders_channel` |
| `triage/domain/AiConsultOrder.payChannel` | `ai_consult_orders.pay_channel` | `ck_ai_consult_orders_channel` |
| `profile/domain/IdCardHdPurchase.payChannel` | `id_card_hd_purchases.pay_channel` | `ck_id_card_hd_purchases_channel` |

**后果：** 加 `MIXED` 使它在四个实体上都变得类型可表达，但只有电商订单会用。**处置见 AD-3。**

### 1.2 `NotificationType` 是 **19** 值不是 18；`OrderType` **根本不落库**

PRD §8B 写「`NotificationType`（18 值）」——以 `V97__union_notification_types_two_lines.sql` 的 CHECK 列表逐条数，实际 **19** 值。

更要紧的是 PRD 把 `OrderType` 与另两个并列为「落库为 varchar + CHECK」——**全仓无任何 `order_type` 列**，`OrderType` 位于 `order/dto/`，是纯 DTO 枚举。**改它只有编译期风险，没有运行期静默失效风险**，与另两个不同级。

### 1.3 「共享枚举重排」的事故已经发生过一次，且是**静默**的

`V97` 的文件头记录：`feat/content-moderation` 与 `v1.1-dev` 两条线各自 `DROP+ADD` 了 `ck_notifications_type`，合并后 V72 后跑、其列表不含审核线三值 → **审核通知整类失效**，两边各自测试全绿、合并无冲突、编译不报错。

**V1.4.0 要动 `PayChannel`，处境完全相同。** 纪律见并行契约 §2.3 规则 **E-2 / E-3**。

### 1.4 复购引擎的三个数据输入，两个不在（承接 PRD L-9 / L-10 / DEP-6，此处只记架构含义）

- **L-9 体重字段不存在** → 本版本 story 内补（`pet_profiles` 加列），**可控**
- **L-10 到期间隔不存在** → C-11 拍板 FR-108 挪 1.2.0，**`health_records` 本版本零 schema 改动**
- **DEP-6 每日建议喂量数据未到位** → FR-109 的唯一计算依据，**不可控**

> 🔴 **架构含义：** FR-109 的实现必须做到「**数据未到位时静默不触发，而不是报错或猜测**」。这不是防御性编程的锦上添花——按当前 DEP-6 状态，**上线首日 FR-109 极可能对全体用户不触发**，该路径是常态而非异常，需当作一等路径来测。

---

## §2 Invariants & Rules

### AD-1 — 混合支付：扩展 `PaymentIntent`

**目标表：`payment_intents`（既有，V60 建，V85 加过 `expires_at`）**

```sql
-- 号段内分配，见 §4
ALTER TABLE payment_intents ADD COLUMN coin_amount BIGINT;
ALTER TABLE payment_intents ADD COLUMN cash_amount BIGINT;
ALTER TABLE payment_intents ADD COLUMN coin_ratio  NUMERIC(9,6);

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_channel;
ALTER TABLE payment_intents ADD  CONSTRAINT ck_payment_intents_channel
    CHECK (channel IN ('QRIS','PAWCOIN','MIXED'));

-- 三列要么全为 NULL（既有 4 个 purpose），要么全非 NULL 且自洽（MIXED）
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_mixed_shape CHECK (
    (channel <> 'MIXED' AND coin_amount IS NULL AND cash_amount IS NULL AND coin_ratio IS NULL)
 OR (channel  = 'MIXED' AND coin_amount IS NOT NULL AND cash_amount IS NOT NULL
     AND coin_amount >= 0 AND cash_amount >= 0 AND coin_amount + cash_amount = amount)
);
```

**Rules：**

1. 🔴 **`ck_payment_intents_mixed_shape` 是本次扩展的核心不变量。** `coin_amount + cash_amount = amount` 由 DB 强制，任何代码路径都无法写出金额不自洽的意图行
2. **既有 4 个 `PaymentPurpose`（`VET_CONSULT`/`PAWCOIN_TOPUP`/`AI_UNLOCK`/`ID_HD`）三列恒为 `NULL`，`channel` 仍取 `QRIS`|`PAWCOIN`，行为逐字节不变。** 迁移只加可空列 + 放宽一个 CHECK，**不回填、不改既有行**
3. 三个新列在 JPA 侧标 `updatable = false`（与既有 `amount`/`channel` 一致）——**下单时一次性固化，此后不可变**（PRD FR-100A 规则 2）
4. `MIXED` **只在 `PayChannel` 枚举末尾追加**，不重排既有两值（并行契约 E-1）
5. 加枚举值的 Java 提交与放宽 CHECK 的迁移**必须同 PR**（并行契约 E-4）

### AD-2 — 按比例退款：整数累计法，`coin_ratio` 不参与计算

> **这是对 C-10 的细化，不是改动。** C-10 说「退款在同一笔 intent 内按 `coin_ratio` 拆分」；本条定义**怎么算才不漂移**。

**问题：** IDR 无小数，全仓金额一律 `BIGINT` 最小币种单位。若每次部分退款都算 `round(退款额 × coin_ratio)`，多次部分退款的舍入误差会累积——**全额退完后 PawCoin 段可能多退或少退几盾**。少退是用户投诉，多退是 `FR-100A 规则 1`（防套现）的缺口。

**规则：**

```
输入：intent.amount(总额) · intent.coinAmount(Coin 段)
     refundedTotalBefore(此前已退总额) · refundedCoinBefore(此前已退 Coin 段)
     thisRefund(本次退款额)

cumulativeTotal = refundedTotalBefore + thisRefund
cumulativeCoin  = cumulativeTotal * intent.coinAmount / intent.amount   ← 整数除法，向下取整
thisCoin        = cumulativeCoin - refundedCoinBefore
thisCash        = thisRefund - thisCoin
```

1. 🔴 **按累计量取整，不按单次取整。** 这保证：`cumulativeTotal == amount` 时 `cumulativeCoin` 恰好 `== coinAmount`，**全额退款精确归零，零漂移**
2. **中间量用 `long`，不用 `double`/`float`/`BigDecimal` 做除法。** `cumulativeTotal * coinAmount` 的量级是 IDR 金额平方——客单约 285.000、上限 1.000.000，乘积远在 `long` 范围内，无溢出风险
3. **`thisCash` 由减法得出，不独立计算。** 保证 `thisCoin + thisCash == thisRefund` 恒成立，不需要额外校验
4. **`coin_ratio` 列只用于后台展示与对账审计**（AB-13D 需要一眼看出这单 Coin 占比），**任何资金计算路径都不得读它**。代码上以注释 + 单测钉死
5. `refundedTotal` / `refundedCoin` 是**订单侧的累计字段**，随每次退款执行在同一事务内递增；它们与退款单一起构成幂等依据

> **单测必须覆盖的三条：** ① 全额一次退 → Coin 段恰好等于 `coinAmount`；② 拆成 3 次不等额部分退至全额 → 累计 Coin 段恰好等于 `coinAmount`；③ `coinAmount = 1`、`amount = 300000` 的极端比例下不出现负数或超退

### AD-3 — `MIXED` 的 CHECK 只放宽一处

| 表 | 是否放宽 | 理由 |
|---|---|---|
| `payment_intents` | ✅ 放宽 | 电商订单的混合支付落于此 |
| `consult_orders` | ❌ **不放宽** | 兽医咨询恒单渠道 |
| `ai_consult_orders` | ❌ **不放宽** | AI 解锁恒单渠道 |
| `id_card_hd_purchases` | ❌ **不放宽** | 高清图恒单渠道 |

1. 🔴 **这是刻意的非对称，不是遗漏。** 任何人都不得以「统一口径」为由顺手放宽后三个——它们是纵深防御：`MIXED` 一旦被误写进虚拟商品表，DB 层当场拒绝，而不是静默落脏数据
2. 迁移文件头**必须写明这条非对称是刻意的**，防止后续维护者「修复不一致」
3. `triage_tasks.unlock_channel` 走独立的 `UnlockChannel` 枚举，**本次完全不涉及**

### AD-4 — 新建 `shop/` 模块，`order/` 保持聚合层

```
com.tailtopia.shop/          ← 新建，本版本全部电商业务逻辑
├── domain/       Product · Sku · Inventory · Cart · CartItem · ShippingAddress
│                 ShopOrder · ShopOrderLine · Shipment · ReturnRequest · ReturnLine · Review
├── dto/          ...
├── repository/   ...
├── service/      ProductService · InventoryService · CartService · CheckoutService
│                 ShopOrderService · FulfillmentService · ReturnService · ReviewService
├── repurchase/   FoodDepletionEstimator（FR-109）· ProfileRecommender（FR-107）
├── event/        ShopOrderPaidEvent · ReturnCompletedEvent
└── web/          ...

com.tailtopia.order/         ← 既有，本版本只追加一个分支
└── service/OrderCenterService.java   ← 275 行 fan-in 聚合器，纯追加（AD-11）
```

1. **`order/` 不承载电商业务逻辑。** 它当前的职责是「跨类型订单中心的只读聚合」，本版本维持该定位——电商订单的写路径全在 `shop/`
2. **`shop/` 不直接操作 `pawcoin_wallets` / `ledger_entries`。** 一律经 `PawCoinWalletService`（AD-9），保住既有的幂等与双分录不变量
3. 复购引擎放 `shop/repurchase/` 子包而非独立模块——它读档案与订单，**写路径只有「生成触发记录」**，不足以独立成模块

### AD-5 — 退款单独立于订单状态机

1. **`ReturnRequest` 承载 1..N 个 `ReturnLine`**（对应 `ShopOrderLine`），不是「一单一行」
2. 🔴 **部分退款完成时，订单主状态不变。** 只有「该订单全部行的退款均达终态 `已退款`」时，才由系统回写订单为 `已退款`
   > ⚠️ 后台 PRD 原写「退款执行状态需与订单状态联动」——**照字面实现会让退了一行的订单被整单标记为已退款**，连带毁掉 AB-13A 售后成本与 AB-13D 对账。该措辞已在后台 PRD 就地修正，此处再钉一次
3. **同一订单同时只允许一张进行中的 `ReturnRequest`**（状态 ∈ {待CS审核, 待用户寄回, 质检中, 退款执行中}）。用 **DB 部分唯一索引**强制，不靠应用层判断：
   ```sql
   CREATE UNIQUE INDEX uq_return_requests_active_per_order
       ON return_requests (shop_order_id)
       WHERE status IN ('PENDING_REVIEW','AWAIT_SHIPBACK','INSPECTING','REFUNDING');
   ```
   这条索引让并发申请在库级被拒，**不需要任何锁**（守禁分布式锁护栏）
4. **去程运费由 `is_full_return` 字段自动判定**（整单退全退 / 部分退不退），**不提供后台手工开关**——手工可调等于打开凑单-退货套利的口子（PRD FR-104A）
5. **换货零实现**（C-13）：不建换货状态、不建补发流程、不建换货出入库配对

### AD-6 — 库存并发：纯 DB 条件原子写

```sql
-- 锁定（提交订单）：影响 0 行 = 库存不足，直接失败，不重试不排队
UPDATE sku_inventory SET locked = locked + ?, available = available - ?
 WHERE sku_id = ? AND available >= ?;
```

1. 🔴 **禁分布式锁、禁 Redis 扣减、禁 MQ 削峰**（CLAUDE.md 护栏）。范式照 V1.0 决策 **F11**（兽医抢单）——条件更新 + 判影响行数
2. 库存状态机 `可售 → 锁定 → 已扣减`，**每次迁移都是一条条件 UPDATE**，不做读-改-写
3. **支付超时/取消的库存释放，与订单状态迁移在同一事务内**（AD-8）
4. 退货质检通过后的入库是**后台操作**，标记为退货入库批次，与采购入库区分（后台 AB-12B）

### AD-7 — 对外标识一律不可枚举 token

1. **订单号 = `SecureRandom` + Base62 22 位**，照 `profile/service/CardTokenGenerator` 范式（不是复用该类——它在 profile 模块，`shop/` 自建同范式的生成器或提升到 `shared/`）
2. 🔴 **用户看到的订单号就是这个 token。** 不得是自增 id、不得是 `日期+序列号`——后者同样可枚举（PRD FR-102 已就地修正）
3. 商品/SKU 的对外标识同理；**后台内部列表可用自增 id**，但任何下发到 App 或出现在 URL 的标识都必须是 token
4. `payment_intents.public_token` 既有机制不变

### AD-8 — 60 分钟支付窗复用既有过期机制

1. `payment_intents.expires_at`（V85 已有）在电商 purpose 下填 `now + 60min`
   > 注意既有注释写「仅 `PAWCOIN_TOPUP` 建单时填 now+60min；其余 purpose 留 null」——**本版本新增电商 purpose 也填**，需同步更新该注释
2. 复用既有的懒过期（查询时判定）+ `@Scheduled` 扫描置 `EXPIRED` 双路径，**不新建定时器**
3. 🔴 **置 `EXPIRED` 与释放库存必须同事务**，否则会出现「订单已失效但库存仍锁定」的悬挂
4. 超时前 10 分钟的提醒推送走既有通知通道（`NotificationType` 需追加值，**末尾追加**，并按并行契约 E-2 认领 `ck_notifications_type`）

### AD-9 — PawCoin 全量复用钱包服务

1. 扣减走 `PawCoinWalletService.debit(userId, coins, PawCoinTxnType.SPEND, refType, refId, idempotencyKey)`；退回走 `credit(..., PawCoinTxnType.REFUND, ...)`；两类溢价走 `credit(..., BONUS, ...)`
2. ✅ **`PawCoinTxnType` 的 `{TOPUP, SPEND, REFUND, BONUS}` 四值够用，本版本不加值** —— 少一个共享枚举要改
3. **幂等键格式** `shop-order:{orderToken}` / `shop-refund:{returnToken}`，照既有 `id-hd:{petProfileId}` 范式。既有 debit 已含 Redis 前置 + 跨 TTL 的 DB 兜底，**不要另造幂等机制**
4. 🔴 **两条溢价必须读两个独立配置项**（`decision-log` C-9 / D-8）：AB-6A 的「激励溢价」与本版本新增的「平台责任补偿溢价」。**写成单值是静默错误**——不报错，只是数字一直不对
5. **PawCoin 段退款只退 PawCoin，后台无例外入口**（FR-100A 规则 1，安全攸关）。这条在服务层就没有对应方法，而不是有方法但加了权限判断

### AD-10 — 出金渠道零改动复用

`pay/refund/domain/PayoutChannel` 实测为 `BCA(0)` / `OVO(2500)` / `GOPAY(2500)`，**与 PRD FR-105 的手续费表逐字一致**。本版本 **0 行改动**直接复用，包括 `pay/refund` 的 `RefundRequest` / `ApprovalStatus` / `RefundService` / `RefundAuditRecorder`。

> 电商退货的 `ReturnRequest`（实物流程：寄回/质检/入库）与 `pay/refund` 的 `RefundRequest`（资金流程：审批/出金/审计）是**两个不同的东西**，前者在质检通过后**驱动**后者。不要合并，也不要把实物字段塞进 `RefundRequest`。

### AD-11 — `OrderCenterService` 追加 `ECOMMERCE`

1. `OrderType` 末尾追加 `ECOMMERCE`（第 5 值）。**不落库**（§1.2），无需迁移
2. 聚合器**在 if 链末尾追加第 4 个分支**（现有 3 个：L77/L83/L89），配一个独立的 `private OrderSummaryView mapShop(...)`
3. 🔴 **既有三个分支、四个映射器、`parseType`、`parseCursor` 一行都不改。禁止顺手重构成 `switch` 或策略模式**（并行契约 O-2）——275 行、三线共享、无法拆分
4. **`ID_HD` 仍然没有分支是刻意的**（javadoc 明写「ID_HD 无源→空」），不要顺手补
5. 明细路由（L122/126/131）同样只追加，保留既有 `getUserId() == userId` 越权校验形状
6. 分页游标语义：既有实现是「各源多取 1 判 hasMore」后归并截断。电商源接入后**游标口径不变**

### AD-12 — 复购引擎：日扫 + 落库触发记录

1. **FR-109 用 `@Scheduled` 日扫**，为命中的用户落一条触发记录（含 `sku_id` / `预计耗尽日` / `trigger_type`），Toko 首页与推送都读这张表。**不做请求时实时计算**——实时算需要跨订单历史 + 商品喂量 + 档案体重三方 join，放在首页请求路径上不合适
2. 🔴 **不新建提醒引擎**（PRD §3.9 明写）。推送复用既有通道，只是多一个 `NotificationType` 值
3. **缺任一输入时静默不触发**（无购买历史 / 商品未配日喂量 / 档案无体重）。见 §1.4——**这是常态路径不是异常路径**，需按一等路径写测试
4. **FR-108 本版本不实现**（C-11）：`shop/repurchase/` 下不建健康记录触发相关的任何类，`health_records` 零 schema 改动
5. `trigger_type` 埋点枚举**保留三值** `DEWORM`/`VACCINE`/`FOOD_LOW`（供 1.2.0 直接追加数据、不断历史序列），但本版本只产生 `FOOD_LOW`
6. ⚠️ **FR-109 的公式有三处已知缺陷待补规格**（PRD SPEC-14）：漏乘购买数量、起算点用「上次购买日」而非收货日、多宠物共食未处理。**这三种情况输入全部齐全、只是算错，「缺输入不触发」的降级策略拦不住**——拆 story 时必须先闭合

### AD-13 — 地址与物流的 PII 边界

1. **订单上存地址快照（冗余列或 JSONB），不存地址簿外键。** 用户改地址簿不得改写历史订单的履约地址
2. 🔴 **日志禁记收货地址、收件人姓名、电话**（CLAUDE.md 护栏，与既有 PII 禁令同级）。物流单号非 PII，可记
3. 后台 AB-11A 的「按电话模糊搜全站订单」是**PII 检索面**，需权限位 + 访问审计——**具体口径待 OQ-41**
4. ⚠️ **注销级联处置待 OQ-41 拍板**（收货地址簿 / 电商订单 / 地址快照 / 物流 / 评价 / 退货申请 + 凭证图 各自的处置与留存年限）。本版本**先按「可级联删除」建模**：所有新表对 `users` 的外键留出 `ON DELETE` 语义的选择空间，不要在 story 阶段把处置写死
   > 这是 `CLAUDE.md` 认定的三类安全攸关节点之一（story 7.3 血统）。**OQ-41 未闭合前不得写注销级联的实现代码**，建模可以先行

---

## §3 不做什么（明确排除，防实现者临场发挥）

| 项 | 状态 | 依据 |
|---|---|---|
| **FR-108 健康记录触发复购** | ❌ 不做，挪 1.2.0 | C-11。连带：`health_records` 零改动、Diary 内嵌 CTA 不做、健康提醒附商品不做 |
| **换货** | ❌ 不做 | C-13。枚举收为 `可退`/`开封不退`/`不可退` |
| **承运商 API 实时轨迹与实时运费** | ❌ 不做 | PRD FR-103 / FR-99。后台手工填运单号 + 外链查询 |
| **全站商品搜索** | ❌ 不做 | PRD FR-93。SKU ≤ 30（C-7） |
| **优惠券 / 促销码 / 会员价** | ❌ 不做 | PRD §7 |
| **个性化推荐算法** | ❌ 不做 | FR-107 首版规则式。SKU 规模受控 |
| **兽医在问诊中推荐 SKU** | 🔴 **架构级禁止** | FR-110 约束性需求 + `decision-log` N-3。**不提供兽医端任何商品链接插入能力**——这是能力缺席，不是权限判断 |
| **任何新中间件** | 🔴 **禁止** | CLAUDE.md。异步只用 `@Async` + DB 状态机 |
| **用户侧票据 / e-Faktur / 税额行** | ⏳ **未决，不是排除** | **OQ-40**。DEP-3 已标高·阻塞上线。**不要默认「不做」也不要临场加**——等拍板 |

---

## §4 Flyway 迁移规划（号段 V101–V139）

> **号段归属见 `PARALLEL-DEV-CONTRACT.md` §1.2，待三人签字。** 下表是段内的规划顺序，实际号按 story 执行顺序在段内单调分配；**段内跳号合法**（仓库已有 V86 空洞为证）。

| 规划号 | 内容 | 触碰共享表 |
|---|---|---|
| V101 | `shop_products` + `shop_skus`（含 `退货规则标识` 三值 CHECK、`每日建议喂量` JSONB） | — |
| V102 | `sku_inventory`（available / locked，条件原子写的目标表） | — |
| V103 | `shipping_addresses`（PII） | — |
| V104 | `carts` + `cart_items` | — |
| V105 | 🔴 **`payment_intents` 加三列 + 放宽 `ck_payment_intents_channel` + 加 `ck_payment_intents_mixed_shape`** | ⚠️ **是**（AD-1/AD-3，须认领） |
| V106 | `shop_orders` + `shop_order_lines`（含地址快照、`refunded_total`/`refunded_coin` 累计列） | — |
| V107 | `shipments`（运单号 + 承运商，手工填） | — |
| V108 | `return_requests` + `return_lines` + **部分唯一索引** `uq_return_requests_active_per_order` | — |
| V109 | `shop_reviews` | — |
| V110 | 🔴 **`pet_profiles` 加体重列（+ 绝育状态）** —— 承接 **L-9** | ⚠️ **是**（共享表，须认领） |
| V111 | `repurchase_triggers`（FR-109 日扫落库） | — |
| V112 | 🔴 **`notifications` 的 `ck_notifications_type` 追加电商相关值** | ⚠️ **是**（事故 2 的同一个约束，**必须认领**，照 V97 写法取并集） |
| V113 | 平台配置追加（PawCoin 单笔上限 / 两条溢价比例与上限 / 免运费门槛 / 运费表） | ⚠️ 视 `platform_config`（V78）实现方式而定 |
| V114+ | 预留 | — |

**三个触碰共享表的迁移（V105 / V110 / V112）必须在群里认领后再写**，且文件头写明工作线与非对称理由（并行契约 F-4 / E-2）。

---

## §5 尚未闭合、会影响架构的开放项

| # | 问题 | 对架构的影响 | 处置 |
|---|---|---|---|
| **OQ-41** | 新增 PII 数据集的注销级联处置与留存年限；是否触及印尼 UU PDP | 决定所有新表的 `ON DELETE` 语义与后台检索面的审计要求 | 🔴 **安全攸关。** 建模先行、实现待拍板（AD-13） |
| **OQ-40** | PPN / 发票 / 税额行 | 决定订单金额明细是否需要税行、退款是否需要票据冲红 | 金额明细结构**留扩展位**，不要写死四行 |
| **OQ-37** | 用户能否选择「本单不使用 PawCoin」 | 影响 `CheckoutService` 的抵扣路径与 `MIXED` 的产生条件 | 服务层把「是否使用余额」做成**入参**而非硬编码，无论拍板结果都零返工 |
| **SPEC-14** | FR-109 公式三处错误 | 直接决定 FR-109 算得对不对 | 拆 story 前必须闭合 |
| **DEP-6** | 每日建议喂量数据 | FR-109 的唯一输入 | 🔴 商务动作，架构侧只能保证「无数据时静默不触发」 |
| **DEP-1** | Toko Tab 位序 | `AppTab` 4 值无空位，动它须三人同意 | 未拍板前**不碰** `bottom_tab_bar.dart`（并行契约 A-3） |
| — | 并行契约本身 | Flyway 号段 / 共享枚举认领 | 🔴 **三人签字后本 delta 的 §4 才可执行** |
