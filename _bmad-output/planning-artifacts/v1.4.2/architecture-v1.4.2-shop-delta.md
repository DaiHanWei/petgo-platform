---
title: "V1.4.2 shop 主题（电商重构）· 架构增量"
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: complete
completedAt: '2026-11-05'
revisedAt: '2026-11-05'   # 就绪度评审 B1–B3 / I1–I15 / m1–m11 与 S-3 ~ S-9 回改
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md
  - _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md   # 通知约束 / PushCategory 交界
  - _bmad-output/implementation-artifacts/specs/spec-topup-60min-window-reuse.md      # GemPay 码 65 分钟
  - _bmad-output/implementation-artifacts/v1.1.0/GEMPAY-INTEGRATION-DESIGN.md         # GemPay 查单 /history
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
  - _bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'petgo-platform'
user_name: 'Dai'
date: '2026-11-05'
version: v1.4.2
theme: shop
baseline: _bmad-output/planning-artifacts/architecture.md
code_baseline: dev_1.4.2（2c507755，已含 social-topic 合入）
---

# V1.4.2 shop 主题（电商重构）· 架构增量

> 只写本主题新增或改变的技术决定，均对照 `dev_1.4.2` 代码核实。**权威顺序**：决策日志 `决策日志-shop.md` > PRD（变更记录日期最晚的一条为准）> 本文件 > UI 稿。与代码现状冲突处已在 §1.2 列出、以代码为准。
>
> **范围（2026-11-05 操作者拍板）**：本线 = App 端 + 后端。后台**页面**另起一条线（《142电商后台prd.md》未入库）；但后台操作背后的**业务逻辑（service 层、金额计算、状态机）在本线实现并测试**，后台线只加 Controller 与页面（拍板 S-2）。本线 stag 验收时券、banner、运单等靠 SQL 种子与内部测试入口。

## 1. 项目上下文分析

### 1.1 需求概览

10 项需求（FR-127 / 128 / 130 / 131 / 132 / 133 / 134 / 136 / 172，FR-129 已延后），按改动性质分五类：

| 类别 | 需求 | 架构含义 |
|---|---|---|
| 新领域 | FR-130 优惠券、FR-172 Biteship 物流 | 券模板 / 用户券 / 兑换码 / 新人券四张表；Biteship 客户端、报价持久化、运单、Webhook、物流异常；本线最重 |
| 资金 | FR-134 资金闭环、FR-131 PawCoin 自选 | 主动查单任务、关单推迟到码失效、迟到款入账、取消退款（含现金）新聚合、成长值扣回；**安全攸关** |
| 结算重做 | FR-128 一页结算、FR-127 运费前置 | 结算试算接口扩参（立即购买 / 券 / 快递 / 保价 / 是否用币），订单快照新增金额列 |
| 改现有规则 | FR-132 banner 轮播、FR-133 页面重做、FR-136 复购下线 | banner 多张 + 排期 + 领券跳转；订单按状态筛选；评价列表开关；品类筛选下线；复购扫描停用 |
| 纯 App | FR-133 各页 UI | Toko 首页、详情、购物车、结算、支付面板、订单、我的券、地址簿全量重做（约 95 屏） |

**非功能需求：**
- 资金正确性：金额全部 `long`（印尼盾无小数），计算单点实现（P-1）；券占用、PawCoin 扣减、库存锁同事务；任何「已到账」都不能被丢弃。
- 成本：Biteship 询价按次计费（超配额每次 Rp5），报价必须复用（D-9）。
- 外部依赖可用性：Biteship 询价失败 → 不可提交、可重试；Webhook 不可信 → 回查（D-12）。
- 护栏：对外标识不可枚举（券、运单、退款单一律 token）；Flyway 时间戳版本号；`ck_notifications_type` DROP + ADD 重列全集。

**规模：** 高复杂度、全栈。后端组件约 12 个（券 / 兑换 / 新人券 / 结算计价 / Biteship 客户端 / 报价 / 运单与物流状态 / 物流异常 / 查单与迟到款 / 取消退款 / banner / 复购下线），App 约 10 块页面组。

### 1.2 已核实的代码现状（`dev_1.4.2`，与 PRD 写法有出入处以代码为准）

路径缩写：`B/` = `petgo-backend/src/main/java/com/tailtopia/`，`A/` = `petgo_app/lib/`。

1. **订单状态机** `ShopOrderStatus` 8 值：PENDING_PAYMENT / PENDING_SHIPMENT / SHIPPED / DELIVERED / COMPLETED / CANCELLED / REFUNDING / REFUNDED；无「已发货 → 已完成」直达边（`B/shop/order/domain/ShopOrderStatus.java:61-78`）。**用户「已发货时确认收货」实际走 SHIPPED → DELIVERED → COMPLETED**。
2. **时限常量写死**：`ShopOrder.PAYMENT_WINDOW = 60` 分钟（`:244`）；`AUTO_DELIVER_AFTER = 7` 天、`AUTO_COMPLETE_AFTER = 7` 天（`:310-313`）。扫描在 `ShopOrderExpiryScanner`：超时取消每 60 秒、自动送达 / 完成每 10 分钟；无 advisory lock，靠 `@Version` 乐观锁 + 逐笔独立事务。
3. **结算只能走购物车**：`PlaceOrderRequest(addressToken, entrySource, triggerType)` 无 SKU 字段（`MeCheckoutController.java:87/161`）；立即购买在 App 端是「先加购再跳结算」（`A/features/shop/presentation/product_detail_page_v2.dart:616-631`）。
4. **PawCoin 自动抵扣**：`PaymentSplit.compute` 取 min(应付, 余额, 单笔上限)（`B/shop/order/domain/PaymentSplit.java:33-54`）；上限在单例表 `shop_pawcoin_rules.max_coin_per_order`（默认 1,000,000），`AdminShopPawcoinRulesService` 无调用方（无后台页）。纯币走 `settlePureCoin`，不建支付意图 → **纯币订单不发 `shop_payment_paid`**。
5. **运费**：`shipping_zones(kecamatan, kota_kabupaten, provinsi, fee, active)` 按 Kecamatan 一维固定运费；免运门槛 `shipping_settings.free_shipping_threshold`（0 = 不做）；地址 `shipping_addresses.kode_pos` 5 位必填。
6. **GemPay**：`GemPayGateway.createCharge` 不传有效期，`ChargeRequest` 也无该字段；GemPay 码固定 **65 分钟、不可调**（`spec-topup-60min-window-reuse.md`，用户此前确认）。主动查单 `queryCharge`（`POST /history`）已接；收款回调只有 success / failure，没有 expired。
7. **迟到款被丢弃**：取消时 `releaseAndCancel` 把意图置 FAILED（`ShopOrderPaymentService.java:350-353`）；之后的到账回调在 `PaymentIntentService.applyCallback` 见终态直接 return（`B/pay/service/PaymentIntentService.java:314-316`）。`PaymentReconciliationService.reconcile/reconcilePending` 无任何生产调用方。送礼线对照：`GiftPaidHandler.java:68-87`（关单后到账 → 退为 PawCoin）。
8. **异常订单退款缺现金**：`AdminShopOrderExceptionService.cancelWholeOrder`（`:119-165`）只退 PawCoin 段 + 补偿，现金段只记审计；`cancelLine`（`:176-200`）只回补库存、不退钱。成长值扣回消费 `RefundSucceededEvent`（`B/growth/listener/GrowthRefundListener.java:43`），发布点两处（`AdminShopOrderExceptionService:139`、`RefundExecutionService:167`）。
9. **退款去向**：`CashDestination{TO_BANK, TO_PAWCOIN}`，`PayoutChannel` BCA 0 / OVO 2500 / GOPAY 2500；补偿配置 `PawCoinConfig.compensation_premium_rate/_cap`。现有退款挂在退货单 `ReturnRequest` 上（按退货行算 quote，口径与本线取消退款不同，见 D-15）。
10. **物流**：`shipments(carrier ∈ JNE/SICEPAT/ANTERAJA, tracking_no, carrier_cost, status, shipped_at, delivered_at)`；后台手工录入发货 `POST /admin/shop/orders/{token}/ship`。`ShopOrderDetailView` 无 `paidAt` / `cancelledAt`。
11. **购物车**：`shop_cart_items.selected` 默认 true；失效行按库内值原样下发（可能为 true），只是不计入合计（`CartService.java:273-274`）。
12. **banner**：`shop_banners(target_type ∈ NONE/PRODUCT/CATEGORY/EXTERNAL, target_value, image_key, active, sort_weight)`，只取一张（`findFirstByActiveTrueOrderBySortWeightDescIdDesc`），无排期列；目标商品下架下发 NONE。
13. **复购**：`/me/shop/recommendations`（`ProfileRecommendationService`，保留）与 `/me/shop/repurchase-cards*`（下线）同在 `MeRepurchaseController`；日扫 `RepurchaseScanService`（`ShopOrderExpiryScanner.java:120` 触发）；推送 `REPURCHASE_FOOD_LOW`。
14. **商品**：`shop_skus` 只有 `net_weight_g`（净含量），**无包裹重量与长宽高**；`shop_products.category`（MAKANAN / OBAT_VITAMIN / CAMILAN / PERAWATAN）、下架 `is_active=false`；详情接口对下架与不存在**都返回 404**（`ShopProductQueryService.java:182`）。评价均分实时 AVG（只算已发布）。
15. **通知**：`NotificationType` 41 值；`ck_notifications_type` 最后一次重建 `V20261104_2010__rebuild_ck_notifications_type_and_push_mutes.sql`（41 值，已含 social-topic 的 2 个）。`PushCategory.PROMO` 存在（可关）、`ORDER_ACCOUNT` 不可关；守卫 `PushCategoryTest`、`MentionNotifyTest`。App 推送设置「INFO & PROMO → Promo & aktivitas」开关即 `PROMO`。
16. **商家平台**：门店归属只在订单头 `shop_orders.referrer_merchant_id`；返佣由商家平台只读扫描 `shop_orders`（`paid_at`, `id` 游标）+ `shop_order_lines.unit_price` 与 SKU 批发价自算，**本仓无返佣接口**。
17. **我的订单**：`GET /api/v1/orders?type=&includeEcommerce=` 不支持按状态筛选或计数。
18. **App**：
    - 页面在 `A/features/shop/presentation/`（`toko_page_v2` / `shop_search_page` / `product_detail_page_v2` / `cart_page_v2` / `checkout_page_v2` / `shop_order_detail_page_v2` / `refund_method_page_v2`（未挂路由）/ `shop_return_unavailable_page`），地址在 `address/presentation/`；订单列表共用 `A/features/order/presentation/order_list_page_v2.dart`。
    - `/shop?category=` 由 `TokoPageV2(initialCategory:)` 消费（`app_router.dart:207`），跳 `/shop` 一律 `go`。
    - 共用支付面板 `A/shared/widgets/qr_payment_sheet.dart:43` `showQrPaymentSheet(...)`，调用方 5 处（电商 / 送礼 / AI 解锁 / 身份证高清 / 一次性解锁 → 宠物名额月租）；充值页与兽医计时支付直接嵌 `qr_pay_parts.dart` 子组件。金额标签 `qrPayTotalLabel`、「取消支付」`vetPayCancel`。
    - 退货申请与退款方式两条路由 builder 已换成 `ShopReturnUnavailablePage`（`app_router.dart:717-728`）。
    - 埋点 `toko_` 事件为各页字面量（38 个），约束在 `test/analytics/v112_events_test.dart`（前缀表已含 `toko_`）。
    - 推送：App 端无任何电商通知类型的深链 / 文案映射。
