---
docType: 'architecture-input-extract'
theme: 'shop-v2（电商板块 V2，v1.3.0）'
purpose: '为 shop-v2 架构 delta 提供「已存在、必须尊重或扩展」的事实底稿'
created: '2026-09-15'
status: 'extract-only（只抽取事实，零设计建议）'
sources:
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-delta.md
  - origin/feat/1.3.0-ops-ui-refactor:_bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
  - _bmad-output/planning-artifacts/v1.4.0/decision-log.md
  - petgo-backend 源码 / db/migration（现场 grep 核实）
---

# shop-v2 架构 delta 输入事实抽取

> 本文件**只记录既有事实**，不含任何新设计、建议或裁定。所有引用给出原文件路径与行号/编号。
> 冲突序（沿用两份 delta 自述）：**admin delta > v1.4.0 delta > v1.1.6 delta > … > V1.0 基线**；跨 story 契约冲突以 `CROSS-STORY-DECISIONS.md` 为准。

---

## A. 电商一期已有架构（source 1：`architecture-v1.4.0-delta.md`）

### A.1 底线继承（delta 开篇「底线继承」段，不重述即延续）

Spring Boot 4 / Java 21 / PostgreSQL + Redis + Flyway；模块化单体；**异步只用 `@Async` + DB 状态机、`@Scheduled` 定时；禁 MQ / 禁调度中间件 / 禁分布式锁 / 禁通用缓存层**；DB snake_case ↔ Java/Dart camelCase ↔ JSON camelCase；RFC 9457 ProblemDetail；对外标识不可枚举 token；env 注入凭证不入库；日志禁 PII / 健康 / 令牌 / 签名 URL；`ddl-auto=validate`，schema 归 Flyway。

「**运维 envelope 零增量**：不引入任何新中间件、不新增外部依赖、不改变部署形态（德国单机）」；物流为「后台手工填运单号 + 外链查询」，**不接承运商 API**（FR-103）。

### A.2 分层与包结构约定（AD-4，delta §2）

```
com.tailtopia.shop/          ← 新建，本版本全部电商业务逻辑
├── domain/       Product · Sku · Inventory · Cart · CartItem · ShippingAddress
│                 ShopOrder · ShopOrderLine · Shipment · ReturnRequest · ReturnLine · Review
├── dto/ · repository/
├── service/      ProductService · InventoryService · CartService · CheckoutService
│                 ShopOrderService · FulfillmentService · ReturnService · ReviewService
├── repurchase/   FoodDepletionEstimator（FR-109）· ProfileRecommender（FR-107）
├── event/        ShopOrderPaidEvent · ReturnCompletedEvent
└── web/
com.tailtopia.order/         ← 既有，只追加一个分支
└── service/OrderCenterService.java   ← 275 行 fan-in 聚合器，纯追加（AD-11）
```

规则原文：① `order/` **不承载电商业务逻辑**，维持「跨类型订单中心的只读聚合」定位，电商写路径全在 `shop/`；② `shop/` **不直接操作 `pawcoin_wallets` / `ledger_entries`**，一律经 `PawCoinWalletService`（AD-9）；③ 复购引擎放 `shop/repurchase/` 子包而非独立模块。

> ⚠️ **实测偏差（代码核实，2026-09-15）**：落地后的 `shop/` 是**按子域再分层**，不是 AD-4 图示的扁平四层。实际子包：`shop/{address,cart,order,returns,review,repurchase}/{domain,dto,repository,service,web}` + 顶层 `shop/{domain,dto,repository}`（商品/SKU/库存/Banner 在顶层）。服务类实名与 AD-4 规划名亦不同（见 D 节）。

### A.3 shop 子域的模块划分（实测，见 D 节明细）

顶层：`ShopProduct` / `ShopSku` / `SkuInventory` / `InventoryMovement` / `ShopBanner` / `ProductCategory` / `Species` / `AgeStage` / `BodySize` / `FeedingGuideEntry` / `ReturnPolicy` / `StockStatus`。
子域：`address/`（`ShippingAddress` · `IndonesiaPhone` · `AddressFields`）、`cart/`（`ShopCart` · `ShopCartItem`）、`order/`（`ShopOrder` · `ShopOrderLine` · `Shipment` · `AddressSnapshot` · `PaymentSplit` · `Carrier` · `CompletionSource` · `DeliverySource` · `ShopPawcoinRules`）、`returns/`（`ReturnRequest` · `ReturnLine` · `RefundSplit` · `OpenedPrecedent` · `RejectDisposal` · `CashDestination` · `ShippingFeeBearer` · `ReturnType`）、`review/`（`ShopReview`）、`repurchase/`（`RepurchaseTrigger` · `DepletionForecast` · `ProfileFacts`）。
admin 侧切片：`com.tailtopia.admin.shop/{dto,service,web}`（12 个 Controller/Service，见 B.4）。

### A.4 支付 / 订单 / 退货 / 库存的状态机与关键裁定

**支付（AD-1 / AD-2 / AD-3）**
- 扩展既有 `payment_intents`（V60 建、V85 加 `expires_at`）：加 `coin_amount BIGINT` / `cash_amount BIGINT` / `coin_ratio NUMERIC(9,6)` 三列（nullable），`PayChannel` 末尾追加 `MIXED`。
- 核心不变量 `ck_payment_intents_mixed_shape`：三列要么全 NULL（既有 4 个 purpose），要么全非 NULL 且 `coin_amount + cash_amount = amount`，由 DB 强制。
- 三列 JPA 标 `updatable = false`，下单时一次性固化。
- **AD-3 刻意非对称**：只放宽 `ck_payment_intents_channel`；`consult_orders` / `ai_consult_orders` / `id_card_hd_purchases` 三处 CHECK **刻意不放宽**作纵深防御，「任何人都不得以『统一口径』为由顺手放宽后三个」。
- **AD-2 退款拆分算法**（整数累计法，`coin_ratio` 不参与计算）：
  ```
  cumulativeTotal = refundedTotalBefore + thisRefund
  cumulativeCoin  = cumulativeTotal * intent.coinAmount / intent.amount   ← 整数除法向下取整
  thisCoin        = cumulativeCoin - refundedCoinBefore
  thisCash        = thisRefund - thisCoin
  ```
  中间量用 `long`，禁 `double/float/BigDecimal` 做除法；`thisCash` 由减法得出；`coin_ratio` **仅供后台展示与对账审计，任何资金计算路径不得读它**；`refundedTotal`/`refundedCoin` 是订单侧累计字段，与退款单共同构成幂等依据。

**订单号与对外标识（AD-7）**
- 订单号 = `SecureRandom` + Base62 22 位，照 `profile/service/CardTokenGenerator` 范式（**不复用该类**，`shop/` 自建同范式或提升 `shared/`）。
- 🔴「用户看到的订单号就是这个 token。不得是自增 id、不得是 `日期+序列号`——后者同样可枚举」。
- 商品/SKU 对外标识同理；**后台内部列表可用自增 id**，但下发 App 或出现在 URL 的标识必须是 token。`payment_intents.public_token` 机制不变。
- ⚠️ 实测与此条存在张力：`OrderDisplayNo` 为「前缀 + 自增 id + 创建时间」的可推算展示号（见 D.3）。

**待支付超时（AD-8）**
- 复用 `payment_intents.expires_at`（V85），电商 purpose 填 `now + 60min`；复用「懒过期（查询时判定）+ `@Scheduled` 扫描置 `EXPIRED`」双路径，**不新建定时器**。
- 🔴 **置 `EXPIRED` 与释放库存必须同事务**，否则「订单已失效但库存仍锁定」悬挂。
- 超时前 10 分钟提醒推送走既有通知通道，`NotificationType` **末尾追加**并按并行契约 E-2 认领 `ck_notifications_type`。

**库存（AD-6）**
- 纯 DB 条件原子写：
  ```sql
  UPDATE sku_inventory SET locked = locked + ?, available = available - ?
   WHERE sku_id = ? AND available >= ?;
  ```
  影响 0 行 = 库存不足，直接失败，**不重试不排队**。
- 状态机 `可售 → 锁定 → 已扣减`，每次迁移都是一条条件 UPDATE，**不做读-改-写**；🔴 禁分布式锁、禁 Redis 扣减、禁 MQ 削峰（范式照 V1.0 决策 **F11** 兽医抢单）。
- 支付超时/取消的库存释放与订单状态迁移同事务；退货质检通过后入库是后台操作，标记「退货入库批次」，与采购入库区分。

**退货 / 退款（AD-5 / AD-10）**
- `ReturnRequest` 承载 1..N 个 `ReturnLine`（对应 `ShopOrderLine`），不是一单一行。
- 🔴 **部分退款完成时订单主状态不变**，只有「全部行退款均达终态」才回写订单 `已退款`。delta 明写后台 PRD 原措辞「退款执行状态需与订单状态联动」照字面实现会毁掉 AB-13A / AB-13D，已就地修正并「此处再钉一次」。
- 同一订单同时只允许一张进行中 `ReturnRequest`，用 **DB 部分唯一索引**强制（不靠应用层）：
  ```sql
  CREATE UNIQUE INDEX uq_return_requests_active_per_order
      ON return_requests (shop_order_id)
      WHERE status IN ('PENDING_REVIEW','AWAIT_SHIPBACK','INSPECTING','REFUNDING');
  ```
- 去程运费由 `is_full_return` 字段自动判定（整单退全退 / 部分退不退），**不提供后台手工开关**（手工可调 = 凑单-退货套利口子，FR-104A）。
- **换货零实现**（C-13）：不建换货状态、不建补发流程、不建换货出入库配对。
- 出金渠道 `pay/refund/domain/PayoutChannel` = `BCA(0)` / `OVO(2500)` / `GOPAY(2500)`，与 FR-105 费率表逐字一致，**0 行改动复用**，含 `RefundRequest` / `ApprovalStatus` / `RefundService` / `RefundAuditRecorder`。
- 🔴 电商 `ReturnRequest`（实物流程）与 `pay/refund` 的 `RefundRequest`（资金流程）是**两个不同的东西**，前者质检通过后**驱动**后者，「不要合并，也不要把实物字段塞进 `RefundRequest`」。

**PawCoin（AD-9）**
- 扣减 `PawCoinWalletService.debit(userId, coins, PawCoinTxnType.SPEND, refType, refId, idempotencyKey)`；退回 `credit(..., REFUND, ...)`；两类溢价 `credit(..., BONUS, ...)`。
- `PawCoinTxnType` 四值 `{TOPUP, SPEND, REFUND, BONUS}` 够用，**本版本不加值**。
- 幂等键 `shop-order:{orderToken}` / `shop-refund:{returnToken}`，照既有 `id-hd:{petProfileId}`；**不要另造幂等机制**。
- 🔴 **两条溢价读两个独立配置项**（C-9 / D-8：AB-6A 激励溢价 vs 平台责任补偿溢价），写成单值是静默错误。
- 🔴 **PawCoin 段退款只退 PawCoin，后台无例外入口**（FR-100A 规则 1，安全攸关）——「在服务层就没有对应方法，而不是有方法但加了权限判断」。

**订单中心接入（AD-11）**
- `OrderType` 末尾追加 `ECOMMERCE`（第 5 值），**不落库**（全仓无 `order_type` 列），无需迁移。
- 聚合器 if 链**末尾追加第 4 个分支** + 独立 `private OrderSummaryView mapShop(...)`；🔴「既有三个分支、四个映射器、`parseType`、`parseCursor` 一行都不改。禁止顺手重构成 `switch` 或策略模式」。
- `ID_HD` 仍无分支是刻意的；明细路由只追加，保留 `getUserId() == userId` 越权校验形状；分页游标口径不变。

**复购引擎（AD-12）**
- `@Scheduled` 日扫 + 落库触发记录（含 `sku_id` / 预计耗尽日 / `trigger_type`），Toko 首页与推送都读这张表，**不做请求时实时计算**。
- 🔴 **不新建提醒引擎**；缺任一输入时**静默不触发**——delta §1.4 明写「这是常态路径不是异常路径，需按一等路径写测试」。
- FR-108 本版本不实现（C-11），`health_records` 零 schema 改动。
- `trigger_type` 保留三值 `DEWORM`/`VACCINE`/`FOOD_LOW`，本版本只产生 `FOOD_LOW`。

**地址与 PII（AD-13）**
- **订单上存地址快照（冗余列或 JSONB），不存地址簿外键**；用户改地址簿不得改写历史订单履约地址。
- 🔴 **日志禁记收货地址、收件人姓名、电话**（与既有 PII 禁令同级）；物流单号非 PII，可记。
- 后台 AB-11A「按电话模糊搜全站订单」是 **PII 检索面**，需权限位 + 访问审计，口径待 OQ-41。

### A.5 已登记的表与命名（delta §4 规划 vs 实测）

delta §4 原规划号段 **V101–V139**（含 V101 `shop_products`+`shop_skus`、V102 `sku_inventory`、V103 `shipping_addresses`、V104 `carts`+`cart_items`、V105 `payment_intents` 三列、V106 `shop_orders`+`shop_order_lines`、V107 `shipments`、V108 `return_requests`+`return_lines`+部分唯一索引、V109 `shop_reviews`、V110 `pet_profiles` 体重列、V111 `repurchase_triggers`、V112 `ck_notifications_type` 追加、V113 平台配置追加）。

