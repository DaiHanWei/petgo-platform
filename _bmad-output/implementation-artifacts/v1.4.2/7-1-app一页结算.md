# Story 7.1: 一页结算（App，D1–D18）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支串行）
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.0 旅程 B / C、§3.2 FR-128、§3.4 FR-130、§3.5 FR-131、§3.10 FR-172、§4、§6 D 组）> `architecture-v1.4.2-shop-delta.md`（D-7、D-8、D-9、D-23、P-1、P-4、P-5、P-9、P-10、P-12）> UI 定稿 `电商/ui-1-4-2-shop-integrated-v1.html` D1–D18 每屏说明（数值照说明，**不从截图量像素**）；跨 story 契约以 `CROSS-STORY-DECISIONS.md` 为准；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**3.1**（`POST /me/checkout/preview`、地址 `serviceable`）、**3.2**（`POST /me/shop-orders` 新请求体与错误码、纯币提交即付款）、**1.2**（`PricingResult` 字段）、**2.1**（券可用性原因）。7.1 是 App 第一条 story。
> 范围层：**只 App**（`petgo_app/`）。支付面板改造归 7.2（本 story 提交后先调用**现有**面板）；首页 / 详情 / 购物车归 8.x；订单详情 v3 归 9.1；地址簿侧（H1–H5）归 9.4。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **S-10**：默认满减券**照 PRD 取抵扣额最大**（并列取先到期），即使个别情况下跌破免运门槛让应付变多；用户可手动换。须有一条用例钉住：商品 302,000、门槛 300,000、券减 5,000 → 默认用券、应付 317,000。
- **C-4**：应付为 0（满减 + 免运券抵光）→ 提交即 `markPaid(source=ZERO_PAY)`，不出码，同纯 PawCoin 路径（App 直接进支付成功页）。
- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。
- **C-2**：错误码一律连字符风格（`coupon-not-found`、`coupon-code-used`、`coupon-not-in-period`、`coupon-already-claimed`、`coupon-sold-out`、`coupon-redeem-locked`、`coupon-invalid`、`quote-expired`、`quote-failed`、`not-serviceable`、`sku-not-sellable`、`product-delisted`），登记进 `ErrorTypes`。

## Story

As a 买家,
I want 在一个页面完成地址、快递、券、PawCoin 的选择并提交,
so that 提交后马上就能付款。

## 接口契约（计划名，来自 3.1 / 3.2 / 1.2 / 2.1；**开工前对照前面 story 实际提交的 DTO 核对**，有出入先改本文件并在 Completion Notes 记一句，不改后端已提交的契约）

**试算** `POST /api/v1/me/checkout/preview`（只读；D-8）
- 请求：`{source: "CART"|"BUY_NOW", buyNow?: {skuToken, qty}, addressToken?, courierKey?, insurance: bool, discountCouponToken?, freeShippingCouponToken?, couponMode: "AUTO"|"MANUAL", useCoin: bool}`。
- 响应（字段照 D-8 / D-7）：`address`（与现 `GET /me/checkout` 同形：token / receiverName / receiverPhone / addressText …，无地址为 null）、`quoteToken`、`couriers[]`（**已按价格升序**：`courierKey`、`courierCode`、`courierServiceCode`、`courierName`、`serviceName`、`etaText`、`price`、`insuranceSupported`）、`selectedCourierKey`、`lines[]` / `unavailableLines[]`（沿用 `CheckoutLine` / `UnavailableLine`）、`pricing`（= `PricingResult`：`goodsAmount, discountCouponAmount, shippingFee, shippingDiscount, shippingDiscountSource (NONE|FREE_SHIPPING_RULE|COUPON), insuranceFee, payable, coinAmount, cashAmount, savedAmount, freeShippingProgress {threshold, remaining}`）、券分组（满减 / 免运各一组：可用 `[]` 与不可用 `[]`，每项 `userCouponToken, couponToken, kind, name, amount, minSpend, validUntil, expiringSoon, reason?`，不可用 `reason ∈ BELOW_MIN_SPEND|OUT_OF_SCOPE|EXPIRED|RULE_BETTER`）、本次选中的 `discountCouponToken` / `freeShippingCouponToken`、`coinBalance`、`coinCap`、`useCoin`、`insurance`、退货政策（`strictestReturnPolicy` 或 `returnPolicyCopyKey`）、`blockReason ∈ NO_ADDRESS|NOT_SERVICEABLE|QUOTE_FAILED|null`。
- 地址未开通 / 无快递 → `blockReason=NOT_SERVICEABLE`，金额位（运费 / 应付 / 币 / 现金）为 null；询价失败 → `QUOTE_FAILED`，同样为 null（**不回退固定运费**）。

**提交** `POST /api/v1/me/shop-orders`（Idempotency-Key 头沿用；D-8）
- 请求：`{source, buyNow?, addressToken, quoteToken, courierKey, insurance, discountCouponToken?, freeShippingCouponToken?, useCoin, agreeReturnPolicy: true}`；服务端重新计价。
- 成功：`ShopOrderRef`（`orderToken, status, totalAmount, payChannel, coinAmount, cashAmount`）。**现金段为 0 时服务端已在提交事务内 `markPaid`（S-3 / 3.2）**，`status` 已是 `PENDING_SHIPMENT`，App 不再调 `/pay`。
- 失败（P-5，App 按 ProblemDetail `typeSlug` 分支，不解析 detail）：409 `unavailable-lines`（带 `unavailableLines`）、422 `not-placeable`、409 `coupon-invalid`、409 `quote-expired`、422 `sku-not-sellable`（立即购买的 SKU 缺包裹参数）、其他。

**其他**：`GET /me/shipping-addresses` 每项新增 `serviceable: bool?`（3.1；null = 询价失败不标注）；`GET /shop/regions`（现有，D3 已开通城市块）；`POST /me/shop-orders/{token}/pay`（现有，7.2 前沿用）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — 路由、入口与 v2 替换 `[L0]`（P-4、epics 7.1 范围）
1. 新建 `A/features/shop/presentation/checkout_page_v3.dart`（`CheckoutPageV3({CheckoutSource source = CheckoutSource.cart, String? buyNowSkuToken, int buyNowQty = 1})`），**删除** `checkout_page_v2.dart`；`app_router.dart:703-704` 的 `/shop/checkout` 改指新页并解析 `?source=buy_now&sku=<skuToken>&qty=<n>`：无参数 / `source=cart` → CART；`source=buy_now` 且 `sku` 非空、`qty` 为正整数 → BUY_NOW；`buy_now` 但参数不合法 → 页面直接给 `checkoutLoadFailed` 失败态（不退化成 CART，免得把车里别的商品结掉）。
2. 旧入口照常进新页：购物车「Checkout」`context.push('/shop/checkout')`（`cart_page_v2.dart:278`）→ CART；旧详情「Beli Sekarang」现为「加购 `buyNow=true` → push `/shop/checkout`」（`product_detail_page_v2.dart:636-648`）→ 也按 CART 进新页，仍可下单（8.2 再改为直达 BUY_NOW）。
3. `initState` 上报 `toko_checkout_page_viewed{entry: "cart"|"buy_now"}`（取代现无属性的 `checkout_page_v2.dart:83`）。
4. BUY_NOW 全程**不读写购物车**：不调 `cartProvider` 任何变更方法，提交成功也不刷新购物车；从结算页返回详情页时**不保存任何选择**（地址 / 快递 / 券 / 币开关都是本页局部状态，页面销毁即丢）。

