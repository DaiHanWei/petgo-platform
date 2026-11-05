# Story 8.2: 商品详情（App，B1–B15）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.0 旅程 A / B、§3.1 FR-127、§3.2「立即购买直达结算」、§3.4「券的入口」、§3.7「商品详情」、§3.10 FR-172「其余细节」、§4、§6 B 组）> `architecture-v1.4.2-shop-delta.md`（D-6、D-9、D-10、D-17、D-23、P-4、P-5、P-9、P-10）> UI 定稿 `电商/ui-1-4-2-shop-integrated-v1.html` B1–B15 每屏说明；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**1.1**（SKU 包裹参数 → 可售判定）、**2.2**（`/shop/products/{token}/coupons`、`/me/coupons/claim`）、**3.1**（`shipping-estimate`）、**6.1**（详情 410、评价开关与分页）、**7.1**（结算页 BUY_NOW、免运进度与不可配送文案 key）、**8.1**（`showSkuPickerSheet`、`CouponCard`、领券失败文案、`tokoNotSellable`）。
> 范围层：**只 App**。购物车归 8.3；地址簿侧地区抽屉（H4）归 9.4（本 story 的 B6 是详情页专用的「只记本地」抽屉）。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。
- **C-9**：商品详情规格补 `sellable`（包裹参数齐全才 true）。
- **C-9**：`GET /shop/regions` 地区树每级补 `id`（区域参考数据，允许外露），详情页按地区估运费传 `regionKecamatanId`。
- **C-9**：评价出参补 `nickname`、`specName`；商品详情补 `reviewListEnabled`（开关状态）；商品列表 / 搜索补 `totalCount`（首页「{n} produk」）。
- **C-2**：错误码一律连字符风格（`coupon-not-found`、`coupon-code-used`、`coupon-not-in-period`、`coupon-already-claimed`、`coupon-sold-out`、`coupon-redeem-locked`、`coupon-invalid`、`quote-expired`、`quote-failed`、`not-serviceable`、`sku-not-sellable`、`product-delisted`），登记进 `ErrorTypes`。

## Story

As a 用户,
I want 在详情页看到能领的券、能不能送到、运费多少、评价,
so that 下单前心里有数。

## 接口契约（计划名，开工前核对 1.1 / 2.2 / 3.1 / 6.1 实际提交）

- `GET /api/v1/shop/products/{token}`：下架 → **410** ProblemDetail `type` 后缀 `product-delisted`；不存在 → 404。新增 / 依赖字段：`skus[].sellable`（缺包裹参数 = false，见待确认 1）；评价相关 `averageRating`、`reviewCount`、`reviews`（开关开时为前 3 条数组，开关关时缺键 / null，见待确认 2）。
- `GET /api/v1/shop/products/{token}/coupons` → `[{couponToken, kind, name, amount, minSpend, validUntil, claimed}]`（游客可读，`claimed` 恒 false）。
- `POST /api/v1/me/coupons/claim {couponToken, source: "DETAIL"}`（同 8.1）。
- `GET /api/v1/shop/products/{token}/shipping-estimate?skuToken=&qty=&addressToken=|regionKecamatanId=` → `{serviceable: bool?, lowestFee: int?, freeShippingProgress: {threshold, remaining}?, quoteFailed: bool}`；游客按地区代表邮编；区域未配邮编按不可配送。
- `GET /api/v1/shop/regions`（现有）：Kecamatan 节点需带 `id`（见待确认 3）。
- `GET /api/v1/shop/products/{token}/reviews?cursor=` → `{total, averageRating, items[{…, reviewerName?, specName?}], nextCursor}`（6.1；昵称 / 规格字段见待确认 4）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — 页面替换与上部顺序 `[L0 widget]` `[L2]`（FR-133.5、UI B1）
1. 新建 `A/features/shop/presentation/product_detail_page_v3.dart`（`ProductDetailPageV3({required String token, String? entrySource})`），删除 `product_detail_page_v2.dart`；`app_router.dart:676-684` 改指新页（`?from=` 归因照传）。
2. **原有模块全部保留**（`product_detail_page_v2.dart`）：图区（多图页码、点开全屏、返回、购物车角标）、价格、商品名、元信息（★评分 · N ulasan · 库存）、PawCoin 行（已登录才显示，:420）、规格、退货政策块（三种政策共用 key，不改字，:497）、Detail Produk（:509，`_stripHtml` 原样）、售罄态（价格变灰 + harga terakhir、图上「已售罄」蒙层）。
3. 上部顺序（epics / PRD）：**图片 → 标题 → 价格 → 可领的券（AC4）→ 送达地区（AC2）→ 运费（AC3）→ 免运进度（AC3）**；其后：规格行「Varian {规格} ›」（AC6）→ 评价区（AC7）→ 退货政策 → Detail Produk。
4. 布局重做：规格由页内 chip 墙改为一行「Varian」点开弹层（与加购同一弹层）；各块改白卡片（`AppTypography` / `AppSpacing` / `AppRounded`，圆角 16、卡间 12）；图区高 266 → **300** 且改为**不裁切**（`BoxFit.contain`，线上 `cover` 会裁图）。
5. 底栏改**悬浮胶囊坞**（11-05 方向一）：左圆形浅紫「加入购物车」图标钮（`add_shopping_cart`，key `pdpAddToCart`）、右紫色大药丸「Beli Sekarang」（`tokoBuyNow` 复用，按钮右侧嵌价格，key `pdpBuyNow`）；悬浮在内容上方，内容底部留 100 让位。
6. 进入上报 `toko_product_detail_viewed{product_token}`（沿用 :89）。

