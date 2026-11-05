# Story 8.1: Toko 首页与搜索（App，A1–A9）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`
> 口径优先级：`决策日志-shop.md`（S-0 ~ S-9）> PRD `电商/1-4-2电商prd.md`（§3.4 FR-130「券的入口 / 新人与新人券 / 新人券细节」、§3.6 FR-132、§3.7 FR-133「找商品」、§3.9 FR-136、§4、§6 A 组）> `architecture-v1.4.2-shop-delta.md`（D-5、D-6、D-16、D-17、D-20、D-23、P-4、P-9、P-10）> UI 定稿 `电商/ui-1-4-2-shop-integrated-v1.html` A1–A9 每屏说明；云端执行以 `cloud-rules-shop.md` 为准。
> 前置：**2.2**（`/me/shop/entry`、`/shop/coupons/newbie-campaign`、`/shop/coupons/for-you`、`/me/coupons/claim`、商品出参 `promo`）、**6.1**（`/shop/banners`、`/shop/products/top`、推荐 `profileSummary`）、**6.2**（复购下线）、**7.1**（`coupon_card.dart`、结算页 BUY_NOW 路由）。
> 范围层：**只 App**。商品详情归 8.2（复用本 story 的规格弹层与推荐网格）；购物车归 8.3（复用推荐网格）；我的券页归 9.3；Diary 复购卡归 9.4。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。
- **C-9**：评价出参补 `nickname`、`specName`；商品详情补 `reviewListEnabled`（开关状态）；商品列表 / 搜索补 `totalCount`（首页「{n} produk」）。

## Story

As a 用户,
I want 打开 Toko 就看到活动 banner、给我的券和为宠物挑的商品,
so that 更快找到想买的东西。

## 接口契约（计划名，开工前核对 2.2 / 6.1 实际提交）