### AC2 — 数据层 `[L0]`（D-8）
1. `checkout_repository.dart`：`preview(CheckoutRequest)` 改为 `POST ApiPaths.meCheckoutPreview`（新常量 `'$base/me/checkout/preview'`）；`placeOrder(PlaceOrderRequest, {idempotencyKey})` 发 AC 契约的请求体。旧 `preview(String?)` / `GET meCheckout` 的 App 调用全部删除（后端旧端点保留给旧版 App，与本 App 无关）。
2. `CheckoutFailure.from` 改为按 `ProblemDetail.typeSlug` 分支（`core/network/problem_detail.dart` 已有 `typeSlug`），`CheckoutFailureKind` 扩为 `unavailableLines, notPlaceable, couponInvalid, quoteExpired, skuNotSellable, generic`；`unavailable-lines` 仍读 `unavailableLines` 行明细；`typeSlug` 缺失时回落到现有按状态码的判断（409 带行明细 → unavailableLines；422 → notPlaceable）。
3. 领域模型（`domain/checkout_preview.dart` 重写，保留 `CheckoutLine` / `UnavailableLine` / `ShopOrderRef`）：`CheckoutPreview`（新字段见契约）、`CourierOption`、`CheckoutPricing`、`FreeShippingProgress {int threshold, int remaining}`（放 `domain/free_shipping_progress.dart`，8.2 / 8.3 复用）、`CouponOption`、`CouponUnavailableReason`（未知值落 `unknown`，按「不可用、不显示原因」处理）、`CheckoutBlockReason`。金额一律 `int?`，缺键即 null。
4. 页面状态 `CheckoutSelection`（地址 token、`courierKey`、`insurance`、`couponMode`、两张券 token、`useCoin`）由页面持有；每次变更发一次试算，**只采用最后一次请求的结果**（自增序号丢弃过期响应）。
5. `ShippingAddress` 加 `final bool? serviceable`（`fromJson` 缺键为 null；`toJson` 不写出，免得 PUT 带上）。

### AC3 — D1 默认版式 `[L0 widget]` `[L2]`（PRD FR-128.1 / .2、UI D1）
自上而下（白卡片、卡间 12、页边 16、卡片圆角 16；正文 15 / 说明 13 用 `AppTypography`；**本页不再用 `ShopText` 小字号与 `ShopSection` 的 3px 灰缝**，见 Dev Notes「视觉」）：
1. **配送卡**：地址行（`location_on` + 「收件人 · 电话」+ 地址全文 + `›`，点开 D4）；快递行（`local_shipping` + 「{courierName} {serviceName} · {etaText}」+ 次行「Termurah」（所选即最便宜时）+ 右侧运费 + `›`，点开 D7）；运费保险行（`Asuransi pengiriman` + 「0,5% dari harga produk」+ 勾选后显示保价费 + 开关，**默认关**）。
2. **商品折叠行**：最多 3 个缩略图（同 SKU 多件显示「×n」角标）+「Produk ({n})」+ 商品金额（`pricing.goodsAmount`）+ `expand_more`；点击原地展开（AC9）。
3. **优惠卡**：顶部绿色 `savings`「Kamu hemat Rp {x} di pesanan ini」（`pricing.savedAmount`，为 0 时整行不画）；满减一行「Diskon belanja −Rp {discountCouponAmount}」+ `›`（点开 D10）；免运一行「Gratis ongkir −Rp {shippingDiscount}」+ `›`（点开 D11）。未选券写「Tidak dipakai」，该组无可用券写「Belum ada yang bisa dipakai」。免运进度见 AC8.5。
4. **支付方式卡**：「Metode pembayaran」；PawCoin 行（`pets` +「Pakai PawCoin」+「Saldo Rp {coinBalance}」+ 右侧本单币支付额 `pricing.coinAmount` 数字 + 开关，有余额默认开）；QRIS 行（`qr_code_2` +「Sisanya lewat QRIS」+「Bisa pakai e-wallet atau m-banking」+ 右侧 `pricing.cashAmount`）；用了 PawCoin 时其下显示不可提现提醒 `checkoutPawcoinNote`（复用，原文不改）。
5. **金额明细卡**「Rincian pembayaran」：按 FR-130 顺序逐行：`checkoutSubtotal`（商品金额）→「Diskon belanja −Rp X」（有满减时）→ `checkoutShippingFee`（所选快递运费）→ 运费减免一行（来源 COUPON 写「Kupon gratis ongkir −Rp X」，来源 FREE_SHIPPING_RULE 写「Potongan ongkir (belanja ≥ Rp {threshold})」，NONE 不画）→ 保价费（勾选时，标签「Asuransi pengiriman」）。**止于应付之前，不出现 PawCoin 行**（FR-128.1 / FR-131.3）；应付合计在底栏。
6. **开封不退同意勾选**：沿用现 `_agreementBlock`（`checkout_page_v2.dart:550-587`）的三种政策文案 key 与「默认已勾、取消则不可提交」规则（FR-104）。
7. **吸底栏**：左「Total bayar」（`checkoutPayable` 复用）+ 绿色「Hemat Rp {savedAmount}」（为 0 不画）+ 应付金额（`pricing.payable`，紫色、等宽数字）；右「Buat Pesanan」（`checkoutSubmit` 复用，高 48、圆角 12）；其下一行：混合支付「Sisa bayar lewat QRIS: Rp {cashAmount}」，币足额「Dibayar penuh dengan PawCoin」，全额 QRIS 不画（D15）。
8. 所有金额只展示服务端 `pricing`，**App 不做任何加减乘除**（P-1）；价格与金额用等宽数字（`FontFeature.tabularFigures()`）。

### AC4 — 地址：D2 / D3 / D4 / D5 `[L0 widget]` `[L2]`（FR-128.2 / .3 / .4、UI D2–D5）
1. **默认地址**：每次进入页面都按地址簿默认地址试算（取 `addressListProvider` 中 `isDefault`，没有默认取第一条）；页内换的地址只对本单，**不写回默认**。
2. **D2 无地址**：地址区 `add_location_alt`「Tambah alamat pengiriman」（`checkoutAddAddress` 复用）点击**直接打开 D5**（不经选择抽屉）；快递区「Isi alamat dulu untuk melihat pilihan kurir」；不询价（请求不带 `addressToken`）；运费、应付、QRIS 金额位显示「—」（不写 Rp 0）；支付方式置灰；提交不可点。
3. **D3 不支持配送**（`blockReason=NOT_SERVICEABLE`）：地址块内警示：`error` 图标 + 标题「Wilayah ini belum bisa dikirim kurir」+ 正文 `checkoutOutOfRangeBody`（复用）+ 按钮「Pilih alamat lain」（`checkoutPickAnotherAddress` 复用）**打开 D4 抽屉**（修线上缺陷：线上跳地址管理页、选了不回填，`checkout_page_v2.dart:237-239`）；快递区「Tidak ada kurir untuk alamat ini」；金额位「—」；页底「Wilayah yang sudah kami layani」城市 chip 块沿用 `_serviceAreaBlock`（`:307-348`，取 `/shop/regions`，取不到整块不画，`checkoutMoreAreas` 复用）；上报 `toko_checkout_out_of_range_shown`（沿用 `:158`，每次进入页面只报一次）；提交不可点。
4. **D4 地址选择抽屉**（取代 `context.push('/me/addresses?select=1')`，`:97-108`）：标题「Pilih alamat」+ 关闭；列出地址簿全部地址（「收件人 · 电话」+ 地址全文），默认地址标「Utama」（`addressDefaultBadge` 复用），`serviceable == false` 的标「Belum dilayani」（复用 `addressNotServiceable`，原文小写，按 Dev Notes 首字母大写渲染）且**仍可选**（选了进 D3）；当前选中描边；底部「+ Tambah alamat baru」→ D5。选中即关抽屉并重新试算。编辑 / 删除不在此处。
5. **D5 页内新增**：近全屏抽屉，标题 `addressAdd`「Tambah alamat」+ 关闭；表单与地址簿**同一组件**（见 AC4.6）；主按钮「Simpan & pakai」；保存成功 → 存入地址簿（`addressRepositoryProvider.create`）→ `ref.invalidate(addressListProvider)` → 本单选中新地址 → 重新试算（D6）。字段与校验照 `address_form_page_v2.dart`。
6. **抽表单组件**：从 `address_form_page_v2.dart`（`_form` :122、`_save` :519-573）抽出 `A/features/shop/address/presentation/address_form_body.dart`（`AddressFormBody({ShippingAddress? initial, required String submitLabel, required ValueChanged<ShippingAddress> onSaved})`，内含字段、校验、地区三级选择、默认开关、保存请求），`AddressFormPageV2` 改为包一层 `Scaffold` + `AddressFormBody`（保存后照旧 `context.pop()`），**地址簿页面行为与现有 `test/shop/address/*` 测试全部不变**。H3 的视觉改动归 9.4。