19. **订单头已有金额列**（`ShopOrder.java:70-113`）：`goods_subtotal`、`shipping_fee`、`shipping_discount`、`total_amount`（= 应付）、`coin_amount`、`cash_amount`、`refunded_total`、`refunded_coin`、`pay_channel`。新增列不得与之重名，沿用这些列承载对应含义（2026-11-05 就绪度评审修正）。行级 `shop_order_lines.refunded_qty` 已被异常取消使用，部分取消复用它，不新增 `cancelled_qty`。
20. **PawCoin 现在是付款时扣**（`ShopOrderPaidHandler:70`、`settlePureCoin`、`CheckoutService:46`；`ShopFinanceDashboardService:232` 口径注释同）→ S-3 改为提交时扣。
21. **电商意图会在 60 分钟被提前过期的三条路径**：意图有效期取订单剩余时间（`ShopOrderPaymentService.java:357-371`）；全局 `PaymentIntentExpiryScanner` 不分业务到点置 EXPIRED（`PaymentIntentService.java:240-252`）；`statusOf` 轮询就地置 EXPIRED（`:368-374`）；另 `requireOwn()` 调 `expireIfOverdue`（`:101`）读单即关单。`reconcile` 对终态意图不查，且在事务内调网关。
22. **`queryCharge` 分不清未付与查询失败**：非 00 错误码与查无数据一律返回空（`GemPayGateway:162-171`）；GemPay `/history` 有 `Expired => Success`、`Failure => Success` 复合态。
23. **后端 21 个文件按 `PaymentStatus.PAID` 统计**（财务看板、导出等）——新增意图状态会让款项从报表消失。
24. **商家平台只扫 `return_requests` 计退款**（`tailtopia-merchant` ARCHITECTURE-SPINE AD-6），看不到本线新表。
25. **请求日志全接口落盘**（接口日志系统）——含路径密钥的 URL 需脱敏。

### 1.3 技术约束与依赖

- 代码基线 `dev_1.4.2`；Flyway 当前最新 `V20261104_2058`。
- 定时任务：`@Scheduled(fixedDelayString="${petgo.*}")` 或 cron + zone；需要串行用 `pg_try_advisory_xact_lock(hashtext(...))`（`TopicHeatJob` 先例）；WIB 用 `ScheduleWindow.WIB`。
- **外部依赖 Biteship**（`https://api.biteship.com`，key 前缀区分测试 / 正式）：报价 `POST /v1/rates/couriers`（邮编 / area_id / 经纬度三选一；`items` 克与厘米；`courier_insurance`）、下单 `POST /v1/orders`（`reference_id` 唯一）、取消 `POST /v1/orders/:id/cancel`、查单 `GET /v1/orders/:id`、Webhook（`order.status` / `order.price` / `order.waybill_id`）。**Webhook 验签与重试机制公开文档未写**；正式 key 的 Order API 需申请开通。
- 与同版本 social-topic 线交界：social-topic 已先合入 `dev_1.4.2`（`V20261104_2010` 重建通知约束为 41 值）→ **本线是后合并方**，在自己的迁移里 DROP + ADD 重列全集（D-21）。
- 与商家平台交界：不改 `shop_orders.paid_at`、`referrer_merchant_id`、`shop_order_lines.unit_price` 的语义（返佣基数不受券影响，PRD §2.3）。

### 1.4 跨模块关注点

- **金额单点计算**：结算试算、提交、订单详情、取消退款、迟到款重下单共用同一个计价器（P-1）。
- **券的占用**：结算试算只读、提交时加锁占用、付款转已使用、取消按原因退回或作废——四个入口同一服务。
- **支付意图生命周期**：查单任务、「我已付款」、关单、迟到回调四个入口对同一意图，必须幂等。
- **订单状态与物流状态解耦**：订单状态机只增「物流异常标记」，不新增订单状态（D-13）。
- **通知与推送**：2 个新通知类型；复购推送停发但枚举保留。
- **埋点**：沿用 `toko_` 前缀，新事件按 PRD §4。

## 2. 起步与技术栈

棕地增量，不选起步模板；不新增任何后端依赖（Biteship 用现有 `RestClient` 直连，不引 SDK），不新增 App 依赖（轮播用 `PageView`，不引轮播库）。护栏不变：异步只用 `@Async` + DB 状态机；禁 MQ / 通用缓存 / 新中间件；schema 归 Flyway。报价复用落库（D-9），不引缓存层。

## 3. 核心架构决策（2026-11-05，按推荐定稿，S-1 / S-2 操作者拍板）

迁移一律时间戳版本号 `V<yyyyMMdd_HHmm>__<snake>.sql`，排在 `V20261104_2058` 之后。整数列用 `INTEGER` / `BIGINT`，不用 `SMALLINT`；金额 `BIGINT`。

### 3.1 优惠券（FR-130）