### AC2 — 送达地区：B1 / B2 / B6 `[L0 widget]` `[L2]`（FR-127.1、UI B1 / B2 / B6）
1. 取值优先级：本页本次在 B6 选的地区 > 已登录的默认地址（`addressListProvider` 中 `isDefault`）> 本地记住的地区（`AppPrefs.shopDeliveryArea`）> 无。
2. 有地址 / 地区：行「Kirim ke」+「Kec. {kecamatan}」（有市名时「Kec. {kecamatan}, {kota}」，同 UI B3）+ `›`；无：「Kirim ke」+「Pilih wilayah pengiriman」+ `›`，运费行显示「Pilih wilayah untuk lihat ongkir」（B2）。
3. 点击 → **B6 地区抽屉**：标题「Pilih wilayah pengiriman」+ 关闭；省 / 市 / 区三级，数据取 `regionTreeProvider`（`/shop/regions`，开通区域树）；顶部面包屑显示已选层级，点某一级回到该级重选；**未开通的区仍列出**、标「Belum dilayani」（复用 `addressNotServiceable`）；当前所选打 `check`。选中区即关抽屉：写 `AppPrefs.shopDeliveryArea`（`{kecamatanId, kecamatan, kota, provinsi}`，**只记本地、不生成收货地址**），并用 `regionKecamatanId` 重新估运费。
4. 选完上报 `toko_delivery_area_selected{serviceable}`（取该次估运费结果的 `serviceable`；询价失败时取区域树里该区的 `serviceable`）。
5. 游客态没有 PawCoin 行（UI B2 说明：稿里为简化未去掉，以结构稿为准）。

### AC3 — 运费与免运进度：B1 / B3 / B4 / B15 `[L0 widget]` `[L2]`（FR-127.1-.3、FR-172.1、D-9、UI B1 / B3 / B4 / B15）
1. `shippingEstimateProvider((productToken, skuToken, qty, addressToken?, regionKecamatanId?))` 调 `shipping-estimate`；`skuToken` = 当前有效 SKU（默认规格规则见 AC6）、`qty` = 1（详情页本身无数量，见待确认 5）；切换规格 / 地区后重新估。
2. **B1**：`serviceable=true` → 运费行「Ongkir」+「Mulai Rp {lowestFee}」；其下免运进度：`threshold > 0 && remaining > 0` →「Belanja Rp {remaining} lagi untuk potongan ongkir」（7.1 的 `tokoFreeShipRemaining`）；`threshold` 为 0 / null 整条不出现。
3. **B4**：`threshold > 0 && remaining == 0` → `check_circle`「Dapat potongan ongkir」（7.1 的 `tokoFreeShipReached`）。
4. **B3**：`serviceable=false` → 运费行换橙色 `local_shipping`「Wilayah ini belum bisa dikirim kurir」（7.1 的 `shopAreaNotServiceable`），**免运进度不显示**；底栏照常可加购 / 立即购买（PRD 只规定结算页不可提交）。
5. **B15**：`quoteFailed=true` → 运费行「Ongkir belum bisa dimuat」+ `refresh`「Coba lagi」（`commonRetry` 复用），点击重新估；**免运进度照常显示**（按商品金额算，不依赖询价）。
6. 没有地址 / 地区时（B2）仍用不带地址参数的估运费拿 `freeShippingProgress` 显示免运进度（见待确认 6）；估运费请求整体失败（网络）按 B15 处理。
7. 所有数值只取服务端（P-1），App 不算「还差多少」。