### AC5 — 快递与保价：D6 / D7 / D8 / D9 `[L0 widget]` `[L2]`（FR-172.2 / .3 / .4、UI D6–D9）
1. **D6 询价中**：首次进入、换地址、页内新增地址、D16 移除商品后的试算期间，快递区「Menghitung ongkir…」，运费与应付「—」，提交不可点；换快递 / 保价 / 券 / 币开关的重算期间**保留上一次金额**、只禁用提交按钮（服务端 30 分钟复用报价，不必闪骨架）。
2. **D7 快递抽屉**：标题「Pilih kurir」+ 关闭；列表按响应顺序（已升序）：「{courierName} · {serviceName}」+「Estimasi {etaText} hari」+ 运费；首项标「Termurah」；`insuranceSupported=false` 标「Tanpa asuransi」；当前选中描边；底部说明「Potongan ongkir maksimal sebesar ongkir termurah. Kalau pilih kurir lebih mahal, selisihnya kamu bayar.」。选中即收起、带 `courierKey` 重算。
3. **换地址时快递保留**：请求带当前 `courierKey`，以响应 `selectedCourierKey` 为准（仍可选则保留，否则服务端选最便宜，FR-128.3）。
4. **D8 询价失败**（`blockReason=QUOTE_FAILED`）：快递区红色「Ongkir belum bisa dimuat.」+「Coba lagi」（`commonRetry` 复用）点击重新试算；金额位「—」；不可提交。
5. **D9 不支持保价**：所选快递 `insuranceSupported=false` → 配送卡**不画保价行**，请求 `insurance=false`；快递行次行改为「Tanpa asuransi」。示例：运费 24.000，免运券封顶按最低价 10.000 → 应付 343.000（数值全来自服务端）。

### AC6 — 券：D10 / D11 / D12 `[L0 widget]` `[L2]`（FR-130.3、UI D10–D12）
1. 首次试算 `couponMode=AUTO`（服务端默认最优组合）；用户在抽屉里任一操作后改 `MANUAL`，并把两张券的当前选择（取自上一次响应）一起带上。
2. **D10 满减券抽屉**：标题「Kupon diskon belanja」+ 关闭；「Bisa dipakai ({n})」组（服务端顺序 = 抵扣额从大到小）、单选、默认选中当前券；「Tidak pakai kupon」单选项；「Belum bisa dipakai ({n})」组置灰、不可选、每张标原因（`BELOW_MIN_SPEND`「Belum capai minimum belanja」/ `OUT_OF_SCOPE`「Produk tidak termasuk」/ `EXPIRED`「Kedaluwarsa」）；底部主按钮「Pakai」确认后关闭并重算。被其他待付款订单占用的券服务端不下发（App 不做过滤）。
3. **D11 免运券抽屉**：同 D10，标题「Kupon gratis ongkir」；`OUT_OF_SCOPE` 文案换「Ada produk di luar cakupan kupon」；`RULE_BETTER`「Potongan otomatis lebih hemat」。
4. **券面组件**新建 `A/features/shop/coupon/presentation/coupon_card.dart`（8.1 / 8.2 / 9.3 复用）：满减券面额 `formatIdr(amount)` + 门槛「min. belanja Rp {min}」；免运券「Gratis ongkir」+ 封顶额；名称；有效期（文案见「待确认」第 4 条）；`expiringSoon=true` 标「Segera berakhir」；右侧单选（抽屉模式）或按钮位（领券模式，供 8.x 传入）。
5. **D12 用满额免运**（`shippingDiscountSource=FREE_SHIPPING_RULE`）：优惠卡免运行写「Potongan otomatis −Rp {shippingDiscount}」，其下小字「Belanja ≥ Rp {threshold}, lebih hemat dari kupon」（两行，不挤一行）；金额明细写「Potongan ongkir (belanja ≥ Rp {threshold})」；`threshold` 取 `pricing.freeShippingProgress.threshold`。

### AC7 — PawCoin：D13 / D14 / D15 `[L0 widget]` `[L2]`（FR-131、UI D13–D15）
1. 首次试算 `useCoin=true`；`coinBalance == 0` 时开关关闭且不可点、QRIS 行写全额。用户关掉后**本页内保持关闭**（后续任何重算都带 `useCoin=false`），不写任何持久化（不跨订单记忆）。
2. **D13 超上限**：`coinBalance > coinCap` 且开关开 → 支付方式卡内 `info` 提醒「Maksimal {coinCap} PawCoin per pesanan. Pemakaian di atas batas ini bisa dianggap pencairan dana.」（改 `checkoutCoinCapped` 原值，数字取响应 `coinCap` 用 `formatIdrAmount`，**不写死 1.000.000**）。
3. **D14 足额**（`cashAmount == 0` 且 `coinAmount > 0`）：不画 QRIS 行；支付方式卡写「Seluruh pesanan dibayar dengan PawCoin, tanpa QRIS.」+ `checkoutPawcoinNote`；底栏次行「Dibayar penuh dengan PawCoin」。
4. **D15 不用**：开关关 → QRIS 行标题「Bayar penuh lewat QRIS」、金额 = 应付；不画 `checkoutPawcoinNote`；底栏无次行。

### AC8 — 其余展示规则 `[L0 widget]`（FR-127.3、FR-128.4）
1. 提交按钮可点 = 有地址 ∧ `blockReason == null` ∧ 无在途试算 ∧ `lines` 非空 ∧ 同意勾选 ∧ 未在提交中。
2. 金额位为 null 时一律显示「—」，**绝不显示 Rp 0**（沿用 FR-99 规则，见 `checkout_page_v2.dart:16-19` 注释）。
3. 首屏试算失败（网络 / 5xx）→ `ShopRetryState(checkoutLoadFailed, commonRetry)`（沿用 `:714-718`）。
4. toast 位置：页面态在底栏上方（`showAppToast(..., extraBottom: 底栏高度)`）；底部抽屉打开时 `top: true`（UI 全局规范）。
5. **免运进度**：`freeShippingProgress.threshold > 0 && remaining > 0` → 优惠卡底部一行 `local_shipping`「Belanja Rp {remaining} lagi untuk potongan ongkir」；`threshold` 为 0 / null 不画；达标不另画（免运行已显示减免）。（UI D 组未画此行、PRD FR-127.3 要求，见「待确认」第 2 条。）