**D-1 券模板** `shop_coupons`：
- `id, public_token, kind (DISCOUNT | FREE_SHIPPING), name_id, name_en, amount BIGINT（满减额；免运券为每张封顶额）, min_spend BIGINT, scope_type (ALL | CATEGORY | PRODUCT), scope_values JSONB（品类码或商品 token 数组）, acquire_type (NEWBIE | CLAIM | CODE), issue_start_at, issue_end_at（发放期）, valid_mode (FIXED | RELATIVE), valid_start_at, valid_end_at, valid_days INTEGER（领取后 N 天）, total_quantity INTEGER NULL（NULL = 不限）, issued_count INTEGER, per_user_limit INTEGER DEFAULT 1, status (ACTIVE | STOPPED), created_at, updated_at`。
- 新人券唯一性：同一时刻只允许一张 `acquire_type = NEWBIE` 且在发放期内且 ACTIVE（PRD AB-24）——service 层校验 + 迁移里加排他约束不可行（时间区间重叠），按 service 层 + `pg_advisory_xact_lock` 串行建 / 改新人券实现。
- 领券 / 兑换 / 新人发放扣总量：`UPDATE ... SET issued_count = issued_count + 1 WHERE id = ? AND status='ACTIVE' AND (total_quantity IS NULL OR issued_count < total_quantity)`，影响 0 行 → 领完。

**D-2 用户券** `user_coupons`：
- `id, public_token, user_id, coupon_id, source (NEWBIE | CLAIM | CODE | BANNER | REISSUE), status (AVAILABLE | LOCKED | USED | VOIDED), locked_order_id NULL, used_order_id NULL, valid_from, valid_until（领取时按模板物化）, claimed_at, updated_at`。
- **「已过期」不落库**，由 `valid_until < now()` 现算（避免定时任务改状态）；`VOIDED` = 运营停用并作废（用户端不显示）。
- 状态机：AVAILABLE →（提交订单）LOCKED →（付款）USED；取消时按 D-3 退回为 AVAILABLE（已过有效期则现算为过期）或转 VOIDED，不退回的保持原状态。
- 每人限领：`SELECT count(*) FROM user_coupons WHERE user_id=? AND coupon_id=?` 在领取事务内 + `(user_id, coupon_id, ordinal)` 不做唯一约束（限领可 > 1）；并发由对 `shop_coupons` 行 `SELECT ... FOR UPDATE` 串行。
- 索引 `(user_id, status, valid_until)`。

**D-3 券的占用与退回**（`CouponLedgerService`，唯一入口）：
- 提交订单：对选中的 `user_coupons` 行 `FOR UPDATE`，校验 AVAILABLE + 下单时刻在有效期内（PRD「按下单那一刻判断」）→ 置 LOCKED、写 `locked_order_id`。失败抛 `CouponInvalidException`（App 走 D17「券已失效、已为你重新选择」）。
- 付款成功（`PaymentIntentPaidEvent` / 纯币结算）→ LOCKED → USED、写 `used_order_id`。
- 订单取消：按 `CancelReason`（D-15）决定 `refundCoupon`：
  - 待付款取消、超时取消、平台原因整单取消、快递原因异常、丢件破损赔付 → 退回：LOCKED/USED → AVAILABLE（清订单字段）；模板已 STOPPED 且选择了作废 → VOIDED。
  - 部分取消、用户原因被退回 → 不动。
- 运营停用并作废（服务层方法 `stopCoupon(couponId, voidUnused)`，后台线调用）：AVAILABLE → VOIDED；LOCKED 的不动（该订单仍可用），其订单取消时转 VOIDED 不退回。
- 迟到款重新下单（D-11）：原订单的券若已被另一订单占用 → 拒绝；已过期仍可用于新订单（`source = REISSUE` 不新建行，直接把原行重新 LOCKED 到新订单）。

**D-4 兑换码** `shop_coupon_codes(id, coupon_id, code VARCHAR(32) UNIQUE（大写规范化）, single_use BOOLEAN, redeemed_by_user_id NULL, redeemed_at NULL, created_at)`：
- 通用码（`single_use=false`）一张码多人兑换，受模板每人限领与总量约束；一次性码兑换后写 `redeemed_*`。
- 失败码与 PRD 文案一一对应（P-5 错误码表）。
- **防暴力试码**：只对「码不存在 / 已被使用」两类失败计数；`RedisRateLimiter` 键 `rl:coupon:redeem-fail:<uid>`，1 小时 10 次；超限后键 `rl:coupon:redeem-lock:<uid>` TTL 1 小时，锁定期内直接 429。

**D-5 新人券**：
- 判定：`users.created_at` 距 now ≤ 30 天 **且** 不存在 `paid_at IS NOT NULL` 的 `shop_orders`；游客不算。
- 触发：App 每次进入 Toko 调 `POST /api/v1/me/shop/entry`（幂等），服务端若存在发放期内的新人券、用户符合条件、且未领过该张 → 发放（`source=NEWBIE`）。返回 `{newbieCouponIssued: bool}`。「活动开始前进过 Toko」天然补发（每次进入都判）。
- 游客弹窗数据：`GET /api/v1/shop/coupons/newbie-campaign`（permitAll）返回当前新人券的面额、门槛、`campaignToken`；App 按「设备 × campaignToken」本地记一次（SharedPreferences）。

**D-6 券接口**：
- `GET /api/v1/me/coupons?tab=available|used&cursor=`：可用 = AVAILABLE 且未过期，外加 LOCKED（带 `lockedByPendingOrder=true`，App 置灰标「已被待付款订单占用」；2026-11-05 收口 C-3）；已使用 = USED；过期与 VOIDED 不展示。每项带 `expiringSoon`（≤ 48h）。
- `POST /api/v1/me/coupons/redeem {code}`。
- `POST /api/v1/me/coupons/claim {couponToken, source}`（详情页 / 首页 / banner）。
- `GET /api/v1/shop/coupons/for-you`（需登录）：首页「给你的券」= 发放期内可领（CLAIM 类、未领满）+ 已到账的新人券（置顶、标 `newbie`）；空则 App 不渲染。
- `GET /api/v1/shop/products/{token}/coupons`：该商品可领的券（含已领状态）。
- 商品卡「Lagi promo」：商品列表 / 搜索 / 推荐出参加 `promo: bool` = 存在发放期内 ACTIVE 的 CLAIM 券覆盖该商品，或当前用户持有可用券覆盖该商品；一次查询批量算（当页商品 × 生效券，券数量小，内存匹配）。

### 3.2 结算与计价（FR-127 / 128 / 131）

**D-7 计价器** `ShopPricingCalculator`（纯函数，无 IO，`B/shop/order/pricing/`）：
- 输入：行（skuId, 单价, 数量, 品类, 商品 token）、选中满减券、选中免运券、免运规则（门槛、全局封顶）、所选报价（运费）、本单最低报价、保价开关与费率、是否用币、余额、单笔上限。
- 输入用计价器自己的值对象（`PricingLine`、`PricingCoupon`），不直接依赖券领域实体（避免 1.2 → 2.1 前向依赖）。
- 输出 `PricingResult`：`goodsAmount, discountCouponAmount, shippingFee, shippingDiscount, shippingDiscountSource (NONE | FREE_SHIPPING_RULE | COUPON), insuranceFee, payable, coinAmount, cashAmount, savedAmount, freeShippingProgress {threshold, remaining}`，以及每张券的可用性与原因（`BELOW_MIN_SPEND | OUT_OF_SCOPE | EXPIRED | RULE_BETTER`）。
- 规则（PRD FR-130 计算顺序，逐条单测）：
  - 满减门槛按**券前**适用范围内商品金额；抵扣 ≤ 适用商品金额。
  - 商品实付 = 商品金额 − 满减。
  - 免运减免封顶 = min(本单最低报价, 封顶)，满额免运封顶取全局配置 `free_shipping_cap`（为空或 0 = 不设额外封顶，只受本单最低报价限制）、免运券取券的 `amount`；免运券有范围时全部商品须在范围内；二者取减得多，相等用满额免运。减免 ≤ 所选快递运费。
  - 保价费 = ceil(商品实付 × 0.5%)（费率配置 `shipping_settings.insurance_rate_bp = 50`），不受免运减免。
  - 应付 = 商品实付 + 运费 − 运费减免 + 保价费。
  - 币 = useCoin ? min(应付, 余额, 单笔上限) : 0；现金 = 应付 − 币。
  - 默认最优组合：满减券取抵扣额最大（并列取先到期），免运券取减免最大且优于满额免运者。
- 门店返佣基数不受影响：订单行 `unit_price` 仍为原单价，券抵扣只记在订单头。

