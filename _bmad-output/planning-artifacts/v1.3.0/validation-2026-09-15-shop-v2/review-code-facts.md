# PRD 代码事实核查 · 电商 V2（PRD-v1.3.0-shop-v2.md）

- 核查日期：2026-09-15
- 代码基线：`feat/1.3.0-shop-v2` @ `a049d20c`（与 `dev_1.3.0` 相同）
- 方法：只读，逐条 grep / 读源码对照；未跑构建、未查库
- 判定：✅ 属实 · ⚠️ 部分属实（行号漂移或结论有偏差）· ❌ 与代码不符 · ❓ 代码层无法验证（依赖库内数据）

**合计 74 条：✅ 51 · ⚠️ 12 · ❌ 9 · ❓ 2**

---

## 一、逐条核查表

### A. 用户端 · 发现位 / 购物车 / 支付（UJ-1，FR-111~114、FR-145、FR-147）

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 1 | `toko_page_v2.dart:25-26` 自记「推荐区 `mainImageKey` 是裸 key、全仓无 key→URL 拼装」 | ⚠️ | 25-26 行确有这段注释，但**已过期**：同文件 `:926-927` 写着「2026-08-27：服务端补下发 mainImageUrl 后这里才真的有图可显示」。PRD 抄的是没更新的文件头注释 |
| 2 | `:768` 复购卡无价 | ✅ | 768 行注释「RepurchaseCardView 不下发价格，故整行不画」 |
| 3 | `:943` 推荐区有价 `Text(formatIdr(it.minPrice))` | ✅ | 943 行 |
| 4 | `:945` 无评分/销量 | ✅ | 945 行注释「接口无此字段 → 整行不显示」 |
| 5 | `RepurchaseCardView` 不下发价格 | ✅ | record 字段：triggerId/triggerType/skuToken/productToken/productName/petName/estimatedDepletionDate/daysLeft/dailyGrams/remainingGrams/purchasedOn，没有价格也没有图 |
| 6 | **FR-114**：`RecommendationItem.mainImageKey` 是裸 objectKey，全仓无 key→URL 拼装，推荐区走占位斜纹 | ❌ | **已经实现了**。`RecommendationView.Item` 同时带 `mainImageKey` 和 `mainImageUrl`（`RecommendationView.java:25-26`）；`ProfileRecommendationService.java:110-111` 用 `imageUrls.publicUrl(...)` 填好 URL；App `toko_page_v2.dart:930` 用 `ShopImage(url: it.mainImageUrl)` 显示。FR-114 本版不用再做 |
| 7 | banner 单图、不能点 | ✅ | `ShopBannerView` 注释写明不带 link |
| 8 | `cart_page_v2.dart:14-22` 整车下单、不画勾选框 | ✅ | 10-22 行；后端 `PlaceOrderRequest(addressToken, entrySource, triggerType)`，没有行选择 |
| 9 | `qr_payment_sheet.dart:17` 的 `pollPaid` 是 `Future<bool>`，FAILED/EXPIRED 没法表达 | ⚠️ | 签名在 `:23`（17 行是注释）。`Future<bool>` 属实，但面板**已经有**「中止」通道 `QrPaymentAborted`（`:14`、`:79`）。电商 `pollPaid`（`shop_order_detail_page_v2.dart:747-756`）轮询的是**订单状态**，不是 payment_intent；只有订单变 CANCELLED 才抛中止，面板随即**静默关闭**，不给任何提示。FR-111 不用改签名，走现有中止通道 + 读 intent 状态就行，工作量比 PRD 估的小 |
| 10 | 对着二维码一直等到 60 分钟超时 | ✅ | `ShopOrder.PAYMENT_WINDOW = 60min`；超时后订单被取消，面板静默关闭 |