> ⚠️ **该号段已作废**：实际落地全部走 E7 时间戳制。树内实存（`petgo-backend/src/main/resources/db/migration/`）：
> `V20260817_1154__init_shop_products_and_skus.sql` · `V20260817_1220__init_sku_inventory.sql` · `V20260817_1246__add_shop_sku_cost_price.sql` · `V20260817_1852__init_inventory_movements.sql` · `V20260817_2240__init_shipping_addresses.sql` · `V20260817_2245__init_shipping_zones.sql` · `V20260817_2259__init_shop_carts.sql` · `V20260817_2308__init_shop_orders.sql` · `V20260817_2313__extend_payment_intents_mixed.sql` · `V20260818_0037__add_shop_order_payment_window.sql` · `V20260818_0102__add_cart_item_attribution.sql` · `V20260818_0357__init_shipments.sql` · `V20260818_0358__add_shop_order_shipped_notification_type.sql` · `V20260818_0359__add_shop_order_exception_notification_type.sql` · `V20260818_0427__init_return_requests.sql` · `V20260818_0441__add_shop_return_notification_type.sql` · `V20260818_0518__init_repurchase_triggers.sql` · `V20260818_0553__init_shop_reviews.sql` · `V20260827_1400__add_shop_product_main_image_size.sql` · `V20260827_1500__init_shop_banner.sql` · `V20260902_1610__add_version_to_shop_orders.sql`

### A.6 §3「不做什么」清单（防实现者临场发挥）

| 项 | 状态 | 依据 |
|---|---|---|
| FR-108 健康记录触发复购 | ❌ 不做，挪 1.2.0 | C-11 |
| 换货 | ❌ 不做 | C-13，枚举收为 `可退`/`开封不退`/`不可退` |
| 承运商 API 实时轨迹与实时运费 | ❌ 不做 | FR-103 / FR-99 |
| 全站商品搜索 | ✅ **做**（2026-08-31 反转） | C-18，挂既有列表接口 `?q=`，搜 name+brand，与 category 组合 |
| 优惠券 / 促销码 / 会员价 | ❌ 不做 | PRD §7 |
| 个性化推荐算法 | ❌ 不做 | FR-107 首版规则式 |
| 兽医在问诊中推荐 SKU | 🔴 **架构级禁止** | FR-110 + N-3，**不提供兽医端任何商品链接插入能力（能力缺席，不是权限判断）** |
| 任何新中间件 | 🔴 禁止 | CLAUDE.md |
| 用户侧票据 / e-Faktur / 税额行 | ⏳ **未决，不是排除** | OQ-40 |

### A.7 §5 开放项（OQ-n / SPEC-n / DEP-n）原表

| # | 问题 | 对架构的影响 | 处置 |
|---|---|---|---|
| **OQ-41** | 新增 PII 数据集的注销级联处置与留存年限；是否触及印尼 UU PDP | 决定所有新表的 `ON DELETE` 语义与后台检索面的审计要求 | 🔴 **安全攸关。** 建模先行、实现待拍板（AD-13） |
| **OQ-40** | PPN / 发票 / 税额行 | 决定订单金额明细是否需要税行、退款是否需要票据冲红 | 金额明细结构**留扩展位**，不要写死四行 |
| **OQ-37** | 用户能否选择「本单不使用 PawCoin」 | 影响 `CheckoutService` 抵扣路径与 `MIXED` 产生条件 | 服务层把「是否使用余额」做成**入参**而非硬编码 |
| **SPEC-14** | FR-109 公式三处错误 | 直接决定 FR-109 算得对不对 | 拆 story 前必须闭合 |
| **DEP-6** | 每日建议喂量数据 | FR-109 唯一输入 | 🔴 商务动作（**已于 2026-08-17 解除**，见 C/decision-log） |
| **DEP-1** | Toko Tab 位序 | `AppTab` 4 值无空位，动它须三人同意 | 未拍板前**不碰** `bottom_tab_bar.dart` |
| — | 并行契约本身 | Flyway 号段 / 共享枚举认领 | 🔴 三人签字后 §4 才可执行 |

**OQ-40 原文**（`PRD-v1.4.0.md:836`）：
> **PPN 与发票**：零售价是否含税？金额明细是否单列税额行？是否向用户提供收据/e-Faktur？ | 无设计侧倾向。⚠️ DEP-3 把「PPN 登记与开票」标为**高·阻塞上线**，但 FR-97 的金额明细只有四行**没有税行**，全文无任何用户侧票据能力，§7 **也没有排除它**——它只是没写。退款/部分退款后的票据冲红同样空白 | Finance + Legal · 关联 DEP-3 / DEP-8

**OQ-41 原文**（`PRD-v1.4.0.md:837`）：
> 新增数据的**注销级联处置**与留存年限（收货地址簿 / 电商订单 / 地址快照 / 物流 / 评价 / 退货申请 + 凭证图） | 无设计侧倾向。存在真实冲突：**已完成订单需留存对账 vs 用户删除权**；且须先定性 FR-98 地址簿是「履约凭证」还是「个人数据」——两种定性导出相反的处置。另需确认本版本首次建立的 PII 数据集是否触及印尼 **UU PDP**（DEP-1~8 无一条覆盖），以及 AB-11A「可按电话模糊搜全站订单」的后台列表是否需要脱敏/访问审计 | Legal · **安全攸关**（`CLAUDE.md` 三类节点之一）

**AD-13 第 4 条原文**（delta:272-273）：
> ⚠️ **注销级联处置待 OQ-41 拍板**（收货地址簿 / 电商订单 / 地址快照 / 物流 / 评价 / 退货申请 + 凭证图 各自的处置与留存年限）。本版本**先按「可级联删除」建模**：所有新表对 `users` 的外键留出 `ON DELETE` 语义的选择空间，不要在 story 阶段把处置写死
> > 这是 `CLAUDE.md` 认定的三类安全攸关节点之一（story 7.3 血统）。**OQ-41 未闭合前不得写注销级联的实现代码**，建模可以先行

**DEP-8 原文**（`decision-log.md:247`）：
> | **DEP-8** | PawCoin 支付实物的税务与收入确认口径：PPN 开票按零售价还是实付真钱；PawCoin 抵扣的运费如何入账 | Joko | AB-13D 对账口径 |

### A.8 §1「代码现状核实」三条偏差（仍然有效的警示）

1. **`PayChannel` 被 4 个实体共用**：`PaymentIntent.channel`(`ck_payment_intents_channel`) / `ConsultOrder.payChannel`(`ck_consult_orders_channel`) / `AiConsultOrder.payChannel`(`ck_ai_consult_orders_channel`) / `IdCardHdPurchase.payChannel`(`ck_id_card_hd_purchases_channel`)。
2. **`NotificationType` 是 19 值不是 18**（按 `V97__union_notification_types_two_lines.sql` 的 CHECK 列表逐条数）；**`OrderType` 根本不落库**，改它只有编译期风险。
3. **「共享枚举重排」事故已发生过一次且是静默的**：`feat/content-moderation` 与 `v1.1-dev` 各自 `DROP+ADD` 了 `ck_notifications_type`，合并后列表不含审核线三值 → 审核通知整类失效，两边测试全绿、合并无冲突、编译不报错。

---

## B. admin 主题 delta 的约束（source 2：`architecture-v1.3.0-admin-delta.md`，ops 分支）

### B.1 五套模板（模板 A/B/C/D/E）规范

| 模板 | 形态（PRD-v1.3.0-admin §5②原表） | 适用 |
|---|---|---|
| **A · 处置工作台** | 双栏：左=队列列表（紧凑行/状态色点），右=选中项完整详情+处置操作区；顶部队列内筛选+计数；**处置完成自动选中下一条**；**全组不做键盘 ↑/↓ 手动切换**（D-14） | 待办中心组 6 页；**Toko 退货、Toko 异常订单同样沿用本模板，但导航归属商城组**（2026-09-08 修订） |
| **B · 管理列表** | 顶部筛选栏 → **统计摘要条**（3~5 个关键数字）→ 表格 → 点行**右侧滑出详情抽屉**；既有独立浅详情页并入抽屉 | ≈20+ 页，含商品、Banner、库存+流水、订单履约、开封判例 |
| **C · 报表** | 图表卡在上 + 维度/时间切换 + 明细表在下；统一「只读」视觉标识 | 5 页：数据看板、复购效果、销售毛利、库存周转、Toko 对账 |
| **D · 配置页** | 业务分组卡片 + 行内编辑 + **每卡自带保存钮**（未修改禁用 → 「已修改」→ 高危确认 → 提交）；组头不标红 | ≈4 页：运营配置、算法参数、**运费配置**、复核设置 |
| **E · 多步工作流** | 现状骨架保留（建批次→编辑→预览→确认），只做视觉统一 | 批量内容工作台、种子发布 |

fragment 命名（AD-11）：`fragments/tpl-a-workbench.html` · `tpl-b-list.html`（含抽屉壳与摘要条）· `tpl-c-report.html` · `tpl-d-config-card.html` · `tpl-e-steps.html`；抽屉 `fragments/drawer-<资源>.html`；侧导航 `fragments/nav.html`。
槽位固定：A = `tabs / filters / queue / detail / actions`；B = `filters / summary / table / drawer`；C = `range / cards / detail`；D = `cards[]`；E = `steps / body / footer`。**页面只填槽位不改壳**。
JS：`admin-core.js` + `admin-workbench.js` + `admin-drawer.js` + `admin-charts.js`，全局挂 `window.Admin.<职责>`，不用 ES module，无构建链。htmx 维持 **1.9.12**（D-31，不升级）。

### B.2 htmx 局部更新约定（AD-9）

- `HX-Request` 头 → 返回 fragment，否则整页；同 Controller 方法两条路径共用 Model，用 `HxRequest` 参数解析器判别。
- 处置成功：200 + 主 fragment（右栏）+ `hx-swap-oob` 带出左栏该行与页签计数；响应头 `HX-Trigger: {"admin:badge-refresh":{}}`（角标 fragment 由 `GET /admin/nav/badges` 提供）。
- 校验/业务失败：**422 + 操作区 fragment**；权限不足 **403 + 禁用态 fragment 并注明所缺权限名**（D-37）；不可逆动作用 `data-confirm`。4xx 由 `admin-core.js` 的 `htmx:beforeSwap` 放行 swap（不用 `response-targets` 扩展）。
- 模板 D/E 与所有整页表单**维持现状 PRG，不混用**；**禁止向 htmx 返回 JSON**。
- `HX-Trigger` 事件名 `admin:<对象>-<动作>`。CSRF 由 `admin-core.js` 统一注入 `X-CSRF-TOKEN`。

### B.3 「零端点变更」原则的确切表述与边界

**AD-9 抽屉条款原文**（delta:175）：
> 抽屉：**页面已有详情 GET（如 `orders/{token}`、`users/{userId}`）的，复用原 mapping 的 `HX-Request` 分支返回抽屉 fragment，不新增 `…/drawer` 端点**（AB-19A 零端点变更）；只有本版新建页面（places / roles / warm-replies）才用 `GET …/{id}/drawer`。列表页 `?open=<id>` 由 `admin-drawer.js` 自动请求抽屉（页内深链，非旧路由兜底）。Story 11.4 一致性核对按此规则判定。

**PRD-v1.3.0-admin §5④ 验收原文**：
> **不动任何功能**——不增删任何页面能力与操作端点；重整前后**逐操作核对不丢一项**，此为核心验收标准。验收底账 = 拆 story 时从 `dev_1.3.0` 代码重新生成的《后台写操作清单》（D-27）；页面分母 = `ui-v1.3.0-admin.html` 的 49 页（D-8）

