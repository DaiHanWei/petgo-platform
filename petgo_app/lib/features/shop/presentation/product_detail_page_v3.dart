/// 商品详情页 v3（v1.4.2 shop Story 8.2 · UI B1–B15，FR-127 / FR-133 / FR-172）。取代 `product_detail_page_v2.dart`。
///
/// ## 上部顺序（PRD / epics，C-11：PRD 与 UI 冲突以 PRD 为准）
/// 图片 → 标题 → 价格 → 可领的券 → 送达地区 → 运费 → 免运进度；其后规格行「Varian ›」→ 评价区 → 退货政策 → Detail Produk。
///
/// ## 从 v2 原样继承的规则（版式变了，规则一条不改）
/// 1. 默认规格「有货 > 低价 > 后台顺序」（[defaultShopSku]，与规格弹层同一函数）；全部售罄不默认选中。
/// 2. `Sisa {n}` 取真实剩余数（FR-95），>20 只说有货。
/// 3. 开封不退在本页明示（FR-104 三处的第 1 处，三种政策共用 key）。
/// 4. 售罄态：图上蒙层、价格转灰 + harga terakhir、主按钮**置灰不消失**、不写「segera」。
/// 5. 游客加购 / 立即购买走软登录浮层，登录后自动完成本次动作、停留原页（不丢意图）。
///
/// ## v3 新增
/// - 「Lihat Alternatif」改为 `go('/shop')`（品类入口 D-17 下线；品类未知也显示）—— 8.1 留下的 v2:549 品类跳转在此收口。
/// - 立即购买直达结算 `?source=buy_now&sku&qty`，**不写购物车**（FR-128.5；删除 v2「先加购 buyNow:true 再跳」）。
/// - 运费 / 免运进度 / 可售 / 评价开关全取服务端（P-1、D-10），App 不算差额。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/storage/prefs.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/case_image_viewer.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_guide_controller.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../address/data/address_repository.dart';
import '../address/domain/shipping_address.dart';
import '../coupon/data/coupon_repository.dart';
import '../coupon/domain/shop_coupon.dart';
import '../coupon/presentation/product_coupons_sheet.dart';
import '../data/cart_repository.dart';
import '../data/shipping_estimate_repository.dart';
import '../data/shop_repository.dart';
import '../data/shop_review_repository.dart';
import '../domain/checkout_preview.dart' show CouponKind;
import '../domain/shipping_estimate.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart';
import '../domain/shop_review.dart';
import 'widgets/delivery_area_sheet.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_review_tile.dart';
import 'widgets/shop_surface.dart';
import 'widgets/sku_picker_sheet.dart';

/// 图区高度（UI B1：266 → 300，且不裁切）。
const double kPdpGalleryHeight = 300;

/// 内容底部给悬浮胶囊坞让位。
const double kPdpDockClearance = 100;

class ProductDetailPageV3 extends ConsumerStatefulWidget {
  const ProductDetailPageV3({super.key, required this.token, this.entrySource});

  final String token;

  /// 归因来源（Story 3.10 归因链起点）。深链进来时为 null —— **不编默认值**。
  final String? entrySource;

  @override
  ConsumerState<ProductDetailPageV3> createState() => _ProductDetailPageV3State();
}

/// 送达地（AC2.1 优先级的解析结果）。
class _Destination {
  const _Destination({required this.label, this.addressToken, this.regionId, this.selectedRegionId})
      : pending = false;

  /// 已登录、地址列表还在加载：先不解析（8.2 复审 #6）—— 否则会先按本地地区询价、地址回来再切换。
  const _Destination.pending()
      : label = '—',
        addressToken = null,
        regionId = null,
        selectedRegionId = null,
        pending = true;

  final bool pending;

  final String label;
  final String? addressToken;
  final int? regionId;

  /// B6 里打勾的那一区（只有地区来源才有）。
  final int? selectedRegionId;
}

class _ProductDetailPageV3State extends ConsumerState<ProductDetailPageV3> {
  /// 用户手动选的规格（弹层里点的 chip / 确认的规格）。null → [defaultShopSku]。
  String? _selectedSkuToken;

  /// 本页本次在 B6 选的地区（优先级最高，AC2.1）。
  ShopDeliveryArea? _pickedArea;

  int _galleryIndex = 0;

  /// 加购请求在途（bug 516：胶囊坞加购钮转圈、两按钮都不可点，防重复提交）。
  bool _adding = false;