### AC4 — 可领的券：B1 / B7 / B8 `[L0 widget]` `[L2]`（FR-130.4、D-6、UI B1 / B7 / B8）
1. `productCouponsProvider(token)`（`GET /shop/products/{token}/coupons`）；空 / 失败 → 整行不画。
2. 行：「Kupon」（`checkoutCouponSection` 复用）+ 至多两枚摘要小签（满减：`formatIdr(amount)`；免运：`checkoutCouponFreeShipping`「Gratis ongkir」，7.1 建）+「Klaim ›」（`couponClaim`，8.1 建）；点整行 → **B7 领券抽屉**。
3. B7：标题「Kupon untuk produk ini」+ 关闭；逐张 `CouponCard`（满减 / 免运两种券面：面额、门槛、名称、有效期），按钮「Klaim」/ 已领「Sudah diklaim」（灰、不可点）。只读规则不在此展开。
4. 点「Klaim」：游客 → 软登录浮层（登录后自动领取这一张）；已登录 → `claim(source=DETAIL)`，请求在途按钮转圈。**B8**：成功 → 该张变「Sudah diklaim」、toast `couponClaimedToast`（抽屉开着 → `top: true`）、**抽屉不关**、上报 `toko_coupon_claimed{coupon_id: couponToken, source: "detail"}`、invalidate `productCouponsProvider` 与 8.1 的 for-you；失败：`already-claimed` → `couponAlreadyClaimed`，`sold-out` → `couponSoldOut`（8.1 建，与 G7 共用），其他同 8.1 AC2.5 / 待确认 4。

### AC5 — 可售与售罄：B5 及售罄态 `[L0 widget]`（FR-172.5、D-10、UI B5）
1. 当前有效 SKU `sellable == false`（`ShopSku.sellable`，8.1 已加）→ 底栏整宽灰按钮「Belum bisa dijual」（`tokoNotSellable`），不可加购、不可立即购买；规格行与弹层里该 SKU 仍可选（选到可售的 SKU 恢复底栏）。
2. 线上售罄态照旧（图上「已售罄」蒙层、价格变灰并标「harga terakhir」、底栏「Stok habis」+「Lihat Alternatif」）；**「Lihat Alternatif」改为 `context.go('/shop')`**（原 `go('/shop?category=…')`，:556；品类下线）；品类未知时也显示该按钮（目标已与品类无关）。
3. 加购 / 立即购买 / 结算对不可售 SKU 服务端返回 422 `sku-not-sellable` → toast `cartGenericError`（兜底，正常情况下按钮已置灰）。

### AC6 — 规格弹层、加购、立即购买：B9 / B10 / B11 `[L0 widget]`（FR-128.5、FR-133.5、旅程 A / B、UI B9–B11）
1. 规格行「Varian」+ 当前有效 SKU 规格名 + `›` → `showSkuPickerSheet(mode: addToCart)`（8.1）。默认规格「有货 > 低价 > 后台顺序」（8.1 抽出的共享函数，原 `_defaultSku` :101-112）；用户在弹层里选的规格回写到本页（运费随之重估）。
2. 胶囊坞「加入购物车」→ 弹层 addToCart（**B9**）：确认「Tambah ke keranjang」→ 加购成功 toast `cartAdded`「Ditambahkan ke keranjang」、**留在详情**、图区购物车角标 +N（**B11**）；失败 toast `cartStockError` / `cartGenericError`（照线上）。
3. 胶囊坞「Beli Sekarang」→ 弹层 buyNow（**B10**）：确认「Beli Sekarang」→ `context.push('/shop/checkout?source=buy_now&sku={skuToken}&qty={n}')`，**不写购物车、不改勾选**（删除原「先加购 `buyNow: true` 再跳结算」实现，:618-648）；从结算页返回详情页什么都不保存。
4. 未登录：弹层照常选规格与数量，点确认时走软登录浮层，登录成功后自动完成本次加购（停留原页）/ 直达结算（沿用 `_onAddTapped` / `_onBuyNowTapped` 的「不跳登录页、不丢意图」规则，:596-634）。
5. 埋点沿用：`toko_add_to_cart_tapped{product_token, sku_token}`、`toko_buy_now_tapped{product_token, sku_token}`、`toko_out_of_stock_shown`（按 SKU 去重，:150-160）。