- `GET /api/v1/shop/banners`（游客可用）→ `[{bannerId, imageUrl, imageW, imageH, targetType: PRODUCT|COUPON|EXTERNAL|TOKO_HOME|NONE, targetValue}]`，≤ 6 张、已按 `sort_weight DESC`、已过滤失效目标；空数组 = 不渲染。旧 `GET /shop/banner` 本 App 不再调用。
- `POST /api/v1/me/coupons/claim {couponToken, source: "HOME"|"BANNER"|"DETAIL"}` → 用户券；409 `coupon-already-claimed`、409 `coupon-sold-out`、409 `coupon-not-in-period`；401 未登录。
- `POST /api/v1/me/shop/entry`（幂等）→ `{newbieCouponIssued: bool, couponToken?}`。
- `GET /api/v1/shop/coupons/newbie-campaign`（游客可用）→ `{campaignToken, couponToken, amount, minSpend}`；无活动 204。
- `GET /api/v1/shop/coupons/for-you`（需登录）→ `[{couponToken, kind: DISCOUNT|FREE_SHIPPING, name, amount, minSpend, validUntil?, claimed: bool, newbie: bool}]`（新人券置顶、`newbie=true`）；空数组 = 不渲染。
- `GET /api/v1/me/shop/recommendations` 新增 `profileSummary {petName, species: DOG|CAT, sizeClass: SMALL|MEDIUM|LARGE|null, ageStage: PUPPY|ADULT|SENIOR|null, weightMissing: bool}`。
- `GET /api/v1/shop/products/top?limit=6`（游客可用）→ 商品摘要数组（带 `promo`）。
- 商品列表 / 搜索 / 推荐 / top 每项新增 `promo: bool`；商品列表分页信封若带 `total` 则显示件数（见待确认 3）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test`（云端可跑）· `[L1]` 连本地 / stag 后端联调 · `[L2]` Android 模拟器视觉（本地）。

### AC1 — 页面替换与结构 `[L0 widget]` `[L2]`（P-4、UI A1 定稿方向）
1. 新建 `A/features/shop/presentation/toko_page_v3.dart`（`TokoPageV3()`，**无 `initialCategory`**），删除 `toko_page_v2.dart`；`app_router.dart:207` 改为 `AppTab.shop => const TokoPageV3()`，**忽略** `?category=`（D-17；URL 带参数也落首页、不报错）。
2. 自上而下：**浅紫柔光顶部**（浅紫为主、左上一点蜜桃、右侧一点薄荷，向下渐隐到页面底色，无分界线）→ 标题「Toko」墨色 + 右上 PawCoin 余额胶囊（白底紫字，沿用 `_pawcoinCapsule` 逻辑：已登录点击进 `/me/pawcoin`，游客显示「Masuk」进 `/login`，`toko_page_v2.dart:338-360`）+ 购物车按钮（红色角标，沿用 `_CartCapsule` :743-763）→ 搜索框（白底 + 浅紫到蜜桃渐变描边，占位「Cari makanan, obat, camilan…」，点击 `context.push('/shop/search')`）→ banner 轮播（AC2）→「Kupon buat kamu」（AC3）→ 为宠物推荐块（AC5）→ 全部商品（AC6）。
3. 删除：banner 作顶栏背景（`_BannerAppBar` :550 起）、吸顶筛选行与品类 chip（`_FilterBar` :403、`_StickyFilterBar` :489）、补货行与补货卡（`_RestockRow` :811-841、`_RestockCard` :843-983）、「去建档」引导条（`_profileSetupRow` :833）、满额免运提示条（UI 11-05 删除）。`toko_repurchase_card_shown` / `_tapped` 在本页的上报点随之删除（Diary 复购卡里的同名事件归 9.4）。
4. 进入页面上报 `toko_tab_viewed`（沿用 :75）。下拉刷新：invalidate banner、for-you、推荐、商品首页（不再 invalidate `repurchaseCardsProvider`，:236）。
5. 全局视觉规范：`AppTypography`（正文 15 / 说明 13）、`AppSpacing` / `AppRounded`（页边 16、卡片圆角 16、卡间 12）；价格紫色 `AppColors.mint`；本页不再用 `ShopText` 小字号。

### AC2 — A1 / A2 / A5 banner 轮播与领券 `[L0 widget]` `[L2]`（FR-132、D-16、UI A1 / A2 / A5）
1. 数据：`shopBannersProvider`（`List<ShopBanner>`，`GET /shop/banners`）；取代 `shopBannerProvider` 单张（`shop_repository.dart:267-269`，旧 provider 与 `fetchBanner` 无引用后删除）。
2. `PageView` 实现（**不引轮播库**）：自动轮播（每 4 秒，用户手动滑动后从当前页重新计时）+ 手动滑动 + 底部页码点；只有 1 张时不自动轮播、不画页码点；图按比例显示**不裁切**（`aspect` 取 `ShopBanner.aspect`，`kShopBannerFallbackAspect` 兜底，沿用 bug 600 规则）。页面不在前台（`TickerMode` 关）时暂停自动轮播。
3. **A2**：空数组 → 整块不渲染，「Kupon buat kamu」直接接在搜索框下。
4. 点击按 `targetType`：`PRODUCT` → `context.push('/shop/products/{value}?from=BANNER')`；`EXTERNAL`（仅 https）→ `launchUrl(externalApplication)`，打不开静默；`TOKO_HOME` → 留在首页（无动作）；`NONE` 不可点；`COUPON` → AC2.5。800ms 内连点只算一次（沿用 `_onBannerTap` :135-139）。
5. **A5 领券 banner**：游客点 → 软登录浮层（`loginGuideControllerProvider.showSoftSheet`，登录后自动领取一次）；已登录 → `claim(couponToken=targetValue, source=BANNER)`：成功 toast「Kupon diklaim. Cek di Kupon Saya」+ 上报 `toko_coupon_claimed{coupon_id: couponToken, source: "banner"}` + invalidate for-you；409 `coupon-already-claimed` → toast「Kamu sudah klaim kupon ini」；409 `coupon-sold-out` → toast「Kupon sudah habis」；其他失败见待确认 4。
6. 埋点：`toko_banner_shown{banner_id, position}` 每张**实际露出**超过 50% 时报一次（同一次进入页面同一张只报一次，离开页面再回来重新计，沿用 :97-130 的 `TickerMode` 规则），`position` = 该张在轮播中的**实际位次（1 起）**；`toko_banner_tapped{banner_id, target_type, position}`，`target_type` ∈ `product_detail` / `coupon` / `external_link` / `toko_home` / `none`。
7. `ShopBanner` / `ShopBannerTarget`（`domain/shop_banner.dart`）：加 `coupon('COUPON','coupon')`、`tokoHome('TOKO_HOME','toko_home')`，`category` 枚举值删除（旧值 `CATEGORY` 落 `none`）；`tappable` 同步。

### AC3 — A3「Kupon buat kamu」 `[L0 widget]` `[L2]`（FR-130.4 / .5、D-6、UI A1 / A3）
1. 仅已登录渲染；`couponsForYouProvider`（`GET /shop/coupons/for-you`）空数组 / 失败 → **整块不出现**（不出错误态）。
2. 标题「Kupon buat kamu」+ 右上「Kupon Saya ›」→ `context.push('/me/coupons')`。横滑，两张正好占满一行宽，其余左滑出现。
3. 券面用 7.1 的 `CouponCard` 紧凑变体：满减「面额 + min. belanja Rp {min}」、免运「Gratis ongkir + 封顶额」；按钮位：可领「Klaim」、已领「Diklaim」（灰、不可点）。点「Klaim」→ `claim(source=HOME)`，成功后该张变「Diklaim」+ toast「Kupon diklaim. Cek di Kupon Saya」+ `toko_coupon_claimed{coupon_id, source: "home"}`；失败同 AC2.5。请求在途时按钮转圈、不可重复点。
4. **新人券**（`newbie=true`）：置顶一张紫底券，左上标「KHUSUS PENGGUNA BARU」，按钮位写 `check_circle`「Sudah masuk」（不可领、自动发的），点券面 → `/me/coupons`。**不弹到账弹窗**（11-05 取代）。
5. `/me/coupons` 页面由 9.3 实现：本 story 在 `app_router.dart` 先登记 `GoRoute('/me/coupons')` 指向 `PlaceholderScaffold`（`shared/widgets/placeholder_scaffold.dart`），9.3 替换 builder；路径在 `/me` 前缀下，游客深链自动被受控路由拦截。

### AC4 — 进入 Toko 发新人券与 A4 游客弹窗 `[L0 widget]`（FR-130.5、D-5、UI A3 / A4）
1. **已登录**每次进入 Toko（`initState` 与切回该 Tab 时各一次，同一进程 60 秒内不重复）调 `POST /me/shop/entry`；返回 `newbieCouponIssued=true` → 上报 `toko_coupon_claimed{coupon_id: couponToken, source: "newbie"}`（m3：由 App 在 entry 返回已发放时上报）并 invalidate for-you。失败静默。
2. **游客**进入 Toko：调 `GET /shop/coupons/newbie-campaign`；有活动且本设备未对该 `campaignToken` 弹过 → 弹窗（A4）并在 `AppPrefs` 记下该 `campaignToken`（新增 `shownNewbieCampaigns`，`core/storage/prefs.dart`）；204 / 失败不弹。弹出时上报 `toko_newbie_coupon_popup_shown{coupon_id}`。
3. A4 弹窗：右上关闭 ×、`redeem` 图标、标题「Daftar sekarang, dapat kupon Rp {amount}」、副标题「Khusus pengguna baru · min. belanja Rp {min}」、主按钮「Daftar & klaim」→ `context.push('/login')`；点主按钮上报 `toko_newbie_coupon_popup_tapped{coupon_id, action: "register"}`，点关闭上报同事件 `action: "close"`；**关闭后不再弹**（同设备同活动）。背景为游客态首页：右上「Masuk」、无推荐块、无「Kupon buat kamu」。
4. 注册后首次进 Toko 即走 AC4.1 发券（服务端判定，App 不做任何新人判断）。

### AC5 — A1 / A9 为宠物推荐 `[L0 widget]` `[L2]`（FR-133.4、FR-136.2、D-17、UI A1 / A9）
1. 新建可复用组件 `A/features/shop/presentation/widgets/shop_reco_grid.dart`（`ShopRecoGrid({required ShopRecoSource source})`，8.3 C6、本 story A7 复用）：实色浅紫底块、边界清楚；栏头 + 三列网格最多 6 个（两行）；卡片只放图、名称、价格与「＋」快速加购（AC7），**不显示单个商品推荐理由**（接口仍返回，App 不用）。
2. 数据：有档案（`recommendationsProvider` 返回非降级且有 `profileSummary`）→ 栏头宠物头像（emoji 占位同线上）+「Untuk {petName}」+ 依据行（AC5.3）；无档案 / 游客 → 首页**整块不显示**（规则不变：只用默认宠物、游客和未建档不显示、宠物有离世或手术等记录时整栏隐藏——这些由服务端返回空 / 降级表达）。
3. 依据行按 `profileSummary` 本地拼（**App 不渲染后端显示串**）：ID 形如「Anjing kecil · dewasa」，EN 形如「Small dog · adult」；`sizeClass` 为 null 时只有物种；`ageStage` 为 null 时省略该段；`weightMissing=true` 时末尾再加「· berat belum diisi」（A9 栏头「Anjing · dewasa · berat belum diisi」）。拼接用 `tokoRecoSpeciesSize` 模板（ID「{species} {size}」/ EN「{size} {species}」）+「 · 」连接。
4. **A9 未填体重**：网格下方一条浅紫提示条 `monitor_weight`「Isi berat badan {pet} biar pilihannya lebih pas ›」，点击 `context.push('/profile/edit')`（`app_router.dart:839`）。
5. 曝光与点击埋点沿用：`toko_product_shown{zone: "profile_reco"}`（`:1057`）、`toko_profile_reco_tapped{zone, product_id}`（`:1067`），点击卡片 `push('/shop/products/{token}?from=PROFILE_RECO')`。

### AC6 — A1 全部商品 `[L0 widget]` `[L2]`（FR-133.1 / .3、UI A1）
1. 段头「Semua produk」+ 右侧「{n} produk」（仅当分页响应带 `total` 时显示，见待确认 3）。
2. 商品流**只有「全部」**：`shopProductsProvider` 族键恒 `category: null, keyword: null`；分页、触底预加载（竖向触发、横向不触发）、尾部三态沿用 `ShopProductListController`（`shop_repository.dart:195-257`）。
3. 新卡片组件 `A/features/shop/presentation/widgets/shop_product_card.dart`（首页、搜索结果、推荐网格、空购物车共用）：白卡片、浅紫图底、图片 `BoxFit.contain` 不裁切、名称两行、紫色价格（`minPrice` 为 null 显示占位，**不显示 Rp 0**，沿用现规则）、右下实心「＋」快速加购（热区 44）；`promo=true` 时左上角标 `local_fire_department`「Lagi promo」（红橙渐变，不写金额）。两列网格（`SliverGrid`，固定图区比例 1:1）。
4. 卡片点击 `push('/shop/products/{token}?from=TOKO_ALL_FEATURED')`；曝光 `toko_product_shown{zone: "all_featured"}`（沿用 masonry 现有 zone 值，`shop_product_masonry.dart:104`，开工核对）。
5. **A8 加载失败**：商品流首屏失败 → 失败卡 `cloud_off`「Gagal memuat produk.」（`tokoLoadFailed` 复用）+「Coba lagi」（`commonRetry` 复用）；banner 照常显示；翻页失败维持静默（沿用）。

### AC7 — 「＋」快速加购与规格弹层（与 8.2 共用） `[L0 widget]`（UI A1 主路径「点＋ → B9 规格弹层」）
1. 新建 `A/features/shop/presentation/widgets/sku_picker_sheet.dart`：`Future<void> showSkuPickerSheet(BuildContext, WidgetRef, {required String productToken, required SkuPickerMode mode, String? entrySource})`，`mode ∈ addToCart | buyNow`。弹层内容（UI B9 / B10）：商品图、当前价、库存、「Dipilih: {规格}」、「Pilih Varian」规格 chip（售罄规格仍可点，与线上一致）、「Jumlah」数量步进器（`ShopStepper`，下限 1、上限 = SKU `remaining`，为 null 时不设客户端上限）、确认按钮（addToCart「Tambah ke keranjang」/ buyNow「Beli Sekarang」复用 `tokoBuyNow`）。**只有一个 SKU 时也弹**（为了选数量）。默认规格沿用详情页规则「有货 > 低价 > 后台顺序」（`product_detail_page_v2.dart:101-112` 的 `_defaultSku`，抽成共享函数）。
2. SKU 加 `sellable`（`ShopSku.sellable`，`domain/shop_product_detail.dart`，缺键按 true）：所选 SKU `sellable=false` → 确认按钮整宽灰「Belum bisa dijual」不可点（B5 的弹层形态；详情页底栏归 8.2）。
3. addToCart：游客 → 软登录浮层，登录后自动完成本次加购（沿用 `_onAddTapped` :596-616 规则）；已登录 → `cartProvider.add(skuToken, qty: n, entrySource:)` → 关弹层 + toast `cartAdded`「Ditambahkan ke keranjang」、购物车角标更新；失败 toast `cartStockError` / `cartGenericError`（沿用）。上报 `toko_add_to_cart_tapped{product_token, sku_token}`（沿用）。
4. buyNow：游客同上软登录；已登录 → `context.push('/shop/checkout?source=buy_now&sku={skuToken}&qty={n}')`（7.1 已支持），**不写购物车**；上报 `toko_buy_now_tapped{product_token, sku_token}`（沿用）。本 story 首页只用 addToCart；buyNow 供 8.2。

### AC8 — A6 / A7 搜索 `[L0 widget]` `[L2]`（FR-133.2、UI A6 / A7）
1. **A6**：`shop_search_page.dart` 输入框、清空、自动聚焦、300ms 防抖、两列结果、尾部「Sudah semua untuk saat ini」（`tokoNoMore`）照线上；结果卡换成 `ShopProductCard`（含「＋」与 Lagi promo）；`entrySource` 仍 `TOKO_SEARCH`（:142）。
2. **A7 无结果**：沿用两行（`search_off` +「Tidak ada produk yang cocok dengan “{kw}”.」`tokoSearchEmpty` 复用 + 第二句改「Coba kata lain.」）；其下放 `ShopRecoGrid`：有档案 → 同首页推荐块（栏头同首页）；无档案 / 游客 → 栏头「Mungkin kamu suka」、取 `GET /shop/products/top?limit=6`。
3. 本页取数恒 `category: null`（沿用测试「本页恒以 category=null 取数」）。

### AC9 — 品类入口落首页、复购清理 `[L0]`（FR-133.1、FR-136.1、D-17、D-20）
1. `/shop?category=xxx` → 首页（AC1.1）；健康记录品类行 `health_list_page.dart:426` 改为 `context.go('/shop')`（埋点 `triage_category_jump_tapped{record_type}` 不变）；运营推送 / 弹窗的 CATEGORY 目标 `ops_target_action.dart:110` **不改**（仍 `go('/shop?category=…')`，路由忽略参数即落首页，`test/ops/*` 断言不变）；详情售罄「看替代品」归 8.2。
2. 旧复购推送：`deep_link_routes.dart` `pushPayloadToLocation`（:69 起）加 `case 'REPURCHASE_FOOD_LOW': return '/shop';`（D-20：旧推送点开落 Toko 首页）。
3. 删除仅被 Toko v2 使用的复购代码：`repurchaseCardsProvider` 在本页的读取与 invalidate；`ProfileRecoZoneV2`（`repurchase_zones_v2.dart:248-460`，lib 内无引用）及其测试组随删——**`RepurchaseTriggerCardV2`（Diary 在用，`growth_archive_page.dart:581`）与 `repurchaseCardsProvider` 定义保留到 9.4**。`recommendationsProvider` 与 `Recommendations` 模型保留（FR-136.2）。

### AC10 — 埋点 `[L0]`（PRD §4、D-23、P-9）
1. 新事件（字面量）：`toko_coupon_claimed{coupon_id, source: home|banner|newbie}`、`toko_newbie_coupon_popup_shown{coupon_id}`、`toko_newbie_coupon_popup_tapped{coupon_id, action: register|close}`。`coupon_id` 一律传券**模板 token**（不可枚举，非用户券 token）。
2. **`_claimed` 不在 `test/analytics/v112_events_test.dart` 的 `allowedSuffixes`**（:375 起）→ 按该文件先例「名字由 PRD 定死，扩表、不改名」追加 `'_claimed'` 并写注释；不放宽正则。
3. 删除：本页 `toko_repurchase_card_shown` / `_tapped`。保留：`toko_tab_viewed`、`toko_product_shown`、`toko_profile_reco_tapped`、`toko_banner_shown` / `_tapped`（属性见 AC2.6）。
4. 新事件不进 `Analytics.appsflyerEvents`。

### AC11 — 文案 `[L0]`（PRD §6 A 组、P-10）
按 Dev Notes「文案表」两份 arb 同时加 / 改，`flutter gen-l10n`；标「复用」的引原 key；源码不硬编码用户可见字符串。

### AC12 — 回归与还原度 `[L0]` `[L2]`
1. `flutter analyze` 零警告、`flutter test` 全绿；按设计打破的断言迁移改写（见「测试」），不删、不 skip。
2. **还原度 ≥ 95%**（L2）：A1–A9 逐屏对照打分，记入 Completion Notes。

## Tasks / Subtasks

- [ ] **T0 开工核对**：2.2 / 6.1 / 6.2 接口字段与错误 `type`；7.1 的 `CouponCard` API；有出入先改本文件
- [ ] **T1 数据层**：`api_paths.dart`（`shopBanners`、`shopCouponsForYou`、`shopNewbieCampaign`、`meShopEntry`、`meCouponsClaim`、`shopProductsTop`）；`A/features/shop/coupon/data/coupon_repository.dart`（claim / forYou / newbieCampaign / entry + `CouponClaimFailure`）；`shop_repository.dart`（banners、top）；`ShopProductSummary.promo`；`Recommendations.profileSummary`；`ShopSku.sellable`；`AppPrefs.shownNewbieCampaigns`
- [ ] **T2 组件**：`shop_product_card.dart`、`shop_reco_grid.dart`、`sku_picker_sheet.dart`、`coupon/presentation/coupons_for_you_row.dart`、`coupon/presentation/newbie_popup.dart`、banner 轮播
- [ ] **T3 页面**：`toko_page_v3.dart`；删 v2；路由；搜索页改造
- [ ] **T4 清理**（AC9）：健康记录行、推送深链、`ProfileRecoZoneV2`
- [ ] **T5 埋点 / 文案**：`v112_events_test.dart` 扩 `_claimed`；arb
- [ ] **T6 测试** + `flutter analyze && flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（核 `dev_1.4.2` @ 2c507755）

| 位置 | 现状 |
|---|---|
| `A/features/shop/presentation/toko_page_v2.dart`（1174 行） | `TokoPageV2(initialCategory)` :54-57、:74、`didUpdateWidget` :85；banner 曝光 / 点击 :117-163（`position` 恒 1 :127、:143；品类 banner 切品类 :150-152）；`build` :166；下拉刷新 invalidate 复购 :236-237；banner 顶栏 `_BannerAppBar` :550；吸顶筛选 `_FilterBar` :403 / `_StickyFilterBar` :489；`_RestockRow` :286 / :811；`_ProfilePicksRail` 横滑 :985-1110（标题 `tokoRecoLabel` / `tokoTopSellersLabel` :1011-1013）；商品流 `ShopProductMasonry(entrySource: _selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY')` :311-315；`_pawcoinCapsule` :338；`_CartCapsule` :743 |
| `A/features/shop/presentation/shop_search_page.dart`（261 行） | 防抖 300ms :47；结果 `ShopProductMasonry(entrySource: 'TOKO_SEARCH')` :138-142；无结果 `_noResult` :174-191（`tokoSearchEmpty` + `tokoSearchNoResultHint`） |
| `A/features/shop/presentation/widgets/shop_product_masonry.dart` | 瀑布流卡片，曝光 `toko_product_shown` :104，点击 `?from=` :115。新页不再用；**文件保留**（是否删除由 9.4 收尾时按引用判断） |
| `A/features/shop/presentation/widgets/repurchase_zones_v2.dart`（460 行） | `RepurchaseTriggerCardV2` :50（Diary 用）、`ProfileRecoZoneV2` :248（lib 无引用） |
| `A/features/shop/domain/shop_banner.dart` | `ShopBannerTarget{none, product, category, external}`；`tappable` |
| `A/features/shop/data/shop_repository.dart` | `fetchBanner` :118（单张）；`shopBannerProvider` :267；`shopProductsProvider` :259（族键含 category / keyword） |
| `A/features/shop/data/shop_repurchase_repository.dart` | `recommendations()` :18、`cards()` :23、`dismiss` :31；`recommendationsProvider` :44、`repurchaseCardsProvider` :56 |
| `A/features/shop/domain/shop_repurchase.dart` | `Recommendations{degraded, missing, petName, items}` :100-139（无 `profileSummary`） |
| `A/core/router/app_router.dart` | `_tabRootPage` :204-211（`TokoPageV2(initialCategory: …)` :207）；`/profile/edit` :839 |
| `A/core/router/deep_link_routes.dart` | `/shop` 只能 `go`（:17-24）；`pushPayloadToLocation` :69 无电商类型 |
| `A/features/profile/presentation/health_list_page.dart` | 品类行 `context.go('/shop?category=$category')` :426 |
| `A/features/ops/domain/ops_target_action.dart` | CATEGORY → `OpsGoAction('/shop?category=$value')` :110（不改） |
| arb | `tokoSearchHint`「Cari produk atau merek」、`tokoSearchNoResultHint`「Coba kata lain, atau telusuri per kategori.」、`tokoRecoLabel`「Pilihan buat {petName}」、`tokoTopSellersLabel`「Paling banyak dibeli」 |

### UI 逐屏要点（A1–A9）

| 屏 | 要点 |
|---|---|
| A1 已登录 | 浅紫柔光顶部、「Toko」墨色、右上 PawCoin 白底紫字胶囊 + 购物车；搜索框白底 + 浅紫到蜜桃渐变描边；依次 banner → 给你的券（直接领、右上进我的券）→ 为宠物推荐（浅紫实色底块，依据只在栏头说一次）→ 全部商品（白卡片、浅紫图底、Lagi promo 角标、紫色价格、实心「＋」热区 44）。11-05：推荐由横滑改三列网格（≤6 即两行，不再有被屏幕边切开的卡）；推荐卡只留图、名称、价格、「＋」；给你的券两张占满一行、其余左滑；各段间距收紧；删搜索框下利益点 / 满额免运提示条；品类筛选下线；补货卡与建档引导条删除 |
| A2 无 banner | 整块不渲染，推荐栏直接接在搜索框下 |
| A3 新人券 | 不再弹窗；「给你的券」置顶一张紫底券，标「新人专享」，按钮位「已到账」（自动发、不用领）；点券面进「我的券」 |
| A4 游客弹窗 | 有新人券活动时游客进 Toko 弹一次（同设备每个活动一次）；主按钮「注册领取」进注册，右上关闭后不再弹；底下游客态：右上「登录」、无推荐栏 |
| A5 banner 领券 | 点「领券」类 banner 即领取，原地提示「已领取，可在我的券查看」；已领过「你已领取过这张券」 |
| A6 搜索结果 | 搜索本版不优化：输入框、清空、自动聚焦、300ms 防抖、两列结果、尾部「已经全部显示」照线上；只换成与首页一致的商品卡（含「＋」） |
| A7 无结果 | 沿用线上两行，第二句改「换个词试试」；下方放推荐（规则同 C6：有档案用为宠物推荐、栏头同首页；无档案「你可能喜欢」取运营权重前 6）；卡片同首页可「＋」 |
| A8 加载失败 | 商品流失败卡 +「重试」，banner 照常；翻页失败静默 |
| A9 未填体重 | 推荐段不套卡片，与其他段靠留白分组；栏头「小狗 · 成年 · 体重未填」，卡片底部一条浅紫提示条「填一下 Mochi 的体重，推荐更准 ›」进编辑宠物档案页；推荐只按物种与年龄段算；规则不变（只用默认宠物、≤6、游客与未建档不显示、离世 / 手术记录整栏隐藏） |

### 文案表（PRD §6 A 组；新 key 两份 arb 都加）

| 屏 | key（建议） | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| A1 | `tokoRecoHeader` | Untuk {pet} | For {pet} | 见待确认 1（UI 写「Pilihan untuk」） |
| A1 | `tokoRecoSpeciesSize` | {species} {size} | {size} {species} | 依据行首段模板 |
| A1 | `tokoRecoSpeciesDog` / `tokoRecoSpeciesCat` | Anjing / Kucing | dog / cat | §6 示例「{Anjing} {kecil}」/「{Small} {dog}」 |
| A1 | `tokoRecoSizeSmall` / `Medium` / `Large` | kecil / sedang / besar | Small / Medium / Large | 见待确认 2（词表来源） |
| A1 | `tokoRecoAgePuppy` / `Adult` / `Senior` | muda / dewasa / senior | young / adult / senior | 沿用推荐理由印尼语词表（`ProfileRecommendationService.java:188-194`） |
| A9 | `tokoRecoWeightMissing` | berat belum diisi | weight not added | 接在依据后 |
| A9 | `tokoRecoAddWeight` | Isi berat badan {pet} biar pilihannya lebih pas | Add {pet}'s weight for better picks |  |
| A1 | `tokoAllProducts` | Semua produk | All products |  |
| A1 | `tokoProductCount` | {n} produk | {n} products |  |
| A1 | `tokoPromoBadge` | Lagi promo | On promo | 不写金额 |
| A3 | `couponNewbieBadge` | KHUSUS PENGGUNA BARU | NEW USERS ONLY |  |
| A3 | `couponNewbieAdded` | Sudah masuk | Added |  |
| A4 | `couponNewbiePopupTitle` | Daftar sekarang, dapat kupon Rp {amount} | Sign up now and get a Rp {amount} coupon |  |
| A4 | `couponNewbiePopupSub` | Khusus pengguna baru · min. belanja Rp {min} | New users only · min. spend Rp {min} |  |
| A4 | `couponNewbiePopupCta` | Daftar & klaim | Sign up & claim |  |
| A5 | `couponClaimedToast` | Kupon diklaim. Cek di Kupon Saya | Coupon claimed. Find it in My coupons |  |
| A5 / G7 | `couponAlreadyClaimed` | Kamu sudah klaim kupon ini | You've already claimed this coupon | 本 story 首建；8.2 / 9.3 复用 |
| G7 | `couponSoldOut` | Kupon sudah habis | This coupon has run out | 领券失败共用（UI B8 说明），本 story 首建 |
| A7 | `tokoSearchNoResultHint`（**改值**） | Coba kata lain. | Try a different word. | 去掉「按品类逛」 |
| A1 | `tokoSearchHint`（**改值**） | Cari makanan, obat, camilan… | Search food, medicine, treats… |  |
| A1 | `couponForYouTitle` | Kupon buat kamu | Coupons for you | §6 备注「新模块，待拍板」，见待确认 5 |
| A1 / G1 | `couponMyCoupons` | Kupon Saya | My coupons | 9.3 页标题复用 |
| A1 / B1 | `couponClaim` | Klaim | Claim | 8.2 复用 |
| A1 | `couponClaimedShort` | Diklaim | Claimed |  |
| C6 / A7 | `tokoMightLike` | Mungkin kamu suka | You might like | 本 story 首建，8.3 复用 |
| B9 | `tokoAddToCartConfirm` | Tambah ke keranjang | Add to cart | 规格弹层确认键（本 story 首建弹层，8.2 复用） |
| B9 | `tokoQuantity` | Jumlah | Quantity |  |
| B9 | `tokoSelectedVariant` | Dipilih: {x} | Selected: {x} |  |
| B5 | `tokoNotSellable` | Belum bisa dijual | Not available yet | 弹层 / 详情底栏共用 |
| 复用 | `tokoSearchEmpty`、`tokoNoMore`、`tokoLoadFailed`、`commonRetry`、`cartAdded`、`cartStockError`、`cartGenericError`、`tokoBuyNow`、`tokoChooseVariant`（「Pilih Varian」，开工核对原文）、`tokoPawcoinCapsule`、`tokoTitle` | — | — | 原文不改 |

`tokoRecoLabel` / `tokoTopSellersLabel` 新页不再引用，**不删**（9.4 收尾统一清理无引用电商 key）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 规格弹层（B9 / B10）在本 story 建，因为首页「＋」就要用；8.2 详情复用并补 B 组测试。
2. `/me/coupons` 本 story 先挂占位页，9.3 替换。
3. 运营推送的 CATEGORY 目标不改代码（路由忽略参数即落首页），避免动 `test/ops/*`；健康记录行改 `go('/shop')`。
4. banner 自动轮播间隔 4 秒（稿与 PRD 未定，取常见值）。
5. `TOKO_HOME` 目标埋点值 `toko_home`（PRD 只说加「领券」取值，品类跳转改落首页后旧 `category_list` 不再出现）。

### 实现提示

- 轮播：`PageController` + `Timer.periodic(4s)`；`onPageChanged` 里记当前页并为该张做曝光判定；`VisibilityDetector` 现有实现若是自写的 `LayoutBuilder` 露出比例（`_onBannerFraction`），沿用其思路对当前页计算。
- `CouponClaimFailure.from(DioException)`：`typeSlug` 后缀 `already-claimed` / `sold-out` / `not-in-period` / 其他。
- `AppPrefs` 加 `Set<String> shownNewbieCampaigns`（`getStringList`），并在 `_pruneRemovedKeys` 之外，不影响旧键。
- `/me/shop/entry` 去重：模块级 `DateTime? _lastEntryCall`，60 秒内跳过；切换账号（`resetUserScopedCaches`）时清零。
- `ShopRecoGrid` 的「＋」与首页商品卡同一个 `showSkuPickerSheet`。

### 架构约束

- `/shop` 一律 `go`；新页 / 组件不得 `push('/shop')`。
- App 不渲染后端显示串（推荐依据本地拼）；不展示推荐理由。
- 对外标识：券用 `couponToken`，埋点 `coupon_id` 也是 token，不用自增 id。
- 不引新依赖（轮播用 `PageView`）；不对整文件 `dart format`。

### 与前后 story 的边界

- 7.1：`CouponCard` 已建；结算页 BUY_NOW 路由已建。
- 8.2：详情页用本 story 的 `showSkuPickerSheet`、`couponClaim`、`couponAlreadyClaimed`、`couponSoldOut`、`tokoNotSellable`；售罄「看替代品」改落首页在 8.2 做。
- 8.3：空购物车用 `ShopRecoGrid`（有档案推荐 / 无档案 `tokoMightLike` + top 6）。
- 9.3：替换 `/me/coupons` 占位；兑换码失败文案复用 `couponAlreadyClaimed` / `couponSoldOut`。
- 9.4：Diary 复购卡、`repurchaseCardsProvider`、复购 arb key、`ShopProductMasonry` 是否删除。

### 测试

**L0（云端可跑）**
- `test/shop/toko_page_v3_test.dart`（新，取代 `toko_page_v2_test.dart`，后者随 v2 删除；用例迁移）：
  - 原样迁移：布局不溢出（411dp、1.3 倍字号、320dp）；`minPrice` 为 null 显占位不显 Rp 0；价格千分位；游客不触发登录引导、右上「Masuk」、搜索入口可用；点搜索 → `/shop/search`；本页取数恒不带关键词；触底预加载（竖向触发 / 横向不触发 / 有 banner 时仍能翻页）。
  - **按设计改写**：「顶栏前景随 tone」「banner 作顶栏背景、遮罩、胶囊压图」「筛选行吸顶」「四个品类 chip」「显式全部标签」组 → 改为「顶栏前景恒墨色、无品类 chip、页面中不存在 `_FilterBar`、取数族键 `category == null`」与「banner 在搜索框下方按比例不裁切（原 bug 600 断言改到轮播图）」。
  - 新增：轮播（多张自动翻页、手动滑、页码点、1 张不轮播不画点、`TickerMode` 关时暂停）；A2 空数组不渲染；各 `targetType` 跳转；A5 领券成功 / 已领 / 领完 toast 与埋点；A3 for-you（游客不渲染、空不渲染、Klaim → Diklaim、新人券置顶「Sudah masuk」点券面进 `/me/coupons`）；A4（首次弹、记 prefs 后不再弹、关闭 / 注册两种 action 埋点、204 不弹）；`/me/shop/entry` 60 秒去重与 `newbie` 埋点；A1 / A9 栏头拼接（ID / EN 两种语序、size / age 缺省、weightMissing、提示条跳 `/profile/edit`）；无档案首页不显示推荐块；Lagi promo 角标仅 `promo=true`；A8 失败卡 + 重试；`?category=OBAT_VITAMIN` 打开仍是首页且无报错。
- `test/shop/toko_banner_tracking_test.dart`（改）：「点品类 banner → tapped(category_list) 并切到该品类」**按设计改写**为「旧 `CATEGORY` 值解析为 none、不可点」与「`TOKO_HOME` → tapped(toko_home) 留在首页」；`position` 断言改为实际位次（第 2 张露出报 `position: 2`）；其余用例迁到 v3 页面。
- `test/shop/cart_attribution_test.dart`（改）：「Toko 卡片：全部精选与品类页是两个不同的入口」读源码的断言**按设计改写**为读 `toko_page_v3.dart` 并断言恒用 `'TOKO_ALL_FEATURED'`（品类入口已下线）。
- `test/shop/epic1_analytics_test.dart`（改）：`toko_tab_viewed` + `toko_product_shown(zone)` 改到 v3；「Epic 1 声明的四个事件在源码里确实存在」保持绿。
- `test/shop/repurchase_zones_v2_test.dart`（改）：删去 `ProfileRecoZoneV2` 相关组（「档案推荐区：泛推荐不得伪装成个性化」「降级态网格」「降级推荐区 · 411dp」）——这些用例对应的组件已按 FR-136 / D-17 删除，**在 Completion Notes 逐条列出被移除的用例名与原因**；触发卡相关用例保留到 9.4。`test/profile/growth_archive_test.dart:76` 的 `find.byType(ProfileRecoZoneV2)` 断言改为不引用该类型（改为断言 Diary 页不出现推荐网格 `ShopRecoGrid`）。
- `test/shop/shop_search_page_test.dart`（改）：「无结果时报关键词，并给下一步动作」断言第二句改为「Coba kata lain.」；新增 A7 推荐（有档案 / 无档案 top 6）。
- `test/shop/consult_shop_boundary_test.dart`（改）：`:237` 期望 `'/shop?category=OBAT_VITAMIN'` **按设计改写**为 `'/shop'`；「跳转不带商品」断言保留。
- `test/shop/sku_picker_sheet_test.dart`（新）：单 SKU 也弹、默认规格规则、步进器上限、`sellable=false` 置灰、addToCart 成功 toast 与角标、游客软登录后自动加购、buyNow 路由 `source=buy_now&sku=…&qty=…` 且未调 `cartProvider.add`。
- `test/shop/shop_product_card_test.dart`（新）：角标、占位价、「＋」热区 ≥ 44。
- `test/shared/deep_link_routes_test.dart`（如已存在则补；否则新建）：`REPURCHASE_FOOD_LOW` → `/shop`。
- `test/analytics/v112_events_test.dart`：扩 `_claimed` 后全绿；`test/l10n/microcopy_rules_test.dart` 绿。
- 跑：`cd petgo_app && flutter analyze && flutter test`。

**L1（本地 / stag）**：stag 造 3 张 banner（商品 / 领券 / 外链）验证轮播与跳转；造 CLAIM 券与新人券验证 for-you、新号首次进入发券；游客弹窗一次性。
**L2（本地模拟器）**：A1–A9 逐屏还原度 ≥ 95%，记入 Completion Notes。

> 云端只能跑 L0：Completion Notes 必须写「**L1/L2 待本地验收**」。

### Project Structure Notes

- 新增：`A/features/shop/presentation/toko_page_v3.dart`、`presentation/widgets/shop_product_card.dart`、`widgets/shop_reco_grid.dart`、`widgets/sku_picker_sheet.dart`、`widgets/shop_banner_carousel.dart`、`A/features/shop/coupon/data/coupon_repository.dart`、`coupon/presentation/coupons_for_you_row.dart`、`coupon/presentation/newbie_popup.dart`。
- 删除：`toko_page_v2.dart`、`test/shop/toko_page_v2_test.dart`（用例迁入 v3）、`ProfileRecoZoneV2`（同文件内类）。
- 改动：`app_router.dart`、`deep_link_routes.dart`、`api_paths.dart`、`shop_repository.dart`、`shop_repurchase_repository.dart`、`domain/shop_banner.dart`、`domain/shop_product.dart`、`domain/shop_product_detail.dart`、`domain/shop_repurchase.dart`、`shop_search_page.dart`、`health_list_page.dart`、`core/storage/prefs.dart`、`l10n/app_*.arb`、`test/analytics/v112_events_test.dart`。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.2/epics-v1.4.2-shop.md#Story 8.1]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md#3.4（券的入口、新人与新人券、新人券细节）；3.6 FR-132；3.7 找商品；3.9 FR-136；§4；§6 A 组]
- [Source: _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md#D-5、D-6、D-16、D-17、D-20、D-23、P-4、P-9、P-10]
- [Source: _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md#就绪度评审直接更正 I10、m3]
- [Source: _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html#A1–A9、B9 / B10（弹层）]

## 待确认 / 发现的规划冲突

1. **推荐栏头文案三处不一致**：PRD §6 A1 行「Untuk {pet}」；同表 C6 备注写「有档案时用『Pilihan untuk {pet}』」；UI 稿画「Pilihan untuk Mochi」；线上 key `tokoRecoLabel`「Pilihan buat {petName}」。按「§6 表行优先」取 A1 行「Untuk {pet}」，需设计侧统一。
2. **体型词表来源**：PRD 说「词表沿用推荐理由的印尼语词表」，但后端推荐理由的体型词是「< 10 kg / 10–25 kg / > 25 kg」（`ProfileRecommendationService.java:197-203`），而 §6 示例是「kecil」。本 story 体型用 kecil / sedang / besar（按 §6 示例），年龄用后端词表 muda / dewasa / senior；需设计侧确认。
3. **「{n} produk」件数**：商品列表分页响应没有总数字段（6.1 未加）。本 story 只在响应带 `total` 时显示件数，否则只显示「Semua produk」。
4. **领券其他失败无文案**：`coupon-not-in-period`（banner 已过滤，理论少见）与网络错误在 §6 没有领券场景的文案（G7「Kode ini tidak dalam masa berlaku」是兑换码语境）。本 story：`not-in-period` 静默刷新列表（该券随之消失），网络错误不弹 toast、按钮恢复可点。
5. **「Kupon buat kamu」模块 §6 标「新模块，待拍板」**：PRD 11-05 正文已拍板该模块存在，文案按 §6 实现。
6. **`_claimed` 词尾扩表**：`toko_coupon_claimed` 过不了命名守卫词尾白名单，按先例扩表。
7. **快速加购范围**：UI 说首页「＋」开 B9 弹层，但 epics 8.1 没把弹层列入范围、8.2 才有 B9。为免前向依赖，弹层在本 story 建。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev），依据决策日志 S-0 ~ S-9、PRD §6、架构增量、UI 定稿与 `dev_1.4.2` @ 2c507755 代码现状。