  /// 切规格 / 地区时先显示上一次结果，避免运费行整行闪烁。
  ShippingEstimate? _lastEstimate;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_detail_viewed', {'product_token': widget.token});
  }

  ShopSku? _effectiveSku(ShopProductDetail d) {
    if (d.isSingleSku) return d.skus.first;
    if (_selectedSkuToken != null) {
      for (final s in d.skus) {
        if (s.token == _selectedSkuToken) return s;
      }
    }
    return defaultShopSku(d.skus);
  }

  /// 售罄：生效规格售罄，或（全部售罄时无生效规格）所有规格售罄。
  bool _isSoldOut(ShopProductDetail d) {
    final sku = _effectiveSku(d);
    if (sku != null) {
      final out = sku.stockStatus == StockStatus.outOfStock;
      if (out) _reportOutOfStock(sku.token);
      return out;
    }
    final allOut =
        d.skus.isNotEmpty && d.skus.every((s) => s.stockStatus == StockStatus.outOfStock);
    if (allOut) {
      for (final s in d.skus) {
        _reportOutOfStock(s.token);
      }
    }
    return allOut;
  }

  final Set<String> _outOfStockReported = {};

  /// 售罄曝光，按 SKU 去重（build 期间调用，不去重会随重建反复上报）。
  void _reportOutOfStock(String skuToken) {
    if (!_outOfStockReported.add(skuToken)) return;
    Analytics.capture('toko_out_of_stock_shown', {
      'product_token': widget.token,
      'sku_token': skuToken,
    });
  }

  // ---------------------------------------------------------------- 送达地

  /// AC2.1：本页本次选的地区 > 已登录的默认地址 > 本地记住的地区 > 无。
  _Destination? _destination(AppLocalizations l10n) {
    String areaLabel(String kec, String kota) =>
        l10n.tokoShipToArea(kota.isEmpty ? kec : '$kec, $kota');
    final picked = _pickedArea;
    if (picked != null) {
      return _Destination(
          label: areaLabel(picked.kecamatan, picked.kota),
          regionId: picked.kecamatanId,
          selectedRegionId: picked.kecamatanId);
    }
    if (ref.watch(authControllerProvider).isLoggedIn) {
      final addrAsync = ref.watch(addressListProvider);
      if (addrAsync.isLoading && !addrAsync.hasValue) return const _Destination.pending();
      final addresses = addrAsync.maybeWhen(
          data: (v) => v, orElse: () => const <ShippingAddress>[]);
      final def = addresses.where((a) => a.isDefault).firstOrNull;
      if (def != null) {
        return _Destination(
            label: areaLabel(def.kecamatan, def.kotaKabupaten), addressToken: def.token);
      }
    }
    final stored = ref.watch(shopDeliveryAreaProvider).asData?.value;
    if (stored != null) {
      return _Destination(
          label: areaLabel(stored.kecamatan, stored.kota),
          regionId: stored.kecamatanId,
          selectedRegionId: stored.kecamatanId);
    }
    return null;
  }

  ShippingEstimateQuery? _estimateQuery(ShopSku? sku, _Destination? dest) {
    // 🔴 无收货地不询价：后端对「既无地址也无地区」只回 NO_DESTINATION、不带免运进度（story 待确认 6）。
    // 🔴 不可售 SKU 不询价：后端 422 sku-not-sellable，底栏已置灰。
    if (sku == null || !sku.sellable || dest == null || dest.pending) return null;
    return (
      product: widget.token,
      sku: sku.token,
      address: dest.addressToken,
      region: dest.addressToken == null ? dest.regionId : null,
    );
  }

  Future<void> _openAreaSheet(ShopProductDetail d, _Destination? dest) async {
    final area = await showDeliveryAreaSheet(context, selectedKecamatanId: dest?.selectedRegionId);
    if (area == null || !mounted) return;
    setState(() => _pickedArea = area);
    // 只记本地，不生成收货地址（AC2.3）。
    await ref.read(shopDeliveryAreaProvider.notifier).select(area);
    if (!mounted) return;
    // AC2.4：取该次估运费结果的 serviceable；询价失败 / 未询价时取区域树里该区的。
    bool? serviceable;
    // 本页刚选的地区优先级最高 → 目的地就是它（不在回调里 ref.watch）。
    final q = _estimateQuery(_effectiveSku(d),
        _Destination(label: '', regionId: area.kecamatanId, selectedRegionId: area.kecamatanId));
    if (q != null) {
      try {
        serviceable = (await ref.read(shippingEstimateProvider(q).future)).serviceable;
      } catch (_) {}
    }
    serviceable ??= area.serviceable;
    Analytics.capture('toko_delivery_area_selected', {'serviceable': serviceable ?? false});
  }

  // ---------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopProductDetailProvider(widget.token));

    if (async.hasError && async.error is ShopProductDelisted) {
      return _delisted(l10n);
    }
    // 🔴 loading / error 态自带 AppBar（正常态返回箭头画在图区里，不画就没有出口）。
    final placeholderBar = async.hasValue ? null : ShopAppBar(title: l10n.tokoTitle);
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: placeholderBar,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.tokoDetailLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(shopProductDetailProvider(widget.token)),
        ),
        data: (d) => Stack(
          children: [
            Positioned.fill(child: _content(l10n, d)),
            Positioned(
              left: 16,
              right: 16,
              bottom: 12 + MediaQuery.paddingOf(context).bottom,
              child: _dock(l10n, d),
            ),
          ],
        ),
      ),
    );
  }

  /// B13 已下架：顶栏只有返回、`remove_shopping_cart`、两句文案、「Ke Toko」→ `go('/shop')`。**不走通用重试**。
  Widget _delisted(AppLocalizations l10n) => Scaffold(
        key: const ValueKey('pdpDelisted'),
        backgroundColor: ShopColors.bg,
        appBar: const ShopAppBar(title: ''),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.remove_shopping_cart, size: 56, color: ShopColors.text4),
                const SizedBox(height: 16),
                Text(l10n.tokoDelistedTitle,
                    textAlign: TextAlign.center,
                    style: AppTypography.title.copyWith(fontSize: 16, color: ShopColors.text)),
                const SizedBox(height: 6),
                Text(l10n.tokoDelistedBody,
                    textAlign: TextAlign.center,
                    style: AppTypography.body.copyWith(color: ShopColors.text3)),
                const SizedBox(height: 20),
                ShopButton(
                  key: const ValueKey('pdpDelistedCta'),
                  label: l10n.tokoDelistedCta,
                  variant: ShopButtonVariant.purple,
                  // `/shop` 是 shell 分支根，一律 go（push 会二次构建 StatefulShellRoute）。
                  onTap: () => context.go('/shop'),
                ),
              ],
            ),
          ),
        ),
      );

  Widget _content(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _effectiveSku(d);
    final policy = sku?.returnPolicy ?? d.returnPolicy;
    final soldOut = _isSoldOut(d);
    final dest = _destination(l10n);
    return ListView(
      key: const ValueKey('pdpList'),
      padding: EdgeInsets.zero,
      children: [
        _gallery(l10n, d, soldOut),
        const SizedBox(height: 12),
        _titleCard(l10n, d, sku, soldOut),
        _couponCard(l10n),
        _shippingCard(l10n, d, sku, dest),
        if (d.skus.isNotEmpty) _variantCard(l10n, d, sku),
        _reviewCard(l10n, d),
        _returnPolicyCard(l10n, policy),
        if (d.detailHtml != null) _detailCard(l10n, d),
        const SizedBox(height: kPdpDockClearance),
      ],
    );
  }

  // ---------------------------------------------------------------- 图区

  /// 图区 300 高、**不裁切**（`BoxFit.contain`）。售罄时整图盖蒙层。
  Widget _gallery(AppLocalizations l10n, ShopProductDetail d, bool soldOut) {
    final images = <String?>[
      if (d.mainImageUrl != null) d.mainImageUrl,
      ...d.galleryUrls,
    ];
    final pages = images.isEmpty ? <String?>[null] : images;
    final topInset = MediaQuery.paddingOf(context).top;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Container(
        key: const ValueKey('pdpGallery'),
        height: kPdpGalleryHeight + topInset,
        color: ShopColors.surface,
        child: Stack(
          children: [
            Positioned.fill(
              top: topInset,
              child: PageView.builder(
                itemCount: pages.length,
                onPageChanged: (i) => setState(() => _galleryIndex = i),
                itemBuilder: (c, i) {
                  final url = pages[i];
                  final img = ShopImage(
                    url: url,
                    size: kPdpGalleryHeight,
                    fillWidth: true,
                    radius: 0,
                    fit: BoxFit.contain,
                  );
                  if (url == null || url.isEmpty) return img;
                  return Semantics(
                    button: true,
                    label: l10n.tokoViewImageFull,
                    child: GestureDetector(
                      key: const ValueKey('pdpGalleryZoom'),
                      onTap: () {
                        // 🔴 传原图 URL；下标在过滤后的表里查。
                        final srcs = [
                          for (final u in pages)
                            if (u != null && u.isNotEmpty) u,
                        ];
                        showImageGalleryFullScreen(context,
                            srcs: srcs, initialIndex: srcs.indexOf(url));
                      },
                      child: img,
                    ),
                  );
                },
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              top: 0,
              height: topInset + 46,
              child: const IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: ShopColors.imageTopScrim,
                    ),
                  ),
                ),
              ),
            ),
            // ShopSoldOutOverlay 自带 Positioned.fill（整个图区含状态栏下方一并盖住，同 v2）。
            if (soldOut)
              ShopSoldOutOverlay(
                label: l10n.tokoOutOfStock,
                scrim: ShopColors.soldOutScrimDetail,
                labelSize: 17,
                radius: 0,
                // 🔴 到货时间必须是区间或不显示（禁用 segera）。
                subtitle: null,
              ),
            Positioned(
              left: kShopScreenEdge - kShopImageButtonInset,
              top: topInset + 8 - kShopImageButtonInset,
              child: ShopImageButton(
                icon: Icons.arrow_back_ios_new,
                semanticLabel: MaterialLocalizations.of(context).backButtonTooltip,
                onTap: () => context.pop(),
              ),
            ),
            Positioned(
              right: kShopScreenEdge - kShopImageButtonInset,
              top: topInset + 8 - kShopImageButtonInset,
              child: const _CartButton(),
            ),
            if (pages.length > 1)
              Positioned(
                right: kShopScreenEdge,
                bottom: 10,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                  decoration: BoxDecoration(
                    color: ShopColors.countdownScrim,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text('${_galleryIndex + 1}/${pages.length}',
                      style: const TextStyle(
                          fontFamily: ShopText.mono, fontSize: 10, color: ShopColors.surface)),
                ),
              ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 标题 + 价格

  /// 均分 / 条数：优先详情的 `reviewSummary`，旧后端缺失时回落 `/reviews`（story 待确认 2）。
  ProductReviews? _reviewSummary(ShopProductDetail d) =>
      d.reviewSummary ??
      ref.watch(productReviewsProvider(widget.token)).maybeWhen(data: (r) => r, orElse: () => null);

  Widget _titleCard(AppLocalizations l10n, ShopProductDetail d, ShopSku? sku, bool soldOut) {
    final reviews = _reviewSummary(d);
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    final price = sku?.price ?? d.minPrice;
    final meta = <String?>[
      // 🔴 无评分不显示 0；无评价不显示条数。
      if (reviews?.averageRating != null && reviews!.total > 0)
        '★ ${reviews.averageRating!.toStringAsFixed(1)}',
      if (reviews != null && reviews.total > 0) l10n.tokoReviewsCount(reviews.total),
      if (sku != null && !soldOut) _stockLabel(l10n, sku),
    ].whereType<String>().toList();

    return ShopCard(
      key: const ValueKey('pdpTitleCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(d.name, key: const ValueKey('pdpTitle'), style: ShopText.productNameDetail),
          const SizedBox(height: 8),
          Row(
            key: const ValueKey('pdpPrice'),
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                price == null ? l10n.tokoPriceUnavailable : formatIdr(price),
                style: ShopText.priceHero.copyWith(
                  fontSize: soldOut ? 24 : 26,
                  color: soldOut ? ShopColors.text4 : ShopColors.accent,
                ),
              ),
              if (soldOut) ...[
                const SizedBox(width: 8),
                Text(l10n.tokoLastPrice, style: ShopText.body.copyWith(color: ShopColors.text4)),
              ],
            ],
          ),
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(meta.join('  ·  '),
                key: const ValueKey('pdpMeta'), style: ShopText.meta.copyWith(fontSize: 10.5)),
          ],
          // PawCoin 行：已登录才显示，售罄时不渲染（买不到就不谈怎么付）。
          if (!soldOut && loggedIn) ...[
            const SizedBox(height: 10),
            _pawcoinRow(l10n),
          ],
        ],
      ),
    );
  }

  String? _stockLabel(AppLocalizations l10n, ShopSku sku) {
    final n = sku.remaining;
    if (n == null) {
      return sku.stockStatus == StockStatus.lowStock ? l10n.tokoLowStockNoCount : null;
    }
    // 🔴 n 是后端给的真实剩余数，不虚构（FR-95）。
    return n <= 20 ? l10n.tokoLowStock(n) : l10n.tokoStockAvailable;
  }

  /// PawCoin 行。🔴 文案必须是「部分」抵扣（混合支付永远有真钱那部分）。
  Widget _pawcoinRow(AppLocalizations l10n) {
    final balance = ref.watch(pawCoinProvider).maybeWhen(
          data: (s) => s.balance,
          orElse: () => null,
        );
    return Container(
      key: const ValueKey('pdpPawcoinRow'),
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
      decoration: BoxDecoration(
        color: ShopColors.purpleBg,
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
      ),
      child: Row(
        children: [
          const ShopCoinMark(),
          const SizedBox(width: 8),
          Expanded(
            child: Text(l10n.tokoPawcoinPartial,
                style: ShopText.cardTitle.copyWith(
                    fontSize: 11, fontWeight: FontWeight.w600, color: ShopColors.purpleText)),
          ),
          if (balance != null)
            Text(l10n.tokoPawcoinBalance(formatIdr(balance)),
                style: ShopText.badge.copyWith(fontSize: 10.5, color: ShopColors.purple)),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 可领的券（AC4）

  Widget _couponCard(AppLocalizations l10n) {
    final coupons = ref.watch(productCouponsProvider(widget.token)).maybeWhen(
        data: (v) => v, orElse: () => const <ShopCoupon>[]);
    // 空 / 失败 → 整行不画。
    if (coupons.isEmpty) return const SizedBox.shrink();
    return ShopCard(
      key: const ValueKey('pdpCouponRow'),
      padding: EdgeInsets.zero,
      child: ShopPressable(
        key: const ValueKey('pdpCouponRowTap'),
        onTap: () => showProductCouponsSheet(context, productToken: widget.token),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Text(l10n.checkoutCouponSection,
                  style: AppTypography.body
                      .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text)),
              const SizedBox(width: 10),
              Expanded(
                child: Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  clipBehavior: Clip.hardEdge,
                  children: [
                    for (final c in coupons.take(2))
                      Container(
                        key: ValueKey('pdpCouponTag_${c.couponToken}'),
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: ShopColors.purpleTagBg,
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          c.kind == CouponKind.freeShipping
                              ? l10n.checkoutCouponFreeShipping
                              : formatIdr(c.amount),
                          style: AppTypography.caption.copyWith(
                              color: ShopColors.purple, fontWeight: FontWeight.w600),
                        ),
                      ),
                  ],
                ),
              ),
              Text(l10n.couponClaim,
                  style: AppTypography.caption
                      .copyWith(color: AppColors.mint, fontWeight: FontWeight.w600)),
              const Icon(Icons.chevron_right, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 送达 / 运费 / 免运（AC2 / AC3）

  Widget _shippingCard(
      AppLocalizations l10n, ShopProductDetail d, ShopSku? sku, _Destination? dest) {
    final q = _estimateQuery(sku, dest);
    ShippingEstimate? est;
    var loading = false;
    if (q != null) {
      final a = ref.watch(shippingEstimateProvider(q));
      // 8.2 复审 #5：在途由 isLoading 驱动（重试 invalidate 后 AsyncValue 仍带旧值、isLoading=true），在途禁用「Coba lagi」。
      loading = a.isLoading;
      if (a.hasValue) {
        est = a.value;
        _lastEstimate = est;
      } else {
        est = _lastEstimate;
      }
    }

    final rows = <Widget>[
      _line(
        key: 'pdpShipTo',
        icon: Icons.location_on_outlined,
        label: l10n.tokoShipTo,
        value: dest?.label ?? l10n.tokoChooseArea,
        onTap: () => _openAreaSheet(d, dest?.pending == true ? null : dest),
      ),
    ];

    if (dest != null && dest.pending) {
      rows.add(_line(
          key: 'pdpShippingLoading',
          icon: Icons.local_shipping_outlined,
          label: l10n.tokoShippingLabel,
          value: '—'));
    } else if (dest == null) {
      // B2：先选地区。
      rows.add(_line(
        key: 'pdpShippingChooseArea',
        icon: Icons.local_shipping_outlined,
        label: l10n.tokoShippingLabel,
        value: l10n.tokoChooseAreaForShipping,
        muted: true,
      ));
    } else if (q != null) {
      rows.addAll(_shippingRows(l10n, q, est, loading));
    }

    return ShopCard(
      key: const ValueKey('pdpShippingCard'),
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(children: rows),
    );
  }

  List<Widget> _shippingRows(
      AppLocalizations l10n, ShippingEstimateQuery q, ShippingEstimate? est, bool loading) {
    final out = <Widget>[];
    if (est == null) {
      // 首次询价在途。
      out.add(_line(
          key: 'pdpShippingLoading',
          icon: Icons.local_shipping_outlined,
          label: l10n.tokoShippingLabel,
          value: '—'));
      return out;
    }
    final progress = est.freeShippingProgress;
    var showProgress = true;
    if (est.quoteFailed) {
      // B15：可重试；免运进度照常（按商品金额算，不依赖询价）。
      out.add(Padding(
        key: const ValueKey('pdpShippingFailed'),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            const Icon(Icons.local_shipping_outlined, size: 18, color: ShopColors.text3),
            const SizedBox(width: 8),
            Text(l10n.tokoShippingLabel, style: AppTypography.body.copyWith(color: ShopColors.text)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(l10n.tokoShippingLoadFailed,
                  style: AppTypography.caption.copyWith(color: ShopColors.text3)),
            ),
            ShopPressable(
              key: const ValueKey('pdpShippingRetry'),
              minSize: kShopMinTapTarget,
              onTap: loading ? null : () => ref.invalidate(shippingEstimateProvider(q)),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.refresh, size: 16, color: AppColors.mint),
                  const SizedBox(width: 2),
                  Text(l10n.commonRetry,
                      style: AppTypography.caption
                          .copyWith(color: AppColors.mint, fontWeight: FontWeight.w600)),
                ],
              ),
            ),
          ],
        ),
      ));
    } else if (est.serviceable == false) {
      // B3：橙色提示；免运进度不显示；底栏照常可加购。
      showProgress = false;
      out.add(Padding(
        key: const ValueKey('pdpShippingNotServiceable'),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            const Icon(Icons.local_shipping, size: 18, color: ShopColors.warnTitle),
            const SizedBox(width: 8),
            Expanded(
              child: Text(l10n.shopAreaNotServiceable,
                  style: AppTypography.body
                      .copyWith(color: ShopColors.warnTitle, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      ));
    } else if (est.serviceable == true && est.lowestFee != null) {
      out.add(_line(
        key: 'pdpShippingFee',
        icon: Icons.local_shipping_outlined,
        label: l10n.tokoShippingLabel,
        value: l10n.tokoShippingFrom(formatIdrAmount(est.lowestFee!)),
      ));
    } else {
      // NO_DESTINATION 等未知组合：当作未选地区。
      showProgress = false;
      out.add(_line(
        key: 'pdpShippingChooseArea',
        icon: Icons.local_shipping_outlined,
        label: l10n.tokoShippingLabel,
        value: l10n.tokoChooseAreaForShipping,
        muted: true,
      ));
    }

    if (showProgress && progress != null && progress.threshold > 0) {
      out.add(Padding(
        key: const ValueKey('pdpFreeShipProgress'),
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
        child: progress.remaining > 0
            ? Text(l10n.tokoFreeShipRemaining(formatIdrAmount(progress.remaining)),
                key: const ValueKey('pdpFreeShipRemaining'),
                style: AppTypography.caption.copyWith(color: ShopColors.purple))
            : Row(
                key: const ValueKey('pdpFreeShipReached'),
                children: [
                  const Icon(Icons.check_circle, size: 16, color: ShopColors.saving),
                  const SizedBox(width: 4),
                  Text(l10n.tokoFreeShipReached,
                      style: AppTypography.caption.copyWith(color: ShopColors.saving)),
                ],
              ),
      ));
    }
    return out;
  }

  Widget _line({
    required String key,
    required IconData icon,
    required String label,
    required String value,
    VoidCallback? onTap,
    bool muted = false,
  }) {
    final row = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Icon(icon, size: 18, color: ShopColors.text3),
          const SizedBox(width: 8),
          Text(label, style: AppTypography.body.copyWith(color: ShopColors.text)),
          const SizedBox(width: 10),
          Expanded(
            child: Text(value,
                key: ValueKey('${key}Value'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.end,
                style: AppTypography.caption.copyWith(
                    color: muted ? ShopColors.text4 : ShopColors.text2,
                    fontWeight: muted ? FontWeight.w400 : FontWeight.w600)),
          ),
          if (onTap != null) const Icon(Icons.chevron_right, size: 18, color: ShopColors.text4),
        ],
      ),
    );
    return onTap == null
        ? KeyedSubtree(key: ValueKey(key), child: row)
        : ShopPressable(key: ValueKey(key), onTap: onTap, child: row);
  }

  // ---------------------------------------------------------------- 规格行（AC6.1）

  Widget _variantCard(AppLocalizations l10n, ShopProductDetail d, ShopSku? sku) => ShopCard(
        padding: EdgeInsets.zero,
        child: _line(
          key: 'pdpVariantRow',
          icon: Icons.style_outlined,
          label: l10n.tokoVariantLabel,
          value: sku?.specName ?? '',
          onTap: () => _onDockTapped(l10n, d, SkuPickerMode.addToCart),
        ),
      );

  // ---------------------------------------------------------------- 评价（AC7）

  Widget _reviewCard(AppLocalizations l10n, ShopProductDetail d) {
    // 开关关（默认）→ 评价区不出现。
    if (!d.reviewListEnabled) return const SizedBox.shrink();
    final s = _reviewSummary(d);
    final total = s?.total ?? 0;
    final items = (s?.items ?? const <ShopReviewItem>[]).take(3).toList();
    if (total == 0 && items.isEmpty) {
      // B14：0 条。
      return ShopCard(
        key: const ValueKey('pdpReviews'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.tokoReviewsTitle, style: ShopText.sectionTitle),
            const SizedBox(height: 8),
            Text(l10n.tokoReviewsNone,
                key: const ValueKey('pdpReviewsNone'),
                style: AppTypography.caption.copyWith(color: ShopColors.text3)),
          ],
        ),
      );
    }
    return ShopCard(
      key: const ValueKey('pdpReviews'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(l10n.tokoReviewsHeader(total),
                  key: const ValueKey('pdpReviewsHeader'), style: ShopText.sectionTitle),
              if (s?.averageRating != null) ...[
                const SizedBox(width: 8),
                Text('★ ${s!.averageRating!.toStringAsFixed(1)}',
                    style: AppTypography.caption.copyWith(color: ShopColors.warnTitle)),
              ],
              const Spacer(),
              ShopPressable(
                key: const ValueKey('pdpReviewsSeeAll'),
                minSize: kShopMinTapTarget,
                onTap: () => context.push('/shop/products/${widget.token}/reviews'),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(l10n.tokoReviewsSeeAll,
                        style: AppTypography.caption.copyWith(color: AppColors.mint)),
                    const Icon(Icons.chevron_right, size: 16, color: AppColors.mint),
                  ],
                ),
              ),
            ],
          ),
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) const ShopDivider(),
            ShopReviewTile(review: items[i]),
          ],
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 退货政策 / 详情

  /// 开封不退明示（FR-104 第 1 处）。三种政策共用 key，与结算页、退货申请页同文案。
  Widget _returnPolicyCard(AppLocalizations l10n, ReturnPolicy policy) {
    final (title, body) = switch (policy) {
      ReturnPolicy.returnable => (l10n.tokoReturnableTitle, l10n.tokoReturnableBody),
      ReturnPolicy.noReturnAfterOpen =>
        (l10n.tokoNoReturnAfterOpenTitle, l10n.tokoNoReturnAfterOpenBody),
      ReturnPolicy.noReturn => (l10n.tokoNoReturnTitle, l10n.tokoNoReturnBody),
    };
    return ShopCard(child: ShopWarnBlock(title: title, body: body));
  }

  Widget _detailCard(AppLocalizations l10n, ShopProductDetail d) => ShopCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.tokoDetailSectionTitle, style: ShopText.sectionTitle),
            const SizedBox(height: 7),
            // 修复线 2026-11-13（实机反馈）：原 11 号字 + 1.75 倍行距，字太小、行距比字还显眼。
            Text(stripShopHtml(d.detailHtml!),
                style: ShopText.body.copyWith(fontSize: 13, height: 1.5)),
          ],
        ),
      );

  // ---------------------------------------------------------------- 悬浮胶囊坞（AC1.5 / AC5 / AC6）

  Widget _dock(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _effectiveSku(d);
    final soldOut = _isSoldOut(d);
    final Widget child;
    if (soldOut) {
      // 🔴 主按钮置灰但不消失；次出口回 Toko 首页（品类下线，品类未知也显示）。
      child = Row(
        children: [
          Expanded(
            child: ShopButton(
              key: const ValueKey('pdpSoldOutDisabled'),
              label: l10n.tokoOutOfStock,
              variant: ShopButtonVariant.disabled,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: ShopButton(
              key: const ValueKey('pdpSeeAlternatives'),
              label: l10n.tokoSeeAlternatives,
              variant: ShopButtonVariant.purple,
              onTap: () => context.go('/shop'),
            ),
          ),
        ],
      );
    } else if (sku != null && !sku.sellable) {
      // B5：整宽灰「Belum bisa dijual」，不可加购、不可立即购买。
      child = SizedBox(
        width: double.infinity,
        child: ShopButton(
          key: const ValueKey('pdpNotSellable'),
          label: l10n.tokoNotSellable,
          variant: ShopButtonVariant.disabled,
        ),
      );
    } else {
      final can = sku != null && sku.stockStatus.purchasable && !_adding;
      child = Row(
        children: [
          Semantics(
            button: true,
            label: l10n.tokoAddToCartConfirm,
            child: ShopPressable(
              key: const ValueKey('pdpAddToCart'),
              onTap: can ? () => _onDockTapped(l10n, d, SkuPickerMode.addToCart) : null,
              child: Container(
                width: 52,
                height: 52,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  color: ShopColors.purpleTagBg,
                  shape: BoxShape.circle,
                ),
                child: _adding
                    ? const SizedBox(
                        key: ValueKey('pdpAddToCartLoading'),
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: ShopColors.purple))
                    : const Icon(Icons.add_shopping_cart, color: ShopColors.purple),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Semantics(
              button: true,
              child: ShopPressable(
                key: const ValueKey('pdpBuyNow'),
                onTap: can ? () => _onDockTapped(l10n, d, SkuPickerMode.buyNow) : null,
                child: Container(
                  height: 52,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  decoration: BoxDecoration(
                    color: ShopColors.purple,
                    borderRadius: BorderRadius.circular(26),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(l10n.tokoBuyNow,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: AppTypography.body.copyWith(
                                color: ShopColors.surface, fontWeight: FontWeight.w700)),
                      ),
                      if (sku != null)
                        Text(formatIdr(sku.price),
                            key: const ValueKey('pdpBuyNowPrice'),
                            style: AppTypography.body.copyWith(
                                color: ShopColors.surface,
                                fontWeight: FontWeight.w700,
                                fontFeatures: const [FontFeature.tabularFigures()])),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      );
    }
    return Container(
      key: const ValueKey('pdpDock'),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: ShopColors.surface,
        borderRadius: BorderRadius.circular(34),
        boxShadow: const [
          BoxShadow(color: Color(0x1F2E2742), blurRadius: 16, offset: Offset(0, 4)),
        ],
      ),
      child: child,
    );
  }

  /// 胶囊坞 / 规格行 → 规格弹层（B9 / B10）。弹层里点的规格回写本页（运费随之重估）。
  Future<void> _onDockTapped(AppLocalizations l10n, ShopProductDetail d, SkuPickerMode mode) async {
    if (_adding) return;
    final pick = await pickShopSku(
      context,
      productToken: widget.token,
      mode: mode,
      initialSkuToken: _effectiveSku(d)?.token,
      onSkuChanged: (s) {
        if (mounted) setState(() => _selectedSkuToken = s.token);
      },
    );
    if (pick == null || !mounted) return;
    setState(() => _selectedSkuToken = pick.sku.token);
    final isBuy = mode == SkuPickerMode.buyNow;
    // 事件名写字面量（埋点清单对账按 `Analytics.capture('<名>'` 提取，三元式会被漏掉）。
    if (isBuy) {
      Analytics.capture('toko_buy_now_tapped', {
        'product_token': widget.token,
        'sku_token': pick.sku.token,
      });
    } else {
      Analytics.capture('toko_add_to_cart_tapped', {
        'product_token': widget.token,
        'sku_token': pick.sku.token,
      });
    }
    void perform() {
      if (!mounted) return;
      if (isBuy) {
        // 🔴 直达结算，不写购物车、不改勾选（FR-128.5）。
        context.push(buyNowCheckoutLocation(pick.sku.token, pick.qty, widget.entrySource));
      } else {
        _performAdd(l10n, pick);
      }
    }

    if (ref.read(authControllerProvider).isLoggedIn) {
      perform();
      return;
    }
    // 🔴 游客：软浮层，登录后自动完成本次动作、停留原页（不跳登录页、不丢意图）。
    await ref.read(loginGuideControllerProvider).showSoftSheet(
          context,
          pendingAction: RouteIntent(onResume: perform),
          entrySource: isBuy ? 'toko_buy_now' : 'toko_add_to_cart',
          allowRepeat: true,
        );
  }

  Future<void> _performAdd(AppLocalizations l10n, SkuPick pick) async {
    if (_adding) return;
    setState(() => _adding = true);
    try {
      await ref
          .read(cartProvider.notifier)
          .add(pick.sku.token, qty: pick.qty, entrySource: widget.entrySource);
      if (mounted) showAppToast(context, l10n.cartAdded);
    } on CartMutationError catch (e) {
      // 422 sku-not-sellable 等落 generic（正常情况下按钮已置灰）。
      if (mounted) {
        showAppToast(
            context, e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
      }
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }
}

/// 后台富文本 → 纯文本（v2 `_stripHtml` 原样）：块级标签换成换行，不引富文本渲染器（XSS 面 + 排版控制权）。
String stripShopHtml(String html) => html
    .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
    // 列表项：开标签起新行、闭标签不再补换行 —— 否则「</li><li>」成了两次换行，每条之间空一行（实机反馈）。
    .replaceAll(RegExp(r'<li[^>]*>', caseSensitive: false), '\n• ')
    .replaceAll(RegExp(r'</(p|div|h[1-6]|tr|ul|ol)\s*>', caseSensitive: false), '\n')
    .replaceAll(RegExp(r'</li\s*>', caseSensitive: false), '')
    .replaceAll(RegExp(r'<[^>]*>'), ' ')
    .replaceAll('&nbsp;', ' ')
    .replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll(RegExp(r'[ \t]+'), ' ')
    .replaceAll(RegExp(r' *\n *'), '\n')
    .replaceAll(RegExp(r'\n{3,}'), '\n\n')
    // 列表项之间（含前一段与第一条之间）不留空行。
    .replaceAll(RegExp(r'\n\n(?=• )'), '\n')
    .trim();

/// 图上的购物车按钮（带件数角标；加购成功后 +N，B11）。
class _CartButton extends ConsumerWidget {
  const _CartButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return ShopImageButton(
      key: const ValueKey('cartBadgeV2'),
      icon: Icons.shopping_cart_outlined,
      semanticLabel: l10n.cartOpen,
      badgeCount: ref.watch(cartItemCountProvider),
      onTap: () => context.push('/shop/cart'),
    );
  }
}