### AC9 — D18 商品展开 `[L0 widget]`
1. 折叠行点击 → 原地展开：头部「Produk ({n})」+ 商品金额 + `expand_less`；每件一行（72 缩略图、名称、规格、单价、`×qty`）；再点收起；不跳页。`unavailableLines` 不在此列。

### AC10 — 提交与失败：D16 / D17 及其余 `[L0 widget]`（FR-128.6、P-5）
1. 点提交：上报 `toko_checkout_submit_tapped`（沿用）→ `placeOrder`（带当前 `quoteToken`、`courierKey`、`insurance`、两张券 token、`useCoin`、`agreeReturnPolicy: true`、`source`、`buyNow`）。提交中按钮转圈，不置灰（沿用 `ShopButton.loading`）。
2. **D16 `unavailable-lines`**：`showShopConfirm` 居中弹窗（沿用 `_showUnavailable`，`:673-711`）：标题 `checkoutUnavailableTitle`、逐行「{商品名} · Sisa X, diminta Y」/「Sudah ditarik」、按钮「Batal」/「Hapus & lanjut」（确认键墨色）。CART：确认后逐行 `cartProvider.remove` → 停在本页重新试算，地址 / 快递 / 券 / 币选择保留。**BUY_NOW**：弹窗只给「Batal」（无可移除的车行），关闭后 `context.pop()` 回详情页（见「待确认」第 5 条）。
3. **D17 `coupon-invalid`**：toast「Kupon sudah tidak berlaku. Kami pilihkan kupon lain.」→ `couponMode` 重置为 AUTO 重新试算 → **停在本页**，不自动再提交。
4. **`quote-expired`**：不弹提示，静默重新试算（保留其余选择），停在本页等用户再提交。
5. **`not-placeable` / `sku-not-sellable`**：toast `checkoutNotPlaceable`（复用）；**其他**：toast `checkoutGenericError`（复用）。价格变动不提示（维持线上）。
6. 单已落库后的任何后续失败都不能把页面退回「可再次提交」（沿用 `:631-635` 注释的规则：CART 的购物车刷新不 await、错误吞掉）。

### AC11 — 提交成功分流与 E6 支付成功页 `[L0 widget]`（FR-128.7、旅程 C 4 / 7、S-3）
1. 成功后：`ref.invalidate(pawCoinProvider)`（**币在提交时已扣**，S-3）；CART 时 `unawaited(cartProvider.refresh())`；不再弹 `checkoutOrderPlaced` toast。
2. **纯币**（`ShopOrderRef.cashAmount == 0` 或 `status != PENDING_PAYMENT`）：上报 `toko_order_payment_succeeded{pay_channel: "PAWCOIN", attribution_source}`（属性与 `shop_order_detail_page_v2.dart:784-785` 纯币分支同形；`attribution_source` 取不到时整键不放）→ `growthPopupTrigger.afterAction()` → `context.pushReplacement('/shop/orders/{token}/paid')`（E6）。**不调 `/pay`。**
3. **需扫码**：`context.pushReplacement('/shop/orders/{token}?autopay=1')`：`ShopOrderDetailPageV2` 加 `autoPay` 入参，首帧后自动执行一次现有 `_pay`（`:772` 起，现有面板、现有轮询与四态分派原样）——视觉上即「结算页提交 → 订单详情上直接弹出支付面板」（UI E1 背景即订单详情）；关掉面板 = 留在待付款详情、倒计时继续（FR-128.7）。路由 `/shop/orders/:token`（`app_router.dart:708-712`）读 `autopay=1`。
4. **E6 支付成功页**（本 story 落地，纯币路径需要；7.2 把 QRIS 到账也接到这里）：新建 `presentation/shop_pay_success_page.dart`，路由 `/shop/orders/:token/paid`。内容：绿色 `check` 圆 +「Pembayaran berhasil」（`shopOrderPaid` 复用）+ 应付金额（`totalAmount`）+ 混合支付时一行「PawCoin Rp {a} + QRIS Rp {b}」（纯币 / 纯 QRIS 不画）+「Pesanan kamu sedang disiapkan untuk dikirim.」；按钮「Lihat pesanan」→ `pushReplacement('/shop/orders/{token}')`、「Lanjut belanja」→ `context.go('/shop')`（`/shop` 必须 `go`，`deep_link_routes.dart:17-24`）；**系统返回键等同「Lihat pesanan」**（`PopScope`）。金额取 `shopOrderDetailProvider(token)`，加载中骨架、失败只显示标题与两个按钮（不编数字）。

### AC12 — 埋点 `[L0]`（PRD §4、D-23、P-9）
1. `toko_checkout_page_viewed{entry}`（AC1.3）。
2. `toko_order_submitted` 在原属性（`item_count`、`items[{sku_id, qty, entry_source, trigger_type}]`，`:640-651`）上**追加**：`coupon_ids`（本单实际使用的券**模板** `couponToken` 列表，无则空列表）、`coin_used`（bool：`pricing.coinAmount > 0`）、`courier`（`"{courierCode}_{courierServiceCode}"`，小写）、`shipping_fee`（所选快递运费 int）、`insurance`（bool）。不放订单号、地址、手机号、金额以外的自由文本。
3. 事件名一律字面量 `Analytics.capture('…')`；`v112_events_test.dart` 与 `epic3_analytics_test.dart` 保持绿（本 story 无新事件名，不需扩前缀 / 词尾表）。

### AC13 — 文案 `[L0]`（PRD §6 D 组 + E6、P-10）
1. 按 Dev Notes「文案表」在 `app_id.arb` / `app_en.arb` 两份同时加 / 改，跑 `flutter gen-l10n`，不手改生成产物；标「复用」的直接引原 key、原文不改。
2. 源码不得硬编码任何用户可见字符串（金额格式化走 `formatIdr` / `formatIdrAmount`）。`test/l10n/microcopy_rules_test.dart` 绿（en / id key 对齐、EN 值不得与 ID 同值，除同形词）。

### AC14 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告；`flutter test` 全绿。按设计打破的旧断言**迁到 v3 测试并改写为新规则**（不删、不 skip），映射见 Dev Notes「测试」。
2. **还原度 ≥ 95%**（L2，本地）：Android 模拟器逐屏对照 D1–D18 打分，记入 Completion Notes；golden 截图不写（云端与本机字体渲染不同）。

## Tasks / Subtasks