**D-8 结算接口**（取代现 `GET /me/checkout`）：
- `POST /api/v1/me/checkout/preview`，请求 `{source: CART | BUY_NOW, buyNow?: {skuToken, qty}, addressToken?, courierKey?, insurance: bool, discountCouponToken?, freeShippingCouponToken?, couponMode: AUTO | MANUAL, useCoin: bool}`；POST 是因为参数多，仍为只读。响应含地址、`quoteToken`、快递列表（按价格升序）、已选快递、行（含不可售原因）、`PricingResult`、可用 / 不可用券分组、币余额与上限、`returnPolicyCopyKey`、不可提交原因（`NO_ADDRESS | NOT_SERVICEABLE | QUOTE_FAILED`）。
- `POST /api/v1/me/shop-orders`（Idempotency-Key 沿用），请求 `{source, buyNow?, addressToken, quoteToken, courierKey, insurance, discountCouponToken?, freeShippingCouponToken?, useCoin, agreeReturnPolicy: true}`。服务端**重新计价**（不信任客户端金额）；报价从 `quoteToken` 取（D-9），不再询价。**报价须与本单绑定**：报价的收货邮编 = 所选地址邮编、`items_hash` = 本单商品与数量算出的值，否则 409 `quote-expired`（防拿近处便宜报价下远处的单）。
- **PawCoin 提交时扣（S-3）**：提交事务内扣币（写 PawCoin 流水，`biz_ref` = 订单），`ShopOrderPaidHandler` 与 `settlePureCoin` 不再扣币；所有取消路径（用户取消、超时、平台取消）退回已扣币；`ShopFinanceDashboardService` 口径注释与统计同步改为「提交时扣」。消除「QRIS 已到账但付款时币不足导致回调回滚」的资损路径。
- **旧版 App 仍可下单（S-4）**：请求体无 `quoteToken` 时走**旧结算模式**：服务端按地址询价、自动选最便宜快递、不用券（`couponMode` 视为不用）、`useCoin=true`、不保价、`agreeReturnPolicy` 视为已同意（旧版 App 客户端已有勾选）；旧 `GET /me/checkout` 同样改为按 Biteship 最低报价算运费。两种模式共用计价器与提交事务，只在入参装配处分支（`LegacyCheckoutAdapter`）。SKU 不可售、不可配送时旧版收到其已识别的 409 / 422。
- 立即购买：`source=BUY_NOW` 时不读购物车、不删购物车行。`CART` 时只结算 `selected=true` 且有效的行，下单后删除这些行（现状）。
- 提交失败（409 / 422，ProblemDetail `type` 区分）：`unavailable-lines`（沿用 `unavailableLines`）、`not-placeable`（422）、`coupon-invalid`（新增，409）、`quote-expired`（新增，409，App 重新试算）。
- 订单头**沿用**已有列：`goods_subtotal`（商品金额）、`shipping_fee`（所选快递运费）、`shipping_discount`（运费减免）、`total_amount`（应付）、`coin_amount` / `cash_amount`。**新增列**（迁移 ③）：`checkout_source (CART | BUY_NOW | LEGACY)`、`discount_coupon_amount BIGINT NOT NULL DEFAULT 0`、`discount_user_coupon_id`、`shipping_discount_source (NONE | FREE_SHIPPING_RULE | COUPON)`、`free_shipping_user_coupon_id`、`insurance_fee BIGINT NOT NULL DEFAULT 0`、`insurance_selected BOOLEAN NOT NULL DEFAULT false`、`courier_code`、`courier_service_code`、`courier_name`、`courier_service_name`、`courier_eta_text`、`shipping_quote_id`（FK D-9）、`coin_selected`、`cancel_reason`、`cancelled_at`、`reorder_of_order_id`、`late_paid_amount`、`late_paid_at`、`late_paid_intent_id`、`late_payment_resolution (REORDERED | REFUNDED)`。存量订单这些列为空 / 默认值，App 订单详情对空值降级为线上展示。
- **PawCoin 自选**：`PaymentSplit.compute` 增 `useCoin` 参数；上限读 `shop_pawcoin_rules`（不变）；结算响应带 `coinCap` 供 App 文案读配置（D13 提示）。

**D-9 Biteship 报价与复用**：
- `BiteshipClient`（`B/shared/logistics/biteship/`）：`rates(...)`、`createOrder(...)`、`cancelOrder(...)`、`getOrder(...)`；`RestClient`，连接 3s / 读 8s 超时；key 走 env `BITESHIP_API_KEY`，`BITESHIP_MODE=live|sandbox|stub`（stub 返回固定两家报价，供 L0/L1 与云端）。
- **询价用邮编**（发货地邮编 → 收货地 `kode_pos`），`items` 按 SKU 逐项传（名称、`value` = 单价、数量、包裹重量克、长宽高厘米），`couriers` 取配置 `BITESHIP_COURIERS`。偏远地区邮编精度风险记入 §3.6，上线后观察，必要时再映射 area_id。
- 报价落库 `shipping_quotes(id, public_token, user_id NULL, dest_postal_code, items_hash, origin_postal_code, response JSONB, lowest_price BIGINT, created_at, expires_at)`：
  - 复用键 `(dest_postal_code, items_hash, origin_postal_code)`，有效期 30 分钟内直接复用（详情页、购物车、结算页共用）；`items_hash` = SKU 包裹参数 × 数量排序后 SHA-256。
  - 结算响应返回 `quoteToken`；提交时用它锁定快递与运费，过期 → `quote-expired`。
  - 每日任务删 2 天前的报价（被订单引用的不删，`ON DELETE` 由订单 FK 保护 → 订单引用列可空，清理时排除被引用行）。
- 详情页「运费 Rp X 起」：`GET /api/v1/shop/products/{token}/shipping-estimate?skuToken=&qty=&addressToken=|regionKecamatanId=` → `{serviceable, lowestFee, freeShippingProgress}`；游客按地区（所选区的代表邮编：`shipping_zones` 新增 `postal_code` 列，迁移 ②，运营补齐；未配置的区按「不支持配送」处理）。
- 购物车：`GET /api/v1/me/cart` 响应新增 `estimate {lowestFee, payable, saved, freeShippingProgress, reason}`，询价规则同上（默认地址 → 详情页选过的地区由 App 传 `regionKecamatanId` → 都没有不含运费）。
- 可配送判定 = 地址的 Kecamatan 在 `shipping_zones.active=true` **且**报价至少一家；`shipping_zones.fee` 停用（列保留，不再读）。
- **地址簿「暂不支持快递配送」标注**（就绪度 I10）：地址列表接口 `GET /me/shipping-addresses` 每项加 `serviceable`：区域未开通 → false；已开通 → 用**标准探测包裹**（1,000 g，20×15×10 cm，配置项）按邮编询价，结果按邮编缓存在 `shipping_quotes`（`items_hash` = 探测包裹，复用 24 小时）；询价失败 → `serviceable = null`（App 不标注）。

**D-10 包裹参数**：`shop_skus` 新增 `package_weight_g INTEGER, package_length_cm INTEGER, package_width_cm INTEGER, package_height_cm INTEGER`（迁移 ①，可空）。任一为空 → SKU「暂不可售」：商品详情 / 加购 / 立即购买 / 结算均拒（422 `sku-not-sellable`），购物车行标失效。存量 SKU 上线前须由运营补齐（上线检查项）。发货地 `shipping_settings` 新增 `origin_postal_code, origin_contact_name, origin_contact_phone, origin_address, free_shipping_cap BIGINT, insurance_rate_bp INTEGER DEFAULT 50`（迁移 ②）。

### 3.3 资金闭环（FR-134）

**D-11 支付窗口、主动查单与迟到款（拍板 S-1 / S-6 / S-7）**：
- **关单推迟到码失效**：订单 `expires_at` 仍为建单 + 60 分钟（App 倒计时用）；新增 `payment_intents.provider_expires_at` = 出码时刻 + 65 分钟；**电商意图自身的 `expires_at` 也改为 = `provider_expires_at`**（不再取订单剩余时间）。
- **所有过期 / 关单路径统一判据「订单已过 `expires_at` 且该单所有意图都已过 `provider_expires_at`」**（§1.2 第 21 条的四条路径全部改）：
  - `ShopOrderPaymentService` 出码时的意图有效期；
  - 全局 `PaymentIntentExpiryScanner` 对电商意图（`purpose = SHOP_ORDER`）按 `provider_expires_at` 判；
  - `statusOf` 轮询与 `requireOwn()` 的懒过期改为只读、不再就地置过期 / 关单（关单只由扫描器执行）；
  - `isPaymentExpiredAt`、`findOverduePendingPayment` 同步改判据。