### AC7 — 评价：B1 / B12 / B14 `[L0 widget]` `[L2]`（FR-133.6、D-17、UI B1 / B12 / B14）
1. **开关关**（详情无 `reviews`，默认）：评价区不出现；元信息行维持线上「★ 均分 · N ulasan」（无评分不显示 0，无评价不显示条数）。
2. **开关开且有评价**：评价区「Ulasan ({n})」+「★ {avg}」+「Lihat semua ›」；页内最多 3 条（昵称、星级、相对时间、正文；有图显示缩略图）；「Lihat semua」→ `/shop/products/{token}/reviews`。
3. **B14 开关开且 0 条**：评价区标题「Ulasan」（`tokoReviewsTitle` 复用）+「Belum ada ulasan」；元信息行不显示 ★ 与条数。
4. **B12 全部评价页**：新建 `presentation/review_list_page.dart`，路由 `/shop/products/:token/reviews`（游客可访问）；顶栏「Ulasan ({n})」；头部「{avg} dari 5 · {n} ulasan」（`tokoReviewsSummary` 复用）；列表每条：昵称、星级、相对时间、「Varian: {规格}」、正文、缩略图（有则显示，点开复用现有全屏查看器）；游标分页触底加载；加载失败通用重试。运营隐藏的评价服务端已过滤，App 不做处理。

### AC8 — 已下架：B13 `[L0 widget]`（FR-133.7、D-17、UI B13）
1. 详情请求 410（`typeSlug` 以 `product-delisted` 结尾）→ 页内整页「商品已下架」态：顶栏只有返回、`remove_shopping_cart` 图标、「Produk ini sudah tidak dijual」、「Lihat produk lain di Toko.」、按钮「Ke Toko」→ `context.go('/shop')`；**不走通用重试**。
2. 404 与网络失败仍走通用失败态 `tokoDetailLoadFailed`「Gagal memuat produk ini.」+ `commonRetry`（沿用 :173-178）。
3. `ShopRepository.fetchDetail`（`shop_repository.dart:130-133`）把 410 映射为 `ShopProductDelisted` 异常，provider 透传。

### AC9 — 埋点 `[L0]`（PRD §4、P-9）
1. 新事件字面量：`toko_delivery_area_selected{serviceable}`、`toko_coupon_claimed{coupon_id, source: "detail"}`（`_claimed` 词尾已由 8.1 扩表）。
2. 沿用事件（名称与属性不变）：`toko_product_detail_viewed`、`toko_add_to_cart_tapped`、`toko_buy_now_tapped`、`toko_out_of_stock_shown`。
3. 属性不含 PII（不放地区名、地址、邮编）；`epic1_analytics_test` 的 NFR-5 扫描保持绿。

### AC10 — 文案 `[L0]`（PRD §6 B 组、P-10）
按 Dev Notes「文案表」两份 arb 同时加，`flutter gen-l10n`；复用的 key 原文不改。

### AC11 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告、`flutter test` 全绿；按设计打破的断言迁移改写（见「测试」），不删、不 skip。
2. **还原度 ≥ 95%**（L2）：B1–B15 逐屏对照打分，记入 Completion Notes。

## Tasks / Subtasks