- [ ] **T0 开工核对**（cloud-rules 第 0 步 + 「story 文件是提前写的」条）：对照 3.1 / 3.2 / 1.2 / 2.1 已提交代码核对「接口契约」字段名与 ProblemDetail `type`；有出入先改本文件
- [ ] **T1 数据层**（AC2）：`api_paths.dart` 加 `meCheckoutPreview`；`checkout_repository.dart` 重写 preview / placeOrder / `CheckoutFailure`；`domain/checkout_preview.dart` 新模型；`domain/free_shipping_progress.dart`；`ShippingAddress.serviceable`
- [ ] **T2 地址表单抽组件**（AC4.6）：`address_form_body.dart`；`AddressFormPageV2` 改为包装；跑 `test/shop/address/*` 确认不变
- [ ] **T3 页面骨架**（AC1、AC3、AC8、AC9）：`checkout_page_v3.dart`（配送卡 / 商品折叠 / 优惠卡 / 支付方式 / 金额明细 / 同意 / 底栏）；删 v2；改路由
- [ ] **T4 抽屉**（AC4.4-4.5、AC5.2、AC6）：地址抽屉、新增地址抽屉、快递抽屉、券抽屉；`coupon/presentation/coupon_card.dart`
- [ ] **T5 提交与分流**（AC10、AC11）：失败分派；`ShopOrderDetailPageV2(autoPay:)` + 路由 `autopay=1`；`shop_pay_success_page.dart` + 路由 `/shop/orders/:token/paid`
- [ ] **T6 埋点与文案**（AC12、AC13）：arb 两份 + `flutter gen-l10n`
- [ ] **T7 测试**（见「测试」）+ `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，核 `dev_1.4.2` @ 2c507755）

路径前缀 `A/` = `petgo_app/lib/`。

| 位置 | 现状 | 本 story 处置 |
|---|---|---|
| `A/features/shop/presentation/checkout_page_v2.dart`（719 行） | 文件头 5 条硬规则（:1-33），其中第 5 条「不含优惠券」与第 2 条「两段金额以扣减行出现在明细」被 PRD 推翻；`_pickAddress` 跳整页选择器 `/me/addresses?select=1`（:97-108）；`_paymentBlock` :413、`_amountBlock` :505（PawCoin 作为「− Rp」行进明细 :525）、`_agreementBlock` :550、`_bottomBar` :589；`_submit` :626-671（埋点 :640、成功 `pushReplacement('/shop/orders/…')` :655）；`_showUnavailable` :673-711 | 整文件删除；规则 1（不自算）、3（截断说明）、4（开封不退同源 key）、FR-99（不显示 Rp 0）、单已落库不放开按钮的写法**迁到 v3 照搬** |
| `A/features/shop/data/checkout_repository.dart` | `preview` 为 `GET meCheckout?addressToken=`（:18-25）；`placeOrder` 只发 `addressToken/entrySource/triggerType`（:41-60）；`CheckoutFailure.from` 只看状态码（:73-93）；`checkoutPreviewProvider` 按地址 family（:101-104） | 重写（AC2）；provider 改为页面内直接调用 repository（参数多、需要丢弃过期响应，family 不合适） |
| `A/features/shop/domain/checkout_preview.dart` | `CheckoutPreview`（:70-178，`canSubmit` :135）、`UnavailableLine` :180、`ShopOrderRef` :212 | 重写 `CheckoutPreview`，保留后两者 |
| `A/core/router/app_router.dart` | `/shop/checkout` → `CheckoutPageV2()`（:703-704）；`/shop/orders/:token`（:708-712）；import :31 | 改指 v3 + query；订单详情加 `autopay`；新增 `/shop/orders/:token/paid` |
| `A/features/shop/presentation/shop_order_detail_page_v2.dart` | `_pay` :772-859（`/pay` → 纯币当场结清分支 :781-791 → `showQrPaymentSheet` :805）；`_bottomBar` :646 | 只加 `autoPay` 入参与首帧触发；9.1 换 v3 时须保留同等能力 |
| `A/features/shop/address/presentation/address_form_page_v2.dart`（599 行） | `_form` :122、`_serviceHint` :331、`_save` :519-573（`pop` :561） | 抽 `AddressFormBody`（AC4.6） |
| `A/features/shop/address/domain/shipping_address.dart` | 无 `serviceable` | 加可空字段 |
| `A/features/shop/presentation/product_detail_page_v2.dart` | 「Beli Sekarang」= 加购 `buyNow:true` 后 push `/shop/checkout`（:618-648） | 本 story 不改（8.2 改为直达 BUY_NOW） |
| arb | `checkoutCoinCapped` = 「Maksimal {amount} PawCoin per pesanan」；`checkoutServiceAreaTitle` = 「Dikirim ke wilayah ini?」；`checkoutFreeShipping` EN = 「Free shipping discount」（与 PRD EN 不同，故新建 key） | 见文案表 |

### UI 逐屏要点（D1–D18，摘自每屏说明）

| 屏 | 要点 |
|---|---|
| D1 默认 | 11-05 定新版式（原提案 D18，D2–D17 统一换此版式）：① 配送卡 = 地址 + 快递（只显已选一家，点开 D7）+ 运费保险（开关，默认关）；② 商品折叠一行缩略图 + 件数 + 商品金额；③ 优惠卡顶部绿色「这单省了」、满减一行、免运一行、默认最优可换；④ 支付方式「使用 PawCoin」开关（有余额默认开）+ 剩余 QRIS 金额 + 不可提现提醒；⑤ 金额明细按 FR-130 顺序到运费减免（合计在底栏，PawCoin 不进明细）；⑥ 开封不退勾选（默认勾）；⑦ 底栏「应付 · 已省 Rp X」（绿）+ 应付 +「提交订单」，混合时下一行「还需用 QRIS 支付 Rp X」，足额「全部用 PawCoin 支付」。示例 349.000 − 20.000 + 10.000 − 10.000 = 329.000；币 12.000 + QRIS 317.000 |
| D2 无地址 | 「Tambah alamat pengiriman」点开**直接进 D5**；快递区不询价；运费与应付「—」；支付方式置灰；提交不可用 |
| D3 不支持配送 | 地址块内警示（标题改 FR-127 统一文案，正文沿用 `checkoutOutOfRangeBody`）+「Pilih alamat lain」**打开 D4**（修线上：跳地址管理页不回填）；页底已开通城市 chip 块；运费位不写 Rp 0 |
| D4 地址抽屉 | 页内完成（取代整页选择）；列出全部地址，默认标「Utama」，不可配送标「Belum dilayani」仍可选（选了进 D3）；当前选中描边；只对本单、不改默认；「Tambah alamat baru」→ D5；不做编辑 / 删除 |
| D5 新增地址 | 与地址簿 H3 同一套表单，近全屏抽屉；保存存地址簿并回填本单 → 重新询价 D6；第一个地址自动设为默认（服务端现有规则，App 不另做）；标签与默认开关收到底部（以 H3 为准） |
| D6 询价中 | 进页或换地址 / 改商品后询价期间，快递区「Menghitung ongkir…」，运费与应付「—」，提交不可用 |
| D7 快递抽屉 | 按运费从低到高，每项名称、服务类型、预计时效、运费；最便宜标「Termurah」默认选中；不支持保价标「Tanpa asuransi」；底部说明免运减免封顶按最低运费；选完收起重算 |
| D8 询价失败 | 快递区红色提示 +「Coba lagi」，不可提交，不退回固定运费 |
| D9 不支持保价 | 不显示保价行；示例同时演示选贵快递：运费 24.000，免运券减免封顶最低运费 10.000，应付 343.000 |
| D10 满减券 | 可用券按抵扣额从大到小、默认选中最优、可「Tidak pakai kupon」；不可用一组标原因；被其他待付款订单占用的券不出现 |
| D11 免运券 | 同 D10；特有原因「Ada produk di luar cakupan kupon」「Potongan otomatis lebih hemat」 |
| D12 满额免运 | 优惠区免运行「Potongan otomatis −Rp X」+ 下一行小字「Belanja ≥ Rp X, lebih hemat dari kupon」；明细「Potongan ongkir (belanja ≥ Rp X)」；未采用的免运券不扣；相同额用满额免运 |
| D13 超上限 | 本单最多用上限，其余 QRIS；支付方式区提醒，数字读后台配置 |
| D14 足额 | 不出 QRIS 行；底栏「Dibayar penuh dengan PawCoin」；提交后不出码直接 E6 |
| D15 不用 | 可取消勾选；本次结算内保持；全额 QRIS；底栏无拆分小字；不可提现提醒不显示 |
| D16 部分商品不能买 | `showShopConfirm` 居中弹窗，逐行「Sisa X, diminta Y / Sudah ditarik」，「Batal」/「Hapus & lanjut」（确认键墨色），移除后停在本页重算；其余两种失败照线上 toast |
| D17 券已失效 | toast（底栏上方；抽屉开着时在顶部）+ 按最优组合重选重算，停在本页等用户再提交 |
| D18 商品展开 | 点击原地向下展开，每件一行（图、名称、规格、单价、数量），头部箭头朝上，再点收起，不跳页 |
| E6（本 story 建） | 显示应付金额（混合拆分）、「Lihat pesanan」→ 订单详情、「Lanjut belanja」→ Toko；纯币单提交后也到这里；系统返回键等同「Lihat pesanan」 |

### 文案表（PRD §6 D 组 + E6；新 key 两份 arb 都加）

| 屏 | key（建议） | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| D1 | `checkoutCourierLabel` | Kurir | Courier |  |
| D1 | `checkoutCourierEta` | Estimasi {n} hari | Est. {n} days | `n` = 服务端 `etaText`（如 `2–4`），见待确认 3 |
| D1 | `checkoutInsuranceTitle` | Asuransi pengiriman | Shipping insurance | 明细行同用 |
| D1 | `checkoutInsuranceRate` | 0,5% dari harga produk | 0.5% of product price |  |
| D2 | `checkoutNoAddressCourierHint` | Isi alamat dulu untuk melihat pilihan kurir | Add an address to see couriers |  |
| D3 | `checkoutNoCourier` | Tidak ada kurir untuk alamat ini | No courier for this address |  |
| D3 / B3 | `shopAreaNotServiceable` | Wilayah ini belum bisa dikirim kurir | Couriers can't deliver to this area yet | 本 story 首建；8.2 B3 复用 |
| D3 | `checkoutServiceAreaTitle`（**改值**） | Wilayah yang sudah kami layani | Areas we deliver to | 原「Dikirim ke wilayah ini?」 |
| D6 | `checkoutQuoting` | Menghitung ongkir… | Calculating shipping… |  |
| D8 | `checkoutQuoteFailed` | Ongkir belum bisa dimuat. | Couldn't load shipping. | 「Coba lagi」复用 `commonRetry` |
| D1 | `checkoutItemsCount` | Produk ({n}) | Items ({n}) |  |
| D1 | `checkoutCouponSection` | Kupon | Coupons |  |
| D1 | `checkoutCouponDiscount` | Diskon belanja | Order discount | 优惠卡与明细行同用 |
| D1 | `checkoutCouponFreeShipping` | Gratis ongkir | Free shipping | 不复用 `checkoutFreeShipping`（EN 不同） |
| D1 | `checkoutCouponNotUsed` | Tidak dipakai | Not used |  |
| D1 | `checkoutCouponNoneAvailable` | Belum ada yang bisa dipakai | None available |  |
| D1 | `checkoutPaymentDetails` | Rincian pembayaran | Payment details |  |
| D1 | `checkoutFreeShippingCouponRow` | Kupon gratis ongkir | Free-shipping coupon | 明细行 |
| D1 | `checkoutPaymentMethod` | Metode pembayaran | Payment method |  |
| D1 | `checkoutCoinBalanceShort` | Saldo Rp {x} | Balance Rp {x} |  |
| D12 | `checkoutAutoShipDiscount` | Potongan otomatis | Auto discount |  |
| D12 | `checkoutAutoShipDiscountHint` | Belanja ≥ Rp {x}, lebih hemat dari kupon | Spend ≥ Rp {x}, saves more than the coupon |  |
| D12 | `checkoutShipRuleRow` | Potongan ongkir (belanja ≥ Rp {x}) | Shipping discount (spend ≥ Rp {x}) | 明细行 |
| D11 | `couponReasonRuleBetter` | Potongan otomatis lebih hemat | Auto discount saves more | 免运券不可用原因 |
| D15 | `checkoutPayFullQris` | Bayar penuh lewat QRIS | Pay in full with QRIS |  |
| D14 | `checkoutPaidFullCoinNote` | Seluruh pesanan dibayar dengan PawCoin, tanpa QRIS. | The whole order is paid with PawCoin, no QRIS. |  |
| D14 | `checkoutPaidFullCoin` | Dibayar penuh dengan PawCoin | Paid in full with PawCoin | 底栏次行 |
| D13 | `checkoutCoinCapped`（**改值**） | Maksimal {amount} PawCoin per pesanan. Pemakaian di atas batas ini bisa dianggap pencairan dana. | Up to {amount} PawCoin per order. Using more may be treated as cashing out. | `{amount}` 读 `coinCap` |
| D4 | `checkoutPickAddressTitle` | Pilih alamat | Choose address |  |
| D4 | `checkoutAddNewAddress` | Tambah alamat baru | Add new address |  |
| D5 | `checkoutSaveAndUse` | Simpan & pakai | Save & use |  |
| D7 | `checkoutPickCourierTitle` | Pilih kurir | Choose courier |  |
| D7 | `checkoutCourierCheapest` | Termurah | Cheapest |  |
| D7 | `checkoutCourierNoInsurance` | Tanpa asuransi | No insurance |  |
| D7 | `checkoutCourierCapNote` | Potongan ongkir maksimal sebesar ongkir termurah. Kalau pilih kurir lebih mahal, selisihnya kamu bayar. | Shipping discounts cover up to the cheapest courier's fee. If you pick a pricier courier, you pay the difference. |  |
| D10 | `couponDiscountSheetTitle` | Kupon diskon belanja | Order discount coupons |  |
| D11 | `couponFreeShippingSheetTitle` | Kupon gratis ongkir | Free-shipping coupons |  |
| D10 | `couponAvailableCount` | Bisa dipakai ({n}) | Available ({n}) | 9.3 G1 复用 |
| D10 | `couponUnavailableCount` | Belum bisa dipakai ({n}) | Not available ({n}) |  |
| D10 | `couponDontUse` | Tidak pakai kupon | Don't use a coupon |  |
| D10 | `couponApply` | Pakai | Apply |  |
| D10 | `couponReasonMinSpend` | Belum capai minimum belanja | Minimum spend not reached |  |
| D10 | `couponReasonProductExcluded` | Produk tidak termasuk | Product not included |  |
| D11 | `couponReasonSomeExcluded` | Ada produk di luar cakupan kupon | Some items aren't covered |  |
| D10 | `couponReasonExpired` | Kedaluwarsa | Expired |  |
| D10 / G1 | `couponExpiringSoon` | Segera berakhir | Expiring soon | 本 story 首建，9.3 复用 |
| D10 / A4 | `couponMinSpend` | min. belanja Rp {min} | min. spend Rp {min} | PRD A4「券面『满 X 可用』同此句式」 |
| D17 | `checkoutCouponInvalidReselected` | Kupon sudah tidak berlaku. Kami pilihkan kupon lain. | That coupon is no longer valid. We picked another one for you. |  |
| D18 | `checkoutSavedOnOrder` | Kamu hemat Rp {x} di pesanan ini | You save Rp {x} on this order |  |
| D18 | `checkoutUseCoin` | Pakai PawCoin | Use PawCoin |  |
| D18 | `checkoutRestViaQris` | Sisanya lewat QRIS | The rest via QRIS |  |
| D18 | `checkoutQrisApps` | Bisa pakai e-wallet atau m-banking | Works with e-wallets or mobile banking |  |
| D1 | `checkoutRemainingQris` | Sisa bayar lewat QRIS: Rp {x} | Remaining via QRIS: Rp {x} |  |
| D1 / C1 | `tokoSavedAmount` | Hemat Rp {x} | You save Rp {x} | 本 story 首建，8.3 复用 |
| D1 / B1 / C1 | `tokoFreeShipRemaining` | Belanja Rp {x} lagi untuk potongan ongkir | Spend Rp {x} more for a shipping discount | 本 story 首建（AC8.5），8.2 / 8.3 复用 |
| B4 / C | `tokoFreeShipReached` | Dapat potongan ongkir | Shipping discount unlocked | 本 story 一并建（8.2 / 8.3 用） |
| E6 / F17 | `shopOrderCoinCashSplit` | PawCoin Rp {a} + QRIS Rp {b} | PawCoin Rp {a} + QRIS Rp {b} | 同形词全在 microcopy 白名单；9.1 F17 复用 |
| E6 | `shopPaySuccessPreparing` | Pesanan kamu sedang disiapkan untuk dikirim. | Your order is being prepared for shipping. |  |
| E6 | `shopPaySuccessViewOrder` | Lihat pesanan | View order |  |
| E6 | `shopPaySuccessContinue` | Lanjut belanja | Continue shopping |  |
| 复用 | `checkoutTitle`「Checkout」、`checkoutSubmit`「Buat Pesanan」、`checkoutPayable`「Total bayar」、`checkoutSubtotal`、`checkoutShippingFee`、`checkoutAddAddress`、`checkoutOutOfRangeBody`、`checkoutPickAnotherAddress`、`checkoutMoreAreas`、`checkoutUnavailableTitle` / `Remove` / `Stock` / `Delisted`、`commonCancel`「Batal」、`checkoutNotPlaceable`、`checkoutGenericError`、`checkoutPawcoinNote`、`checkoutLoadFailed`、`tokoReturnableBody` / `tokoNoReturnAfterOpenBody` / `tokoNoReturnBody`、`addressDefaultBadge`「Utama」、`addressNotServiceable`、`addressAdd`「Tambah alamat」、`commonRetry`、`shopOrderPaid`「Pembayaran berhasil」、`commonClose` | — | — | 原文不改 |

v2 独有、新页不再引用的 key（如 `checkoutQrisRemainder`、`checkoutPawcoinFull`、`checkoutCoinBalance`、`checkoutShippingReguler`、`checkoutShippingUnavailable`、`checkoutChangeAddress`、`checkoutOrderPlaced`）**本 story 不删**（可能仍被他处或测试引用；无引用的电商旧 key 由 9.4 收尾时统一清理）。

### 视觉（UI 稿「全局视觉规范」）

顶栏白底、返回 `Icons.chevron_left_rounded` 28、标题 18 / w600；字号 `AppTypography`（正文 15、说明 13、免责 12）；间距 / 圆角 `AppSpacing` / `AppRounded`（UI 稿写 `AppRadius`，本仓实际类名是 `AppRounded`，`core/theme/rounded.dart`）：页边 16、卡片圆角 16、卡间 12；区块用白卡片（建议在 `shop_surface.dart` 新增 `ShopCard`，不改 `ShopSection`——订单 v2 / 退款页仍在用）；价格紫色 `AppColors.mint #845EC9`、已付墨色；主按钮高 48、圆角 12；金额等宽数字；toast 实色。