### B. 售后出口（UJ-2 / UJ-4，FR-115~120）

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 11 | `_bottomBar` 在 `PENDING_SHIPMENT` 直接 `return null` | ✅ | `shop_order_detail_page_v2.dart:618-690`，待发货态不匹配任何分支，落到 `return null` |
| 12 | 退货入口只在 `status == completed` 出现，全项目只有一处 push | ✅ | `:689` + `:706`，全仓只有这一处 `context.push('/shop/orders/.../return')`。⚠️ **PRD 漏了 `DELIVERED`**：后端 `requireReturnable` 允许 `DELIVERED‖COMPLETED`（7 日窗口内）提普通退货，但 App 在 DELIVERED 只给「确认收货」，同样点不到退货 |
| 13 | SHIPPED 只给「确认收货 / 查物流」 | ✅ | `canConfirmReceipt = shipped‖delivered`；`_helpBlock` 注释「发货态只做告知不给退货按钮」 |
| 14 | `CANCEL_BEFORE_SHIPMENT` 门控是 `PENDING_SHIPMENT`，`skipsShipback=true`，`platformFault=false` | ✅ | `ReturnType.java:21` + `ReturnRequestService.java:305` |
| 15 | `REFUSED_ON_DELIVERY` 可以从 `SHIPPED` 发起，`platformFault=true` | ✅ | `ReturnType.java:18` + `ReturnRequestService.java:306` |
| 16 | `ReturnType:12-30` | ✅ | 枚举常量在 12-21 行，构造器在 23-30 行 |
| 17 | `RefundExecutionService:222-258` 有两条独立溢价：① `isPlatformFault` → `compensationPremiumRate/Cap`；② `isUndelivered && thisCash>0` → `premiumRate/premiumFixed` | ⚠️ | 实际在 **231-267** 行（小漂移）。结论有两处要补：(a) ② 还有**第三道门** `r.getCashDestination() == TO_PAWCOIN`（`:265-267`），用户选退回现金时拿不到激励溢价；(b) ① 的基数是 `split.thisCoin()`（币段），② 的基数是 `split.thisCash()`（现金段），不是同一个钱 |
| 18 | 注释明令「两条溢价必须是两个独立配置项，写成同一个数值会静默毁掉 AB-13A / AB-6C」 | ✅ | `:250-253`、`:307-309` |
| 19 | 拒收「两条全中，拿双份」；自助取消「只中 ②」 | ⚠️ | 有条件：只有**订单有币段**时才有 ①，只有**有现金段且用户选了转 PawCoin** 时才有 ②。纯现金 + 退现金的拒收，两条都拿不到。套利面确实存在，但 FR-116 的验收应按「币段 / 现金段 × 退款去向」四种组合来写 |
| 20 | `OpenedPrecedent.java:16` 写着 S-4「90 日 ≤2 次」，全仓没有实现 | ✅ | 只在该注释和迁移 `V20260818_0440` 注释里出现；`returns/` 下没有任何频次计数或查询 |
| 21 | `requireReturnable` 只在提交时校验 | ✅ | 唯一调用点 `ReturnRequestService.java:97`（submit）；`ReturnRequest.approve()` 只校验退货单状态 |
| 22 | **FR-115 ②**：批准期间订单被发货没有守卫 | ❌ | **守卫已经在了**：`CANCEL_BEFORE_SHIPMENT` / `REFUSED_ON_DELIVERY` 提交时订单立刻转 `REFUNDING`（`ReturnRequestService.java:152-155`），而 `ShopOrderFulfillmentService.ship()` 只接受 `PENDING_SHIPMENT‖SHIPPED`（`:69-72`），REFUNDING 的单发不了货。驳回时 `restoreOrderStatus` 会恢复原状态 |
| 23 | `InventoryService:46-82` 里 lock/release/commit/restock 四个原语的语义 | ✅ | 与 PRD 表格一致 |
| 24 | `restock` 全仓只有一个调用方 `InventoryMovementService.doInbound` | ⚠️ | `InventoryService.restock()` 实际**零调用方**；`doInbound` 调的是 **`SkuInventoryRepository.restock`**（`InventoryMovementService.java:171`）。结论方向对，对象说错了 |
| 25 | `doInbound` 只由 `receivePurchase` / `receiveReturn` / `stocktake` 触发 | ❌ | `stocktake` **不走** `doInbound`，走的是 `inventory.stocktakeTo` CAS。触发 doInbound 的只有 receivePurchase 和 receiveReturn |
| 26 | 这三者都是运营在后台手工登记的动作 | ⚠️ | `receiveReturn` 还会被业务流程**连带调用**：`AdminReturnService.passInspection`（`:170`）、`AdminReturnService.executeRefund`（`:219`）、`AdminShopOrderExceptionService.cancelWholeOrder → restock`（`:93/:237`） |
| 27 | **D-23 查证结果**：发货前取消目前**没有任何库存回补路径**，FR-115 要求在退款执行里补一次 restock | ❌ | **路径已经在了**：`AdminReturnService.executeRefund`（`:205-222`）在首次执行、且类型为 `CANCEL_BEFORE_SHIPMENT` 时，逐行 `movements.receiveReturn(...)` 回补；注释写的是「发货前取消：货从未出库，但付款时已 commit 扣了实际库存 —— 退款执行的同时按原订单号以退货入库批次回补」，并注明拒收不在这里回补。这也是 `refunds.execute` 的**唯一**调用方。所以 FR-115 实现要求 1 已经做了；要求 3（拒收不立即回补）也已经是现状。**真正的缺陷是 PRD 第 2 条担心的反面**：现有实现**恰好复用了** `receiveReturn`，SKU 没有采购入库记录时会抛异常，导致整个 `executeRefund` 事务回滚，退款执行不下去 |
| 28 | `receiveReturn` 会调 `lastPurchaseCostPrice`，没有采购记录的 SKU 直接抛异常 | ✅ | `InventoryMovementService.java:82`、`:150-159` |
| 29 | `POST /shop-returns/{token}/shipback` 和 `registerShipback()` 都有，但全 App 没有 UI 调用 | ✅ | 后端在 `MeReturnController.java:114`；App 在 `shop_return_repository.dart:78`，`presentation/` 下零调用 |
| 30 | 后台 `/admin/shop/returns/{token}/shipback` 存在 | ✅ | `AdminReturnController.java:178` |
| 31 | 退货收件地址三项全空 | ❓ | 三个字段 ✅（`shipping_settings.return_address_text / return_receiver_name / return_receiver_phone`）；「全空」是库里的数据，代码层看不到 |
| 32 | 批准退货时没有任何地址告警 | ✅ | `AdminReturnService.approve()` 没有读 shipping_settings |

