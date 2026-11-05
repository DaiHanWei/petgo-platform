# Story 9.1: 电商订单列表与订单详情（App，F1–F12、F17、F18、F20–F22）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、串行）
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9 + 评审更正）> PRD `电商/1-4-2电商prd.md`（§3.0「订单状态在用户侧的显示」、FR-133 订单、FR-134 ②③、FR-172 物流异常、§4、§6 F 组）> `architecture-v1.4.2-shop-delta.md`（D-11 宽限期、D-13、D-18、D-19、D-21、P-1、P-4、P-9、P-10、P-12）> UI 稿 `电商/ui-1-4-2-shop-integrated-v1.html` F 组每屏下方说明（数值照说明与全局规范取，**不从截图量像素**）。跨 story 契约以 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md` 为准。云端执行须知 `cloud-rules-shop.md` 优先于本文件的通用步骤。
> 前置：**6.2**（`GET /me/shop-orders`、`/counts`、订单详情扩展字段）、**5.1 / 5.2**（`logistics` 出参与配送异常）、**4.1 / 4.2**（`refunds[]`、`cancelledLines`、`reorderedTo/From`）、**2.3**（`SHOP_ORDER_REORDERED` 通知类型）、**7.2**（支付面板电商参数与「去支付」拉起逻辑）。后端未就绪的字段按「接口契约」写 mock 测试，联调留 10.1。
> 范围层：**只 App**（`petgo_app/`）。选择退款方式页、F13 / F16 / F19 / F25 的退款状态头与「选择退款方式」入口归 **9.2**；我的券页归 **9.3**。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-6**：物流异常类型 `LOST_DAMAGED` 拆为 `LOST`、`DAMAGED`（订单头 `logistics_exception` 取值：`PICKUP_FAILED | RETURNED | STALLED | LOST | DAMAGED`）；用户侧「配送异常」条件为已确认的 `RETURNED / LOST / DAMAGED`；埋点 `exception_type ∈ returned | lost | damaged`。取消原因 `CancelReason.LOST_DAMAGED` 保持不拆。
- **C-10**：券快过期、重新下单两类推送点开**直达**对应页面（我的券 / 新订单），按 PRD。
- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。
- **C-9**：订单详情补 `reorderedFromOrderToken`（新单可点回原单）。

## Story

As a 买家,
I want 按状态找订单，详情看清金额、物流、退款和重新下单的来龙去脉,
so that 不用问客服也知道订单情况。

## 接口契约（来自 6.2 / 5.x / 4.x，实现前核对后端实际 JSON；名字有出入以已提交代码为准并改本文件）

- `GET /api/v1/me/shop-orders?group=all|pending_payment|pending_shipment|to_receive|completed|cancelled&cursor=` → `{items[], nextCursor, hasMore}`；分组按 `displayStatus`（宽限期待支付归 `cancelled`，S-7）。每项（6.2 story AC1.4 `ShopOrderListItem`）：`orderToken`、`displayNo`、`status`（原始）、`displayStatus`、`totalAmount`（列表金额统一为订单总额，UI F1 说明）、`createdAt`、`thumbnailUrl`、`itemTitle`、`itemCount`、`paymentDeadline`（仅 `displayStatus=PENDING_PAYMENT` 时下发）、`canPay`、`logisticsStatusKey`（`awaiting_pickup | picked | in_transit | out_for_delivery | delivered`，手工运单 / 无运单为 null）、`deliveryIssue`（bool）、`refundActionRequired`（bool，本 story 不用，9.2 可用于列表提示）；null 字段按全局 NON_NULL 不下发。
- `GET /api/v1/me/shop-orders/counts` → `{pendingPayment, toReceive}`（D-18）。
- `GET /api/v1/me/coupons/summary` → `{availableCount}`（2.2 story AC8.4 补的接口，专供「我的券」入口计数）。
- `GET /api/v1/me/shop-orders/{token}`（D-19，6.2 story AC3.2 表；原 22 个字段保留，新字段在末尾，存量订单为 null）：`displayStatus`、`paymentDeadline`、`canPay`、`paidAt`、`cancelledAt`、`cancelReason`（4.1 `CancelReason` 名）、`amounts {goodsAmount, discountCouponAmount, shippingFee, shippingDiscount, shippingDiscountSource (NONE|FREE_SHIPPING_RULE|COUPON), insuranceFee, payable, courierName, courierServiceName}`、`paymentMethods {coinAmount, coinStatus (HELD|PAID|RETURNED), cashAmount, cashStatus (AWAITING|PAID|UNPAID)}`（金额 0 的一项为 null）、`logistics {provider (MANUAL|BITESHIP), courierName, waybillId, statusKey, trackingUrl, exception, exceptionType}`（5.1 / 5.2：已确认的退回 / 丢件破损 → `exception = DELIVERY_ISSUE`、`exceptionType ∈ RETURNED | LOST_DAMAGED`，其余两者 null）、`refunds[] {refundToken, scope, reason, status, totalRefund, coinRefund, cashRefund, compensationAmount, cashDestination, payoutChannel, canChooseDestination, payoutFailed}`（本 story 只读 `scope=LINE` 的金额做 F17 合计；其余归 9.2）、`cancelledLines[] {lineIndex, productName, specName, cancelledQty, refundAmount}`、`couponReturn`（`RETURNED | EXPIRED | NOT_RETURNED`，未取消或未用券为 null）、`reorderedToOrderToken`、`reorderedFromOrderNo`（无原单 token，见「待确认」3）、`lateCashPaid`；旧字段 `goodsSubtotal / shippingFee / shippingDiscount / totalAmount / coinAmount / cashAmount / packages[]` 仍在，作存量 / 旧后端降级。
- `POST /api/v1/me/shop-orders/{token}/confirm-receipt`、`/cancel`、`/pay`、`/payment-check`（7.2）不变。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调（10.1 本地做）· `[L2]` Android 模拟器视觉与还原度（本地）。

### AC1 — 全局视觉规范（本 story 覆盖的两页）`[L0]` `[L2]`（UI 稿「研发执行说明」第 3 条、全局布局）
1. 顶栏白底（`.ab` 高 44；返回键热区 44、`Icons.chevron_left_rounded` 28；标题 18 / w600）；订单列表页为大标题（`.ab.big`：高 52、标题 24 / w700）。
2. 字号改用 `AppTypography`（正文 15、说明 13、免责 12），**不再用 `ShopText` 小字号**；间距 / 圆角用 `AppSpacing` / `AppRadius`：页边 16、卡片白底圆角 16、卡内边距 16、卡间 12；页底色 `#F3F1F8`（`--bg`，取 `colors.dart` 中同值 token，没有则按 `AppColors` 最近的页底色 token，Completion Notes 记）。
3. 价格紫色（`AppColors.mint` #845EC9，待付款）/ 已付墨色（`#2E2A45`）；主按钮高 48、圆角 12、16 / w600；次按钮白底 + 1.5 `line` 描边；小胶囊按钮高 36、全圆角、13 号。
4. 只换视觉，线上「原有保留」的模块功能与文案不变（UI 稿第 4 条「哪些不能动」）。