### 拍板点（按建议实现，Completion Notes 复述）

1. 需扫码时「提交 → 订单详情自动弹面板」（`?autopay=1`），而不是在结算页上弹面板：关面板后用户自然落在待付款详情（FR-128.7），7.1 不需要复制 `_pay` 的四态分派；7.2 抽公共支付流程时两处共用。
2. E6 页面在本 story 落地（纯币直达需要），7.2 只把 QRIS 到账接到同一页。
3. 立即购买提交失败 `unavailable-lines` 时只给「Batal」并回详情页。
4. 免运进度只画「再买 X」一行，达标不另画（D 组 UI 未画此行，见待确认 2）。

### 实现提示

- 试算请求的「最后一次胜出」：`int _seq`；每次发请求 `final my = ++_seq;`，响应回来 `if (my != _seq) return;`。
- 券选择状态：首个响应后把 `discountCouponToken` / `freeShippingCouponToken` 存到页面状态；抽屉确认时 `couponMode=MANUAL` + 两个 token（未改的那张沿用上次响应值；选「Tidak pakai kupon」传 null）。
- `CheckoutFailure.from`：先 `ProblemDetail.fromDioException(e)?.typeSlug`；`unavailable-lines` 时照旧从 body 读 `unavailableLines`。
- `autoPay`：`initState` 里 `WidgetsBinding.instance.addPostFrameCallback((_) { if (widget.autoPay) _payWhenLoaded(); })`，等 `shopOrderDetailProvider` 首次 data 后调用一次 `_pay`，用布尔防重入；订单已非待付款则不触发。
- 提交时取当前响应的 `quoteToken`；`quoteToken == null`（无地址 / 不可配送 / 询价失败）时按钮本就不可点。
- 抽屉用 `showModalBottomSheet(isScrollControlled: true)`；D5 近全屏用 `DraggableScrollableSheet` 或 `FractionallySizedBox(heightFactor: .92)`；键盘用 `keyboard_safe_area.dart`。