- **关单前最后查单**：满足判据后 `queryCharge` 一次——明确 PAID（含 `Expired => Success` / `Failure => Success`）→ 按到账处理；明确 EXPIRED / FAILED → 取消（退币、退券、回补库存）；**返回空或任何错误一律算「查询失败」，不关单**，下一轮重试；同一订单连续 10 次查询失败 → ERROR 日志 + 飞书告警（防 GemPay 故障期间订单永不关单无人知）。`queryCharge` 返回值改为三态 `PAID | NOT_PAID | UNKNOWN`。
- **出码只在订单前 10 分钟内**：首次出码失败（E7）后可重试出码，但订单创建超过 10 分钟仍无可用码 → 不再出新码、提示订单已无法支付（防止第 55 分钟出码把库存与券占到 120 分钟）。因此关单最晚 = 建单 + 75 分钟。已有可用码时「去支付」复用同一个码。
- **宽限期显示（S-7）**：`now > expires_at` 的待支付订单，接口 `displayStatus = CANCELLED`、列表归「已取消」组、不可去支付；若 5 分钟内到账，订单转为待发货，用户刷新后看到「待发货」。不新增文案。
- **定时查单** `ShopPaymentReconcileJob`：每 2 分钟，扫 PENDING **及因关单置为 EXPIRED 不足 24 小时**的电商意图（出码 ≥ 3 分钟），调 reconcile；`pg_try_advisory_xact_lock`；单次最多 100 笔。**网关调用移出事务**（先查、再开短事务落结果，P-7）。
- **「我已付款」**：`POST /api/v1/me/shop-orders/{token}/payment-check` → 同步查单一次并返回订单支付状态；同订单 10 秒内只真查一次（`RedisRateLimiter`）。
- **迟到款不再丢弃（就绪度 I1：不新增意图状态）**：仅对 `purpose = SHOP_ORDER` 的意图，`applyCallback` / reconcile 遇 FAILED / EXPIRED 而网关返回成功时，意图**照常转 PAID**（报表、导出按 PAID 统计不漏），新增列 `payment_intents.paid_after_close BOOLEAN NOT NULL DEFAULT false` 置 true，并发布现有 `PaymentIntentPaidEvent`。`ShopOrderPaidHandler` 见订单已 CANCELLED → 写 `late_paid_amount / late_paid_at / late_paid_intent_id` 到原订单、INFO 日志，不改订单状态。其他业务线（送礼、充值、问诊、解锁）的回调行为不变。
- **客服处置（服务层，后台线调用）**：
  - `reorderFromLatePayment(orderId, operator)`：校验库存、原券未被其他订单占用（被占用 → 拒绝；已过期仍可用）；新建订单 `reorder_of_order_id = 原单`，行、地址、快递、运费、保价沿用原单快照，**不重新询价**；应付按原单快照。**多退少补（S-6）**：已到账金额 ≥ 新单现金段 → 多出部分退为 PawCoin（不给补偿；这部分不是退消费，**不触发成长值扣回**）；不足 → 差额从用户 PawCoin 扣，余额不够 → 拒绝重下单（只能走退款）。新单**走统一的「已付款」路径** `ShopOrderPaymentService.markPaid(order, source=REORDER)`：写 `paid_at`、发 `ShopOrderPaidEvent`（飞书发货提醒、成长值入账、商家返佣扫描按 `paid_at` 均不漏）、服务端埋点 `shop_payment_paid`；发通知 `SHOP_ORDER_REORDERED`；原单 `late_payment_resolution = REORDERED`。原单详情返回 `reorderedToOrderToken`，新单返回 `reorderedFromOrderNo`。
  - `refundLatePayment(orderId, operator)`：生成取消退款单（D-15），`reason = LATE_PAYMENT`，全额为现金、无补偿、无币段；`late_payment_resolution = REFUNDED`。
  - 同一笔迟到款只能处理一次（`late_payment_resolution` 条件更新，并发安全）。

**D-12 Biteship 运单、Webhook 与物流状态**：
- `shipments` 新增列（迁移 ④）：`provider (MANUAL | BITESHIP)`、`biteship_order_id`、`waybill_id`、`tracking_id`、`courier_code`、`courier_service_code`、`provider_status`（Biteship 原值）、`provider_status_at`、`insurance_amount`、`superseded_at`（快递原因重发时旧运单）。`carrier` 放宽为 VARCHAR（存 Biteship 快递码；原枚举值保留兼容存量）。
- **下运单（服务层，后台线调用）** `BiteshipShipmentService.createWaybill(orderId)`：`reference_id` = 订单号 + 序号（重发唯一）；带 `courier_insurance`（勾了保价 = 商品实付）；成功 → 订单仍 PENDING_SHIPMENT；失败 → 订单标 `waybill_failed_at` + 原因，同一订单重试计数，**5 分钟内全局重试 ≥ 10 次写 ERROR 日志并发飞书机器人告警**（沿用 `shop/order/notify` 的飞书提醒通道）。
- **Webhook** `POST /api/v1/webhooks/biteship/{secret}`（permitAll，路径密钥 = env `BITESHIP_WEBHOOK_SECRET`，常量时间比较）：先把载荷写入收件表 `biteship_webhook_events(id, biteship_order_id, event, payload JSONB, received_at, processed_at NULL, attempts, last_error)`（迁移 ④）再回 200；`@Async` 处理时**不信任载荷**，只取 `order_id` 调 `getOrder` 回查真实状态；处理失败留在收件表，每 5 分钟任务重试（上限 10 次后告警）；幂等。另每 6 小时对未签收的在途运单主动 `getOrder` 回查一次（兜底 Biteship 不重发）。**接口请求日志对该路径的密钥段脱敏**（§1.2 第 25 条）。
- **状态映射**（`BiteshipStatusMapper`，单测穷举 15 值）：

| Biteship | 用户侧物流文案 | 订单动作 |
|---|---|---|
| confirmed / scheduled / allocated / picking_up | 待揽收 | — |
| picked | 已揽收 | PENDING_SHIPMENT → SHIPPED（`shipped_source = BITESHIP_PICKED`） |
| in_transit / on_hold | 运输中 | 同上兜底 |
| dropping_off | 派送中 | 同上兜底 |
| delivered | 已签收 | → DELIVERED（`DeliverySource.BITESHIP`） |
| courier_not_found | （不变） | 标异常 `PICKUP_FAILED` |
| return_in_transit / returned | （不变，确认后显示配送异常） | 标异常 `RETURNED`（待运营判原因） |
| rejected / disposed / cancelled | （不变） | 记录，提醒运营 |

- 运营手工「标记已发货」（`shipped_source = MANUAL`）保留；手工运单（`provider=MANUAL`）不显示物流状态。
- 自动送达兜底 7 天 → **14 天**（`AUTO_DELIVER_AFTER`）；自动完成 7 天不变。

**D-13 物流异常**（不新增订单状态，标记列在订单头，迁移 ④）：`logistics_exception (NULL | PICKUP_FAILED | RETURNED | STALLED | LOST | DAMAGED)`（C-6 拆分）、`logistics_exception_at`、`logistics_exception_confirmed BOOLEAN`（运营确认为 ② / ④ → 用户侧显示「配送异常」）、`claim_pending BOOLEAN`。
- 有标记的订单**不参与**自动送达与自动完成（扫描 SQL 加 `logistics_exception IS NULL`）；标记清除后重新计时（以清除时刻为起点：新增 `timer_resumed_at`）。
- 停滞检测 `LogisticsStallJob`：每小时，Biteship 运单 `provider_status_at` 超 3 天未更新且未签收 → 标 `STALLED`（用户侧不提示）；之后收到任何新物流状态 → 自动清除 `STALLED` 并按 `timer_resumed_at` 重新计时（避免运营不处理时订单永不自动完成）。
- 用户「确认收货」任何异常下可点：完成订单并清除标记，写运营提醒（订单备注 + 日志）。
- App 显示规则：`logistics_exception_confirmed = true` 且类型为 RETURNED / LOST_DAMAGED → 状态「配送异常，请联系平台客服」+ 联系客服按钮；其余显示最后物流状态。

**D-14 取消原因与退款口径**：`CancelReason` 枚举（订单头 `cancel_reason`，迁移 ③）：`USER_UNPAID_CANCEL | PAYMENT_TIMEOUT | OUT_OF_STOCK | WAYBILL_FAILED | PICKUP_FAILED | COURIER_FAULT | USER_FAULT_RETURNED | LOST_DAMAGED | LATE_PAYMENT`；每个值带 `platformFault`、`refundCoupon`、`refundShipping`、`compensate` 四个属性（照 `ReturnType` 写法，**由类型推出，不给运营手工开关**）。