### AC2 — 订单列表：「购物」二级分组 + 计数 + 我的券入口（F1、F2、F3、F18）`[L0 widget]` `[L2]`（FR-133.9、D-18、PRD §3.0 状态表）
1. 一级标签不变：`Semua / Konsultasi / PawCoin / Belanja`（`orderFilterAll` / `orderFilterKonsultasi` / `orderFilterPawcoin` / `orderTypeEcommerce`）。**只有选中「Belanja」**时出现二级标签行：`Semua`（复用 `orderFilterAll`）/ `Belum bayar` / `Dikemas` / `Dikirim`（新 key，§6 F1）/ `Selesai`（复用 `shopOrderStatusCompleted`）/ `Dibatalkan`（复用 `shopOrderStatusCancelled`）。选其他一级标签时无二级行。
2. 二级标签样式（UI `.sub`）：横向一行、项间距 24、左右内边距 16、白底、底部 1px `line2` 分隔；13 号 `ink2`，选中 `mint6`(#6C48AE) w600 + 2px `mint` 下划线；「Belum bayar」「Dikirim」后接红色数字角标（`AppColors` 红 #F0425A 底、白字 10 / w600、胶囊、最小宽 16、内边距 2·4），数量取 `/counts` 的 `pendingPayment` / `toReceive`，**为 0 时不显示角标**；counts 请求失败 → 不显示角标、不报错。
3. 选「Belanja」后列表数据改走 `GET /me/shop-orders?group=`（新 repository 方法），游标分页沿用现有 controller 范式（加载更多、下拉刷新）；「Semua」一级标签仍走通用 `/orders` 混排，**不受影响**（`OrderCardV2` 原样，现有测试全绿）。
4. 「我的券」入口（只在「Belanja」下）：二级标签行下方一条浅紫横条（`tint` #F8F2FF 底、圆角 12、内边距 12·16），左 `Icons.confirmation_number_outlined` 20 `mint`、文字「Kupon Saya · {n} bisa dipakai」（新 key `shopOrderCouponEntry(n)`，§6 F1），右 `chevron_right`；`n` 取 `/me/coupons/summary` 的 `availableCount`（请求失败或 0 时只显示「Kupon Saya」，不显示「0 bisa dipakai」）（复用 8.1 / 9.3 的 `couponMyTitle`，不存在则本 story 新增，§6 A1 / G1「Kupon Saya / My coupons」）。点击 `context.push('/me/coupons')`；**若 `/me/coupons` 路由在本 story 开工时尚未注册（9.3 未做），入口先不渲染**，由 9.3 打开（T0 核对，Completion Notes 记）。
5. 电商订单卡（新 `ShopOrderCard`，F1 / F2）：卡头 `TOKO` 徽标 + 单号（等宽）+ 右侧状态文字；卡体首件图（52×52，圆角 12）+ 名称（单行省略）+「dan N barang lain」（复用 `orderCardMoreItems`）或日期 `dd/MM` + 右侧「Jumlah」（`orderAmountLabel`）与 `formatIdr(totalAmount)`（待付款紫色、其余墨色）。状态文字映射（PRD §3.0）：PENDING_PAYMENT →「Menunggu pembayaran」（`shopOrderStatusPendingPayment`）；PENDING_SHIPMENT →「Menunggu dikirim」（`shopOrderStatusPendingShipment`）；SHIPPED / DELIVERED → 物流状态文案（AC4.3 五段 key，`logisticsStatusKey` 缺失时 SHIPPED 用 `shopOrderStatusShipped`、DELIVERED 用 F9 新 key「Paket sudah diterima」）；`deliveryIssue=true` →「Pengiriman bermasalah」（F2 新 key）；COMPLETED / CANCELLED / REFUNDING / REFUNDED → `shopOrderStatusCompleted` / `shopOrderStatusCancelled` / `orderStatusRefunding` / `orderStatusRefunded`。
6. 卡上操作（最多两个，主右次左）：
   - 待付款且 `canPay=true`：左侧「Bayar dalam {mm:ss}」倒计时（复用 `shopOrderCountdown`，`ShopCountdown` 组件，服务端 `paymentDeadline` 为准），右侧「Bayar sekarang」（复用 `orderPayNow`）小胶囊 → **直接拉起支付面板**（复用 7.2 story 抽出的 `features/shop/presentation/shop_payment_launcher.dart` `launchShopPayment(context, ref, order, {isRetry})`——列表上下文先 `ref.read(shopOrderDetailProvider(token).future)` 取详情再调），**不再先跳详情**（F1 说明；线上是跳详情）。面板关闭后刷新本页列表与 counts。
   - 待收货（SHIPPED / DELIVERED）：右侧「Konfirmasi terima」（复用 `shopOrderConfirmReceipt`）→ 直接确认收货（**无二次确认**，同 AC5.5）；`deliveryIssue=true` 时左侧加「Hubungi CS」（F2 新 key）→ 打开客服 WhatsApp（同详情页入口）。
   - 其余状态无按钮；点卡片任意处进 `/shop/orders/{token}`。
7. 空态（F3）：`EmptyState` 风格（`.empty`：图标块 72×72 圆角 24 `tint` 底 + `Icons.receipt_long` + 标题 18 / w600 `orderEmpty` + 副句 13 `orderEmptyHint`）；**只在「Belanja」一级下**再加主按钮「Mulai belanja」（UI 稿原文，§6 未列，见「待确认」5）→ `context.go('/shop')`。任一二级分组为空同样显示。
8. 加载失败（F18）：失败卡（`Icons.cloud_off` + `orderLoadFailed`「Gagal memuat pesanan」+「Coba lagi」`commonRetry`）→ 重试；**加载更多失败**也给「Coba lagi」（现 `loadMoreFailed` 状态存在但无 UI，补上）。修线上「失败只有一行字、没有重试」。
9. 宽限期（S-7）：`displayStatus=CANCELLED` 的待支付单出现在「Dibatalkan」组、卡上无「Bayar sekarang」（以后端分组与 `canPay` 为准，App 不按 `expiresAt` 自判）。

### AC3 — 订单详情页重写为 `shop_order_detail_page_v3.dart`（F4 / F6 / F7 / F9 / F10 / F11 / F12）`[L0 widget]` `[L2]`（FR-133.10、D-19、P-4）
1. 新文件 `features/shop/presentation/shop_order_detail_page_v3.dart`，`/shop/orders/:token` 路由改指新页；**同 story 删除 `shop_order_detail_page_v2.dart`**，其测试迁到 `test/shop/shop_order_detail_page_v3_test.dart`（保留仍成立的断言、按新规则改断言，见 Dev Notes「会被打破的测试」）。
2. 版式自上而下（UI F4 / F7）：**状态头卡** → 物流卡（有运单时）→ 商品卡（行 + 金额明细）→ 支付方式卡 → 收货地址卡 → 订单号与下单时间卡 → 客服卡（`shopOrderHelpTitle` / `shopOrderHelpBody` + `SupportWhatsAppButton`，原样保留）→ 底栏。**支付方式移到商品金额下面**（线上在最上，F4 说明「布局调整」）。
3. 状态头（PRD §3.0 表；线上详情任何地方都不显示状态名，本 story 新增）——标题 15 / w600 + 副句 13 `ink2`，按 `displayStatus`（缺失回落 `status`）：
   | 状态 | 标题 | 副句 / 附加 |
   |---|---|---|
   | 待付款（F4）| 倒计时块即状态头：`shopOrderCountdownTitle`「Selesaikan pembayaran dalam」/ 倒计时 / `shopOrderCountdownHint` | 底栏见 AC3.8 |
   | 待发货（F6）| `shopOrderStatusPendingShipment`「Menunggu dikirim」| F6 新句「Pesanan sedang disiapkan. Kami kabari saat sudah dikirim.」 |
   | 已发货（F7）| 「Sedang dikirim」（UI 稿原文，§6 未列，暂新增 `shopOrderShippedTitle`，见「待确认」5）| F7 新句「Pesanan sedang dalam perjalanan ke alamatmu.」 |
   | 已送达（F9）| F9 新 key「Paket sudah diterima」| F9 新句「Konfirmasi terima kalau semua barang sudah sesuai. Otomatis selesai dalam 7 hari.」 |
   | 配送异常（F10，`logistics.exception=DELIVERY_ISSUE`，覆盖已发货 / 已送达）| F2 新 key「Pengiriman bermasalah」| F10 新句 + 状态头内主按钮「Hubungi CS」（AC4.5） |
   | 已完成（F11）| `shopOrderStatusCompleted`「Selesai」| F11 新句「Terima kasih sudah belanja di TailTopia.」 |
   | 已取消（F12）| 见 AC3.4 | |
   | 退款中 / 已退款 | `orderStatusRefunding` / `orderStatusRefunded`（通用兜底；具体退款态 F13 / F16 / F19 / F25 由 **9.2** 覆盖本行）| — |
   | 部分取消（F17）| 原状态标题不变 | 副句改 F17 新句「1 produk dibatalkan karena stok habis. Produk lain tetap dikirim.」（见 AC6.1） |
   | 由原单重下的新单（F21）| 原状态标题（待发货）| 副句改 F21「Pesanan ini dibuat ulang dari pesanan {no}.」（AC6.3） |
   | 原单已重下（F20）| F20「Dibatalkan · sudah dipesan ulang」| F20 副句 +「Lihat pesanan baru」（AC6.2） |
4. 已取消（F12）状态头：标题 `shopOrderStatusCancelled`「Dibatalkan」；副句按 `cancelReason`：`PAYMENT_TIMEOUT` → F12「Waktu pembayaran habis, pesanan dibatalkan otomatis.」；`OUT_OF_STOCK` → 标题改 F13「Dibatalkan · stok habis」（副句与退款入口归 9.2）；其余原因（`USER_UNPAID_CANCEL` / 运单失败 / 揽收失败 / 快递原因 / 用户原因退回 / 丢件破损 / 迟到款）§6 无对应句 → **只显示标题不加副句**（见「待确认」4）；`cancelReason` 为 null（存量）→ 只显示标题。其下退回说明条（F12，`confirmation_number` 图标 + 13 号）：`couponReturn = RETURNED` **且** `paymentMethods.coinStatus = RETURNED` → 「PawCoin Rp {x} dan kupon sudah dikembalikan ke akunmu.」（F12 新 key，x = `paymentMethods.coinAmount`）；`couponReturn = EXPIRED`（UI：券已过期写「kupon sudah kedaluwarsa」不退）§6 无文案 → 不显示说明条（见「待确认」4）；其余不显示（PawCoin 退回仍由支付方式卡「Dikembalikan ke saldo」告知）。
5. 金额明细（商品卡下半，`.kv` 13 号 `ink2`、值 `ink` w500、减项 `mint6` w600；FR-130 计算顺序，**只展示服务端字段，App 不做任何加减**，P-1）：
   - 「Subtotal produk」`checkoutSubtotal` = `amounts.goodsAmount`（`amounts` 缺失时整块回落旧字段 `goodsSubtotal / shippingFee / shippingDiscount / totalAmount`）；
   - 「Diskon belanja」（§6 D1，复用 7.1 建的 key）= `−amounts.discountCouponAmount`，为 0 / null 不显示；
   - 「Ongkos kirim ({courierName})」= `amounts.shippingFee`（`amounts.courierName` 为空时只显示 `checkoutShippingFee`）；
   - 运费减免行 = `−amounts.shippingDiscount`，为 0 不显示；标签按 `amounts.shippingDiscountSource`：`COUPON` →「Kupon gratis ongkir」（§6 D1，复用 7.1 key）、`FREE_SHIPPING_RULE` →「Potongan otomatis」（§6 D12，复用 7.1 key）、null（存量）→ 线上 `checkoutFreeShipping`；
   - 「Asuransi pengiriman」（§6 D1，复用 7.1 key）= `amounts.insuranceFee`，为 0 / null 不显示；
   - 合计行（`.kv.big`：15 / w600，值 18 / w700）：待付款「Total bayar」（`checkoutPayable`）、已付款「Total dibayar」（见「待确认」5）、已取消 / 退款「Total pesanan」（`shopOrderCancelledTotalLabel`）= `amounts.payable`。
   - **金额明细不出现 PawCoin**（FR-131.3）；线上「合计行给现金段 + `+ PawCoin` 小字」的写法删除，改由支付方式卡单列。
6. 支付方式卡（「Metode pembayaran」§6 D1，复用 7.1 key）：PawCoin 行（`pets` 图标 + `checkoutPawcoin` + 状态小字 + 金额）、QRIS 行（`qr_code_2` + `checkoutQris` + 状态小字 + 金额）；金额取 `paymentMethods.coinAmount / cashAmount`（`paymentMethods` 缺失回落旧字段 `coinAmount` / `cashAmount`，状态按旧逻辑推）；为 null 的项不显示。状态小字**按服务端状态位**（不由 App 推断）：`coinStatus` HELD → `shopOrderCoinHeld`、PAID → `shopOrderPaidLabel`、RETURNED → `shopOrderCoinReturned`；`cashStatus` AWAITING →「Menunggu」、PAID → `shopOrderPaidLabel`、UNPAID →「Tidak dibayar」（后两个为 UI F4 / F12 原文，§6 未列，见「待确认」5）。
7. 物流卡（F7 / F9 / F10 / F11）——标题「Pengiriman」（`shopOrderShippingSection`）+ 右侧「Lacak di situs kurir ↗」（`shopOrderTrackOnCarrierSite`，有 `logistics.trackingUrl`〔缺失回落 `packages[0].trackingUrl`〕才显示，沿用 `_openCarrierSite`）；一行「{courierName} · {courierServiceName}」+ 运单号（等宽）+「Salin」（`shopOrderCopy`，复制并 toast `shopOrderCopied`，埋点沿用 `toko_order_tracking_copy_tapped`）；`logistics.provider=BITESHIP` 且无异常时下方 5 段状态（AC4）；`provider=MANUAL`（或 `logistics` 为 null 的存量单，降级读 `packages[0]`）**不显示 5 段**，只留快递名、运单号与「去承运商官网查询」。已完成（F11）**也显示物流卡**（改线上：线上已完成不显示）。待付款 / 待发货 / 已取消不显示物流卡。
8. 底栏：
   - 待付款且 `canPay=true`：左「Batalkan」（`shopOrderCancelShort`，次按钮）+ 右「Bayar Sekarang {Rp QRIS 部分}」（`shopOrderPayNow`，金额 = `paymentMethods.cash` / `cashAmount`）→ 7.2 的拉起支付面板逻辑原样搬入 v3（含 `payment-check`、`onAborted`、埋点 `toko_order_pay_tapped` 等全部保留）；`canPay=false`（宽限期 / 已无法支付）→ 不出底栏，状态头按 `displayStatus` 显示已取消（S-7）。
   - 已发货 / 已送达（含配送异常）：只有「Konfirmasi terima」（`shopOrderConfirmReceipt`）——无异常时主按钮实色；配送异常时用**次按钮**（主操作「Hubungi CS」在状态头内，F10 说明）。**线上底栏的「Lacak di situs kurir」去掉**（与物流卡重复，F7 说明）。
   - 其余状态无底栏（待发货 F6 用户不能自助取消，客服入口在页底）。9.2 会在退款待选 / 打款失败态加底栏按钮。
9. 订单号卡：「Nomor Pesanan」（`orderNumberLabel`）+ 单号（等宽，`displayNo` 为空回落 `orderToken`，沿用 `_displayedOrderNo`）+ **复制图标**（`Icons.content_copy` 16，新增，F4 说明「订单号可复制（线上不能）」，复制后 toast 复用 `commonCopied`「Tersalin」——现有 `shopOrderCopied` 文案是「Nomor resi disalin」（运单号）不能用）；「Dibuat pada」（`orderCreatedAtLabel`）+ 时间。
10. 取消订单（F5）：沿用 `showShopConfirm`（确认键墨色），标题 `shopOrderCancelConfirm`、正文 = `shopOrderCancelConfirmBody` + 空格 + F5 新句「PawCoin dan kupon akan dikembalikan.」（订单有币段或用了券才追加；否则只用原正文）、按钮 `shopOrderCancelConfirmYes` / `No`；成功 / 失败 toast 照线上（`shopOrderCancelled` / `shopOrderCancelFailed`）；成功后失效本页、`cartProvider`、`pawCoinProvider`、订单列表与 counts。
11. 下拉刷新、加载转圈、加载失败重试（`shopOrderLoadFailed` + `commonRetry`）沿用线上。倒计时归零只刷新一次（沿用 `_refreshedOnExpiry`）——刷新后由服务端 `displayStatus` 决定显示（S-7：60~65 分钟显示已取消）。

### AC4 — 物流 5 段状态与配送异常（F7 / F9 / F10）`[L0 widget]` `[L2]`（FR-133.10、FR-172.7、D-12、D-13）
1. 5 段竖排时间轴（UI `.tl .st`：节点圆点 12、连线 2px `line2`、段间距 12；已过 `done` 紫色、当前 `on` 紫色加粗、未到灰）：「Menunggu dijemput / Sudah dijemput / Dalam perjalanan / Sedang diantar / Diterima」（§6 F7；「Dalam perjalanan」复用 `shopOrderPackageInTransit`，其余 4 个新 key）。`statusKey`：`awaiting_pickup`=第 1 段、`picked`=第 2、`in_transit`=第 3、`out_for_delivery`=第 4、`delivered`=第 5（全亮，F9）；未知值 / null → 不画时间轴，只留快递与运单号。**第一版不做逐条轨迹、不显示时间戳**。
2. 删除线上人工时间线 `_timelineBlock` 与免责行 `shopOrderManualTrackingNotice`（「Status diperbarui manual…」）——接入 Biteship 后不再人工更新（F7 说明）。key 本身保留不删（见 Dev Notes）。
3. 配送异常（F10）：`logistics.exception == 'DELIVERY_ISSUE'` → 状态头如 AC3.3，物流卡**只留快递与运单号**（不画 5 段）；状态头内主按钮「Hubungi CS」（`chat` 图标）→ 与页底客服同一入口（`SupportWhatsAppButton` 同一 `onTap` / `openSupportWhatsApp(orderNo, screen: 'shop_order_detail')`）。揽收失败 / 物流停滞 / 未确认 → 后端不下发 `DELIVERY_ISSUE`，App 继续按 `statusKey` 显示最后物流状态（PRD：卡件期间不提示）。
4. 埋点（PRD §4）：配送异常状态头**首次渲染时**报一次 `toko_delivery_exception_shown{exception_type}`（同一页面实例只报一次，刷新不重复）；点「Hubungi CS」报 `toko_delivery_exception_cs_tapped{exception_type}`。`exception_type` 取 `logistics.exceptionType`（5.2 AC5 下发）：`RETURNED → 'returned'`、`LOST_DAMAGED → 'lost_damaged'`；缺字段 → `'unknown'`（见「待确认」1）。列表卡上的「Hubungi CS」同样报 `_cs_tapped`（不报 `_shown`）。
5. 确认收货（F7 / F9 / F10 / 列表卡）：**不弹二次确认**（PRD 11-05 拍板），点即调 `confirm-receipt`；成功 toast 复用 `shopOrderReceiptConfirmed`「Terima kasih, pesanan selesai」（PRD §6 无新句，见「待确认」6），失效详情 + 列表 + counts；失败 toast `shopOrderConfirmReceiptFailed`。按钮转圈期间不可重复点。埋点沿用 `toko_order_receipt_confirm_tapped` / `_succeeded`。任何物流异常下都可点（FR-172.7）。

### AC5 — 宽限期与「去支付」（S-7、D-11）`[L0]`
1. `displayStatus=CANCELLED` 而 `status=PENDING_PAYMENT`（宽限期）→ 详情按已取消显示（状态头「Dibatalkan」+ F12 超时副句）、无倒计时、无底栏；期间到账后用户下拉刷新看到「Menunggu dikirim」。**App 不自行按本地时钟改状态**。
2. 「去支付」只在 `canPay=true` 时出现（列表卡与详情底栏一致）；`canPay` 缺失（旧后端）→ 回落线上判据（待付款且 `expiresAt` 未过）。

### AC6 — 部分取消、重新下单（F17 / F20 / F21 / F22）`[L0 widget]` `[L2]`（FR-134 ②③、D-11、D-19）
1. 部分取消（F17）：`cancelledLines[].lineIndex` 命中的商品行名称下加红标签「Dibatalkan · stok habis」（`.tag.red`：11 / w600、`#C4263C` 字、`#FDE7EB` 底、圆角 4；文案取 F13 新 key「Dibatalkan · stok habis」，原因非缺货时只显示「Dibatalkan」`shopOrderStatusCancelled`），该行整体降权（不透明度 0.55）。金额合计下方加两行：「Dikembalikan ({n} produk dibatalkan)」（F17 新 key，n = `cancelledLines[].cancelledQty` 之和）+ 金额 = `refunds[]` 中 `scope=LINE` 的 `totalRefund` 之和；其下小字「PawCoin Rp {a} + QRIS Rp {b}」（F17 新 key，a / b = 同上 `coinRefund` / `cashRefund` 之和；§6 只给了两段句式，a = 0 时仍用该句式显示「PawCoin Rp 0」，不另造句）。**全部金额取服务端**（例：60.333 = 2.200 + 58.133，App 不分摊不取整；UI 稿写 60.332 / 58.132，差 1 盾的取整方向以架构 D-15 floor 为准）。状态头副句见 AC3.3。部分取消行的「选择退款方式」底栏按钮归 9.2。
2. 原订单已重新下单（F20）：`reorderedToOrderToken != null` → 状态头标题「Dibatalkan · sudah dipesan ulang」、副句「Pembayaranmu masuk setelah waktu habis. Kami sudah membuat pesanan baru untukmu.」、行内链接按钮「Lihat pesanan baru」（三句均 F20 新 key）→ `context.push('/shop/orders/{reorderedToOrderToken}')`。此时不显示 F12 退回说明条。
3. 新订单（F21）：`reorderedFromOrderNo != null` → 状态头副句「Pesanan ini dibuat ulang dari pesanan {no}.」（F21 新 key），单号 `{no}` 紫色可点 → 有 `reorderedFromOrderToken` 时 push 原单详情；没有该字段则单号不可点（只展示，见「待确认」3）。
4. 重新下单通知（F22，`SHOP_ORDER_REORDERED`，2.3 加类型、4.2 发送）：
   - 深链：`DeepLinkRoutes.pushPayloadToLocation` 加分支 `SHOP_ORDER_REORDERED` → `/shop/orders/{targetRef}`（targetRef = 新订单 token；空 → 通知中心兜底），放在「无 targetRef 落通知中心」短路**之后**的 id 寻址 switch 中（token 非数字，不做 `int.tryParse`，但要拒绝含 `/`、`?` 的值以防拼出非法路由 → 落通知中心）。
   - 通知中心文案：读后端写入的 title / body（后端已按收件人语言渲染整句，正文带单号，App 无从本地拼——沿用 `_refundTitle` / `_refundBody` 的「读后端 + 本地兜底」写法），任一为空回落本地 F22 新 key：标题「Pesananmu sudah dibuat ulang」、正文兜底「Pembayaranmu sudah kami terima. Pesanan sedang disiapkan.」（F22 句去掉 `{no}` 的兜底版，见「待确认」7）。图标 `Icons.local_shipping_rounded`、`AppColors.mint` / `violet100`。
   - 埋点类型映射 `_notifTypeForAnalytics`：`'SHOP_ORDER_REORDERED' => 'shop_order_reordered'`（显式登记）。
   - 推送点击：UI F22「点开进新订单 F21」、epics 10.1「点开落对页面」→ 在 `core/push/push_service.dart`:63-66 `pushClickLocation` 的直达白名单里加 `SHOP_ORDER_REORDERED`（先例 `GROWTH_LEVEL_UP`；其余通知类推送仍落通知中心，2026-08-07 产品口径不变）；深链为通知中心兜底时照旧落通知中心。9.3 会以同样方式加 `COUPON_EXPIRING`——两处改同一个函数，后做的 story 合并为一个 `Set` 判断。`push_service` 相关测试补一例。

### AC7 — 文案 `[L0]`（P-10、PRD §6 F 组；改 ARB 后跑 `flutter gen-l10n`，不手改生成产物）
EN / ID 两份 arb 同时加；新 key 前缀 `shopOrder*`。下表「复用」的引原 key，不新增。

| 屏 | 建议 key | 印尼语（`app_id.arb`）| 英语（`app_en.arb`）| 备注 |
|---|---|---|---|---|
| F1 | `shopOrderGroupUnpaid` | Belum bayar | Unpaid | 二级标签 |
| F1 | `shopOrderGroupPacking` | Dikemas | Packing | |
| F1 | `shopOrderGroupShipped` | Dikirim | Shipped | |
| F1 | — 复用 `orderFilterAll` / `shopOrderStatusCompleted` / `shopOrderStatusCancelled` | Semua / Selesai / Dibatalkan | | §6「已完成、已取消、全部复用现有词条」 |
| F1 | `shopOrderCouponEntry` `{n}`(int) | Kupon Saya · {n} bisa dipakai | My coupons · {n} available | |
| F1 | — 复用 `shopOrderCountdown` `{time}` | Bayar dalam {time} | | §6「剩 mm:ss 付款」与现有 key 同句 |
| F2 | `shopOrderDeliveryIssue` | Pengiriman bermasalah | Delivery problem | 列表状态 + F10 状态头标题 |
| F2 | `shopOrderContactCs` | Hubungi CS | Contact CS | |
| F6 | `shopOrderPendingShipmentHint` | Pesanan sedang disiapkan. Kami kabari saat sudah dikirim. | Your order is being prepared. We'll let you know when it ships. | 标题复用 `shopOrderStatusPendingShipment` |
| F7 | `shopOrderShippedHint` | Pesanan sedang dalam perjalanan ke alamatmu. | Your order is on its way. | |
| F7 | `shopOrderLogisticsAwaitingPickup` | Menunggu dijemput | Waiting for pickup | |
| F7 | `shopOrderLogisticsPicked` | Sudah dijemput | Picked up | |
| F7 | — 复用 `shopOrderPackageInTransit` | Dalam perjalanan | | §6 注明复用 |
| F7 | `shopOrderLogisticsOutForDelivery` | Sedang diantar | Out for delivery | |
| F7 | `shopOrderLogisticsDelivered` | Diterima | Delivered | |
| F9 | `shopOrderDeliveredTitle` | Paket sudah diterima | Package delivered | |
| F9 | `shopOrderDeliveredHintV3` | Konfirmasi terima kalau semua barang sudah sesuai. Otomatis selesai dalam 7 hari. | Confirm receipt once everything checks out. Completes automatically in 7 days. | 现有 `shopOrderDeliveredHint` 文案不同，不改它 |
| F10 | `shopOrderDeliveryIssueHint` | Silakan hubungi layanan pelanggan kami. Kami akan bantu menyelesaikan pesanan ini. | Please contact our customer service. We'll help sort out this order. | |
| F11 | `shopOrderCompletedThanks` | Terima kasih sudah belanja di TailTopia. | Thanks for shopping at TailTopia. | |
| F12 | `shopOrderTimeoutCancelledHint` | Waktu pembayaran habis, pesanan dibatalkan otomatis. | Payment time ran out, so the order was cancelled. | |
| F12 | `shopOrderCoinCouponReturned` `{amount}`(String) | PawCoin Rp {amount} dan kupon sudah dikembalikan ke akunmu. | PawCoin Rp {amount} and your coupon have been returned to your account. | amount 传 `formatIdr` 去掉 `Rp ` 前缀后的数字串，照现有 Rp 占位写法核对 |
| F5 | `shopOrderCancelRefundHint` | PawCoin dan kupon akan dikembalikan. | Your PawCoin and coupon will be returned. | 接在 `shopOrderCancelConfirmBody` 后 |
| F13 | `shopOrderCancelledOutOfStock` | Dibatalkan · stok habis | Cancelled · out of stock | 9.2 共用（9.2 不再新增） |
| F17 | `shopOrderPartialCancelHint` | 1 produk dibatalkan karena stok habis. Produk lain tetap dikirim. | 1 item was cancelled because it's out of stock. The rest will still ship. | §6 写死「1」；多件时见「待确认」8 |
| F17 | `shopOrderPartialRefundTotal` `{n}`(int) | Dikembalikan ({n} produk dibatalkan) | Refunded ({n} item cancelled) | |
| F17 | `shopOrderPartialRefundSplit` `{coin}` `{cash}`(String) | PawCoin Rp {coin} + QRIS Rp {cash} | PawCoin Rp {coin} + QRIS Rp {cash} | |
| F20 | `shopOrderReorderedTitle` | Dibatalkan · sudah dipesan ulang | Cancelled · reordered | |
| F20 | `shopOrderReorderedHint` | Pembayaranmu masuk setelah waktu habis. Kami sudah membuat pesanan baru untukmu. | Your payment arrived after the time limit, so we placed a new order for you. | |
| F20 | `shopOrderViewNewOrder` | Lihat pesanan baru | View new order | |
| F21 | `shopOrderReorderedFrom` `{no}`(String) | Pesanan ini dibuat ulang dari pesanan {no}. | This order was placed again from order {no}. | |
| F22 | `shopOrderReorderedNotifyTitle` | Pesananmu sudah dibuat ulang | Your order has been placed again | 通知中心兜底 |
| F22 | `shopOrderReorderedNotifyBody` `{no}`(String) | Pembayaranmu sudah kami terima. Pesanan {no} sedang disiapkan. | We've received your payment. Order {no} is being prepared. | 留给需要时用；兜底见下行 |
| F22 | `shopOrderReorderedNotifyBodyFallback` | Pembayaranmu sudah kami terima. Pesanan sedang disiapkan. | We've received your payment. Your order is being prepared. | ⚠️ §6 无此句，由 F22 去单号派生，见「待确认」7 |

**UI 稿有、PRD §6 未列**（按 cloud-rules「严格取 §6」暂按下表实现并在 Completion Notes「待确认」复述；印尼语取 UI 稿原文，英语为草拟）：`shopOrderMulaiBelanja`「Mulai belanja / Start shopping」（F3）、`shopOrderShippedTitle`「Sedang dikirim / On the way」（F7，或改复用 `shopOrderStatusShipped`）、`shopOrderTotalPaid`「Total dibayar / Total paid」（F6）、`shopOrderQrisWaiting2`「Menunggu / Waiting」（F4）、`shopOrderQrisUnpaid`「Tidak dibayar / Not paid」（F12）。

### AC8 — 还原度与回归 `[L0]` `[L2]`
1. widget 测试覆盖：F1（待付款卡 + 倒计时 + 去支付拉起面板回调）、F2（待收货卡 + 配送异常卡两按钮）、F3（空态 + Belanja 下「Mulai belanja」、其他一级无）、F18（失败卡 + 重试；加载更多失败重试）、二级标签只在 Belanja 出现 + 角标 0 不显示、F4 / F5 / F6 / F7 / F9 / F10 / F11 / F12 / F17 / F20 / F21 各一例、宽限期显示已取消、MANUAL 运单不画 5 段、确认收货无弹窗、金额明细逐行（用 UI 稿示例数：349.000 / −20.000 / 10.000 / −10.000 / 329.000；币 12.000 / QRIS 317.000；F17 用 D-15 推出的 60.333 = 2.200 + 58.133——UI 稿示例 60.332 / 58.132 取整方向与架构不同，App 只显示服务端值，测试用架构口径的数）且不出现 PawCoin 行、存量单新字段全 null 降级（只显示 subtotal / 运费 / 合计）。
2. 深链：`deep_link_routes_test.dart` 加 `SHOP_ORDER_REORDERED` 三例（正常 token、空、含非法字符）。通知中心：`SHOP_ORDER_REORDERED` 读后端 title / body、空时兜底。
3. **不写 golden**；还原度逐屏打分（≥95）在本地 Android 模拟器做，标 L2。`flutter analyze` 零问题、全量 `flutter test` 绿。

## Tasks / Subtasks

- [ ] **T0 开工核对**：分支 `feat/1.4.2-shop` 与 origin 同步；对照 6.2 / 5.1 / 5.2 / 4.1 / 4.2 / 2.3 / 7.1 / 7.2 **已提交代码**核对「接口契约」字段名、7.1 新建的金额明细 key 名（`Diskon belanja` / `Kupon gratis ongkir` / `Potongan otomatis` / `Asuransi pengiriman` / `Metode pembayaran`）、7.2 的「去支付」入口函数名、`/me/coupons` 路由是否已注册；有出入先改本文件（Completion Notes 记），不改前面 story 的对外契约
- [ ] **T1 数据层**（AC2、AC3）：`api_paths.dart` 加 `meShopOrderCounts`（`$meShopOrders/counts`）；`ShopOrderRepository` 加 `list({group, cursor})`、`counts()`；新 domain `ShopOrderSummary`（列表项）、`ShopOrderGroup` 枚举（6 值 ↔ API 小写串）、`ShopOrderCounts`；`ShopOrderDetail` 加 AC 契约字段（全部可空、缺键容错）+ `ShopLogistics`、`ShopCancelledLine`、`ShopOrderRefundSummary`（9.2 再扩）、`CancelReason` 枚举（未知值 → `unknown`）
- [ ] **T2 列表页**（AC2）：`OrderListController` 增 `shopGroup` 状态（只在 `filter == ecommerce` 时生效，切一级标签时重置为 `all`）与 shop 分支取数；新 `shopOrderCountsProvider`（autoDispose；**登记 `app.dart` `resetUserScopedCaches`**）；`_ShopGroupTabs`、`_CouponEntryBar`、`ShopOrderCard`；F3 / F18 / 加载更多失败
- [ ] **T3 详情页 v3**（AC3、AC4、AC5）：新建 `shop_order_detail_page_v3.dart`（状态头 / 物流卡 / 商品与金额明细 / 支付方式 / 地址 / 单号 / 客服 / 底栏）；从 v2 搬入 7.2 支付逻辑、取消、复制、打开承运商站点；删 v2 文件；路由改指 v3
- [ ] **T4 部分取消与重下单**（AC6）：F17 行标与合计、F20 / F21；深链 `SHOP_ORDER_REORDERED`；通知中心标题 / 正文 / 图标 / 埋点映射
- [ ] **T5 文案**（AC7）：两份 arb、`flutter gen-l10n`
- [ ] **T6 埋点**（AC4.4）：`toko_delivery_exception_shown` / `_cs_tapped` 字面量；`v112_events_test.dart` 不需扩后缀（`_shown` / `_tapped` 已在白名单）
- [ ] **T7 测试**（AC8，见 Dev Notes「测试」）；迁移 v2 详情测试
- [ ] **T8** `flutter analyze` + 全量 `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

- **订单列表** `petgo_app/lib/features/order/presentation/order_list_page_v2.dart`（425 行）：多业务共享壳；一级筛选 `_filters`（:44-49，null / vetConsult / pawcoinTopup / ecommerce）、`_FilterTabs`（:171-241，紫底白字样式，选中白下划线）；`_body`（:80-100）失败只显示 `orderLoadFailed` 一行字、无重试（:84）；`_loadMore`（:102-116）无失败态 UI（controller 已有 `loadMoreFailed` 字段，`order_list_controller.dart`:13-22、:87-89）。`OrderCardV2`（:257-425）对电商单只有「Bayar sekarang」且**跳详情不就地支付**（:403-424，注释写明原因）；状态用订单中心粗粒度 `statusCode`，分不出待发货 / 已发货（:395-402）。文件头注释（:15-23）明说「状态 Tab 未实现因为 `/orders` 不支持状态筛选」——本 story 用 6.2 的新接口兑现。
- **列表控制器** `order_list_controller.dart`：`OrderListController`（AsyncNotifier，:49-96）+ `orderListProvider`（:98）；`setFilter` 重置并重拉（:65-70）。`orderListProvider` 已登记 `app.dart`:417 换号重置。
- **订单详情** `features/shop/presentation/shop_order_detail_page_v2.dart`（984 行）：`_content`（:111-134）顺序为倒计时 → 物流 / 时间线 → **支付方式** → 商品 → 地址 → 单号 → 客服；`_countdownBlock`（:141-183，归零刷新一次 `_refreshedOnExpiry`）；`_fulfillmentBlock`（:187-262）；`_timelineBlock` + 免责行 `shopOrderManualTrackingNotice`（:268-293，本 story 删）；`_paymentBlock`（:347-420）；`_itemsBlock`（:431-547）合计行给**现金段 + `+ PawCoin` 小字**（:504-543，本 story 改为金额明细止于应付、币单列）；`_metaBlock`（:574-601，单号不可复制）；`_helpBlock`（:622-642，`SupportWhatsAppButton`）；`_bottomBar`（:646-716，已发货态有「Lacak」次按钮 :689-696，本 story 删）；`_pay`（:772-861，7.2 会改造为电商参数 + payment-check，v3 原样搬）；`_confirmReceipt`（:920-947）**有二次确认弹窗** `showShopConfirm`（:921-930，本 story 删）；`_cancel`（:949-982）。
- **详情模型** `features/shop/domain/shop_order_detail.dart`：`ShopOrderStatus` 8 值 + unknown（:9-59）；`ShopOrderPackage`（:97-133，`trackingUrl`）；`ShopOrderDetail.fromJson`（:279-）只认旧字段。**新字段全部要缺键容错**（存量订单与旧后端）。
- **仓库** `features/shop/data/shop_order_repository.dart`：`detail / pay / cancel / confirmReceipt`（:16-46）；`shopOrderDetailProvider`（autoDispose.family，:56-59）。`ApiPaths.meShopOrders`（`api_paths.dart`:506）。
- **路由** `core/router/app_router.dart`:707-713 `/shop/orders/:token` → `ShopOrderDetailPageV2`；`/me/orders`（:1088-1090）→ `OrderListPageV2`。`/shop/*` 不在 `_controlledLocations`（:172-179），详情页游客访问由后端 401 处理（现状不变）。
- **深链** `core/router/deep_link_routes.dart` `pushPayloadToLocation`（:69-200）：无 `SHOP_ORDER_REORDERED`；id 寻址 switch 在「targetRef 为空 → 通知中心」短路（:150）之后。**通知中心** `features/notify/presentation/notification_center_page.dart`：标题 `_typeLabel`（:980-1023）、正文 `_typeBody`（:1026-1083）、图标表（:811-966）、埋点 `_notifTypeForAnalytics`（:276-289）；读后端 + 兜底范式 `_refundTitle` / `_refundBody`（:1104-1112）。
- **arb 现值**（`lib/l10n/app_id.arb`）：`shopOrderStatusPendingPayment` Menunggu pembayaran（:2036）、`shopOrderStatusPendingShipment` Menunggu dikirim（:2037）、`shopOrderStatusCancelled` Dibatalkan（:2038）、`shopOrderStatusShipped` Dikirim（:2056）、`shopOrderStatusCompleted` Selesai（:2058）、`shopOrderPackageInTransit` Dalam perjalanan（:2068）、`shopOrderReceiptConfirmed` Terima kasih, pesanan selesai（:2075）、`shopOrderCountdown` Bayar dalam {time}（:2034）、`shopOrderCopied` Nomor resi disalin（:2063）、`orderStatusRefunding` / `orderStatusRefunded`（:1497-1498）、`orderCardMoreItems` dan {count} barang lain（:2154）、`shopOrderManualTrackingNotice`（:2199）、`orderPayNow` Bayar sekarang（:2155）、`orderTypeEcommerce` Belanja（:2150）、`checkoutPayable` Total bayar（:2018）、`shopOrderCancelledTotalLabel` Total pesanan（:2196）。

### 会被按设计打破的现有测试（改断言表达新规则，不删不 skip）

- `test/shop/shop_order_detail_page_v2_test.dart` → 迁为 `shop_order_detail_page_v3_test.dart`：
  - 「已支付 → 金额行给**现金段**…」（:216）、「D-4：待支付 → 金额行也给现金段」（:236）、「D-4 极端形态：全额抵扣 → Total due 为 0」（:291）、「已支付的纯币单仍显总额」（:312）、「纯币单不显示 Dibayar Rp 0」（:328）、`shopOrderPaidCoinSplitV2` 相关 → 新规则：合计行恒为 `totalAmount`（应付），币 / 现金只在支付方式卡；
  - 「已发货且有时间线 → 免责行必须在」（:434）、「无任何物流时刻 → 整块不渲染」（:445）→ 改为「免责行与人工时间线不再出现；BITESHIP 画 5 段、MANUAL 不画」；
  - 「已发货 → Lacak + Barang Diterima」（:515）→ 底栏只剩「Konfirmasi terima」，Lacak 在物流卡；
  - 「已发货 → On the way」/「已送达 → Terkirim」（:349 / :361）→ 状态头新标题（F7 / F9）；
  - 确认收货弹窗相关 key `shopOrderConfirmReceiptDialogV2` / `…YesV2` 的用例 → 改为「点击直接调接口、无弹窗」；
  - 「🔴 SD-5：任何状态都不出现退货入口」（:530）**保留**（退货入口仍关闭）；「已完成态底部条整条消失」（:551）保留；网关拒付 / 超时 / 用户取消 / 仅关闭面板（:648-743）随 7.2 的实现保留。
- `test/order/order_ecommerce_card_test.dart`「🔴 点击跳电商专用详情页」（:68）仍成立（「Semua」混排里 `OrderCardV2` 不变）；「Belanja」下改用 `ShopOrderCard`，其「去支付直接拉起面板」新写用例，不改 `OrderCardV2` 的旧用例。
- `test/order/order_card_v2_test.dart` 不受影响（`OrderCardV2` 不改）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 「Belanja」一级下改走 `/me/shop-orders`，「Semua」混排仍走 `/orders` 与 `OrderCardV2`——不把电商卡逻辑塞进通用卡（通用卡服务五类订单，粒度不同）。
2. F12 取消原因副句只用 PRD §6 有的两句（超时、缺货），其余原因只显示「Dibatalkan」，不自造文案。
3. `SHOP_ORDER_REORDERED` 通知中心读后端整句（正文含单号，App 拼不出），沿用送礼退回 `_refundTitle` 范式。
4. 确认收货 toast 复用现有 `shopOrderReceiptConfirmed`（UI F7 写的正是这句；PRD §6 未给新句）。
5. 退款相关状态头（F13 / F16 / F19 / F25）与「选择退款方式」按钮全部留给 9.2；本 story 对 REFUNDING / REFUNDED 只给通用标题，9.2 覆盖。

### 实现提示

- 详情页状态头建议写成纯函数 `ShopOrderHeader resolveHeader(ShopOrderDetail d)`（返回标题 key、副句 key / 参数、附加组件枚举），便于表驱动单测，也给 9.2 留扩展点（9.2 只在该函数前插退款分支）。
- 倒计时一律用现有 `ShopCountdown`（`widgets/shop_countdown.dart`，服务端到期时刻、切后台回来重算）；列表卡多个倒计时同时跑没问题（各自 Ticker）。
- 列表卡「去支付」与详情页共用一个拉起函数，避免两处行为分叉（关面板后刷新、埋点、`capturePurchase`）；共用函数要能在列表上下文拿到订单详情（先 `ref.read(shopOrderDetailProvider(token).future)` 取 `cashAmount` / `expiresAt` / `displayNo` 再拉面板）。
- 复制单号：`Clipboard.setData` 后 toast；单号等宽字体沿用 `ShopText.mono` 字体族（只沿用字体族，字号按新规范 13）。
- 客服 WhatsApp：`SupportWhatsAppButton(orderNo:, screen:)` 在 `features/support/presentation/support_whatsapp_button.dart`，状态头「Hubungi CS」与列表卡「Hubungi CS」复用其打开逻辑（若该组件只暴露按钮，抽出 `openSupportWhatsApp(...)` 函数共用，不改其埋点）。
- 宽限期判断只看 `displayStatus` 与 `canPay`，不要用 `expiresAt < now` 自判（S-7 口径以服务端为准）。

### 架构约束

- P-1：App 不算钱——金额明细、F17 拆分、合计全部是服务端字段；不做 `total - coin` 一类运算（线上 `_cashSegment`（:428-429）的回落减法仅保留给「去支付」按钮金额在 `cashAmount` 缺失的旧后端兜底，新字段在场时用 `paymentMethods.cash`）。
- P-4：新详情页 `*_page_v3.dart`，旧 v2 同 story 删除；列表页原地改 `order_list_page_v2.dart`（架构 §5.2 写明）。
- P-9：埋点字面量；P-10：文案严格 §6，复用标注的 key。
- 不引新依赖；不跑整文件 `dart format`。
- 换号重置：新增的用户维度 provider（counts、shop 列表状态）登记 `resetUserScopedCaches`，并在 `test/app/user_scoped_cache_registry_test.dart` 补登记断言。

### 与前后 story 的边界

- **7.2**：支付面板电商参数、「我已付款」、`canPay`、超时 / 出码失败态都在 7.2；本 story 只把 7.2 改好的详情页支付逻辑搬进 v3、把列表卡改成直接拉起同一面板。
- **9.2**：`refunds[]` 的去向选择、F13 / F14 / F15 / F16 / F19 / F23 / F25、底栏「Pilih cara pengembalian dana」、`/shop/orders/:token/refunds/:refundToken` 路由。本 story 在 F17 只展示退款金额，不出入口。
- **9.3**：`/me/coupons` 页与路由；本 story 只放入口。
- **9.4**：地址簿；无交集。
- **10.1**：真后端联调 F 组所有态（含宽限期、迟到款重下单、配送异常）。

### 测试（全部 L0，云端可跑）

- `test/order/order_list_shop_groups_test.dart`（新）：二级标签只在 Belanja、角标、counts 失败无角标、我的券入口（有 / 无计数、路由未注册不渲染）、F3 / F18、加载更多失败重试、宽限期单在 Dibatalkan 组无去支付。
- `test/order/shop_order_card_test.dart`（新）：F1 倒计时 + 去支付回调、F2 物流文案 / 配送异常两按钮、状态映射表驱动、确认收货无弹窗。
- `test/shop/shop_order_detail_page_v3_test.dart`（由 v2 迁来 + 新增）：状态头表驱动（含 F20 / F21 / F17 / 宽限期）、金额明细逐行与不含 PawCoin、支付方式卡、5 段物流 / MANUAL / 未知 statusKey、配送异常 + 埋点一次、取消确认正文追加句、单号复制、存量单降级。
- `test/shop/shop_order_header_test.dart`（新，纯函数）：`resolveHeader` 全状态表。
- `test/shop/shop_order_detail_model_test.dart`（新或并入现有模型测试）：新字段缺键容错、`CancelReason` 未知值。
- `test/notify/deep_link_routes_test.dart`（补）、`test/notify/shop_reorder_notification_test.dart`（新）、`test/notify/push_payload_test.dart`（补 `pushClickLocation` 对 `SHOP_ORDER_REORDERED` 直达一例）。
- `test/app/user_scoped_cache_registry_test.dart`（补登记）。

### Project Structure Notes

- 新：`lib/features/shop/presentation/shop_order_detail_page_v3.dart`、`lib/features/shop/presentation/widgets/shop_order_card.dart`（或放 `features/order/presentation/widgets/`，与 `order_card.dart` 同目录——选后者，列表页在 order 包）、`lib/features/shop/domain/shop_order_summary.dart`。
- 改：`order_list_page_v2.dart`、`order_list_controller.dart`、`shop_order_repository.dart`、`shop_order_detail.dart`、`api_paths.dart`、`app_router.dart`、`deep_link_routes.dart`、`notification_center_page.dart`、`app.dart`、两份 arb。
- 删：`shop_order_detail_page_v2.dart`（及其 import 处）。

### References

- PRD §3.0「订单状态在用户侧的显示」、§3.7「订单」、§3.8 ② 用户侧显示、§3.10 物流异常、§4、§6 F 组
- 架构 D-11（宽限期 S-7）、D-13、D-18、D-19、D-21、P-1、P-4、P-9 ~ P-12；§5.2
- 决策日志 S-7、I1、I4
- UI 稿 F1 ~ F12、F17、F18、F20 ~ F22 屏下说明；全局布局与「研发执行说明」
- epics Story 9.1

## 待确认 / 发现的规划冲突

1. **配送异常埋点只能报两值**：PRD §4 `exception_type` 要区分「退回 / 丢件 / 破损」，后端（D-13、5.2 AC5）只有 `RETURNED` / `LOST_DAMAGED` 两类，「丢件」「破损」合为 `lost_damaged`（5.2 story 已登记同一问题）。
2. **「我的券 · {n}」计数来源**：架构 D-6 / D-18 都没有可用券计数；2.2 story 已补 `GET /me/coupons/summary {availableCount}`（其「待确认」②），本 story 依赖它。若 2.2 实现时砍掉该接口，入口只显示「Kupon Saya」。
3. **F21「单号可点回原订单」缺原单 token**：D-19 只给 `reorderedFromOrderNo`（展示号），没有 token 无法跳转。建议 6.2 同时下发 `reorderedFromOrderToken`；未提供则单号只展示不可点。
4. **F12 取消原因副句 §6 只覆盖「超时」「缺货」两种**：UI 说明写「超时 / 你取消了 / 平台原因」三类，D-14 有九种原因。其余原因本 story 只显示「Dibatalkan」，需产品补文案（尤其 `USER_UNPAID_CANCEL`「你取消了」、运单 / 揽收失败、用户原因退回、迟到款）。另 UI F12 说明「券已过期写 kupon sudah kedaluwarsa 不退」——6.2 已下发 `couponReturn = EXPIRED`，但 §6 无文案，本 story 对 EXPIRED 不显示说明条，需产品补句。
5. **UI 稿上出现但 PRD §6 未列的文案**：「Mulai belanja」（F3）、「Sedang dikirim」（F7 状态头）、「Total dibayar」（F6）、「Menunggu」（F4 QRIS）、「Tidak dibayar」（F12 QRIS）。按 AC7 末段暂用 UI 原文 + 草拟英文，需产品确认；其中 F7 状态头若产品选择复用现有「Dikirim」则改为复用 `shopOrderStatusShipped`。
6. **确认收货 toast**：PRD FR-133「提示『已确认收货』」、UI F7「Terima kasih, pesanan selesai」、epics「用 PRD §6 定稿文案」——§6 并无此行。按 UI 与线上现有 key `shopOrderReceiptConfirmed` 实现（文案即 UI 所写）。
7. **F22 通知正文含单号**：通知中心沿用「读后端整句 + 本地兜底」，兜底句去掉单号（§6 无此变体）。若产品要求 App 本地拼含单号句，需后端在 `targetRef` 外另给单号字段。
8. **F17「1 produk dibatalkan…」§6 写死数字 1**：同单多行被取消时句子不准；本 story 原样实现（多数情况为 1 行），建议产品补复数句或改为 `{n}`。
9. **推送点击直达新订单改变了「通知类推送一律落通知中心」的口径**（2026-08-07 产品决策，`push_service.dart`:63-66）。按 UI F22 与 epics 10.1 给 `SHOP_ORDER_REORDERED` 开直达（与 `GROWTH_LEVEL_UP` 同例外），请产品确认。
10. **F19 归属重叠**：epics 9.1 范围写「F17–F22」（含 F19），9.2 也列 F19。本 story 把 F19 归 9.2（退款状态头整体由 9.2 负责），9.1 只给 REFUNDED 通用标题。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev）。