### C. 客服 / 工单（FR-130、FR-131）

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 33 | 用户侧 `SupportTicketService` 和后台侧 `AdminTicketRefundService` 的 `resolveRelatedOrder` 都只认 `ConsultOrder` | ⚠️ | 用户侧属实（`SupportTicketService.java:213-220`）。后台侧**没有**叫这个名字的方法，对应的是 `linkOrder`（`:44-66`）和 `ensureRefundRequest`，同样只认 ConsultOrder。**更要紧的是**：`feedback_tickets.related_order_id` 是一个裸 bigint，语义写死为 consult_orders.id（`V70` + `V86` 注释），而 `shop_orders.id` 也是自增 bigint，两边会撞号 ⇒ FR-131 **必须加一个订单类型区分列（要做 Flyway 迁移）**；另外后台的「关联订单」和问诊退款审批（approveNeed）是绑在一起的，扩展时要切开 |
| 34 | `FeedbackTicket` 只有主题 + 正文 + 附件；`TicketInternalNote` 只内部可见；后台四个动作里没有「回复用户」 | ✅ | 实体另有联系方式、CSAT 等字段，但没有会话模型；后台 POST 动作是 resolve / link-order / refund-approve / refund-reject 四个 |
| 35 | `ShopOrderPaidHandler.onPaid` | ✅ | `ShopOrderPaidHandler.java:47` |
| 36 | `LarkOAuthClient` 和 `LarkContentClient` 的 token 缓存 | ✅ | `LarkContentClient.java:46-72`（tenant_access_token，过期前 5 分钟刷新） |
| 37 | `LarkIdentity.openId` 存在但没有落库 | ✅ | record 注释「仅日志关联用，不作身份键」；迁移里没有 open_id 列 |
| 38 | `IndonesiaPhone` 存在 | ✅ | `shop/address/domain/IndonesiaPhone.java` |