**D-15 取消退款单** 新表 `shop_order_refunds`（不复用 `shop_returns`：退货单按退货行与回程运费算 quote，口径不同）：
- `id, public_token, order_id, scope (WHOLE | LINE), order_line_id NULL, reason, goods_refund, shipping_refund, insurance_refund, total_refund, coin_refund, cash_refund, cash_destination NULL (TO_PAWCOIN | TO_BANK), payout_channel NULL, payout_account_enc（AES-GCM，沿用 V1.1 payout PII 加密）, payout_fee, compensation_amount, status (COIN_REFUNDED | AWAITING_DESTINATION | PAYOUT_PENDING | PAYOUT_FAILED | COMPLETED), destination_chosen_at, paid_out_at, created_at, updated_at`。
- 金额（`ShopRefundCalculator`，与 D-7 同包、纯函数）：
  - 部分取消：可取消一行的部分件数，行金额按「单价 × 取消件数」；分摊满减 = floor(满减 × 被取消行金额 / 适用商品金额)（向下取整，对用户有利 ≤ 1 盾）；该部分实付 = 行金额 − 分摊满减；不退运费、保价；币段 = floor(实付 × 原单 `coin_amount` / `total_amount`)，零头入现金。行 `refunded_qty` 累加（复用现有列）。被取消的是最后剩余件 → 按整单处理，退剩余未退应付；剩余币段 = 原 `coin_amount` − `refunded_coin`，剩余现金同理。
  - 整单：平台原因全额退剩余未退；`USER_FAULT_RETURNED` 扣用户实付运费（运费 − 减免）与保价费；`LOST_DAMAGED` 买了保价 = 商品实付，没买 = 运营输入的协商额且 ≤ 商品实付；均减去此前部分取消已退金额。
  - 币段立即退回（`coin_refund`）；现金段 > 0 → `AWAITING_DESTINATION`，App 订单详情出现「选择退款方式」。
- **按原因的库存与状态**（就绪度 I6，拍板 S-8 / S-9）：
  - 待付款取消 / 超时：回补库存、CANCELLED、无退款单（币原路退回）。
  - 已付款、未发货的平台原因整单取消（缺货、运单失败、揽收失败）：回补库存、REFUNDING →（全部退款单完成）REFUNDED。
  - 已发货后的快递原因（`COURIER_FAULT` 重发不取消）、`LOST_DAMAGED`：不回补库存；SHIPPED → REFUNDING → REFUNDED（现有允许边）。
  - `USER_FAULT_RETURNED`（S-8）：运营取消时订单 → REFUNDING、**不生成退款单**；运营 `confirmReturnInbound(orderId)` 确认入库后回补库存并生成退款单。
  - **已签收 / 已完成后才报破损（S-9）**：订单状态不变，生成 `scope = COMPENSATION` 的赔付退款单（原因 `LOST_DAMAGED`，金额规则同丢件破损），用户同样选去向；订单详情在 `refunds[]` 中展示。
- **成长值扣回幂等键**（就绪度 m1）：`RefundSucceededEvent.refundKey` = `SHOPREF:{退款单 token}:{COIN|CASH_PAWCOIN|CASH_BANK}`，同一退款单不同段不被去重吞掉。
- 用户选择：`POST /api/v1/me/shop-orders/{token}/refunds/{refundToken}/destination {destination, channel?, account?}`：
  - TO_PAWCOIN：即时入账；`reason.compensate` 为真时补偿 = 现金段 × `compensation_premium_rate`（封顶 `_cap`），只对现金段 → `COMPLETED`。
  - TO_BANK：扣渠道费 → `PAYOUT_PENDING`；财务标记已打款（服务层 `markPaidOut`，后台线调用）→ `COMPLETED`；标记失败 `markPayoutFailed` → `PAYOUT_FAILED`，用户可重填或改选 PawCoin。
- **成长值扣回**：币段退回时、现金选 TO_PAWCOIN 时、TO_BANK 标记已打款时，各发一次 `RefundSucceededEvent`（金额 = 本次退款额，`GrowthRefundListener` 已按占比扣回且累计封顶，不改它）；补偿额不计入扣回基数。
- 订单状态：见上「按原因的库存与状态」；部分取消订单状态不变。
- **门店返佣扣回（S-5，登记为遗留）**：商家平台目前只扫 `return_requests`，看不到 `shop_order_refunds`；本线保证 `shop_order_lines.refunded_qty` 与 `shop_orders.refunded_total` 在取消时正确累加，商家仓扫描改造**不在本线**，门店结算正式开始前补（见 §3.6）。
- 7 天未选：`RefundDestinationReminderJob` 每日写运营提醒（后台线展示），不推送用户。
- 现有 `AdminShopOrderExceptionService.cancelWholeOrder / cancelLine` 改为调用本服务（行为修正：现金段不再只记审计）。

### 3.4 banner、商品、订单列表、复购下线（FR-132 / 133 / 136）

**D-16 banner**：`shop_banners` 新增 `start_at, end_at`（排期，NULL = 不限）；`target_type` 加 `COUPON`（`target_value` = 券 token）；CHECK 同步（迁移 ⑤）。`GET /api/v1/shop/banners`（新，返回最多 6 张，按 `sort_weight DESC`）：过滤 active + 排期内 + 目标有效（商品在售、券在发放期且未领完）；`CATEGORY` 目标下发为 `TOKO_HOME`（App 落 Toko 首页）。旧 `GET /shop/banner` 保留给旧版 App（返回第一张）。领券 banner 点击 → `POST /me/coupons/claim {source: BANNER}`，已领返回 409 `already-claimed`。

**D-17 商品与评价**：
- 下架区分：商品详情对 `is_active=false` 返回 **410**（ProblemDetail `type=product-delisted`），不存在仍 404；App 410 → B13「商品已下架」页。
- 评价：`shop_reviews` 新增 `hidden_at`（迁移 ⑥）；均分与条数只算已发布且未隐藏；`GET /api/v1/shop/products/{token}/reviews?cursor=`；开关 `platform_config` 键 `shop.review.display-enabled`（默认 false，沿用现有平台配置读法），关闭时详情接口不带评价列表、App 维持均分 + 条数。隐藏操作是服务层 `hideReview`，后台线调用。
- 品类筛选下线：后端品类参数保留（兼容旧版 App）；App 端 `/shop?category=` 一律忽略参数落首页。
- 推荐栏头摘要（就绪度 I10）：`/me/shop/recommendations` 响应加 `profileSummary {petName, species, sizeClass, ageStage, weightMissing}`（取值沿用推荐理由的词表，App 按 PRD §6 A1 / A9 拼文案）。
- 搜索无结果推荐：复用 `/me/shop/recommendations`（有档案）或新增 `GET /api/v1/shop/products/top?limit=6`（按运营权重，游客可用）。

**D-18 电商订单列表**：新增 `GET /api/v1/me/shop-orders?group=all|pending_payment|pending_shipment|to_receive|completed|cancelled&cursor=` 与 `GET /api/v1/me/shop-orders/counts`（返回 `pendingPayment`、`toReceive`）。分组映射：`to_receive` = SHIPPED + DELIVERED；`cancelled` = CANCELLED + REFUNDING + REFUNDED。通用 `/orders` 不动。

**D-19 订单详情响应扩展**：金额明细字段（D-8 列）、`paymentMethods {coin, cash}`、`logistics {provider, courierName, waybillId, statusKey (awaiting_pickup | picked | in_transit | out_for_delivery | delivered), exception}`、`refunds[]`（退款单 token、状态、金额、可否选择去向）、`cancelledLines`、`reorderedToOrderToken / reorderedFromOrderNo`、`paidAt`、`cancelledAt`、`paymentDeadline`、`canPay`（`now < expires_at`）。

**D-20 复购下线**：`RepurchaseScanService` 日扫停用（配置 `petgo.shop.repurchase.scan-enabled=false` 默认，代码保留一个版本后删）；`/me/shop/repurchase-cards*` 返回空列表（旧版 App 兼容，不 404）；`REPURCHASE_FOOD_LOW` 不再产生，枚举与约束值保留（历史行）；`/me/shop/recommendations` 保留。旧推送深链落 Toko 首页（App）。

### 3.5 通知、推送、埋点

**D-21 通知类型（同一迁移）**：`ck_notifications_type` DROP + ADD，值 = **当前树里最后一条重建它的迁移的全集**（现为 `V20261104_2010`，41 值）+ `COUPON_EXPIRING`、`SHOP_ORDER_REORDERED` = 43；`NotificationType` 加两值；`PushCategory`：`COUPON_EXPIRING → PROMO`、`SHOP_ORDER_REORDERED → ORDER_ACCOUNT`；同步改 `PushCategoryTest`、`MentionNotifyTest`。