**正式例外清单**（delta「拍板回写」段，D-36～D-46）：
> AB-19A 零端点变更的**正式例外**：`warn`(+reason，早定) · `refund-reject`(+reason) · `refunds/{token}/reject`(+reason) · `payout`(+出款凭证 objectKey，退款单加列 → 迁移 #11) · B12 三个 stag-only 模拟回调端点（D-41，`@StagOnly`）。

另：PRD §5③ 例外清单第 12 条 = D-36 三处留痕字段；第 13 条 = B12 模拟回调仅测试环境可见（D-41）+ 场所增「城市」字段与筛选（D-39）。旧独立详情页地址**不做跳转兜底**（D-23），并入抽屉后旧路由直接删除。

### B.4 权限模型（AD-4，D-9 方案 A）

- 新表 `admin_roles(id, code VARCHAR(32) UNIQUE, name VARCHAR(60), name_key VARCHAR(80) NULL, role_type VARCHAR(8) SYSTEM|CUSTOM, created_by, created_at, updated_at)`。
- 新表 `admin_role_permissions(role_id FK, permission_code VARCHAR(64), UNIQUE(role_id, permission_code))`。
- `admin_accounts` 加 `role_id BIGINT NULL REFERENCES admin_roles ON DELETE RESTRICT`；另加 `security_version INT NOT NULL DEFAULT 0`（AD-1）。
- **解析顺序**（登录时一次，`AdminUserDetailsService.resolvePermissions`）：`accountType=SUPER_ADMIN` → 隐式全权；`role=OPS_MANAGER` → 仍读枚举；`role=CUSTOM` → 读 `admin_account_permissions`（现状不动）；其余 → 读 `role_id` 指向的 `admin_role_permissions`。
- **4 个预置角色**：`OPERATIONS` / `FULFILLMENT` / `SUPPORT` / `FINANCE`，各建一行 `SYSTEM` 角色，`name_key` 指向三语 message key，权限码从枚举灌入，现有账号按 `role` 回填 `role_id`；迁移后 `AdminRole` 枚举中这四个值**保留为「标记」**，权限列表清空并注明「以表为准」。自定义角色 `code` 自动生成 `role-<id>`；删除自定义角色 = 服务层校验无账号引用 + DB FK RESTRICT 兜底。
- 迁移 #10 `admin_accounts_role_check_add_role_template`：`role` CHECK `DROP+ADD` 全集，新增 `ROLE_TEMPLATE` 值。
- **新权限码不预授予任何预置角色**（`place.manage`、`comment.virtual_post`），超管隐式拥有。
- **AD-1 账号变更版本号**：改邮箱/停用/改角色/改账号级权限/角色模板改权限 → `security_version +1`（角色模板改权限时批量 +1 该角色下全部账号）；登录写入会话属性，`AdminSessionGuardFilter` 每请求比对，不等则 `session.invalidate()` 跳 `/admin/login?relogin`；**过滤器不重算权限**（D-2）。
- 既有事实（评审核实）：`AdminPermissions` **72 个常量**，`AdminRole` **6 个非超管角色**，只有 `CUSTOM` 落表。

### B.5 Epic 10 覆盖的电商后台页面

Epic 10「商城组页面重构（最后一批）」—— **Toko 17 页套模板 A/B/C/D**。**AD-12：全部 story 排在 v1.4.0 电商线合入 `dev_1.3.0` 之后启动**，启动前先用 Story 2.1 脚本重新生成商城组写操作清单。

| Story | 页面 | 路由 | 模板 / 关键点 |
|---|---|---|---|
| 10.1 | A7 Toko 退货审核 | `/admin/shop/returns` | 模板 A；页签 待审核/待寄回/待质检/待退款/已完结·已驳回；右栏五步进度条 + 退货行表 + **退款试算卡 8 行** + 用户说明与凭证 + 当前步操作区 + 「查开封判例」链接；**端点不变** |
| 10.1 | A8 Toko 异常订单 | `/admin/shop/order-exceptions` | 模板 A；页签 待处理/已处理；异常原因卡 + 订单行表（行级勾选）+ 部分取消(≥1 行)/整单取消并退款/联系用户后继续；权限 `shop.order_fulfill`；**两页计数不进待办中心角标** |
| 10.2 | B15 Toko 订单履约 | `/admin/shop/orders` | 模板 B；摘要条 待发货·在途·今日签收；筛选含**收件人电话独立搜索**；抽屉 = 订单卡（金额段+时间轴）/商品行表（退货规则列）/收货信息/包裹表（逐包裹标记送达）/操作条；**`shop/orders/{token}` 详情路由退役**；异常挂起订单打标链到 A8 |
| 10.2 | C1 Toko 对账 | `/admin/shop/reconciliation` | 模板 C；期间筛选 + 四核对卡字段原样；校验行不平整卡红框；只读标识 |
| 10.3 | B16 商品管理 | `/admin/shop/products` | 列表模板 B（摘要条 上架数·下架·SKU 总数）+ **表单页保留独立路由套模板 D** 四分组卡（基本信息/图片与详情/每日建议喂量/规格与价格）各自独立保存钮；**商品主体与 SKU 两个 `<form>` 现状保留**；进货价 `shop.cost_view` 门控；喂量区间不重叠校验；有在途订单的 SKU 不可删 |
| 10.3 | B17 Banner | `/admin/shop/banners` | 表格预览图/尺寸/权重/状态三档；**新建/编辑收进抽屉**（图 objectKey + 权重）；上下架/删除 data-confirm；端点不变 |
| 10.4 | B18 库存管理 + 流水页 | `/admin/shop/inventory`、`/inventory-movements` | 模板 B；摘要条 售罄 SKU·低库存·锁定合计；抽屉 = SKU 流水摘要 + **四操作页签（采购入库/退货入库/报损原因必填/盘点调整 data-confirm 复述差异）**，权限 `shop.inventory_edit`；流水页独立只读套模板 B |
| 10.4 | B19 开封判例 | `/admin/shop/return-precedents` | 独立页；业务定位提示常驻；表 情形/判定/理由/时间 + 检索；沉淀判例表单；A7「查判例」落本页检索 |
| 10.5 | C2 复购引擎效果 / C3 销售与毛利 / C4 库存周转 | — | 三页模板 C 只读；字段现状原样图表化；C4 统计窗口筛选 + 建议动作列保留；权限 C2 `config.view` 或 `order.view`，C3/C4 `shop.finance_view` |
| 10.6 | D1 服务范围与运费配置 | `/admin/shop/shipping` | 模板 D 三卡（可配送区域行内编辑+新增行+启停开关 / 免运门槛 / 退货收件地址）各自保存钮；`shop.*` 权限沿用；端点不变 |

**主题登记表现状**（`_bmad-output/planning-artifacts/v1.3.0/README.md` §2，权威）：admin 主题「云端批跑中（2026-09-09 起）；**Epic 10 的 6 条跳过，等电商线合入后再做**」；shop-v2 主题「Epic 号段**待定**；**第二批运营提效组依赖 admin Epic 10**」。文件命名约定：架构 delta = `architecture-v1.3.0-<主题>-delta.md`（shop-v2 该件尚缺，PRD = `PRD-v1.3.0-shop-v2.md` + 附录 `-addendum.md`，决策日志 = `决策日志-shop-v2.md`）。Epic 号段跨主题可重复，但**引用必须带主题**。

依赖与顺序原文：「1 → 2 硬前置；3、4、5、6 相互独立可并行；7、8、9 依赖 2 的模板壳；**10 额外依赖电商线合入**；11 最后。」**Epic 号段：admin 主题占 Epic 1～11；App 分支主题自 Epic 12 起。**

### B.6 审计规范

- 所有写操作走既有 `AdminAuditService.record` 哈希链 + `AuditActions` 常量；新动作码**全部追加此处**（`ACCOUNT_RENAMED` `ACCOUNT_EMAIL_REBOUND` `ACCOUNT_WARNED` `PLACE_*` `COMMENT_VIRTUAL_POST` `WARM_REPLY_READ` `ROLE_CREATED/UPDATED/DELETED` `TIER_CREATED`；定价复用 `PRICING_UPDATED`）。
- 命名：审计码 `<对象>_<动作>` UPPER_SNAKE，同对象前缀一致；权限码 `<资源>.<动词>` 小写下划线。
- **审计 detail 禁写评论正文全文**（只摘要 ≤50 字）；应用日志只记 id 与动作码，禁记评论正文、邮箱、坐标。
- 强制三件套（Enforcement 原文）：「写操作三件套 `@PreAuthorize` + `AdminAuditService.record` + 三语 key；htmx 端点四条测试齐全才算 L0；触碰账号/角色/权限的写操作必须递增 `security_version`（漏加返工）」。
- 展示时间一律 WIB `yyyy-MM-dd HH:mm`（审计页亦然，D-22），走 `AdminTime`（**现状已有 `admin/web/AdminTime.java`，`@Component("adminTime")`，模板 `@adminTime.wib`，2.3a 搬迁复用而非新建**）；入库 UTC；金额 IDR 整数千分位。
- 导出只经 `AdminExportWriter`（AD-10）：xlsx 走既有 POI（`AdminPaymentExportService` 范式），CSV 走 RFC 4180 转义（引号/逗号/换行/前导 `=` 防公式注入），表头取当前会话 locale 的 message key，时间列 WIB 字样；文件名 `<资源>-<yyyyMMdd>.<csv|xlsx>`；**禁止自拼字符串**。

### B.7 i18n 规范

- `messages_{zh_CN,en,id}.properties` 各 2141 key，会话级切换；**新增 key 三包同批，zh/en/id 齐备才可合入，缺一即红**。
- key 前缀：新增 `admin.v130.<页面>.<语义>`；错误 `admin.err.<模块>.<原因>`；flash `admin.flash.<模块>.<结果>`；高危确认 `admin.v130.<页面>.confirm.<动作>`。
- CI 校验三包 key 集合相等：`scripts/ci/check-i18n-keys.sh`（本版新增）。
- 反例（Enforcement）：**模板写死中文**。
- 角色 `name_key` 指向三语 message key（系统角色）；自定义角色 `name` 由运营自填**单语**。

### B.8 其它横切（对 shop-v2 后台工作直接生效）

- 新模块沿用四层 `admin/<module>/{domain,repository,service,web,dto}`；`Admin` 前缀只用于 Controller / Service，实体与仓储不加前缀。
- 后台路由：页面 `GET /admin/<复数资源>`；抽屉 `GET /admin/<复数资源>/{id}/drawer`；动作 `POST /admin/<复数资源>/{id}/<动词>`；对外 token 参数名 `{token}`，内部 id 用 `{id}`。
- 事件监听统一 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`，**禁 `REQUIRED`**（既有通知事故的修法）。
- **admin 新模块不得直接注入业务包 Repository**，只经 service 接口与事件（存量 70 处历史债不新增）。
- 状态列一律 `status VARCHAR + CHECK(UPPER_SNAKE 全集)`，**改 CHECK 必须 `DROP + ADD` 重列全集**。
- 无新中间件、无新容器、无新 env；`@StagOnly` 为项目首个 stag 门控注解，落 `admin/shared`。
- 新表列**先查 `docs/reference/db-schema-reference.md` 防撞名**。
- 测试放 `test/.../admin/<module>/Admin<Module>IntegrationTest.java`，每个新 Controller **至少四条**：整页 200、`HX-Request` 返 fragment、422 行内错、403 禁用态。

---

## C. 跨 story 契约（source 3：`CROSS-STORY-DECISIONS.md`，冲突以此为准）

### C.1 C5 —— 对外 DTO 变更四处同改

> **C5 | 契约 | 契约一致性是可证伪验收**：每个对外 `*Response` 的**字段集 / 枚举线格式**是一条带层级的 AC——① 后端字段集回归（**L0**：纯 Jackson 序列化金标 test，无 DB）② App `mock` ↔ data DTO 字段对齐（**L0**）③ 真后端 ↔ mock 同请求字段集一致（**L1**）。**改任一对外 DTO 必须同步改：后端 record + App data DTO + App mock + 对应契约 test，四处不同步即视为契约破坏，PR 不绿**。示范实现：`FeedResponseContractTest`（content 模块，钉 `FeedPageResponse`/`FeedItemResponse`）

配套 **C4**：「接口契约**后端主导**……App 的 `mock_backend.dart` 与 data 层 DTO 是**后端契约的镜像，不得自创字段**。需求方向允许 App→后端（consumer-driven），但裁决一律落到后端 DTO + 文档。**联调以真后端为准——mock 漂了改 mock，禁止在客户端兜底转换抹平契约差异**」。

### C.2 i18n 模型（App 不渲染后端显示串）

- **F8**：UX 品牌/视觉真相 = `petgo_app/lib/core/theme/colors.dart`，非 UX markdown 文档。
- **2026-08-16 Story 3.4 决策（三处同改）**：同一句文案落在三处，漏改任一处表现为「站内看到新文案、推送收到旧文案」——
  | # | 位置 | 谁在用 |
  |---|---|---|
  | ① | `ModerationNotifyListener` 的 `send(title, body)` | 落库的通知行（数据记录） |
  | ② | `messages_{zh_CN,en,id}.properties` 的 `notify.REPORT_REVIEWED.*` | **离线推送**（按收件人语言渲染） |
  | ③ | **App 的 ARB `notifyBodyReportReviewed`** | **站内通知中心 —— 用户真正看到的那句** |

  三处都有测试守着（后端 `ReportReviewedCopyTest`，前端通知中心用例）。
- 用户记忆基线（`petgo-i18n-model-and-debt`）：**App 绝不渲染后端显示串，按 code + typeSlug 本地化**；里程碑标题源 = `milestone_titles.dart`。

### C.3 E4 —— 图片上传 / EXIF

> **E4 | 隐私 | EXIF 剥离客户端为主路径 + 公开桶服务端兜底**：公开桶对外图（尤其 H5 名片）必须经 OSS `x-oss-process` 去元数据或后端重处理，防改过的客户端绕过客户端剥离 | 2-1（AC2 + B2 兜底方法）；2-6（B2 H5 图必走服务端去 EXIF）

共享设施归属：`shared/media`（`StsService` / `SignedUrlService` / `AliyunOssClient`）由 2.1 建；`ImToOssArchiver` 2.1 占位 → 2.5 实现 → 5.x 用。
风险台账 **R-EXIF**：关键词/EXIF 等「规则化」手段的固有局限，E5 否定处理 + E4 服务端兜底已缓解主要面。

### C.4 E7 —— Flyway 迁移规则（取代 E2/E6，常设）

> **E7 | 基建（取代 E6，2026-08-21 hanwei 拍板）| Flyway 时间戳制为常设规则，无过渡条款**：新迁移一律 `V<yyyyMMdd_HHmm>__<snake_case>.sql`，禁止序列号。① **存量序号迁移 V1–V108 一律保留原号，不改名不返工**（名单固化在 `scripts/ci/flyway-legacy-versions.txt`，只增不减）—— 它们多已应用到 prod / `petgo_stag`，改名会让 Flyway 找不到已应用记录而拒绝启动；**一切以数据库现状为准，树迁就库、不是库迁就树**。② **冻结判据从「在不在 main 上」改为「有没有被任何环境应用过」**：已应用的绝不能改（checksum 对不上即启动失败），从未应用过的可以直接改。③ CI 守门相应放宽：只对**不在 legacy 名单内**的新增文件强制时间戳格式，其余检查（树内同号 / 动 main 已有文件 / 与 main 撞号）不变。④ **打包一律 `mvn -B clean package`** —— 不带 clean 会把改名前的旧迁移留在 `target/classes` 里一并打进 jar，Flyway 报 `Found more than one migration with version X` 启动即崩。

配套 CLAUDE.md §纪律5：**改 CHECK 约束必须重列全集**（`DROP + ADD` 全量重建，值取自当前树里最后一条重建它的迁移）；`out-of-order=true` 全环境常开。

### C.5 D1 / D2 —— 注销级联

> **D1 | 数据生命周期 | 注销时 `consult_sessions`/`consult_ratings` 匿名化保留**（剥 user PII，保留症状/评级/评分供运营 FR-33 与未来 FR-5 库），与 UGC 一致；**`triage_tasks` 仍物理删除**（纯个人 AI 健康记录）
> **D2 | 数据生命周期 | 注销时腾讯 IM 聊天媒体**：调 IM 删除该用户会话媒体，或确认 IM 侧 TTL 自动清理（二选一，dev 落实并记录）；存档到私密桶②的副本随个人图删除。**不可「按隐私边界处理」含糊带过**

**F21（2026-08-19，有意偏离 D2/7.3）**：**OSS 对象任何情况不再物理删除**——`MediaDeletionService` 改只记账 no-op（API 形状保留），`AliyunOssClient` 删除原语整体移除；**DB 行删除/匿名化不受影响，仅对象存储保留**。风险由业务负责人确认承担，「**新增任何删除逻辑须先回本条重新拍板**」。
**F18**：`DELETE /api/v1/pet-profiles/me` 复用 `ProfileDeletionService`，删除语义「立即物理删」，UGC 保留，`petStatus` 不改。
**F20**：运营后台展示已注销用户（`users.deleted_email` / `deleted_display_name` 快照，仅 `AdminUserService` 读）。
**`AccountDeletionJob`**：7.3 建，消费各模块 `deleteByUserId`/`anonymizeByUserId`。

### C.6 不可枚举对外标识

- CLAUDE.md 护栏：「对外暴露标识一律**不可枚举 token**，不用自增 id 直接外露」。
- v1.4.0 AD-7 落到电商：订单号 = `SecureRandom` + Base62 22 位（`CardTokenGenerator` 范式）；商品/SKU 对外标识同理；后台内部列表可用自增 id。
- admin delta 路由约定：对外 token 参数名 `{token}`，内部 id 用 `{id}`。

### C.7 异步 `@Async` + DB 状态机

- CLAUDE.md 护栏：「异步只用 `@Async` + DB 状态机，**禁止引入 MQ / 通用缓存层 / 新中间件**（Kafka/RabbitMQ/Caffeine 等一律不加）」。
- **F5**：定时类系统推送必须用 Spring 原生 `@Scheduled` 每日扫描 + `@Async` 逐条投递 + **DB 去重标记位**；禁 Quartz / Kafka / 任何调度或消息中间件。
- **F11**：并发竞争走 **DB 层原子条件更新**（判影响行数），杜绝双写；**禁 MQ / 分布式锁中间件，纯 DB 原子写**。
- admin delta：事件监听统一 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`（`REQUIRED` 会静默吞写，既有通知事故）。
- 社区关系端口 **AD-18**：**无缓存**——每次查库走唯一索引，禁止为其引 Redis 或本地缓存。

### C.8 其它可能被 shop-v2 触及的既有契约

- **C1**：「当前用户」资源统一 `/api/v1/me`，**全平台不用 `/users/me`**。
- **C3**：点赞/举报表统一 `content_likes` / `content_reports`（带模块前缀）。
- **表归属总表**：每张表恰好一个创建者。admin delta 的「对上游文档反向影响」已要求把 9 张新表（`ops_daily_metrics` / `admin_roles` / `admin_role_permissions` / `places` 五表 / `warm_reply_followups`）追加进该总表。
- **M-1（2026-07-27）**：取消「一兽医一单」占用互斥，`awaitingPay`(单条) → `awaitingPays`(列表) 属 **API 契约变更**，前后端须同批部署。
- **安全规则层只升不降不可绕过**：凡新增「向用户展示他人内容」的位置（列表/详情/推荐位），一律默认套用 `UserHideRelationReader` 端口，不做逐场景例外。

### C.9 v1.4.0 decision-log 中与 shop-v2 直接相关的 S-n / C-n（source 4）

**已确认 C-n（节选与电商 V2 相关者）**
- **C-7** 首批 SKU 上限锁 **30**（AB-10D 超限告警）；坐实规则式推荐 FR-107、固定运费表 FR-99。
- **C-8** PawCoin 可购买实物，规则落 **FR-100A** + 后台 **AB-6D**；合规 DEP-7、税务 DEP-8。
- **C-9** 平台责任退货时 **PawCoin 段不能退现金**，补偿走 Coin 侧溢价。
- **C-12** 支持**行级部分退货**，同一订单同时仅允许一张进行中退货申请；去程运费整单退全退/部分退不退，**不给 CS 手工开关**；部分退货不改订单主状态。
- **C-13** 砍「换货」，枚举收为 `可退`/`开封不退`/`不可退`。
- **C-14** 砍「当日达 Sameday」，只留「标准快递 Reguler 2–4 日」一档；运费表由二维降为**区域一维**。
- **C-15** 手机号归一化存 **E.164**：`+62` + 9–12 位有效位，首位必为 `8`，自动剥前导 `0`；**邮编 × Kecamatan 一致性做「警告不阻断」**。
- **C-16** PawCoin 余额超单笔上限 → **截断到上限，差额走 QRIS，结算页明示原因**。
- **C-17** 锁定库存防滥用三阈值全做：① 同用户并发待支付订单 ≤ 3 单；② 单 SKU 单用户锁定量 ≤ min(5 件, 该 SKU 可售的 20%)；③ 连续 2 次超时后冷却 30 分钟。纯服务端 + 纯 DB，无新依赖。
- **C-18 / C-19** 做搜索：只搜 **name + brand**，无搜索历史、无热搜词；**吸顶行 = 放大镜 + 分类依次排开，点放大镜进独立搜索页 `/shop/search`**；后端 `q` 与 `category` 在服务层**仍是与关系**（`ShopProductQueryServiceTest` 守着）。

**设计侧代拍 S-n（每条可逆，报编号即可推翻）**
- **S-1** 自动置「已送达」M = **7 日**；最坏路径 D7 `DELIVERED` → D14 `COMPLETED`，退货窗口 D7–D14。
- **S-2** 一单多包：订单支持 1..N 个 `shipments`，转 `DELIVERED` 条件 = **所有包裹送达**；7 日自动确认以最后一个包裹送达为起点。
- **S-3** 超卖处置：运营在 AB-11D **手工选单取消** + 全额退款含运费 + 站内信致歉 + 按平台责任补偿溢价补 Coin。真正的超卖来源是**盘点/报损/退货入库撤销**，不是并发。
- **S-4** 溢价骗退风控：同用户 90 日内最多 **2 次**质量问题补偿溢价；超出后仍全额退款、不再发溢价，后台标记。**关键是不拒退货**。
- **S-5** AB-6D 总开关限定「仅资金链路故障时使用」，**只影响新下单，不影响已付款订单**；运费开关关闭时运费行不参与 PawCoin 抵扣。
- **S-6** 「开封不退」承载位在结算页**金额明细上方**；多 SKU **取最严标识**（`不可退` > `开封不退` > `可退`），可展开看逐行。
- **S-7** 退货寄回：**用户自寄**到后台配置的退货收件地址（AB-11C 增配一项）；平台承担情形按实际运单金额在退款时一并返还，用户上传运单凭证；🔴 **不做上门取件**。
- **S-8** 状态机补齐四条边：① 拒收 `SHIPPED → REFUNDING`；② 退款驳回回边（`REJECTED` 后回到驳回前状态，**不是终态**）；③ 增 `REFUND_FAILED` 中间态，可重试，超 3 次转人工；④ 用户主动撤销退货申请（`PENDING_REVIEW` / `AWAIT_SHIPBACK` 两态可撤销）。
- **S-9** 拒收商品以**「退货入库批次」**入库；入库单采购单号填**原订单号**、进货单价取该 SKU **最近一次采购入库单价**；🔴 **不允许留空**。
- **S-10** 质检不通过：用户端 `REJECTED` 态须展示 驳回原因 + 质检照片 + 商品处置方式（退回用户/报损）+ 回寄单号；**回寄运费由平台承担**。
- **S-11** AB-13A 补三行：运费收入 / 承运成本 / 退款手续费；🔴 **承运成本需在 AB-11B 发货时录入**（按运单实际金额）。
- **S-12** 本版本**不做钱包侧余额批次分层**；AB-13D 增一行「其中：赠币核销额（近似）」；钱包表预留 `batch_type` 扩展位但不启用。**标注「本批 13 条里最值得复议的一条」**。
- **S-13** AB-13B/13C 双数据源：🔴 **以服务端业务库为主口径**，PostHog 仅作辅助交叉验证；**后台不反拉 PostHog API**（会引入外部依赖）。
- **S-14** FR-109 公式三处修正：① `可用天数 = (净含量 × qty) ÷ 日喂量`；② 起算点改用**订单送达日**；③ 多宠物共食本版本按唯一宠物算（L-11 单账号单宠物硬约束仍在，`ProfileService.java:56` `existsByOwnerId` → 409）。
- **S-15** 规则 6 措辞改为「PawCoin **不可用于任何平台外部结算，含 PPN 等税费**」；与 FR-110 同为约束性条款，**验收须是「能力缺席」**（服务层不存在转让接口、不存在外部结算出口）。

**代码矛盾 L-n（仍需 shop-v2 知晓）**：L-9 `PetProfile` 无体重/绝育字段；L-11 单账号单宠物 409；L-12 `AppTab` 4 值无空位（归 DEP-1）。
**依赖 DEP-n**：DEP-3 NIB/KBLI/PPN（阻塞上线）· DEP-4 品牌授权书（阻塞备货）· DEP-5 承运商账号（阻塞 FR-99 运费表）· ✅DEP-6 已于 2026-08-17 解除（喂量数据印在包装上，录商品时照抄）· DEP-7 PawCoin 合规 · **DEP-8（见 A.7 原文）** · DEP-9 自营备货上限与周转纪律。
**N-1**：复购引擎是本版本存在理由；**「排期取舍时应压缩最小闭环的可选项，不应压缩复购引擎」**。
**N-3**：问诊信任优先于电商转化，FR-110 约束优先于任何转化率优化。

---

## D. 与 shop-v2 需求直接相关的既有实现事实（代码核实）

> 全部为 `git grep` / 源码核实（2026-09-15，分支 `feat/1.3.0-shop-v2`）。路径均为绝对路径。

### D.1 品类 / 物种枚举定义位置与 CHECK 约束迁移

**不存在 `ShopCategory` / `PetSpecies`**，实名如下：

| 枚举 | 声明位置 | 值（含行号） |
|---|---|---|
| `ProductCategory` | `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/domain/ProductCategory.java:9` | `MAKANAN`:11 · `OBAT_VITAMIN`:13 · `CAMILAN`:15 · `PERAWATAN`:17 |
| `Species` | `…/shop/domain/Species.java:8` | `DOG`:9 · `CAT`:10 · `UNIVERSAL`:12 |
| `BodySize` | `…/shop/domain/BodySize.java:8` | `SMALL`:9 · `MEDIUM`:10 · `LARGE`:11 · `UNIVERSAL`:12 |
| `AgeStage` | `…/shop/domain/AgeStage.java:9` | `PUPPY`:10 · `ADULT`:11 · `SENIOR`:12 · `UNIVERSAL`:13 |
| `ReturnPolicy` | `…/shop/domain/ReturnPolicy.java:12` | `RETURNABLE`:14 · `NO_RETURN_AFTER_OPEN`:16 · `NON_RETURNABLE`:18（= C-13 收敛后的三值） |
| `StockStatus` | `…/shop/domain/StockStatus.java:8` | `OUT_OF_STOCK`:10 · `LOW_STOCK`:12 · `IN_STOCK`:13 |
| `InventoryMovementType` | `…/shop/domain/InventoryMovementType.java:13` | `PURCHASE_INBOUND`:21 · `RETURN_INBOUND`:31 · `DAMAGE`:39 · `STOCKTAKE`:48 |
| `ShopOrderStatus` | `…/shop/order/domain/ShopOrderStatus.java` | `PENDING_PAYMENT`:35 · `PENDING_SHIPMENT`:37 · `SHIPPED`:39 · `DELIVERED`:41 · `COMPLETED`:43 · `CANCELLED`:45 · `REFUNDING`:47 |

宠物侧物种是**另一个枚举**：`PetType`（`…/profile/domain/PetType.java:10`：`CAT`:11 · `DOG`:12 · `OTHER`:13）。映射出口唯一：`ProfileRecommendationService.speciesOf(PetType)`（`…/shop/repurchase/service/ProfileRecommendationService.java:219`，`OTHER → UNIVERSAL`），物种硬过滤在 `:143–148`。

**CHECK 约束全部在** `/Users/dai/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260817_1154__init_shop_products_and_skus.sql`：
- `ck_shop_products_category`:40–41 → `('MAKANAN','OBAT_VITAMIN','CAMILAN','PERAWATAN')`，列 `category VARCHAR(24) NOT NULL`:24
- `ck_shop_products_species`:42–43 → `('DOG','CAT','UNIVERSAL')`，列 `species VARCHAR(16) NOT NULL`:28
- `ck_shop_products_body_size`:44–45（nullable）· `ck_shop_products_age_stage`:46–47（nullable）
- `ck_shop_products_return_policy`:49–50，列:34–35 `DEFAULT 'NO_RETURN_AFTER_OPEN'`
- `ck_shop_skus_price`:70 · `ck_shop_skus_net_weight`:71 · `ck_shop_skus_return_policy`:72–73（nullable = 继承商品级）
- 索引：`uq_shop_products_token`:53 · `idx_shop_products_listing (category, sort_weight DESC, id DESC)`:54 · `uq_shop_skus_token`:76 · `idx_shop_skus_product`:77
- `ck_inventory_movements_type` 在 `V20260817_1852__init_inventory_movements.sql:41–42`

> ⚠️ 新增品类/物种值 = **末尾追加枚举 + `DROP+ADD` 重列全集迁移**（CLAUDE.md §纪律5 + 枚举 javadoc 明写「只在末尾追加，不重排/不删除/不改既有值拼写——重排不报错但会静默改变全部历史行的语义」）。

### D.2 `ShopProduct` / `ShopSku` 主要字段与 publicToken

**`ShopProduct`** — `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/domain/ShopProduct.java`，`@Table(name="shop_products")`:30，class:31

`id`:35 · `publicToken`:38（`public_token`，nullable=false，**length=32, updatable=false**，:37）· `name`:41(len60) · `brand`:44(len60) · `category`:48(len24) · `mainImageKey`:52（**OSS objectKey 不是 URL**）· `mainImageW`:65 / `mainImageH`:68 · `galleryKeys`:73（JSONB）· `species`:77 · `bodySize`:81(nullable) · `ageStage`:85(nullable) · `detailHtml`:88(TEXT) · `feedingGuide`:96（`List<FeedingGuideEntry>` JSONB）· `shelfLifeNote`:99(len120) · `returnPolicy`:103 · `sortWeight`:107 · `active`:111（列 `is_active` —— **无 status 枚举，上下架是 boolean**）· `createdAt`:114 / `updatedAt`:117。
方法：`create(String publicToken, …)`:129 · `apply(…)`:151 · `list()`:178 · `delist()`:196 · `getPublicToken()`:216。**商品上无价格，价格在 SKU。**

**`ShopSku`** — `…/shop/domain/ShopSku.java`，`@Table(name="shop_skus")`:27，class:28
`id`:32 · `publicToken`:35（`length=32, updatable=false`，:34）· `productId`:38（updatable=false）· `specName`:41(len40) · `price`:45（**`long`，BIGINT 最小币种单位，无 DECIMAL**，NFR-9）· `netWeightG`:49 · `costPrice`:56（`V20260817_1246__add_shop_sku_cost_price.sql` 加列）· `returnPolicy`:61（nullable = 继承商品）· 时间戳:64/:67。
方法：`create(String publicToken, …)`:73 · `effectiveReturnPolicy(ReturnPolicy productLevel)`:117。

**publicToken 生成**：`/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/service/ShopTokenGenerator.java:21`（`@Component`），`generate()`:29 —— `BASE62`:23–24 = `"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"`，`LENGTH = 22`:25，`new SecureRandom()`:27，`BASE62[random.nextInt(BASE62.length)]`:31–33。javadoc:13–18 明写**刻意复制自 `profile.service.CardTokenGenerator`，charset + length 必须保持一致**（= AD-7 的落地形态）。
调用点（**token 由调用方生成后传进实体工厂，实体从不自生成**）：`AdminShopProductService.java:54`（商品）/`:97`（SKU）· `CheckoutService.java:203`（ShopOrder）· `ShippingAddressService.java:66` · `ReturnRequestService.java:136`。

### D.3 `OrderDisplayNo` 算法与调用点

文件 `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/order/dto/OrderDisplayNo.java`（final class:14，private ctor:28）。

```java
// of(String prefix, long id, Instant createdAt)  :31–34
String date = createdAt.atZone(WIB).format(YMD);
return prefix + "-" + date + "-" + String.format("%06d", id);
```
- 格式 `PREFIX-yyyyMMdd-NNNNNN`；`WIB = ZoneId.of("Asia/Jakarta")`:25，`YMD = "yyyyMMdd"`:26。
- 序号 = **表自增主键 `id` 零填充 6 位**，**零随机性 —— 设计上可枚举**；javadoc:12 说明不可枚举的 `orderToken` 仍是查询键，本号仅供展示；**计算得出、从不落库**（:8）。
- 前缀常量：`VET_CONSULT="CONSVET"`:16 · `AI_UNLOCK="CONSAI"`:17 · `TOPUP="TOPUP"`:18 · `ECOMMERCE="TOKO"`:23。

**调用点**：`…/order/service/OrderCenterService.java`:263、276、289、314、340、348、356、**374（`mapShop` 摘要）**、**384（`shopDetail`）**（import:7）· `…/admin/aiorder/service/AdminAiOrderService.java:77` · `…/admin/consult/service/AdminConsultOrderService.java:92` · 测试 `…/src/test/java/com/tailtopia/order/OrderCenterEcommerceIntegrationTest.java:198,203`（断言 `startsWith("TOKO-")`）。

**同范式姊妹类**：`…/pay/dto/PaymentDisplayNo.java:17`，`of(PaymentIntent)`:26 → `PAYVET/PAYAI/PAYHD/PAYTOPUP/PAYSHOP`（switch:30–38），同样 WIB + `yyyyMMdd` + `%06d`（用 `payment_intents.id`），id/createdAt 为 null 时返回 null（:27）。

> ⚠️ `shop_orders` 另有内部连续列 `seq_no`（`ShopOrder.java:36–37`；迁移 `V20260817_2308__init_shop_orders.sql:20`，注释:94），**从不对外暴露**（`ShopOrderView.java:9–10`），与 `OrderDisplayNo` 无关。

### D.4 购物车与下单接口 DTO

**购物车 `MeCartController`** — `…/shop/cart/web/MeCartController.java`，`@RequestMapping("/api/v1/me/cart")`:25

| 方法 | 路径 | 行 | 参数 |
|---|---|---|---|
| GET | `/api/v1/me/cart` | :34–35 | — |
| POST | `/api/v1/me/cart/items` | :47–52 | `@RequestParam skuToken` · `@RequestParam(defaultValue="1") int qty` · `entrySource`(可选) · `triggerType`(可选) —— **全是 query param，无请求体 DTO** |
| PUT | `/api/v1/me/cart/items/{skuToken}` | :55–58 | `@PathVariable skuToken` · `@RequestParam int qty` |
| DELETE | `/api/v1/me/cart/items/{skuToken}` | :61–63 | |
| DELETE | `/api/v1/me/cart/invalid-items` | :67–69 | |

**无 `CartItemRequest` DTO。** 响应 `CartView`（`…/shop/cart/dto/CartView.java:17`）= `(List<CartLine> lines:18, List<CartLine> invalidLines:19, long subtotal:20, int itemCount:21)`；`CartLine`:28 = `skuToken:29, productToken:30, productName:31, specName:32, long price:33, int qty:34, mainImageUrl:35, availableStock, invalidReason:37, entrySource:38, triggerType:39`；常量 `REASON_DELISTED`:56 / `REASON_OUT_OF_STOCK`:58。
`CartService`（`…/shop/cart/service/CartService.java:33`）：`cartOf`:54 · `add(long,String,int)`:61 · `add(long,String,int,String,String)`:73 · `setQty`:98 · `remove`:115 · `clearInvalid`:121 · `view`:146。
实体 `ShopCartItem`（`…/shop/cart/domain/ShopCartItem.java:14`，`@Table("shop_cart_items")`:13）：`cartId`:21 · `skuId`:24 · `qty`:27 · `entrySource`:38(len32) · `triggerType`:41(len32)（归因列由 `V20260818_0102__add_cart_item_attribution.sql` 加）。

**结算 / 订单 `MeCheckoutController`** — `…/shop/order/web/MeCheckoutController.java`，`@RequestMapping("/api/v1/me")`:34

| 方法 | 路径 | 行 | 说明 |
|---|---|---|---|
| GET | `/api/v1/me/checkout` | :67–71 | `@RequestParam String addressToken` → `CheckoutPreviewView` |
| POST | `/api/v1/me/shop-orders` | :85–95 | `@ResponseStatus(CREATED)`:86 · `@RequestHeader("Idempotency-Key")` 可选:88 · `@RequestBody(required=false) PlaceOrderRequest`:89 → `ShopOrderView` |
| GET | `/api/v1/me/shop-orders/{token}` | :103–108 | `ShopOrderDetailView` |
| POST | `/api/v1/me/shop-orders/{token}/pay` | :116–121 | Idempotency-Key 可选；**无 channel 参数**（QRIS only，FR-100） |
| POST | `/api/v1/me/shop-orders/{token}/cancel` | :124–131 | |
| POST | `/api/v1/me/shop-orders/{token}/confirm-receipt` | :144–150 | |

**`PlaceOrderRequest`** 是 Controller 内嵌 record（`MeCheckoutController.java:159`）：
```java
public record PlaceOrderRequest(String addressToken, String entrySource, String triggerType) {}
```
校验：`addressToken` 空 → `AppException.validation("请选择收货地址")`:90–92。**不存在 `CheckoutPreviewRequest`**（预览走 query param）。

**`CheckoutPreviewView`**（`…/shop/order/dto/CheckoutPreviewView.java:24`）：`address:25, boolean serviceable:26, List<CheckoutLine> lines:27, unavailableLines:28, long goodsSubtotal:29, Long shippingFee:30, Long shippingDiscount:31, Long payableTotal:32, Long coinAmount:33, Long cashAmount:34, long coinBalance:35, long maxCoinPerOrder:36, boolean coinCapped:37, String strictestReturnPolicy:38, String shippingMethod:39`；`METHOD_REGULER="REGULER"`:42（C-14 单档落地）；`CheckoutLine`:45–63（比 `CartLine` 多 `returnPolicy`:53）；最严策略折叠:100–123；`of(CheckoutPreview)`:65。

**`CheckoutService`**（`…/shop/order/service/CheckoutService.java:52`）：`preview(long,String)`:99（`@Transactional(readOnly=true)`:98）· `placeOrder(long,String,String,String)`:156（便捷重载）· `placeOrder(long userId, String addressToken, String entrySource, String triggerType, String idempotencyKey)`:172 —— 幂等键按用户加前缀 `"shop-checkout:" + userId + ":" + idempotencyKey`:176–177；空车检查:188；地址 + 可服务性:193–194；**二次库存检查** `collectUnavailable(cart)` → `CheckoutUnavailableException`（HTTP 409 + `unavailableLines` 扩展）:197–200；建单:203–204；行归因 `ol.attributeTo(...)`:216–218；`settlePawCoinSegment(ShopOrder)`:248；内嵌 `record CheckoutPreview(...)`:346–348。

**公开目录** `ShopProductController`（`…/shop/web/ShopProductController.java:30`，`@RequestMapping("/api/v1/shop/products")`:29）：`GET /` 带 `@RequestParam(required=false) category, q`:48–50（C-18/C-19 的「与关系」落点）· `GET /{token}`:55–56。

### D.5 库存服务原语与库存流水表

**`InventoryService`** — `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/shop/service/InventoryService.java:29`

| 方法 | 行 | 签名 | 失败 |
|---|---|---|---|
| `lock` | :47 | `void lock(long skuId, long qty)` | 影响 0 行 → `AppException.conflict("已售罄")`:50 |
| `release` | :56 | `void release(long skuId, long qty)` | 0 → `conflict("库存锁定量不足，无法释放")`:60 |
| `commit` | :66 | `void commit(long skuId, long qty)` | 0 → `conflict("库存锁定量或实际库存不足，无法出库")`:69 |
| `restock` | :75 | `void restock(long skuId, long qty)` | 0 → `notFound("库存记录不存在")`:78 |
| `ensureRow` | :84 | `void ensureRow(long skuId)` | 幂等 |
| `statusOf` | :93 | `StockStatus statusOf(long available)` | |
| `availableBySkuId` | :102 | `Map<Long,Long>` | |
| `rowsBySkuId` | :118 | `List<SkuInventory>` | |
| `lowStockThreshold` | :122 | `long` | |
| `statusFn` | :133 | `Function<Long,StockStatus>` | |

**`SkuInventoryRepository`（原子条件写层）** — `…/shop/repository/SkuInventoryRepository.java:20`

| 方法 | 行 | WHERE 守卫 |
|---|---|---|
| `int lock(skuId, qty)` | :36（query:33） | `i.actual - i.locked >= :qty` |
| `int release(skuId, qty)` | :47（:44） | `i.locked >= :qty` |
| `int commit(skuId, qty)` | :59（:56） | `i.locked >= :qty and i.actual >= :qty`（两者同减） |
| `int restock(skuId, qty)` | :79（:77） | 仅 skuId；`@Modifying(clearAutomatically=true, flushAutomatically=true)`:76 |
| `int damage(skuId, qty)` | :97（:94） | `i.actual - i.locked >= :qty`（**按可售量，不是实际量**，:93） |
| `int stocktakeTo(skuId, counted, expectedBefore)` | :120–121（:117） | `i.actual = :expectedBefore and i.locked <= :counted` —— **对前值做 CAS** |
| `int ensureRow(skuId)` | :132 | 原生 `INSERT … ON CONFLICT (sku_id) DO NOTHING`:129–131 |

每条写语句同时 `i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP`。

**`InventoryMovementService`（写流水的业务入口）** — `…/shop/service/InventoryMovementService.java:40`
`receivePurchase(skuId, qty, purchaseNo, supplier, costPrice, inboundDate, actorAccountId)`:63–64 · `receiveReturn(skuId, qty, originalOrderNo, inboundDate, actorAccountId)`:82–83（进货单价自动取 `lastPurchaseCostPrice(skuId)`:86 —— **S-9 落地**）· `writeOff(skuId, qty, reason, actorAccountId)`:98（调 `inventory.damage`:102，审计 `AuditActions.SHOP_INVENTORY_DAMAGED`:110）· `stocktake(skuId, countedActual, reason, actorAccountId)`:123–124（读 `before`:130 → `stocktakeTo`:131，审计 `SHOP_INVENTORY_STOCKTAKED`:138）· `recentMovements(skuId, limit)`:146 · `lastPurchaseCostPrice(skuId)`:156。

**流水表 `inventory_movements`**（**不是** `shop_inventory_txns`）—— 迁移 `/Users/dai/work/petgo-platform/petgo-backend/src/main/resources/db/migration/V20260817_1852__init_inventory_movements.sql`，`CREATE TABLE`:19。
列：`id`:20 · `sku_id BIGINT NOT NULL REFERENCES shop_skus(id) ON DELETE CASCADE`:21 · `movement_type VARCHAR(32)`:24 · `qty_delta BIGINT`（带符号）:27 · `actual_before`:28 · `actual_after`:29 · `reason VARCHAR(500)`:31 · `purchase_no VARCHAR(64)`:32 · `supplier VARCHAR(200)`:33 · `cost_price BIGINT`:34（敏感，`shop.cost_view` 门控）· `inbound_date DATE`:35 · `operator_account_id BIGINT NOT NULL REFERENCES admin_accounts(id)`:38 · `created_at TIMESTAMPTZ DEFAULT now()`:39。
约束：`ck_inventory_movements_type`:41 · `_delta_consistent (actual_after = actual_before + qty_delta)`:45 · `_non_negative`:47 · `_inbound_required`:53 · `_inbound_positive`:58 · `_damage_negative`:60 · `_reason_required`:64；索引 `ix_inventory_movements_sku_created (sku_id, created_at DESC)`:70。
实体 `InventoryMovement`（`…/shop/domain/InventoryMovement.java:29`，`@Table`:28）：**全字段 `updatable=false`（append-only）**；工厂 `inbound(...)`:97 · `damage(...)`:110 · `stocktake(...)`:119。

**补充 —— `ShopOrder` 与 `ReturnStatus` 落地形态（供 delta 直接引用）**

`…/shop/order/domain/ShopOrder.java`：`publicToken`:32–33（len32，updatable=false）· `seqNo`:36–37（insertable/updatable=false，内部序号）· `status`:43–44(len32) · `goodsSubtotal`:46 / `shippingFee`:49 / `shippingDiscount`:53 / `totalAmount`:56（**全部 updatable=false**）· **地址快照为扁平冗余列**（AD-13 落地）：`ship_receiver_name`:60(len40) · `ship_receiver_phone`:62(len16) · `ship_provinsi`:64 · `ship_kota_kabupaten`:66 · `ship_kecamatan`:68 · `ship_address_line`:70(len120) · `ship_kode_pos`:72(len5)，**均 updatable=false** · **AD-2 累计列** `refunded_total`:76 / `refunded_coin`:78 · `is_full_return`:80（C-12 去程运费自动判定） · `pay_channel`:85（`com.tailtopia.pay.domain.PayChannel`）/ `coin_amount`:87 / `cash_amount`:89 · `expires_at`:97（AD-8 60min 窗）· `payment_intent_token`:101(len64) · `shipped_at`:105 / `delivered_at`:112 / `completed_at`:114 · `delivery_source`:118 / `completion_source`:121 · `version`:130（`V20260902_1610__add_version_to_shop_orders.sql`）。

`…/shop/returns/domain/ReturnStatus.java`：`PENDING_REVIEW` · `REJECTED`（**不是纯终态**，S-8②/S-10）· `AWAIT_SHIPBACK` · `INSPECTING` · `REFUNDING` · `REFUNDED` · `REFUND_FAILED`（S-8③，`refundAttempts > 3` 转人工）· `CLOSED`（超 7 日未寄回）· `WITHDRAWN`（S-8④）。
🔴 类内常量 `ACTIVE = {PENDING_REVIEW, AWAIT_SHIPBACK, INSPECTING, REFUNDING, REFUND_FAILED}`，javadoc 明写「**必须与库级部分唯一索引 `uq_return_requests_active_per_order` 的 WHERE 子句逐字一致**」——注意**实现里的 ACTIVE 比 AD-5 原文多了 `REFUND_FAILED`**。`TERMINAL = {REJECTED, REFUNDED, CLOSED, WITHDRAWN}`。
资金出口：`…/shop/returns/service/RefundExecutionService.java:47` —— `quote(String returnToken)`:77 · `execute(String returnToken)`:94 · `markFailed(...)`:190 · `retry(...)`:198 · `record Quote(long refundTotal, long coinRefund, long cashRefund, …)`:349 · `record Outcome(long coinRefunded, long cashRefunded, long compensationPremium, …)`:367。相关枚举：`ShippingFeeBearer`（含 `PLATFORM`）· `RejectDisposal`（含 `RETURN_TO_USER`）· `CashDestination`（含 `TO_BANK`）· `ReturnType`（含 `REFUSED_ON_DELIVERY`，S-8①拒收）。

**库存表 `sku_inventory`** —— 迁移 `V20260817_1220__init_sku_inventory.sql:15`：`id`:16 · `sku_id` FK CASCADE:17 · `actual BIGINT DEFAULT 0`:18 · `locked BIGINT DEFAULT 0`:19 · `version BIGINT DEFAULT 0`:21 · 时间戳:22–23；CHECK:24–27（含 `locked <= actual`）；`uq_sku_inventory_sku`:30。**可售量是算出来的，从不落库**（:20）；实体 `SkuInventory`:24，`available()` = `actual - locked`:54；⚠️ `version` 是普通列，**不是 JPA `@Version`**（:41–42）。

**运费 / 服务范围表**（Epic 10 Story 10.6 的数据底座）：`V20260817_2245__init_shipping_zones.sql` —— `shipping_zones`:14（`uq_shipping_zones_kecamatan UNIQUE (kecamatan)`:28 · `ck_shipping_zones_fee_non_negative`:29 —— **C-14「区域一维运费表」的落地**）+ `shipping_settings`:35（**单例表**：`ck_shipping_settings_singleton CHECK (id = 1)`:41 · `ck_shipping_settings_threshold`:42）。Banner 表：`V20260827_1500__init_shop_banner.sql:15` `shop_banners`。

### D.6 PostHog 服务端客户端与事件上报入口

**门面接口**：`com.tailtopia.shared.analytics.AnalyticsClient`（`…/shared/analytics/AnalyticsClient.java:23`），唯一方法 `void capture(String distinctId, String event, Map<String,Object> properties)`:33。
**实现**：`PostHogAnalyticsClient implements AnalyticsClient`（`…/shared/analytics/PostHogAnalyticsClient.java:48`，`@Component`:47）；`@Async("analyticsExecutor") capture(...)`:96–98。
- 传输：**Spring `RestClient` 裸 HTTP `POST {host}/i/v0/e/`，无 PostHog SDK**（:105–116）；body = `api_key`/`event`/`timestamp`（`Instant.now().toString()`:113）/`properties`；`distinct_id` **塞进 properties**:104。
- `DEFAULT_HOST = "https://eu.i.posthog.com"`:53；`resolveHost()` 把空白视为缺失:79–81；超时 `analytics.posthog.timeout-seconds` 默认 3s（`SimpleClientHttpRequestFactory`:83–89）。
- 开关 `isEnabled()` = api key 非空:92–94，关闭时 `capture` 立即返回:99–101；**吞掉所有异常，只记事件名、绝不记 properties**:117–120。
- 配置：`application.yml:305–312` —— `POSTHOG_SERVER_KEY` / `POSTHOG_HOST` / `POSTHOG_TIMEOUT_SECONDS:3`。执行器 bean `analyticsExecutor`（`…/shared/async/AsyncConfig.java:38–47`，core 1 / max 2 / queue 200 / `DiscardPolicy`；依赖 `spring.task.execution.mode=force`:33–36）。
- **distinctId**：`AnalyticsDistinctId.of(long userId)`（`…/shared/analytics/AnalyticsDistinctId.java:27`）= `sha256("tailtopia-user-" + userId)` 小写 hex（`PREFIX`:22），**必须与 Dart `Analytics.distinctIdFor` 逐字节一致**；H5 未登录走 `AnonymousVisitorId`。

**后端上报入口只有 3 处**：`MilestoneAnalyticsListener`（`…/profile/service/MilestoneAnalyticsListener.java:30`，`@TransactionalEventListener`:37，事件 `"milestone_achieved"`:40，**刻意不加 `@Async`**:20）· `CardPageAnalytics`（`…/profile/service/CardPageAnalytics.java:46`，`pet_card_link_opened`:37 / `pet_card_cta_tapped`:38 / `pet_card_cta_outcome`:39）· `PostSharePageAnalytics`（`…/content/service/PostSharePageAnalytics.java:52`，`post_share_link_opened`:42）。

🔴 **后端侧无事件名白名单、无属性脱敏器**——契约只是 javadoc（`AnalyticsClient.java:15–21`）。全部白名单/脱敏在 Flutter：`…/petgo_app/lib/core/analytics/analytics.dart:22`（`capture`:150 · `buttonTapped`:231 · `identifyUser`:82 · `reset`:118）；`scrub(Map)`:254–262 + 递归 `_scrubValue`（含 list）:265–282；PII key 黑名单 `_piiKeys`:39–49、后缀黑名单 `_piiKeySuffixes = {name,phone,address,email,whatsapp}`:290–292、自由文本黑名单 `_freeTextKeys`:53–55、`_maxStringValueLen = 64`:58；按钮白名单 `_allowedButtonIds`（8 项）:218–222，常量在 `…/core/analytics/button_ids.dart:5–13`；AppsFlyer 事件白名单 `appsflyerEvents`（6 项）:130–137。
> 决策链接：**S-13 —— AB-13B/13C 以服务端业务库为主口径，后台不反拉 PostHog API**。

### D.7 客服号写死的三处位置

⚠️ **实测只有 2 处源码命中，第三处（WhatsApp deep link，SHOP-FR-26）在代码中尚不存在**：

| # | 位置 | 字面量 |
|---|---|---|
| 1 | `/Users/dai/work/petgo-platform/petgo_app/lib/shared/widgets/customer_service_sheet.dart:12` | `const String _kCsWhatsappNumber = '081290906953';` |
| 1b | 同文件 `:13` | `const String _kCsEmail = 'cs@tailtopia.id';` |
| 2 | `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/auth/service/AuthService.java:40–41` | `static final String DEACTIVATED_MESSAGE = "账号已被停用，如有疑问请联系客服：WhatsApp 081290906953 / 邮箱 cs@tailtopia.id";` |

- 全仓 grep `081290906953` 在 `petgo-backend/src` + `petgo_app` 下**恰好 2 命中**；备用号 `081399779133` **0 命中**。
- `cs@tailtopia.id` 另出现在 6 个静态法务页：`…/resources/legal/{support.html:57,83,95,100,104 · child-safety.html:95,106,112 · account-deletion.html:58,80,84 · privacy.html:182 · terms.html:146 · mitra-terms.html:129}`，由 `…/src/test/java/com/tailtopia/shared/web/LegalPageControllerTest.java:62,80` 断言。
- **无任何 seed SQL / 迁移含该号码**；无配置表承载 CS 联系方式（`V78__init_platform_config.sql` 只定义 `pricing_config` / `pawcoin_config` / `pawcoin_topup_tiers`，**无通用 key-value 设置表**）。
- **App 内无 `wa.me` / `whatsapp://` 深链**（`launchUrl` 调用点为 `vet_me_page.dart:73` · `shop_order_detail_page_v2.dart:726` · `promo_target.dart:28` · `settings_page.dart:336` · `agreement_links.dart:18`，无一是 WhatsApp）。
- 规划侧引用：`PRD-v1.3.0-shop-v2.md:211`（SHOP-FR-26 的「三处」清单）· `决策日志-shop-v2.md:19`（SD-10）· `validation-2026-09-15-shop-v2/round2-review-fidelity.md:38,152`（finding L-3 指出 `AuthService.java:41` 是漏掉的第三处）。

### D.8 工单 `feedback_tickets.related_order_id` 现状

**列存在**：`…/db/migration/V70__init_feedback_tickets.sql:20` `related_order_id BIGINT,` —— **nullable、无 FK、无索引**（建表 :10–35）；此后从未 ALTER，仅 `V86__add_column_comments.sql:669` 加注释。反向 FK：`V71__init_refund_requests.sql:15` `related_ticket_id BIGINT REFERENCES feedback_tickets(id) ON DELETE SET NULL`。

实体 `…/support/domain/FeedbackTicket.java:56–57` `@Column(name="related_order_id") private Long relatedOrderId;`；建构入参:97/赋值:107；mutator `linkRelatedOrder(long orderId)`:137–139；getter:190。

**两条写入路径，其中一条实际是死路**：
- **路径 A（用户建单，从不触发）**：`SupportTicketService.createTicket(...)`（`…/support/service/SupportTicketService.java:80–110`）→ `resolveRelatedOrder(userId, relatedOrderToken)`:213–222 —— 空 → null:214–216；查 `ConsultOrderRepository.findByOrderToken`:217（**只认兽医问诊单**）；**找不到或不属本人则静默返回 null**:218–221（OPEN-1 宽松，:209–212）。API：`CreateTicketRequest.relatedOrderToken`（`…/support/dto/CreateTicketRequest.java:21`），`POST /api/v1/support-tickets`（`…/support/web/SupportTicketController.java:34–42`，传参:39）。
  🔴 **Flutter 从不发这个字段**：repository 声明了可选参（`…/petgo_app/lib/features/support/data/support_repository.dart:23`，条件加入:35–36），但**唯一调用点** `…/features/support/presentation/ticket_compose_page.dart:115–123` **未传 `relatedOrderToken`** ⇒ 用户建单恒存 `NULL`。
- **路径 B（后台回填，线上唯一活路径）**：`AdminTicketRefundService.linkOrder(String ticketToken, String orderToken, long adminId)`（`…/admin/support/service/AdminTicketRefundService.java:43…`，`@Transactional`:43）—— 订单须存在:46–47；归属校验 `order.getUserId().equals(t.getUserId())` 否则 422:48–50；当前订单已有 APPROVED 退款时禁止改挂:54–61（PR#34 finding #5）。端点 `POST /admin/support-tickets/{ticketToken}/link-order`（`…/admin/support/web/AdminSupportTicketController.java:105–118`，`@PreAuthorize(HANDLE_AUTH)`:106）。

**读路径**：**从不下发用户**——`SupportTicketView` javadoc（`…/support/dto/SupportTicketView.java:10`），契约测试断言其缺席（`…/src/test/java/com/tailtopia/support/dto/SupportTicketViewContractTest.java:30`，`"relatedOrderId"` 列为禁字段）。后台把 id 转 token：`AdminSupportTicketQueryService.java:88–99`（同时派生 `refundToken` / `refundNeedDecision`），DTO 字段 `AdminTicketView.relatedOrderToken`（`…/admin/support/dto/AdminTicketView.java:41`）；退款创建读它 `AdminTicketRefundService.java:98–101`。

### D.9 后台 Excel 导入既有范式 `SeedBatchExcelService`

`@Service class SeedBatchExcelService` —— `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchExcelService.java:49–50`，**无构造器、无依赖，几乎全 `static`**。常量：`WIB`:53 · `TIME_FMT="yyyy-MM-dd HH:mm"`:55–56 · `BATCH_TYPES=[DAILY,KNOWLEDGE]`:59 · `SPECIES_OPTIONS`:68–69 · `HEADERS`:71–72 · `HINTS`:75–81。

**解析**：Apache POI `org.apache.poi:poi-ooxml:5.4.1`（`pom.xml:84–87`），**DOM 模式，全仓无 SXSSF/SAX**。`parse(MultipartFile)`:166–206 —— `WorkbookFactory.create(in)`:172 · `DataFormatter`:173 · **只读 sheet 0**:174 · `for (Row row : sheet)`:175。列（0-based）：0 正文:179 · 1 图片文件名（逗号分隔，顺序=展示顺序）:180→`splitNames`:222–234 · 2 发布账号（`昵称 (id=N)` 或纯数字）:181→`parseAccount`:243–256 · 3 内容类型:182→`parseType`:264–278 · 4 物种:183（此处不校验）· 5 排期时间 WIB:184→`parseTime`:281–291。跳行：表头**按行号**`row.getRowNum()==0`:176–178（**从不校验表头文字**）· 空行:185–187 · 模板提示行按 `HINTS[0]` 精确匹配 `isHintRow`:214–216 · null-safe `cell()`:218–220。
模板下载 `template(List<PublishIdentityOption>)`:84–136 —— `XSSFWorkbook`:85，sheet `"批量内容"`:86 / `"选项"`:87，下拉 `createFormulaListConstraint`:119–125 + `addDropdown`:149–157（`CellRangeAddressList(2,1000,col,col)`:153）。

**校验分三处**：
1. **解析期 fail-fast（整份文件中止）**：空文件:167–170（`admin.err.seedBatch.fileRequired`）· POI/IO 异常:197–200（`parseFailed`）· 零数据行:201–204（`noDataRows`）· **单元格内容类型非法 → 整份上传失败** `parseType`:274–277（`typeNotAllowed`）。刻意宽松处：时间解析失败 → null:288–290；账号解析不出 → null:255。
2. **落库期收集问题**：`SeedBatchEntryService.appendRows`（`…/admin/seed/service/SeedBatchEntryService.java:134–182`）—— 缺作者:151–153 · 缺类型:154–156 · 图片文件名不在批次:158–166；以 `；` 拼进 `row.setErrorMessage(...)`:176–178。**行仍然落库**（作者占位 `0L`:170、类型默认 `DAILY`:171）。
3. **权威规则**：`SeedBatchValidator.validate / validateRow`（`…/admin/seed/service/SeedBatchValidator.java:57–141`）—— 继承错误:74–76 · 作者存在/在池内/启用:79–94 · 类型允许集:98–105 · 物种枚举:108–112 · 资产 URL 仍有效:117–124 · 正文与图片都空:127–131 · **内容哈希重复 = 警告不是错误**:134–139。空白字段继承批次默认：`SeedRowDefaults.java:45–90`。

**错误呈现**：`record RowValidation(SeedBatchRow row, List<String> errors, boolean duplicate)`（`…/admin/seed/dto/RowValidation.java:18`，`passes()`:20–22 / `warns()`:25–27）；**逐行累积全部错误、逐行全收**（`SeedBatchValidator.java:62–66,:70`）。发布统计 `record PublishOutcome(int published, int scheduled, int skippedByError, int skippedByDuplicate, int failed, int alreadyDone)`（`…/admin/seed/service/SeedBatchPublishService.java:83–88`）。**导入本身只给一条 flash，无逐行报告**（`AdminSeedBatchWorkspaceController.java:248–250`）。

**事务边界**：`SeedBatchExcelService` **完全无 `@Transactional`**（解析在事务外）；导入的唯一事务是 `SeedBatchEntryService.appendRows` 上的 `@Transactional`:133 ⇒ **整份文件原子**。发布则相反粒度：**逐行 `@Transactional(propagation = REQUIRES_NEW)`**（`SeedBatchPublishService.java:240` `safelyFail` / `:255` `publishOrScheduleRow`；`confirm(...)`:97,:107）。

**dry-run / 预览**：**导入无 dry-run**——`POST …/import` 直接写 DRAFT 行。预览是对已落库行的独立步骤：`GET /admin/seed-batches/{batchId}/preview`（`AdminSeedBatchWorkspaceController.java:344–355`）→ `SeedBatchPublishService.preview(long batchId)`:76–80（readOnly，返 `List<RowValidation>`）。流程中唯一开关是 `includeDuplicates`（controller:362，service:97–98/:107–108，默认 false）。

**Controller**：`@Controller AdminSeedBatchWorkspaceController`（`…/admin/seed/web/AdminSeedBatchWorkspaceController.java:35`，**无类级 `@RequestMapping`**）；`AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')"`:37–38，每个 handler 都 `@PreAuthorize(AUTH)`。导入：`@PostMapping("/admin/seed-batches/{batchId}/import")`:240 · `importExcel(@PathVariable long batchId, @RequestParam MultipartFile file, RedirectAttributes flash)`:242–243 · 体 `excel.parse(file)` → `entry.appendRows(batchId, raws)`:245–246。模板下载 `@GetMapping("…/template")` + `@ResponseBody`:226–237。

🔴 **限额**：`parse` **不检查 content-type、不检查文件名、无行数上限**（从不读 `getContentType()`/`getOriginalFilename()`，全靠 `WorkbookFactory.create` 抛异常）；前端仅 `accept=".xlsx,.xls"` 提示（`templates/admin/seed-batch-workspace.html:115`）；唯一真实上限是全局 multipart（`application.yml:202–205`：`max-file-size 10MB` / `max-request-size 20MB`）。
🔴 **无任何测试端到端跑 `parse(MultipartFile)`**（`…/src/test/java/com/tailtopia/admin/seed/SeedBatchEntryIntegrationTest.java` 只覆盖 `template`:327,:346 · `identityLabel`:364 · `parseAccount`:464–467 · `parseTime`:473–478）。

**它不是唯一的 Excel 导入器**——另有遗留并行实现 `AdminSeedBatchService.readLines(MultipartFile)`（`…/admin/virtual/service/AdminSeedBatchService.java:60–89`）：`WorkbookFactory.create`:69，**只读 2 列**:72–74，压平成 `文本 ||| url1, url2`:78–82，按文件名后缀还接受 `.csv/.tsv/.txt`:66–68，**直接发布无预览**（`publishBatch` `@Transactional`:100）；端点在 `AdminSeedBatchController.java:68–69` / `@PostMapping("/admin/seed-batch/import")`:110–113。POI 导出侧：`AdminPaymentExportService.java:67` · `AdminUserService.java:222–237`。

### D.10 图片上传 / 预签名 / 转存相关服务类

| 类 | file:line | 职责 |
|---|---|---|
| `shared.media.AliyunOssClient` | `…/shared/media/AliyunOssClient.java:19` | SDK 包装：预签名 PUT、服务端 put、CDN/EXIF URL 构造 |
| `shared.media.PresignedUploadService` | `…/shared/media/PresignedUploadService.java:24` | 签发上传票据（key 生成 + headers + TTL） |
| `shared.media.SignedUrlService` | `…/shared/media/SignedUrlService.java:23` | 私有桶短 TTL 预签名 **GET** |
| `shared.media.MediaProperties` / `MediaConfig` | `:12` / `:9` | `@ConfigurationProperties("media")`（嵌套 `Oss`:46 · `SignedUrl`:130） |
| `shared.media.MediaScope` | `:11` | `PUBLIC("public")`:12 / `PRIVATE("private")`:13 |
| `shared.media.MediaObjectKeys` | `:31` | objectKey 归属校验（`belongsTo`:37 · `requireAllOwned`:52） |
| `shared.media.MediaDeletionService` | `:18` | 删除 **no-op**（F21） |
| `shared.media.ImToOssArchiver` | `:21` | IM 聊天图 → 私有桶转存 |
| `shared.media.ImMediaFetcher`（接口） | `:9` | **`src/main` 内无实现 bean** |
| `shared.media.web.MediaController` | `:26` | **唯一签发凭证的端点** |
| `shared.media.dto.UploadUrlRequest` / `UploadUrlResponse` | `:11` / `:19` | `{scope, contentType}` / `{uploadUrl, objectKey, method, headers, publicUrl}` |
| `admin.seed.service.AdminSeedImageService` | `:30` | multipart → 公共桶，7 条后台上传线共用 |
| `admin.tagicon.AdminTagIconService` | `:36` | 标签图标（仅 PNG/WebP，≤512KB） |
| `admin.service.AdminVetService` | `:31`（`updateAvatar`:149） | 兽医头像 |
| `profile.service.OgImageService` | `:25` | 渲染并上传 OG PNG |
| `content.larksync.LarkContentSyncService` / `LarkContentClient` | `:66` / `:38`（`downloadFile`:237） | Lark Drive 转存 |
| `shop.service.ShopImageUrlResolver` | `…/shop/service/ShopImageUrlResolver.java:26` | objectKey → CDN URL（与 `AliyunOssClient.publicUrl` 重复实现） |
| `admin.seed.service.SeedBatchAssetService` | `:34` | 批次资产上传 + 配额 + SHA-256 去重 |
| `shared.logging.LogSanitizer` | `:19`（正则:47–48） | 掩蔽 `Signature=`/`OSSAccessKeyId=`/`x-oss-`/`X-Amz-`/`Expires=\d` |

**预签名上传**：`AliyunOssClient.presignedPutUrl(bucket, objectKey, contentType, ttlSeconds, publicRead)`:56（体:56–72）—— `HttpMethod.PUT`:61 · 过期:62 · **Content-Type 进签名**:63 · 公共对象把 `x-oss-object-acl: public-read` 进签名:64–66；**用主账号 AK，无 STS/RAM 角色**。`PresignedUploadService.issue(MediaScope, long userId, String contentType)`:43–69 —— TTL 默认 **600s**（`MediaProperties.java:134`），缺 bucket:45–47 / PUBLIC 缺 CDN base:51–53 fail-fast；headers:62–66；`publicUrl` 仅 PUBLIC:67。🔴 **无 MIME 白名单**——空 → `application/octet-stream`:55，`extFor`:86–93 只认 jpeg/png/webp/heic，其余 → `.bin`。
**预签名读**：`SignedUrlService.sign(objectKey)`:36（:41–43）· `signAll(List)`:53（:59–63），TTL 默认 **300s**（`MediaProperties.java:132`），**从不记日志**。消费者：`TriageProcessor.java:99` · `ConsultAiContextService.java:23` · `ConsultRequestService.java:62` · `AdminSupportTicketQueryService.java:86` · `AdminAnomalyController.java:33` · `AdminVetQualificationController.java:35` · **`AdminReturnController.java:57`**。公共桶读是**不签名**的裸 CDN URL（`AliyunOssClient.publicUrl`:85–87 · `ShopImageUrlResolver.publicUrl`:38–47）。

**objectKey 约定**：`<keyPrefix><scope>/<userId>/<base64url 16 随机字节>.<ext>`（`PresignedUploadService.java:56–57`，随机:79–83）；prefix 取 `normalizedKeyPrefix()`（`MediaProperties.java:115–127`，env `MEDIA_OSS_KEY_PREFIX`，如 `stag/`）；归属校验重建同一 prefix（`MediaObjectKeys.java:42`）。
其余前缀：`private/health/<petId>/<22位base62>.jpg`（`ImToOssArchiver.java:71`）· `public/og/<profileId>/<cardToken>.png`（`OgImageService.java:77–78`）· `public/vet-avatar/<vetId>/<UUID>.<ext>`（`AdminVetService.java:155–156`）· `public/tag-icon/<UUID>.<ext>`（`AdminTagIconService.java:103`）· `public/lark-content/<contentCode>/<fileName>`（`LarkContentSyncService.java:225–226`）· 通用 `public/<folder>/<UUID>.<ext>`（`AdminSeedImageService.java:75–76`）。
`folder` 取值含 **`shop-product`**（`AdminShopProductController.java:210`）· **`shop-banner`**（`AdminShopBannerController.java:101`）· **`shop-return-inspection`**（`AdminReturnController.java:218`）· `seed-post` · `seed-batch/<batchId>` · `virtual-avatar` · `pin-promo`。

🔴 **EXIF：服务端不做剥离**。字节原样进 OSS，`src/main` 无 Thumbnailator/imgscalr/metadata-extractor；`ImageIO` 只用于读头测尺寸（`ImageBytesMeasurer.java:36–48` · `ImageSizeBackfillService.java:129–159`）与生成新 OG 图（`OgImageService.java:42–70`）。剥离是**投递期、委托 OSS**：`EXIF_STRIP_PROCESS = "image/format,jpg"`（`AliyunOssClient.java:25`）· `publicExifStrippedUrl`:93–95 · `static exifStrippedDeliveryUrl(String)`:101–107；**只在 5 处施用**：`CardPageController.java:113,:168` · `VisitorProfileResponse.java:44` · `VisitorProjectionService.java:262,:272`。**Feed / shop / admin 的投递路径都没套**，`SignedUrlService` 也从不追加 `x-oss-process` ⇒ 存量对象保留原始 EXIF/GPS。（与 **E4**「客户端为主 + 公开桶服务端兜底」的差距即在此。）

**转存 / copy-from-URL**：
- **(A) Lark Drive → OSS（在用）**：`LarkContentSyncService.publishRow(...)`:220，循环:222–230；下载:224；`oss.putPublicObjectWithAcl(key, bytes, contentTypeOf(bytes))`:228；类型按魔数 `contentTypeOf`:280。下载 `LarkContentClient.downloadFile(String fileToken)`:237–253 打固定端点 `/open-apis/drive/v1/files/{token}/download`，**无 SSRF 面**。`MAX_IMAGE_BYTES = 15MB`:41，在**全量下载到内存之后**才判:246；魔数校验 `looksLikeImage`:256–271（JPEG/PNG/GIF/WEBP）于:249；空体:243。⚠️ Lark 列表来的 `fileName` **未净化**即拼进 key:226–227。
- **(B) IM → 私有桶（死路）**：`ImToOssArchiver.archiveImImagesToPrivate(long petId, List<String> imImageRefs)`:45–63 —— `fetcherProvider.getIfAvailable()` 恒 null（`ImMediaFetcher` 无实现）→ 告警并返空:49–54。无大小限制、无类型检查、无 SSRF 检查；key `buildPrivateKey(petId)`:66 写死 `.jpg`；`putPrivateObject`:59 → `AliyunOssClient.java:187–195` **完全不设 ObjectMetadata**（与 `putPublicObject` 不同）。调用方 `ConsultArchiveListener.java:24–26` · `HealthEventService.java:30`。
- **(C) 未校验的远端抓取（非转存）**：`ImageSizeBackfillService.measure(String url)`:129–159 —— `URI.create(url).toURL().openConnection()`:135 读的是 `content_posts.image_urls`；有超时:136–137，但**无 scheme/host 白名单、无 SSRF 防护**，异常吞成 null:150–153。

**签发凭证端点恰好一个**：`POST /api/v1/media/upload-url`（`MediaController.java:39–45`，类级 `@RequestMapping("/api/v1/media")`:25）。**无 `@PreAuthorize`**，靠 JWT 链 + `currentUserId(jwt)` 抛 401:47–56；限流 `rl:media:upload:<userId>` **30 次 / 1 分钟**:28–29,:43。
服务端 multipart 端点（字节过后端）：`AdminSeedAssistController.java:56–59` · `AdminSeedBatchWorkspaceController.java:305–309` · **`AdminShopProductController.java:205–208`** · **`AdminShopBannerController.java:96–99`** · **`AdminReturnController.java:211–215`** · `AdminWebController.java:267–271`（内联 `image/*` + ≤5MB:272–280）· `AdminContentTagController.java:97,:122` · `AdminUserTagController.java:91,:114` · `AdminContentPinController.java:95` · `AdminVirtualAccountController.java:94`。
闸门：`AdminSeedImageService` `MAX_IMAGES=9`:33 · `MAX_BYTES=10MB`:36 · 白名单 `{image/jpeg,image/png,image/webp}`:39（HEIC 拒:119–122）；`AdminTagIconService` `MIN_SIDE=42`:49 · `MAX_BYTES=512KB`:52 · 白名单 `{image/png,image/webp}`:55（JPEG 拒:97–100）。
🔴 `MediaObjectKeys.requireAllOwned` **全仓只被调用一次**：`shop/returns/service/ReturnRequestService.java:107`。工单 `attachmentObjectKeys` 至今未校验（`MediaObjectKeys.java:28–29` 自承，落库在 `SupportTicketService.java:230–234,:99–101`）。

**配置**（`application.yml:140–153`）：`ALIYUN_ACCESS_KEY_ID`:141 · `ALIYUN_ACCESS_KEY_SECRET`:142 · `OSS_ENDPOINT`（默认 `https://oss-ap-southeast-5.aliyuncs.com`）:144 · `OSS_REGION`（`ap-southeast-5`）:145 · `OSS_PUBLIC_BUCKET`:146 · `OSS_PRIVATE_BUCKET`:147 · `OSS_CDN_BASE_URL`:148 · `MEDIA_OSS_KEY_PREFIX`:150 · `SIGNED_URL_TTL_SECONDS`(300):152 · `UPLOAD_URL_TTL_SECONDS`(600):153。凭证闸 `hasCredentials()`（`AliyunOssClient.java:35–38`）：缺失时 `putPublicObject` **直接返回假 CDN URL 而不上传**:141–144；`putPrivateObject` **无此闸**。
**删除按 F21 全面停用**：`AliyunOssClient.java:197–198` 内联写明禁令，`src/main` 无任何 `deleteObject/deleteObjects`；`MediaDeletionService.deletePrivateKeys`:23–28 / `deletePublicByUrls`:31–36 只记日志；no-op 调用方 `AccountDeletionService.java:54,:152` · `ProfileService.java:38,:118`。ACL 现状：`putPublicObject` 恒设 `x-oss-object-acl: public-read`（`publicObjectMetadata`:131–153,:156,:158,:164–170），`putPublicObjectWithAcl` 已 `@Deprecated` 透传:178–181。

### D.11 权限码常量类、分组列表与 `AdminPermissionsTest` 计数

`public final class AdminPermissions` —— `/Users/dai/work/petgo-platform/petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java:13`；私有构造:329–330；唯一方法 `public static boolean isValid(String code)`:333–335（= `ALL.contains(code)`）。命名：常量 = 码的 UPPER_SNAKE，值 = `<模块>.<动作>` 小写点分，**原样用作 Spring authority**。

**总计 72 个权限码**，按命名空间：`vet.*` 7（:18–25）· `user.*` 8（:28,:29,:30,:32,:38,:39,:46,:53）· `content.*` 15（:56–60,:62,:68,:69,:75,:76,:84,:91,:99,:108,:155）· `consult.*` 3（:111–113）· `rating.*` 1（:116）· `support.*` 2（:119,:120）· `refund.*` 4（:124,:126,:128,:129）· `order.*` 3（:133,:134,:136）· `virtual_account.*` 2（:140,:141）· `seed.*` 1（:176）· `config.*` 6（:180,:182,:193,:202,:210,:220）· `settlement.*` 2（:224,:226）· `payment.*`/`risk.*` 4（:230,:238,:240,:241）· **`shop.*` 10**（`SHOP_PRODUCT_VIEW`=`shop.product_view`:245 · `SHOP_PRODUCT_EDIT`=`shop.product_edit`:247 · `SHOP_COST_VIEW`=`shop.cost_view`:249 · `SHOP_COST_EDIT`=`shop.cost_edit`:251 · `SHOP_INVENTORY_VIEW`=`shop.inventory_view`:253 · `SHOP_INVENTORY_EDIT`=`shop.inventory_edit`:260 · `SHOP_ORDER_VIEW`=`shop.order_view`:264 · `SHOP_ORDER_FULFILL`=`shop.order_fulfill`:266 · `SHOP_ORDER_PHONE_SEARCH`=`shop.order_phone_search`:274 · `SHOP_FINANCE_VIEW`=`shop.finance_view`:283）· `admin.*` 4（:286–289）。
仅存在于注释的死码（非常量）：`content.stats_view` / `content.stats_export`（:157–163）· `content.export` · `content.view_reporters` · `consult.edit_sessions` · `admin.manage_roles`（:10–11）。

**分组**：`public record PermissionGroup(String titleCode, List<String> permissionCodes)`（嵌套，:14）；`public static final List<PermissionGroup> GROUPS = List.of(...)`:292（跨:292–317）—— **恰好 2 组，各 36 码**，每个常量仅属一组：index 0 `perm.group.view`:293–304、index 1 `perm.group.edit`:305–317。导出类码（`CONTENT_LIST_EXPORT` · `USER_PHONE_EXPORT` · `ORDER_EXPORT` · `PAYMENT_LIST_EXPORT` · `SHOP_ORDER_PHONE_SEARCH`）归在 **view 组**。
`ALL` **从 GROUPS 派生而非手工维护**：`Stream.concat(GROUPS…flatMap, Stream.of(CONTENT_MANUAL_REVIEW)).distinct().toList()`:323–327（concat 已是 no-op，历史注释:320–322）；**无 `values()`**。消费者 `AdminAccountAdminController.java:140–141` → 模板 `admin/admin-accounts.html:55,:128`，标签 `#{'perm.' + ${p}}`（:50,60,102,134）。

**`AdminPermissionsTest`** —— `/Users/dai/work/petgo-platform/petgo-backend/src/test/java/com/tailtopia/admin/account/domain/AdminPermissionsTest.java:17`（包级可见，不起 Spring）。用例：`allContainsV11NewCodesWithoutDuplicates()`:19/:20 · `isValidRecognizesNewCodesAndRejectsUnknown()`:45/:46 · `everyPermissionHasBilingualLabel()`:57/:58 · `listStableSize()`:77/:78 · 辅助 `load(String path)`:68。
**计数断言原文**：
- `:125` `        List<String> all = AdminPermissions.ALL;`
- `:126` `        assertThat(all).hasSize(72);`
- `:42` `assertThat(new HashSet<>(AdminPermissions.ALL)).hasSameSizeAs(AdminPermissions.ALL);`（去重，相对断言）
- `:40` `assertThat(AdminPermissions.ALL).contains(AdminPermissions.CONTENT_MANUAL_REVIEW);`（**按名钉住**，防止靠改数字「修」失败，理由:33–39）
- 72 的账在注释台账 :79–124（:117「45+10+15=70」→ :119「+content.list_export ⇒71」→ :123–124「+payment.list_export ⇒72」）。⚠️ **台账自身前后不一致**（:112 在 :96 已累到 62 后又从 49 重起），**只有最终的 72 正确**。
- `everyPermissionHasBilingualLabel`:59–65 只校验 `messages_zh_CN.properties` 与 `messages_en.properties`（loader:68–75）——**`messages.properties` 与 `messages_id.properties` 不在其覆盖内**。

**其它复制了该清单或计数的地方**：
- 守门测试（从 ALL 派生，无硬编码数字）：`AdminPermissionWiringTest.java:110–118`（双向：`src/main` 的 `.java`/`.html` 里每个 `hasAuthority('x')` 字面量 ∈ ALL，且每个 ALL 码至少有 1 处闸门；排除项:58–59）· `AdminRoleTest.java:24` · `Epic8ChainIntegrationTest.java:180–181,:187–196`。
- `AdminRole.java:3…` 有 50 条 `import static …AdminPermissions.*`；类型安全，但**角色覆盖度刻意不被门控**（`AdminPermissionWiringTest.java:48–56`）。
- 🔴 `db/migration/V86__add_column_comments.sql:623` —— `COMMENT ON COLUMN admin_account_permissions.permission_code` 里**内嵌了一份过期的硬编码清单**（约 34 码，仍列已删的 `content.export`/`content.view_reporters`，**缺全部 `shop.*`** 及 `content.pin_*`/`content.tag_*`/`content.throttle_*`/`user.phone_*`/`config.algo_param_*`/`payment.list_export`）。
- 模板里 100+ 处 `sec:authorize="hasRole('SUPER_ADMIN') or hasAuthority('<code>')"` 字面量，集中在 `templates/admin/layout.html`（:26,31,39,42,49,57,60,71,76,82,86,97,100,103,106,111,116,119,124,139,148,155,160），另含 `shop-return-detail.html:281` 等；由 `AdminPermissionWiringTest` 守而非计数守。
- i18n：`messages{,_zh_CN,_en,_id}.properties` 各 **74** 个 `perm.*` key = 72 码 + 2 个分组标题（`messages_zh_CN.properties:335–336`）。
- Dart 侧 **无任何后台权限码**；`button_ids.dart:8,12,13` 的 `consult.start` / `vet.accept_queue` / `vet.advice_template` 是埋点按钮 id，命名空间无关。
- 🔴 **文档已过期**：`/Users/dai/work/petgo-platform/docs/reference/admin-permission-codes.md` 最后更新 2026-06-30，**只列 72 码中的 19 个**。