### 架构约束

- P-1：App 不算钱（包括「已省」「剩余 QRIS」），全部取 `pricing`。
- S-3：币在提交时扣 → 提交成功即 `invalidate(pawCoinProvider)`；纯币单服务端已付款，App 不调 `/pay`。
- S-4：旧版 App 下单走后端旧模式，与本 App 无关；本 App **必须**带 `quoteToken`，否则会被服务端当作旧版处理。
- `/shop` 一律 `go` 不 `push`（`deep_link_routes.dart:17-24`）。
- 不引新依赖；不对整文件 `dart format`（手工对齐缩进，只跑 `flutter analyze`）。

### 与前后 story 的边界

- 7.2：`qr_payment_sheet.dart` 可选参数、「我已付款」、E1–E5 / E7、把订单详情 `_pay` 抽成公共流程并把 QRIS 到账导向 E6。本 story 只调用现有面板（通过订单详情 `autoPay`）。
- 8.2：详情「立即购买」改为 `context.push('/shop/checkout?source=buy_now&sku=…&qty=…')`（本 story 已支持该 URL）；B3 复用 `shopAreaNotServiceable`；免运进度 key 复用。
- 8.3：购物车「去结算」路由不变；`tokoSavedAmount` / 免运进度 key 复用。
- 9.1：订单详情 v3 须保留 `autopay=1` 与支付成功跳 E6 的能力；F17 复用 `shopOrderCoinCashSplit`。
- 9.3：`coupon_card.dart`、`couponExpiringSoon`、`couponAvailableCount` 复用。
- 9.4：地址簿 H1 用 `ShippingAddress.serviceable`；H3 视觉改造基于 `AddressFormBody`。

### 测试