### D. 运营后台（FR-121~129、FR-139~144、FR-148）

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 39 | `addThumb` 永远 `appendChild` | ✅ | `static/admin/admin.js:334-367` |
| 40 | `MAX=9`，超出用 `alert` 拒绝 | ✅ | `admin.js:256`、`:372-375`（主图 1 张 + 图集最多 8 张） |
| 41 | 没有「设为主图」按钮 | ✅ | 主图 = 第一张缩略图，只能拖拽换序 |
| 42 | `shop-product-form.html:220` SKU 区只在编辑态展示 | ✅ | `th:if="${productId} != null"` |
| 43 | SKU 表单没有图片字段 | ✅ | 字段只有 specName / price / netWeightG / returnPolicy / costPrice |
| 44 | 3B 纪律：`POST /admin/shop/products/{id}/skus` 出过 `@ModelAttribute` id 撞名事故 | ⚠️ | 事故史属实，但**已经修了**：路径变量已改名为 `{productId}`（`AdminShopProductController.java:253-263`，注释完整记录了原因），模板里的隐藏 `<input name="id"/>` 也还在。FR-125 放开新建态时这条仍要遵守，但「正中靶心」的风险已经降下来了 |
| 45 | `AdminShopOrderRow` 不带 PII，注释「区里有几万人」 | ✅ | 属实 |
| 46 | 对账页：from/to + 12 个汇总数字，没有下钻、没有导出 | ✅ | `shop-reconciliation.html` 共 13 行：12 个数值 + 1 个「两段之和 = 实付」是否平衡的标记 |
| 47 | `SeedBatchExcelService` 存在 | ✅ | `admin/seed/service/SeedBatchExcelService.java` |
| 48 | `ProductCategory` 只有 `MAKANAN/OBAT_VITAMIN/CAMILAN` | ❌ | **四个值**：多一个 **`PERAWATAN`** |
| 49 | `Species` 只有 `DOG/CAT` | ❌ | **三个值**：多一个 **`UNIVERSAL`** |
| 50 | `AdminShopProductService:130/145/150/154/159/162/211/214` 是服务端校验 | ✅ | 8 行都是 validation 抛点（进货价 / 名称长度 / 品类 / 物种 / 退货规则 / 图集 8 张 / 价格 / 净含量） |
| 51 | `application.yml:203` 图片上限 10MB | ✅ | `max-file-size: ${MULTIPART_MAX_FILE_SIZE:10MB}` |
| 52 | `admin/shop/` 下 grep moderation 零命中 | ✅ | 零命中 |
| 53 | 库存页没有分页/搜索/筛选；商品页没有搜索/分页 | ✅ | `AdminShopInventoryController.list` 直接 `skus.findAll()`；商品列表只有 category / active 两个筛选 |
| 54 | C 端 `ShopProductController.list()` 返回全量 `List`，不分页 | ✅ | `:48-51`，参数只有 category 和 q |
| 55 | `AdminShopListingService.java:27` 默认不设 SKU 上限 | ✅ | 27 行附近的注释 + `@Value("${petgo.shop.sku-cap:0}")` |
| 56 | 没有人类可读的商品/SKU 编码，只有 spec_name 和 token | ✅ | ShopProduct / ShopSku 只有 `publicToken` |
| 57 | `detailHtml` 以 HTML 入库，App `_stripHtml` 后按纯文本显示 | ✅ | `product_detail_page_v2.dart:488/639` |
| 58 | `ShopSku.netWeightG` 是净含量 | ✅ | `ShopSku.java:49` |
| 59 | `ShopOrder.shippingDiscount` 是订单级字段 | ✅ | `ShopOrder.java:54` |