- [ ] **T0 开工核对**：1.1 / 2.2 / 3.1 / 6.1 字段（`sellable`、410 `type`、评价字段、估运费响应、regions 的 kecamatan id）；8.1 的弹层 API；有出入先改本文件
- [ ] **T1 数据层**：`api_paths.dart`（`shopProductCoupons(token)`、`shopProductShippingEstimate(token)`）；`ShopProductDetail` 评价字段；`fetchDetail` 410 映射；`shipping_estimate.dart` 模型 + provider；`RegionKecamatan.id`；`AppPrefs.shopDeliveryArea`；`ShopReviewRepository.page(token, cursor)`
- [ ] **T2 页面**：`product_detail_page_v3.dart`（上部顺序、胶囊坞、规格行、券行、送达 / 运费 / 免运）；删 v2；路由
- [ ] **T3 抽屉与新页**：`delivery_area_sheet.dart`（B6）、`product_coupons_sheet.dart`（B7 / B8）、`review_list_page.dart`（B12）、下架态（B13）
- [ ] **T4 埋点 / 文案**
- [ ] **T5 测试** + `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（核 `dev_1.4.2` @ 2c507755）

| 位置 | 现状 |
|---|---|
| `A/features/shop/presentation/product_detail_page_v2.dart`（704 行） | 入参 `token, entrySource` :56-62；`_selectedSkuToken` / `_effectiveSku` / `_defaultSku` :75-112；`_isSoldOut` :118；售罄曝光 :150-160；失败态 `tokoDetailLoadFailed` :173；`_gallery` :210、`_priceBlock` :339、`_titleBlock` :369（评分取 `productReviewsProvider` :371-381）、`_pawcoinRow` :420、`_variantBlock` :452、`_returnPolicyBlock` :497、`_detailBlock` :509；`_bottomBar` :530（售罄「Lihat Alternatif」`go('/shop?category=…')` :556）；`_onAddTapped` :596、`_onBuyNowTapped` :618（加购 `buyNow:true` 后 push `/shop/checkout` :636-648） |
| `A/features/shop/data/shop_repository.dart` | `fetchDetail` :130；`shopProductDetailProvider` :272 |
| `A/features/shop/data/shop_review_repository.dart` | `forProduct(productToken)` 一次性取（`limit`），无游标；`productReviewsProvider` :61 |
| `A/features/shop/domain/shop_review.dart` | `ShopReviewItem{id, rating, content, imageUrls, createdAt, reviewStatus}`（无昵称、无规格） |
| 后端 `shop/review/web/ShopReviewController.java:40` | `GET /shop/products/{token}/reviews?limit=`，下架即 404（`findByPublicTokenAndActiveTrue`） |
| `A/features/shop/address/domain/shipping_address.dart` | `RegionKecamatan{name, serviceable}`（:104-110，**无 id**）；后端 `RegionTree.Kecamatan(String name, boolean serviceable)` 同样无 id |
| `A/features/shop/data/cart_repository.dart` | `add(..., buyNow)` :37-50（参数保留，`cart_attribution_test` 在测；详情页不再用） |
| arb | `tokoBuyNow`「Beli Sekarang」、`tokoAddToCart`「Tambah ke Keranjang」（大写 K，PRD 新文案为小写，故新建 key）、`tokoReviewsTitle`「Ulasan」、`tokoReviewsSummary`、`tokoReviewsEmpty`（长句，B14 用 PRD 新短句） |

### UI 逐屏要点（B1–B15）

| 屏 | 要点 |
|---|---|
| B1 已登录有默认地址 | 原有模块全部保留；新增按 FR-133：可领的券（点 Klaim 开 B7）→ 送达地区（点开 B6）→ 运费「Mulai Rp X」（最低报价）→ 免运进度；评价区页内 3 条 +「Lihat semua」；规格改一行「Varian」点开弹层；白卡片；图区 266→300 不裁切；底栏悬浮胶囊坞（左浅紫圆钮加购，右紫色药丸立即购买、价格嵌按钮），内容底部留 100。11-05：评价列表受后台开关控制、默认关，关时评价区不出现、元信息只显示 ★ 均分与条数 |
| B2 选择送达地区 | 未登录或无地址：送达行「Pilih wilayah pengiriman」，运费行提示先选地区，点开 B6；结果只记本机、不生成收货地址；游客无 PawCoin 行 |
| B3 不支持配送 | 不在开通范围或 Biteship 无快递：运费行换橙色提示，免运进度不显示；底栏仍可加购 |
| B4 已享减免 | 商品实付达到门槛：进度条换「Dapat potongan ongkir」；门槛 0 整条不出现 |
| B5 暂不可售 | SKU 缺包裹重量或体积 → 底栏整宽灰按钮「Belum bisa dijual」；线上售罄态照旧，「看替代品」改回 Toko 首页 |
| B6 地区抽屉 | 省 / 市 / 区三级，取开通区域树；面包屑可点回上一级；未开通的区仍列出标「Belum dilayani」；已登录有地址时顶部多一组「Alamat saya」快捷选项（结构稿未画，见待确认 7） |
| B7 领券抽屉 | 列出该商品可领的券（满减 / 免运两种券面），逐张「Klaim」；券面面额、门槛、名称、有效期 |
| B8 领取成功 | 按钮变「Sudah diklaim」、toast、抽屉不关；失败（领完 / 达限领）toast，文案与 G7 共用 |
| B9 规格弹层·加购 | 线上规格页内 chip、数量固定 1；改弹层：图、当前价、库存、已选、规格 chip（售罄仍可点）、数量步进器（上限 = 库存）、确认；单 SKU 也弹 |
| B10 规格弹层·立即购买 | 同 B9，确认「Beli sekarang」→ 直接进 D1（单 SKU 直接结算，不写购物车） |
| B11 已加入购物车 | toast `cartAdded`，留在详情，角标 +N；失败 toast 照线上 |
| B12 全部评价 | 新页：均分与条数、评价列表（昵称、星级、时间、规格、正文），只读；有图显示缩略图 |
| B13 已下架 | 线上下架只走通用失败 + 重试（重试也没用）；新增下架页；网络失败仍通用失败态 |
| B14 暂无评价 | 评价区「Belum ada ulasan」，元信息行不显示 ★ 与条数（不显示 0） |
| B15 运费获取失败 | 运费行「Ongkir belum bisa dimuat」+「Coba lagi」重新询价；免运进度照常 |

### 文案表（PRD §6 B 组；新 key 两份 arb 都加）

| 屏 | key（建议） | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| B1 | `tokoShipTo` | Kirim ke | Ship to |  |
| B1 | `tokoShippingLabel` | Ongkir | Shipping |  |
| B1 | `tokoShippingFrom` | Mulai Rp {fee} | From Rp {fee} |  |
| B2 | `tokoChooseArea` | Pilih wilayah pengiriman | Choose delivery area | B6 抽屉标题同用 |
| B2 | `tokoChooseAreaForShipping` | Pilih wilayah untuk lihat ongkir | Choose an area to see shipping |  |
| B15 | `tokoShippingLoadFailed` | Ongkir belum bisa dimuat | Couldn't load shipping | 「Coba lagi」复用 `commonRetry`；与 D8 句号不同，不共用 |
| B1 | `tokoVariantLabel` | Varian | Variant |  |
| B1 | `tokoReviewsHeader` | Ulasan ({n}) | Reviews ({n}) | B12 顶栏同用 |
| B1 | `tokoReviewsSeeAll` | Lihat semua | See all |  |
| B14 | `tokoReviewsNone` | Belum ada ulasan | No reviews yet |  |
| B7 | `couponForProductTitle` | Kupon untuk produk ini | Coupons for this product |  |
| B7 | `couponClaimedDone` | Sudah diklaim | Claimed |  |
| B13 | `tokoDelistedTitle` | Produk ini sudah tidak dijual | This product is no longer sold |  |
| B13 | `tokoDelistedBody` | Lihat produk lain di Toko. | See other products in Toko. |  |
| B13 | `tokoDelistedCta` | Ke Toko | Go to Toko |  |
| 7.1 已建 | `shopAreaNotServiceable`（B3）、`tokoFreeShipRemaining`（B1）、`tokoFreeShipReached`（B4）、`checkoutCouponSection`「Kupon」、`checkoutCouponFreeShipping`「Gratis ongkir」 | — | — |  |
| 8.1 已建 | `couponClaim`「Klaim」（B1）、`tokoNotSellable`（B5）、`tokoQuantity` / `tokoSelectedVariant` / `tokoAddToCartConfirm`（B9）、`couponClaimedToast`、`couponAlreadyClaimed`、`couponSoldOut` | — | — |  |
| 复用 | `tokoBuyNow`（B10 确认键、胶囊坞）、`cartAdded`、`cartStockError`、`cartGenericError`、`addressNotServiceable`（B6「Belum dilayani」）、`tokoReviewsTitle`、`tokoReviewsSummary`、`tokoReviewsCount`、`tokoDetailLoadFailed`、`commonRetry`、`tokoOutOfStock`、`tokoSeeAlternatives`、`tokoLastPrice`、`tokoStockAvailable`、`tokoPawcoinPartial`、`tokoPawcoinBalance`、`tokoDetailSectionTitle`、三种退货政策 key | — | — | 原文不改 |

### 拍板点（按建议实现，Completion Notes 复述）

1. 送达地区优先级：本页选的 > 默认地址 > 本地记住的地区；B6 选择总是写本地（供购物车 8.3 在无地址时用）。
2. 详情页估运费 `qty=1`（页面本身无数量；弹层里改数量不触发重估）。
3. 游客进 B6 的选择与已登录一样只写本地。
4. 「Lihat Alternatif」品类未知也显示（目标是首页）。

### 实现提示

- 估运费 provider 族键用 record：`({String product, String sku, String? address, int? region})`，`autoDispose`；切 SKU 时先显示上一结果再替换，避免整行闪烁（运费行在途时显示「—」也可）。
- 410 识别：`ProblemDetail.fromDioException(e)`，`status == 410` 或 `typeSlug?.endsWith('product.delisted')`。
- 评价开关判据：`detail.reviews != null` → 开；为兼容 6.1 实现差异，开工核对后在 Completion Notes 写明最终判据。
- B6 面包屑：`List<String> path`（省、市）；回到某级只需截断 path。
- 胶囊坞用 `Stack` + `Positioned(bottom)`，`ListView` 底部 `SizedBox(height: 100)`。

### 架构约束

- P-1：免运进度、运费全取服务端；App 不算差额。
- D-10：可售判定只看服务端 `sellable`。
- 立即购买不碰购物车（FR-128.5）。
- `/shop` 一律 `go`。
- 埋点不带地区 / 地址 / 邮编。

### 与前后 story 的边界

- 8.1：弹层、券面、领券失败文案、`ShopSku.sellable` 已建。
- 8.3：购物车无地址时用本 story 写的 `AppPrefs.shopDeliveryArea.kecamatanId` 作 `regionKecamatanId`。
- 9.4：地址簿的地区选择（H4）是表单内的三级选择，不复用 B6 的「只记本地」抽屉逻辑（可复用 UI 组件）。

### 测试

**L0（云端可跑）**
- `test/shop/product_detail_page_v3_test.dart`（新，取代 `product_detail_page_v2_test.dart`，后者随 v2 删除；用例迁移）：
  - 原样迁移：R-1 主图放大（有图可点 / 原图 URL / 无图不给入口 / 多图从被点那张打开）；FR-95 库存数不虚构三条；FR-104 开封不退两条；售罄态「主按钮置灰不消失」「价格转灰 + harga terakhir」「不写 segera」「多规格未选不算售罄」；评分「无评分不显示 0」「有评分显示 ★ 与评价数」「不拿评价数冒充已售」；布局不溢出（411dp、1.3 倍字号）。
  - **按设计改写**：「多规格默认选中」六条 → 断言规格行显示的规格名与弹层默认选中项（规则不变）；「品类已知 → 次出口跳同品类列表」→「点 Lihat Alternatif → `go('/shop')`」；「品类未知 → 该按钮不渲染」→「品类未知也渲染且同样落首页」。
  - 新增：上部顺序（标题 → 价格 → 券行 → 送达 → 运费 → 免运）；B1 / B2 / B3 / B4 / B15 五种运费行（含 B3 不画进度、B15 重试重新请求、B15 进度仍在）；送达地区优先级三档；B5 `sellable=false` 底栏「Belum bisa dijual」；券行空不画；B7 列表与「Sudah diklaim」；B8 成功不关抽屉、toast 在顶部、埋点 `source: detail`；领券失败两种文案；游客领券走软登录；B9 加购成功 toast + 角标；B10 立即购买路由带 `source=buy_now&sku&qty` 且**未调** `cartProvider.add`；评价开关关 / 开 / 0 条三态；B13 410 显示下架页、「Ke Toko」`go('/shop')`、404 仍为通用失败；胶囊坞两个 key 存在。
- `test/shop/cart_guest_add_test.dart`（改）：「游客点加购：弹软浮层…」「登录成功 → 自动完成本次加购」两条改为「点胶囊坞加购 → 弹层 → 点确认 → 软浮层 → 登录后自动加购、路由不变」；「bug 516：点 Beli Sekarang 只有它转圈」两条**按设计改写**为「弹层确认键提交中转圈且不可重复点」（胶囊坞按钮只开弹层，不再发请求）；409 本地化文案用例保留。
- `test/shop/cart_attribution_test.dart`（改）：「详情页把路由 ?from= 带进加购请求」改为经弹层确认后断言请求带 `entrySource`；仓储层三条（含「立即购买带 buyNow=true」）不改。
- `test/shop/epic1_analytics_test.dart`（改）：详情页 / 售罄曝光两条迁到 v3。
- `test/shop/delivery_area_sheet_test.dart`（新）：三级选择、面包屑回退、未开通标「Belum dilayani」仍可选、选中写 prefs、埋点 `serviceable`。
- `test/shop/review_list_page_test.dart`（新）：头部汇总、列表字段（昵称 / 规格缺失时不画该行）、触底翻页、失败重试。
- `test/l10n/microcopy_rules_test.dart`、`test/analytics/v112_events_test.dart`：保持绿。
- 跑：`cd petgo_app && flutter analyze && flutter test`。

**L1（本地 / stag，`BITESHIP_MODE=stub`）**：已登录有地址看到「Mulai Rp X」；游客选地区后运费出现、关掉 App 再进仍记得地区；把某 SKU 包裹参数置空 → B5；下架商品 → B13；评价开关开 / 关两种；领券成功后首页「Kupon buat kamu」同步变「Diklaim」。
**L2（本地模拟器）**：B1–B15 逐屏还原度 ≥ 95%，记入 Completion Notes。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新增：`A/features/shop/presentation/product_detail_page_v3.dart`、`presentation/review_list_page.dart`、`presentation/widgets/delivery_area_sheet.dart`、`A/features/shop/coupon/presentation/product_coupons_sheet.dart`、`A/features/shop/domain/shipping_estimate.dart`、`A/features/shop/data/shipping_estimate_repository.dart`（或并入 `shop_repository.dart`）。
- 删除：`product_detail_page_v2.dart`、`test/shop/product_detail_page_v2_test.dart`（用例迁入 v3）。
- 改动：`app_router.dart`（详情改指 v3、新增评价页路由）、`api_paths.dart`、`shop_repository.dart`、`shop_review_repository.dart`、`domain/shop_product_detail.dart`、`domain/shop_review.dart`、`address/domain/shipping_address.dart`（`RegionKecamatan.id`）、`core/storage/prefs.dart`、`l10n/app_*.arb`。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 8.2]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#3.0 旅程 A / B；3.1 FR-127；3.2 立即购买；3.4 券的入口；3.7 商品详情；3.10 其余细节；§4；§6 B 组]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#D-6、D-9、D-10、D-17、D-23、P-1、P-5、§1.2 第 3 / 14 条]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#B1–B15]

## 待确认 / 发现的规划冲突

1. **SKU 可售字段未写进接口 AC**：1.1 只规定 `ShopSku.isSellable()` 与 422，没规定详情 SKU 出参带 `sellable`。本 story 依赖 `skus[].sellable`（8.1 已在 App 端加、缺键按 true）；若 1.1 / 6.1 实际未下发，开工时在 `ShopSkuView` 补一个只加不改的字段（旧版 App 忽略），并记 Completion Notes。
2. **评价开关的 App 判据与均分来源**：6.1 只写「开关关时详情不带评价列表，只均分 + 条数」，现均分 / 条数来自独立的 `/reviews` 接口。本 story 以「详情是否带 `reviews`」判开关，均分 / 条数优先取详情字段、缺失时回落 `/reviews`；以 6.1 实际实现为准。
3. **地区 id 缺失**：`shipping-estimate` 需要 `regionKecamatanId`，但 `/shop/regions` 的 Kecamatan 节点没有 id（后端 `RegionTree.java:25`、App `shipping_address.dart:104-110`）。若 3.1 未补，本 story 在 `RegionTree.Kecamatan` 追加 `Long id`（只加字段，`shipping_zones.id` 为区域参考数据，架构 D-9 / 3.1 已允许外露）。
4. **评价昵称 / 规格字段**：B12 要显示昵称与「Varian」，现评价出参无这两项，6.1 未写。本 story 字段缺失时不画该行；需 6.1 补（昵称须脱敏口径另定）。
5. **「按当前所选 SKU 与数量询价」**：详情页本身没有数量，数量只在弹层里选；本 story 估运费 `qty=1`。
6. **无地区时的免运进度**：UI B2 仍画了免运进度，但 3.1 的 `shipping-estimate` 是否接受「既无地址也无地区」只返回进度未写。本 story 照此调用；若后端拒绝，B2 不显示进度。
7. **B6「Alamat saya」快捷组**：UI 说明写「第三步补」，但定稿稿未画、§6 无文案。本 story 不做；已登录有默认地址时送达行直接显示默认地址。
8. **上部顺序**：UI B1 说明写「价格 → 标题」，PRD / epics 写「标题 → 价格」；按 PRD。
9. **领券成功 toast**：UI B8 画的是「Kupon diklaim」短句，§6 只有 A5 长句「Kupon diklaim. Cek di Kupon Saya」；按 §6 用长句。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。