**L0（云端可跑）**
- `test/shop/checkout_page_v3_test.dart`（新，取代 `checkout_page_v2_test.dart`，后者随 v2 删除；其断言逐组迁移改写）：
  - 迁移映射：「无地址预览」组 → D2（请求不带 `addressToken`、金额位「—」、提交禁用、点地址区打开 D5 而非 push 路由）；「FR-100A 两段金额」组 → 支付方式卡同时显示币额与 QRIS 额、**明细里不再出现 PawCoin 行**（旧断言「PawCoin 段以 − Rp 行在明细」按设计改为「明细无 PawCoin」）；「截断说明」→ D13（文案数字来自 `coinCap`）；「防套现提示」→ `checkoutPawcoinNote` 在用币时出现、D15 不出现；「FR-99 超范围」组 → D3（「—」、提交禁用、城市块、取不到区域不画、换地址出口改为打开 D4）；「FR-104」组原样；「金额一个都不自己算」组 → 改用 `pricing` 字段断言；「D-4 底栏合计不含 PawCoin 段」→ **按新规则改写**：底栏显示 `payable`（含币），次行「Sisa bayar lewat QRIS: Rp {cash}」；「布局不得溢出」三条原样（411dp 正常 / 超范围、1.3 倍字号）。
  - 新增：D1 结构顺序（配送卡 → 商品 → 优惠 → 支付方式 → 明细 → 同意 → 底栏）；D4 选择后请求带新 `addressToken` 且不调设默认；D5 保存后列表刷新、选中新地址、重新试算；D6 询价中「—」与禁用；D7 升序、Termurah、Tanpa asuransi、选中后请求带 `courierKey`；D8 重试；D9 保价行消失、请求 `insurance=false`；D10 / D11 原因文案映射（含 `OUT_OF_SCOPE` 在两种券的不同文案、`RULE_BETTER`）、选择后 `couponMode=MANUAL`；D12 两行文案与明细行；D14 / D15；D16（CART 移除后重试算、BUY_NOW 只有 Batal 且 pop）；D17（toast + `couponMode=AUTO` 重算 + 未再次提交）；`quote-expired` 静默重算；`not-placeable` / `sku-not-sellable` / 其他 toast；D18 展开收起；过期响应被丢弃（先发后到的旧响应不覆盖新结果）；BUY_NOW 全程不调 `cartProvider` 变更方法。
  - 成功分流：纯币 → `pushReplacement('/shop/orders/x/paid')`、不调 `/pay`、上报 `toko_order_payment_succeeded{pay_channel: PAWCOIN}`；需扫码 → `pushReplacement('/shop/orders/x?autopay=1')`；两者都 `invalidate(pawCoinProvider)`。
  - 埋点：`toko_checkout_page_viewed{entry: cart|buy_now}`；`toko_order_submitted` 新五键的值与类型。
- `test/shop/checkout_repository_test.dart`（新）：preview / placeOrder 请求体；`CheckoutFailure.from` 六种 `typeSlug` 与无 `typeSlug` 回落。
- `test/shop/shop_pay_success_page_test.dart`（新）：混合 / 纯币 / 纯 QRIS 三种金额行；两个按钮目的地（`/shop` 用 `go`）；系统返回 = Lihat pesanan；加载失败不编数字。
- `test/shop/shop_order_detail_page_v2_test.dart`（补一条）：`autoPay: true` 首帧自动调用一次 `/pay` 并出面板；`autoPay` 但订单已付款不触发。
- `test/shop/address/address_form_page_v2_test.dart`、`address_book_page_test.dart`：不改断言、保持绿（抽组件的回归证明）；新增 `address_form_body_test.dart`：`onSaved` 回调拿到服务端返回的地址。
- `test/shop/epic3_analytics_test.dart`、`test/analytics/v112_events_test.dart`、`test/l10n/microcopy_rules_test.dart`：保持绿。
- 跑：`cd petgo_app && flutter analyze && flutter test`。

**L1（本地，连 stag / 本地后端 `BITESHIP_MODE=stub`）**：stub 两家报价下 D1 默认最便宜、切快递保价行显隐；造券数据验证默认最优与 D12；币余额 > 上限验证 D13；纯币下单直达 E6、余额即时减少；需扫码单落订单详情并自动弹面板。
**L2（本地 Android 模拟器）**：D1–D18 + E6 逐屏对照 UI 稿打分，还原度 ≥ 95%，记入 Completion Notes。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新增：`A/features/shop/presentation/checkout_page_v3.dart`、`presentation/shop_pay_success_page.dart`、`presentation/widgets/checkout_sheets.dart`（地址 / 快递抽屉，可拆）、`A/features/shop/coupon/presentation/coupon_card.dart`、`coupon/presentation/coupon_picker_sheet.dart`、`A/features/shop/address/presentation/address_form_body.dart`、`A/features/shop/domain/free_shipping_progress.dart`。
- 删除：`checkout_page_v2.dart`、`test/shop/checkout_page_v2_test.dart`（用例迁入 v3 测试）。
- 改动：`app_router.dart`、`api_paths.dart`、`checkout_repository.dart`、`domain/checkout_preview.dart`、`shipping_address.dart`、`address_form_page_v2.dart`、`shop_order_detail_page_v2.dart`、`shop_surface.dart`（加 `ShopCard`）、`l10n/app_id.arb`、`l10n/app_en.arb`。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 7.1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#3.0 旅程 B / C；3.2 FR-128（结构、立即购买、提交失败）；3.4 FR-130；3.5 FR-131；3.10 FR-172（报价、保价）；§4；§6 D 组 / E6]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#D-7、D-8、D-9、D-23、P-1、P-4、P-5、P-9、P-10、P-12、§1.2 第 3 / 4 / 18 / 20 条]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md#S-3、S-4]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#研发执行说明、D1–D18、E6]
- [Source: _bmad-output/implementation-artifacts/v1.4.2/cloud-rules-shop.md]

## 待确认 / 发现的规划冲突

1. **UI 稿有、PRD §6 没有的几处文字**：D1 快递次行「Termurah dari 4 kurir」中的「dari N kurir」、D9「Pilihan kamu」、PawCoin 行「· dipakai Rp X」/「· tidak dipakai」、优惠行左侧「Diskon」/「Ongkir」小标签。按「文案严格取 §6」：快递次行只用「Termurah」/「Tanpa asuransi」；币额只显示数字；小标签不画。需设计侧补入 §6 后再加。
2. **结算页免运进度**：PRD FR-127.3 要求结算页显示「再买 RpX 享运费减免」，UI D 组 18 屏都没画。按 PRD 在优惠卡底部加一行（AC8.5），位置待设计确认。
3. **快递时效文本**：§6「Estimasi {n} hari」要求 `n` 为数字段，Biteship 返回的是时效区间 + 单位。本 story 假设 3.1 的 `etaText` 下发「2–4」这类纯区间；若后端下发整句或单位不是天，开工时按实际字段调整并记 Completion Notes。
4. **券面有效期与封顶文案**：UI 券面「s/d 30 Nov 2026」「Ongkir Maks. Rp 15.000」不在 §6。建议有效期复用现有 `qrPaySavedValidUntil`「Berlaku sampai {time}」，免运券封顶只显示金额；需设计侧补 §6。
5. **立即购买遇库存不足**：PRD / UI 只描述了购物车下单（「Hapus & lanjut」移除车行）。立即购买没有车行可删，本 story 只给「Batal」并回详情页。
6. **E6 落点**：epics 把支付成功页列在 7.2，但 7.1 的 AC 要求纯币直达支付成功页；为免前向依赖，E6 页面在 7.1 落地，7.2 复用。
7. **`coin_used` 属性类型**：PRD §4 只写「PawCoin 使用率」，本 story 取 bool（`coinAmount > 0`）；若产品要金额，改为 int 即可。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。