### E. 支付定性（ACT-1 / FR-112）

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 60 | `payment_intents` 表有 `status` 和 `gateway_meta` 列 | ✅ | `V60__init_payment_intents.sql:13-15` |
| 61 | FR-112：失败原因已经落库、界面零渲染 | ⚠️ | 零渲染 ✅（`AdminPaymentQueryService` 注释「本查询不返 meta」）。「原因已落库」只对**网关回调**成立：懒过期写的是 `markExpired(null)`，不会覆盖 meta；内部作废写的是 `{"reason": "TIMEOUT" / "USER_CANCEL" / "CANCELLED"}`，不是网关原因 |
| 62 | **ACT-1 判读口径**：`EXPIRED` 居多 = 用户扫了不付；`FAILED` 带网关错误码 = 支付故障 | ❌ | **口径和代码对不上**。电商订单 60 分钟超时或用户取消时，`ShopOrderPaymentService.releaseAndCancel` → `failByToken(intent, "TIMEOUT" / "USER_CANCEL")`，把 intent 置成 **FAILED**，不是 EXPIRED（`ShopOrderPaymentService.java:230/243/279/287-298`，`PaymentIntentService.java:158-165`）。问诊取消也是 FAILED + `USER_CANCEL`（`:311`）。`payment_intents` 被 5 种 purpose 共用。**按 PRD 那条 SQL 查，「用户扫了没付」会大量表现为 FAILED**，被误判成网关故障，整份 PRD 的优先级就会被翻过来。SQL 至少要加 `purpose='SHOP_ORDER'`，并按 `gateway_meta->>'reason'` 把 TIMEOUT / USER_CANCEL / CANCELLED 和网关回调的失败分开 |

### F. 缺陷收口（FR-133~138）与其他引用