**D-22 券快过期推送** `CouponExpiryReminderJob`：每天 WIB 10:00；找 `valid_until` 落在 (now, now+48h] 的 AVAILABLE 用户券，按用户合并成 1 条（`COUPON_EXPIRING`，`variant` = 张数）；去重表 `coupon_expiry_notify_log(user_id, wib_date)` PK，且每张券只提醒一次（`user_coupons.expiry_notified_at`，就绪度 I8）；用户关闭 PROMO 不发（走现有推送偏好）；服务端埋点 `toko_coupon_expiry_push_sent{coupon_count}`；推送深链 `/me/coupons`，App 点开报 `toko_coupon_expiry_push_opened`。

**D-23 埋点**：`toko_order_submitted` 补 `coupon_ids`（券模板 token）、`coin_used`、`courier`（`<courier>_<service>`）、`shipping_fee`、`insurance`；`toko_checkout_page_viewed.entry`；banner `position` 取实际位次；其余按 PRD §4。纯币订单补发服务端 `shop_payment_paid`（现状缺口，D-11 同 story 修）。复购卡事件随组件删除。

### 3.6 按推荐定下、需留意的 7 处

1. **S-1（操作者拍板）**：关单推迟到 GemPay 码失效（65 分钟）+ 最后一次查单；App 仍显示 60 分钟倒计时。库存与券最多多占 5 分钟。PRD FR-128「生成二维码时传剩余时间」不实现（GemPay 不支持）；若 GemPay 将来开放有效期参数再改。⚠️ PRD 需设计侧同步。
2. **S-2（操作者拍板）**：后台触发的业务逻辑（建 / 停券、下运单、异常取消退款、标记打款、迟到款重下单 / 退款、评价隐藏、banner 配置、SKU 包裹参数）的 service 层在本线实现与测试；后台线只做 Controller + 页面。本线验收靠 SQL 种子与 `@StagOnly` 测试入口（D-24）。
3. Biteship 询价用 5 位邮编（精度「中」）；游客地区需要 `shipping_zones.postal_code`，运营补齐前该区按「不支持配送」。
4. Biteship Webhook 验签机制文档未写 → 路径密钥 + 回查真实状态，载荷只当通知。
5. 迟到款重新下单沿用原单锁定的快递与运费，不重新询价。
6. 取消退款新建 `shop_order_refunds`，不复用退货单；财务打款页（后台线）需同时覆盖退货单与本表。
7. ~~「我的券」可用列表不出现 LOCKED 券~~ → 收口 C-3：显示并置灰。

8. **S-3**：PawCoin 提交订单时扣，所有取消路径退回。
9. **S-4**：旧版 App 仍可下单（旧结算模式，D-8）。
10. **S-5**：门店返佣的取消扣回登记为遗留，商家仓另做；门店结算开始前必须补。
11. **S-6**：迟到款重下单多退少补（多出退为 PawCoin 无补偿，不足用币补，币不够只能退款）。
12. **S-7**：宽限期（60~65 分钟）订单直接显示已取消；若期间到账，订单变待发货。
13. **S-8 / S-9**：用户原因退回在确认入库后退款；签收后报破损单独生成赔付退款单、不改订单状态。
14. 出码只在订单创建后 10 分钟内，关单最晚建单 + 75 分钟。
15. 迟到款不新增意图状态：意图照常转 PAID + `paid_after_close` 标记，只对电商生效。

**D-24 stag 测试入口**（`@StagOnly`，生产不注册；2026-11-05 story 编写后收口）：前缀 `/admin/stag/shop/**`，挂在后台链 `/admin/**` 下（会话登录 + `hasRole('SUPER_ADMIN')`，与 `AdminPaymentSimulateController` 同），类上 `@StagOnly`（生产不注册）；`SecurityConfig` 后台链只对 `/admin/stag/**` 免 CSRF（便于验收时带后台会话 cookie 用 curl 调用），不走商家内部端口 / `X-Internal-Secret`。入口清单：建券、停券、下运单、推进 stub 物流状态、确认 / 清除物流异常、按原因取消（整单 / 部分）、确认退回入库、签收后赔付、标记已打款、标记打款失败、迟到款重新下单、迟到款退款。均为 service 层薄包装；后台线落地后删除或保留为 stag 工具。

### 3.7 实施顺序

1. 地基：迁移与枚举（包裹参数、发货地 / 免运封顶 / 保价费率、订单与运单新列、券四表、banner 排期、评价隐藏、退款单、通知约束）
2. 计价器与券服务（D-1 ~ D-7）
3. Biteship 客户端、报价、结算接口（D-8 ~ D-10）
4. 资金闭环（D-11、D-14、D-15）
5. 运单、Webhook、物流异常（D-12、D-13）
6. banner、商品、订单列表、复购下线、通知与推送（D-16 ~ D-22）
7. App：结算 → 支付面板 → 首页与搜索 → 详情 → 购物车 → 订单与退款 → 我的券 → 地址簿与复购清理
8. 联调：资金全链路与物流（L1 / L2）

## 4. 实现模式与一致性规则

通用约定见基线架构与根 CLAUDE.md，以下只列本线可能各做各的地方。

- **P-1 金额单点**：所有应付、减免、保价、币 / 现金拆分只在 `ShopPricingCalculator`；所有退款金额只在 `ShopRefundCalculator`。Controller、App 不得自行算钱（App 只展示服务端给的数；购物车「已省」也由服务端给）。两个计算器写表驱动单测，覆盖 PRD 所有例子（例：最低 15,000、上限 20,000、选 28,000 → 减 15,000）。
- **P-2 券状态只经 `CouponLedgerService`**：禁止在别处 `UPDATE user_coupons`。
- **P-3 支付意图只经 `PaymentIntentService` / `PaymentReconciliationService`**：查单任务、我已付款、关单、回调四入口共用 `applyResult(intent, providerResult)`，幂等。
- **P-4 代码位置**：券 `B/shop/coupon/{domain,repository,service,web}`；计价与退款计算 `B/shop/order/pricing/`；取消退款 `B/shop/order/refund/`；Biteship 客户端 `B/shared/logistics/biteship/`、运单与物流 `B/shop/logistics/`；stag 入口 `B/shop/stag/`。App：`A/features/shop/` 下按页重写为 `*_page_v3.dart`（旧 v2 在同 story 删除），券 `A/features/shop/coupon/`，退款 `A/features/shop/refund/`。
- **P-5 错误码**（ProblemDetail `type` 后缀，连字符风格同 `ErrorTypes`，App 按 type 分支，不解析 detail）：`coupon-not-found`（兑换码无效）、`coupon-code-used`、`coupon-not-in-period`、`coupon-already-claimed`、`coupon-sold-out`、`coupon-redeem-locked`（429）、`coupon-invalid`（提交时）、`quote-expired`、`quote-failed`（503）、`not-serviceable`、`sku-not-sellable`、`product-delisted`（410）、`unavailable-lines`、`not-placeable`。
- **P-6 时间**：所有「按天」用 `ScheduleWindow.WIB`；库内 UTC `timestamptz`；有效期比较一律服务端 `now()`。
- **P-7 外部调用**：Biteship / GemPay 调用不在持有行锁的事务内进行（先调用、再开短事务落结果）；失败记 WARN，不打印 key、手机号、地址（脱敏）。
- **P-8 迁移拆分**（一件事一个）：① SKU 包裹参数 ② 发货地 / 免运封顶 / 保价费率 + `shipping_zones.postal_code` ③ 订单头新列（D-8 列表）④ 运单新列 + 物流异常列 + `biteship_webhook_events` ⑤ banner 排期与 COUPON 目标 ⑥ 评价 `hidden_at` ⑦ 券四表（`shop_coupons` / `user_coupons` / `shop_coupon_codes` / `coupon_expiry_notify_log`）⑧ `shipping_quotes` ⑨ `shop_order_refunds` ⑩ `payment_intents.provider_expires_at` + `paid_after_close`；`user_coupons.expiry_notified_at` 并入 ⑦ ⑪ 重建 `ck_notifications_type`（取当前树最后一次重建的全集 + 2）。版本号取规划时刻的时间戳、在 sprint-status 预分配（同 social-topic 先例，满足「时间戳制」；实现时如需新增迁移取创建时刻）。
- **P-9 埋点**：事件名字面量；属性严格按 PRD §4；登记 `v112_events_test.dart`（`toko_` 已在前缀表）。
- **P-10 文案**：严格按 PRD §6 表写 `app_id.arb` / `app_en.arb`，表中标「复用」的引用原 key；新 key 前缀 `toko*`、`coupon*`、`checkout*`、`shopOrder*`、`shopRefund*`。删除复购相关 key。
- **P-11 共用支付面板**：只加可选参数（`showPaidCheck`、`amountLabel`、`hideCancel`、`collapsibleGuide`），默认值保持现状；送礼 / 解锁 / 身份证 / 月租调用点不改，测试证明不受影响。
- **P-12 测试**：计价器与退款计算器表驱动单测；券并发（同券两单同时提交只成功一单）；查单任务幂等；Webhook 回查（伪造载荷不改状态）；守卫测试（`PushCategoryTest`、`MentionNotifyTest`）同 story 改；App 每屏 widget 测试覆盖稿中状态；现有测试全绿（`CheckoutPreviewIntegrationTest` 中「不得出现券字段」断言随 D-8 删除）。