| # | PRD 声称 | 判定 | 实际代码 |
|---|---|---|---|
| 63 | `LogSanitizer.SENSITIVE_KEYS` 缺 `content`、有 `email` | ✅ | `LogSanitizer.java:27-36`。补充：同文件已有 `REQUEST_ONLY_SENSITIVE_KEYS`（`:44`，只对请求体打码）。`content` 是很通用的字段名，放进这个集合比放进全局集合更合适，免得把所有响应体里的 content 都打掉 |
| 64 | 评价接口仍然可以提交（字段是 content） | ✅ | `MeShopReviewController` 有 `POST /api/v1/me/shop-reviews`，`SubmitReviewRequest.content` |
| 65 | FR-135：「C 端硬编码中文 67 处（退货 44 · 评价 13 · 购物车 5 · 地址 3 · 其余 2）」，走 `messages.properties` 修 | ⚠️ | **这些中文不在 App 里**。`petgo_app/lib/features/shop` 生产代码里中文字符串字面量只有 1 处（`shop_dialog.dart`；另有 23 处在 `lib/dev/shop_gallery_main.dart` 调试页）。App 文案走 ARB（`app_en.arb` / `app_id.arb`）。这 67 处实际是**后端** `shop/**` 里的 `AppException` 中文 detail（按包粗数：returns 53 · review 15 · cart 7 · address 7，精确数复现不出来，统计口径可能不同），所以用 `messages.properties` 在后端修，方向是对的。但有两点：(a) 购物车和结算的 repository 已明确「不把后端 detail 原文丢给用户」（`cart_repository.dart:73`、`checkout_repository.dart:60`），用户实际感知不到；(b) `Messages.resolve` 只给挂了 code 的异常按 locale 取文案，而 shop 包里挂 code 的是 0 处 ⇒ 实际工作量是**逐个挂 code 再补三份 properties** |
| 66 | App ARB 写「2×24 小时」，后端是 7 天 | ✅ | `app_id.arb:2099`「2×24 jam」、`app_en.arb:3106`「2x24 hours」；后端 `requireReturnable` 是签收起 7 日 |
| 67 | `ShopOrderDetailView` 没有 `displayNo`；列表显示 `TOKO-…` | ✅ | 详情只有 `orderToken`；列表由 `OrderCenterService` + `OrderDisplayNo.ECOMMERCE="TOKO"` 生成；App 详情页二维码下方显示的也是 `order.orderToken` |
| 68 | `GlobalExceptionHandler` 缺 `HttpRequestMethodNotSupportedException` 分支，405/400 回 500 | ⚠️ | 缺 405 分支 ✅。400 **已经处理**：`MissingServletRequestParameter / MethodArgumentTypeMismatch / HttpMessageNotReadable / MissingServletRequestPart` → 400（`:96-103`）。FR-138 实际只剩 405（最多再加一个 415 `HttpMediaTypeNotSupported`） |
| 69 | `shop_order_detail_page_v2.dart:624`「过期后不保留支付入口」 | ✅ | 624 行 |
| 70 | 权限码 53 个，`AdminPermissionsTest.listStableSize` | ❌ | 测试方法存在 ✅，但当前断言是 **`hasSize(72)`**（`AdminPermissionsTest.java:126`）。53 是从过期的 `v1.4.0/HANDOFF.md:104` 抄来的 |
| 71 | Epic 4 SPEC-2 三条出口（后台标记 / 用户在已发货态确认 / 7 日自动）都已就位 | ✅ | `AdminShopOrderController.markDelivered`、`MeCheckoutController.confirmReceipt`（SHIPPED 可确认）、`ShopOrderFulfillmentService.autoCompleteOverdue`（`AUTO_COMPLETE_AFTER=7d`）；另有发货起 7 日自动置送达 |
| 72 | 售罄数恒为 0，原因是 `is_active` 口径差异（实际 61） | ⚠️ | 机制属实：`ShopFinanceDashboardService.outOfStockSkuCount` 限定 `p.is_active = true`，还 INNER JOIN `sku_inventory`（没有库存行的 SKU 不计入）。「恒为 0 / 实际 61」是 stag 数据，代码层验证不了 |
| 73 | `AGENTS.md:51` 点名 D1/D2 是安全攸关决策 | ❌ | 仓库里**没有 AGENTS.md**。这句话在 `CLAUDE.md:83` |
| 74 | stag 现有 93 个商品 / 264 个 SKU | ❓ | 库内数据 |

---

## 二、对 PRD 有实质影响的偏差