## 5. 项目结构与边界

路径缩写同 §1.2，`R/` = `petgo-backend/src/main/resources/`。＋ 新增，～ 改动，－ 删除 / 停用。

### 5.1 后端

```
B/shop/coupon/            ＋ domain(ShopCoupon, UserCoupon, CouponCode, enums) / repository / service(CouponLedgerService, CouponClaimService, CouponRedeemService, NewbieCouponService, CouponQueryService) / web(MeCouponController, ShopCouponController)
B/shop/order/pricing/     ＋ ShopPricingCalculator, PricingResult, ShopRefundCalculator
B/shop/order/refund/      ＋ ShopOrderRefund, ShopOrderRefundService, CancelReason, RefundDestinationReminderJob
B/shop/order/service/     ～ CheckoutService（preview/place 重写，BUY_NOW，LegacyCheckoutAdapter＋）, ShopOrderPaymentService（提交扣币、useCoin、关单推迟、markPaid 统一入口）, ShopFinanceDashboardService（扣币口径）, ShopOrderExpiryScanner（14 天、异常排除）, ShopOrderListService＋, LatePaymentService＋, ShopPaymentReconcileJob＋
B/shop/order/web/         ～ MeCheckoutController（preview POST、payment-check、refunds destination、列表与计数）
B/shop/logistics/         ＋ BiteshipShipmentService, BiteshipWebhookController, BiteshipStatusMapper, LogisticsStallJob, ShippingQuoteService（替换现 shipping/service 同名类）
B/shared/logistics/biteship/ ＋ BiteshipClient, BiteshipProperties, dto
B/shop/shipping/          ～ ShippingSettings（新列）, ShippingZone（postal_code；fee 停读）
B/shop/domain/            ～ ShopSku（包裹参数）, ShopBanner（排期、COUPON）
B/shop/web/               ～ ShopBannerController（/shop/banners）, ShopProductController（410、shipping-estimate、coupons、reviews、top）
B/shop/review/            ～ hidden_at、开关
B/shop/repurchase/        ～ 扫描停用、repurchase-cards 返回空
B/pay/                    ～ PaymentIntent.provider_expires_at / paid_after_close, PaymentIntentService（过期判据、applyCallback 电商关单后到账转 PAID）, PaymentReconciliationService（三态、网关调用移出事务）, GemPayGateway.queryCharge 三态
B/admin/shop/service/     ～ AdminShopOrderExceptionService 改调 ShopOrderRefundService
B/notify/                 ～ NotificationType +2, PushCategory 映射
B/shop/stag/              ＋ ShopStagController（@StagOnly）
R/db/migration/           ＋ ① ~ ⑪
```

### 5.2 App

```
A/features/shop/presentation/  ～ toko_page_v3（banner 轮播、给你的券、为宠物推荐三列、无品类）, shop_search_page（无结果推荐）, product_detail_page_v3（券、送达地区、运费、免运进度、评价、410 页）, cart_page_v3（估算、失效分组、空态推荐）, checkout_page_v3（配送卡、快递抽屉、券选择、PawCoin 开关、提交失败）, shop_order_detail_page_v3（物流状态、配送异常、退款、重下单）, product_delisted_page＋, review_list_page＋
A/features/shop/coupon/        ＋ my_coupons_page, coupon_card, coupon_picker_sheet, newbie_popup, coupons_for_you_row
A/features/shop/refund/        ＋ refund_destination_page（复用 refund_method_page_v2 的 UI 组件）、payout_account_form
A/features/shop/address/       ～ 地址簿标注不支持配送、表单抽出可嵌入结算页的组件、地区选择抽屉组件＋
A/features/order/presentation/ ～ order_list_page_v2（购物二级分组 + 计数 + 我的券入口）
A/shared/widgets/qr_payment_sheet.dart ～ 可选参数（P-11）
A/core/router/app_router.dart  ～ /shop 忽略 category、/me/coupons、/shop/orders/:token/refunds/:refundToken、/shop/products/:token/reviews
A/features/profile/…           － Diary 复购卡；～ petProfileWeightHint 文案
A/features/notify/…            ～ COUPON_EXPIRING / SHOP_ORDER_REORDERED 深链与文案；REPURCHASE_FOOD_LOW 落 Toko 首页
```

### 5.3 边界与集成点

- 外部：Biteship（报价、运单、Webhook、查单）、GemPay（查单 `/history`）。
- 内部事件：`PaymentIntentPaidEvent`（券转已使用、订单付款）（电商关单后到账同样发此事件，意图带 `paid_after_close`）、`RefundSucceededEvent`（成长值扣回）。
- 商家平台：只读 `shop_orders` / `shop_order_lines`，本线不改其依赖列语义。
- 后台线：调用本线 service 层（券管理、运单、取消退款、打款、重下单、评价隐藏、banner、SKU 包裹参数、发货地与免运封顶配置）。

### 5.4 需求 → 位置

| 需求 | 后端 | App |
|---|---|---|
| FR-127 | D-9 shipping-estimate、购物车 estimate | 详情送达地区 / 运费 / 免运进度，购物车底栏 |
| FR-128 | D-8、D-11 | 结算页 v3、支付面板参数 |
| FR-130 | D-1 ~ D-6、D-22 | 我的券、给你的券、领券抽屉、新人弹窗、券选择 |
| FR-131 | D-7、D-8 | 结算 PawCoin 开关、订单详情支付方式 |
| FR-132 | D-16 | 首页轮播 |
| FR-133 | D-17 ~ D-19 | 首页 / 搜索 / 详情 / 购物车 / 订单 / 地址簿重做 |
| FR-134 | D-11、D-14、D-15 | 我已付款、选择退款方式、退款进度、重下单展示 |
| FR-136 | D-20 | 删补货卡 / 引导条 / Diary 复购卡 |
| FR-172 | D-9、D-10、D-12、D-13 | 快递抽屉、保价、物流状态、配送异常 |

## 6. 架构校验

### 6.1 一致性
- 计价与退款各一个实现（P-1）；券状态单入口（P-2）；支付意图单入口（P-3）。
- 订单状态机不新增状态，物流异常与退款进度用独立字段 / 表，存量流转规则不受影响。
- 返佣基数不变：`unit_price` 不分摊券。

### 6.2 需求覆盖
- PRD §3.0 旅程 A ~ F 每步都落到 §5.4 某行；订单状态显示表由 D-18 分组 + D-19 字段覆盖。
- §4 埋点全部有归属（D-23）。
- 后台 AB-24 / 28 / 38 / 39 的业务逻辑由本线 service 层承接（S-2），页面不在本线。

### 6.3 非功能
- Biteship 询价次数：同邮编同包裹 30 分钟复用，详情 / 购物车 / 结算三处共用。
- 资金：关单前必查单（查询失败不关单）；迟到款落库可查且不脱离 PAID 报表；PawCoin 提交时扣；现金退款全链路有状态。
- 安全：Webhook 路径密钥 + 回查；兑换码防暴力；收款账户加密；新标识全部 token。

### 6.4 就绪度
- 外部待确认（不阻塞开发，阻塞上线）：Biteship 正式 key 与 Order API 开通、Webhook 地址配置、发货地信息；存量 SKU 包裹参数与 `shipping_zones.postal_code` 由运营补齐。
- 依赖后台线：券、banner、运单等在生产需要后台页面才能日常运营；**上线顺序须本线与后台线同批**。后台线另需承接：去掉商品编辑页「未填每日建议喂量」警告、下线「复购引擎效果」看板（FR-136）。
- 遗留（S-5）：商家仓按 `refunded_qty` / `refunded_total` 扣回取消订单的门店返佣。


> 2026-11-05 story 编写后收口（C-1 ~ C-13、S-10）见 `决策日志-shop.md`；各 story 顶部「规划收口补充」优先于其正文。