| 严重度 | 偏差 | 影响的 FR / 决策 | 建议 |
|---|---|---|---|
| **critical** | **ACT-1 的判读口径是错的**（#62）。电商超时未付和用户取消都会把 payment_intent 写成 **FAILED**（reason=TIMEOUT / USER_CANCEL），不是 EXPIRED；表又被 5 种 purpose 共用。照 PRD 原 SQL 查，「扫了不付」会被读成「网关故障」，§4.1 的两条分支和 R-1「整份 PRD 让路」会被错误触发 | ACT-1、R-1、§4.1 全组排序 | SQL 改为 `WHERE purpose='SHOP_ORDER' AND status IN ('FAILED','EXPIRED')`，并按 `gateway_meta->>'reason'` 分桶：TIMEOUT / USER_CANCEL / CANCELLED 算「用户侧」，其余带网关码的才算「支付侧」 |
| **high** | **D-23「发货前取消没有任何库存回补路径」不成立**（#27）。`AdminReturnService.executeRefund` 已经对 `CANCEL_BEFORE_SHIPMENT` 回补，拒收已经不回补。真正的缺陷是它复用了 `receiveReturn`，**SKU 没有采购入库记录时抛异常，整个退款执行回滚** | FR-115 实现要求 1~3、D-23 | 把 D-23 结论改成「回补已存在；需修的是无采购记录 SKU 的抛异常问题（成本价兜底 / 新增 movement 类型）」，FR-115 的后端工作量相应缩小 |
| **high** | **FR-114 已经实现**（#6）。推荐区后端下发 `mainImageUrl`、App 已在用（2026-08-27），PRD 引用的是没更新的文件头注释 | FR-114 | 从本版删掉 FR-114；顺手把 `toko_page_v2.dart:23-26` 的过期注释清掉 |
| **high** | **`NON_RETURNABLE` 商品不能发货前取消，也不能拒收**（PRD 未提）。`requireLineReturnable` 对所有退货类型生效，包括 CANCEL_BEFORE_SHIPMENT 和 REFUSED_ON_DELIVERY（`ReturnRequestService.java:325-330`），所以不可退商品的待发货订单，自助取消会直接 409 | FR-115、FR-118 | 在 FR-115/118 里明确：两类「未交付」类型要不要跳过行级可退校验（语义上货没到用户手里，退货规则不该适用） |
| **medium** | **FR-115 ②「批准期间被发货无守卫」不成立**（#22）。提交时订单已经转 REFUNDING，`ship()` 会拒绝 | FR-115 前置② | 把这条从前置里删掉，改成验收项「REFUNDING 态发货被拒」 |
| **medium** | **「自助取消」其实仍然要运营两步人工操作**（PRD 未提）。提交后退货单停在 PENDING_REVIEW，要后台 approve，再手工 executeRefund（后者是 `refunds.execute` 的唯一入口），没有自动审批 | FR-115/118「工作量几乎全在前端」、UJ-2 的用户感知 | 要拍板：未交付类型是否自动批准并自动执行退款；如果是，要补后端流程，工作量不止前端 |
| **medium** | **FR-131 要做 schema 变更**（#33）。`related_order_id` 语义写死是 consult_orders.id，和 shop_orders.id 会撞号，必须加订单类型列；后台的「关联订单」还和问诊退款审批耦合在一起 | FR-131 工作量 | 在 FR-131 验收里写明：订单类型区分列 + Flyway 迁移 + 与问诊退款审批解耦 |
| **medium** | **FR-117 漏了 DELIVERED 态**（#12）。后端允许 DELIVERED 提普通退货，App 在这个状态只给确认收货 | FR-117 | 覆盖状态改成 PENDING_SHIPMENT / SHIPPED / **DELIVERED** |
| **medium** | **FR-135 的对象和工作量描述不准**（#65）。67 处中文在后端 AppException detail 里，不在 App；App 的购物车和结算刻意不显示 detail；shop 包没有一处挂 message code | FR-135 | 改成「后端 shop 包 AppException 挂 code + 三份 properties」，先确认有没有 C 端页面真的会显示 detail，否则可以降级 |
| **medium** | **FR-116 的溢价条件不完整**（#17、#19）。激励溢价还要求用户选了 `TO_PAWCOIN`；补偿溢价的基数是币段，激励溢价的基数是现金段 | FR-116 / FR-118 验收、D-22 | 验收按「有无币段 × 有无现金段 × 退款去向」组合写，FR-118 那条「到账额 = 应退额 + 补偿溢价」的算式要带上条件 |
| **low** | 品类是 4 个值（多 PERAWATAN），物种是 3 个值（多 UNIVERSAL）（#48、#49） | FR-142、D-25「存量三个枚举值」 | 迁移方案按 4 个品类 + 3 个物种来做 |
| **low** | FR-111 不用改 `pollPaid` 签名，现成的 `QrPaymentAborted` 通道就能用；现状问题是轮询看的是订单不是 intent，而且中止后静默关闭（#9） | FR-111 工作量 | 实现提示：轮询加上 intent 状态，失败和过期走中止通道并带上原因 |
| **low** | FR-138 的 400 部分已经做了，只剩 405（#68） | FR-138 | 标题改成「405 不再回 500」 |
| **low** | 权限数是 72 不是 53（#70）；AGENTS.md 不存在（#73）；3B 撞名已改成 `{productId}`（#44）；stocktake 不走 doInbound（#25） | §9 纪律表、D-23 表述 | 改正引用；`HANDOFF.md:104` 的「当前 53」也已经过期 |
