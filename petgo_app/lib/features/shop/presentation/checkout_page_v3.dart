/// 一页结算（v1.4.2 shop Story 7.1 · UI D1–D18；取代 `checkout_page_v2.dart`）。
///
/// 地址、快递、保价、券、PawCoin 都在本页选完，提交后马上进付款（纯币 / 零元单直达 E6）。
///
/// ## 🔴 从 v2 迁来、一条不改的规则
///
/// 1. **金额一个都不自己算**（P-1）：商品金额 / 满减 / 运费 / 减免 / 保价 / 应付 / 已省 / 币与现金拆分
///    全取服务端试算 `pricing`。前端再算一遍必然与下单时固化的那份漂移。
/// 2. **金额位算不出就显示「—」，绝不显示 Rp 0**（FR-99）：0 会被读成「免运费」。
///    无地址 / 不可配送 / 询价失败 / 询价中，运费、应付、币、现金一律「—」。
/// 3. **「开封不退」同意位**与详情页、退货页**同一批 ARB key**（FR-104），默认已勾、取消则不可提交。
/// 4. **单已落库后的任何后续失败都不能把页面退回「可再次提交」**：购物车刷新不 await、错误吞掉；
///    幂等键在同一份试算结果内复用，网络抖动重点提交取回同一张单。
///
/// ## 🔴 v1.4.2 新规则
///
/// - 试算 `POST /me/checkout/preview` 每次选择变更发一次，**只采用最后一次请求的结果**（自增序号）。
/// - 选择（地址 / 快递 / 保价 / 券 / 币开关）都是本页局部状态：只对本单、不写回默认、不跨订单记忆。
/// - BUY_NOW 全程**不读写购物车**。
/// - 提交必须带 `quoteToken`（S-4：不带会被服务端当作旧版 App）。
/// - 币在提交时扣（S-3）→ 成功即 `invalidate(pawCoinProvider)`；纯币单服务端已付款，App 不调 `/pay`。
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../growth/domain/growth_popup_trigger.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../address/data/address_repository.dart';
import '../coupon/presentation/coupon_picker_sheet.dart';
import '../data/cart_repository.dart';
import '../data/checkout_repository.dart';
import '../domain/checkout_preview.dart';
import '../domain/shop_product.dart' show formatIdr;
import '../domain/shop_product_detail.dart' show ReturnPolicy;
import 'widgets/checkout_sheets.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_dialog.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_surface.dart';

/// 底栏高度（toast 抬到底栏上方用）。
const double _kBottomBarToastLift = 84;

const _tabular = [FontFeature.tabularFigures()];

class CheckoutPageV3 extends ConsumerStatefulWidget {
  const CheckoutPageV3({
    super.key,
    this.source = CheckoutSource.cart,
    this.buyNowSkuToken,
    this.buyNowQty = 1,
    this.buyNowEntrySource,
  });

  final CheckoutSource source;

  /// BUY_NOW 的规格 token。
  final String? buyNowSkuToken;
  final int buyNowQty;

  /// BUY_NOW 的归因来源（路由 `?from=`，与加购 `entrySource` 同值域；v1.4.2 shop 8.2 复审补）。
  /// 透传到试算与提交的 `buyNow.entrySource`；null = 未知，不编默认值。
  final String? buyNowEntrySource;

  @override
  ConsumerState<CheckoutPageV3> createState() => _CheckoutPageV3State();
}

class _CheckoutPageV3State extends ConsumerState<CheckoutPageV3> {
  // ---- 试算 ----
  int _seq = 0;
  CheckoutPreview? _p;
  bool _inFlight = false;

  /// 重型试算（首次 / 换地址 / 新增地址 / 移除商品后）：快递区「Menghitung ongkir…」、金额位「—」。
  bool _quoting = false;
  bool _loadFailed = false;

  // ---- 本单选择（页面销毁即丢） ----
  String? _addressToken;
  String? _courierKey;
  bool _insurance = false;
  bool _manualCoupons = false;
  String? _discountToken;
  String? _freeShippingToken;

  /// 用户关掉后本页内保持关闭（不跨订单记忆，FR-131）。
  bool _useCoin = true;

  /// 开封不退同意（FR-104）。🔴 默认已勾，照设计稿（产品决策，见 v2 同名字段说明）。
  bool _agreed = true;

  // ---- 提交 ----
  bool _submitting = false;

  /// 单已落库：此后提交按钮永不再放开（AC10.6）。
  bool _placed = false;

  /// 同一份试算结果内复用的幂等键；新试算结果到达即作废 —— **除非** [_idemKeyPinned]。
  String? _idemKey;

  /// 🔴 上一次提交结果不明确（超时 / 断网 / 5xx / 409 conflict）：单可能已建。拿到明确结果
  /// （成功或明确业务错误）前**不因新试算作废键**，下次提交用同一把键取回既有订单、不重复扣币。
  bool _idemKeyPinned = false;

  /// 🔴 最近一次**成功**试算对应的选择（试算失败时整体回滚到这里，UI 与请求一致）。
  _Selection? _committed;

  /// 提交中 / 已落库：所有选择交互一律不响应（选择变了而提交用的是旧值 = 资损）。
  bool get _locked => _submitting || _placed;

  bool _expanded = false;
  bool _outOfRangeReported = false;

  bool get _isBuyNow => widget.source == CheckoutSource.buyNow;

  /// `buy_now` 但参数不合法 → 直接失败态（不退化成 CART，免得把车里别的商品结掉）。
  bool get _invalidBuyNow =>
      _isBuyNow && ((widget.buyNowSkuToken ?? '').isEmpty || widget.buyNowQty < 1);

  @override
  void initState() {
    super.initState();
    Analytics.capture(
        'toko_checkout_page_viewed', {'entry': _isBuyNow ? 'buy_now' : 'cart'});
    if (!_invalidBuyNow) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _refresh(heavy: true));
    }
  }

  _Selection get _selection => (
        address: _addressToken,
        courier: _courierKey,
        insurance: _insurance,
        manual: _manualCoupons,
        discount: _discountToken,
        freeShipping: _freeShippingToken,
        useCoin: _useCoin,
      );

  void _restore(_Selection c) {
    _addressToken = c.address;
    _courierKey = c.courier;
    _insurance = c.insurance;
    _manualCoupons = c.manual;
    _discountToken = c.discount;
    _freeShippingToken = c.freeShipping;
    _useCoin = c.useCoin;
  }

  /// 当前选择已拿到成功试算（页面所示 = 提交所用）。
  bool get _selectionSettled => _committed != null && _committed == _selection;

  CheckoutRequest _request() => CheckoutRequest(
        source: widget.source,
        buyNowSkuToken: widget.buyNowSkuToken,
        buyNowQty: widget.buyNowQty,
        buyNowEntrySource: widget.buyNowEntrySource,
        addressToken: _addressToken,
        courierKey: _courierKey,
        insurance: _insurance,
        manualCoupons: _manualCoupons,
        discountCouponToken: _discountToken,
        freeShippingCouponToken: _freeShippingToken,
        useCoin: _useCoin,
      );

  /// 发一次试算。[heavy] = 重型（清金额位）；否则保留上一次金额、只禁用提交。
  Future<void> _refresh({bool heavy = false}) async {
    final my = ++_seq;
    setState(() {
      _inFlight = true;
      if (heavy) _quoting = true;
      _loadFailed = false;
    });
    try {
      final p = await ref.read(checkoutRepositoryProvider).preview(_request());
      if (!mounted || my != _seq) return; // 过期响应丢弃
      setState(() {
        _p = p;
        _inFlight = false;
        _quoting = false;
        _addressToken = p.address?.token;
        // 换地址时快递保留：以响应 selectedCourierKey 为准（仍可选则保留，否则服务端选最便宜）
        _courierKey = p.selectedCourierKey ?? _courierKey;
        _insurance = p.insurance;
        // 🔴 取计价器实际采用值：手选但减免为 0 的券服务端回 null，页面写「Tidak dipakai」
        _discountToken = p.discountCoupons.selectedToken;
        _freeShippingToken = p.freeShippingCoupons.selectedToken;
        _committed = _selection;
        if (!_idemKeyPinned) _idemKey = null;
      });
      if (p.blockReason == CheckoutBlockReason.notServiceable && !_outOfRangeReported) {
        _outOfRangeReported = true;
        Analytics.capture('toko_checkout_out_of_range_shown');
      }
    } catch (_) {
      if (!mounted || my != _seq) return;
      final hadPreview = _p != null;
      setState(() {
        _inFlight = false;
        _quoting = false;
        if (!hadPreview) _loadFailed = true;
        // 🔴 回滚到最近一次成功试算的选择：否则开关显示「关」、提交却按旧试算扣币
        final c = _committed;
        if (c != null) _restore(c);
      });
      if (hadPreview) {
        showAppToast(context, AppLocalizations.of(context).checkoutLoadFailed,
            extraBottom: _kBottomBarToastLift);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final p = _p;
    Widget body;
    if (_invalidBuyNow || (_loadFailed && p == null)) {
      body = _invalidBuyNow
          // 参数不合法重试也没用 → 给「返回」（复用 shopOrderCancelConfirmNo「Kembali / Go back」）；
          // 不能 pop（深链直达）则回 Toko 首页（Tab 根一律 go）
          ? ShopRetryState(
              message: l10n.checkoutLoadFailed,
              retryLabel: l10n.shopOrderCancelConfirmNo,
              onRetry: () => context.canPop() ? context.pop() : context.go('/shop'),
            )
          : ShopRetryState(
              message: l10n.checkoutLoadFailed,
              retryLabel: l10n.commonRetry,
              onRetry: () => _refresh(heavy: true),
            );
    } else if (p == null) {
      body = const Center(child: CircularProgressIndicator());
    } else {
      body = _content(l10n, p);
    }
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.checkoutTitle),
      body: body,
      bottomNavigationBar: p == null || _invalidBuyNow ? null : _bottomBar(l10n, p),
    );
  }

  /// 金额位（运费 / 应付 / 币 / 现金）可展示。
  bool _amountsKnown(CheckoutPreview p) => p.amountsKnown && !_quoting;

  bool _canSubmit(CheckoutPreview p) =>
      p.hasAddress &&
      p.canSubmit &&
      p.selectedCourier != null &&
      !_inFlight &&
      _agreed &&
      !_submitting &&
      !_placed &&
      _selectionSettled;

  Widget _content(AppLocalizations l10n, CheckoutPreview p) {
    return ListView(
      key: const ValueKey('checkoutV3List'),
      padding: const EdgeInsets.only(top: 12, bottom: 12),
      children: [
        _deliveryCard(l10n, p),
        if (p.unavailableLines.isNotEmpty) _unavailableCard(l10n, p),
        if (p.lines.isNotEmpty) _itemsCard(l10n, p),
        _couponCard(l10n, p),
        _paymentCard(l10n, p),
        _detailsCard(l10n, p),
        _agreementCard(l10n, p),
        if (p.blockReason == CheckoutBlockReason.notServiceable) _serviceAreaCard(l10n),
      ],
    );
  }

  // ================================================================ 配送卡（D1–D9）

  Widget _deliveryCard(AppLocalizations l10n, CheckoutPreview p) {
    return ShopCard(
      key: const ValueKey('checkoutDeliveryCard'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Column(
        children: [
          _addressRow(l10n, p),
          const ShopDivider(),
          _courierRow(l10n, p),
          if (p.insuranceAvailable && p.selectedCourier != null && !_quoting) ...[
            const ShopDivider(),
            _insuranceRow(l10n, p),
          ],
        ],
      ),
    );
  }

  Widget _addressRow(AppLocalizations l10n, CheckoutPreview p) {
    final a = p.address;
    if (a == null) {
      // D2：点击直接打开 D5（不经选择抽屉）
      return ShopPressable(
        key: const ValueKey('checkoutAddAddressV3'),
        onTap: _addAddress,
        child: _row(
          icon: Icons.add_location_alt_outlined,
          iconColor: ShopColors.purple,
          title: Text(l10n.checkoutAddAddress,
              style: AppTypography.body
                  .copyWith(fontWeight: FontWeight.w600, color: ShopColors.purple)),
          trailing: const Icon(Icons.chevron_right_rounded, color: ShopColors.text4),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ShopPressable(
          key: const ValueKey('checkoutAddressRowV3'),
          onTap: _pickAddress,
          child: _row(
            icon: Icons.location_on_outlined,
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${a.receiverName} · ${a.receiverPhone}',
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                Text(a.addressText, style: AppTypography.caption),
              ],
            ),
            trailing: const Icon(Icons.chevron_right_rounded, color: ShopColors.text4),
          ),
        ),
        // D3：警示长在地址块内（问题出在这个地址上）
        if (p.blockReason == CheckoutBlockReason.notServiceable && !_quoting)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Container(
              key: const ValueKey('checkoutOutOfRangeNotice'),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: ShopColors.warnBg,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.error_outline_rounded, size: 18, color: ShopColors.errorText),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(l10n.shopAreaNotServiceable,
                                key: const ValueKey('checkoutOutOfRangeTitle'),
                                style: AppTypography.body.copyWith(
                                    fontWeight: FontWeight.w600, color: ShopColors.errorText)),
                            Text(l10n.checkoutOutOfRangeBody, style: AppTypography.caption),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    width: double.infinity,
                    child: ShopButton(
                      key: const ValueKey('checkoutPickAnotherAddress'),
                      label: l10n.checkoutPickAnotherAddress,
                      variant: ShopButtonVariant.purple,
                      // 修线上缺陷：原先跳地址管理页、选了不回填 → 改为打开 D4
                      onTap: _pickAddress,
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _courierRow(AppLocalizations l10n, CheckoutPreview p) {
    Widget hint(String text, {Key? key, Color color = ShopColors.text3}) => _row(
          icon: Icons.local_shipping_outlined,
          title: Text(text, key: key, style: AppTypography.caption.copyWith(color: color)),
        );
    if (!p.hasAddress) {
      return hint(l10n.checkoutNoAddressCourierHint, key: const ValueKey('checkoutCourierNoAddress'));
    }
    if (_quoting) {
      return hint(l10n.checkoutQuoting, key: const ValueKey('checkoutQuoting'));
    }
    final c = p.selectedCourier;
    if (c == null) {
      if (p.blockReason == CheckoutBlockReason.quoteFailed) {
        // D8：不退回固定运费
        return _row(
          icon: Icons.local_shipping_outlined,
          title: Text(l10n.checkoutQuoteFailed,
              key: const ValueKey('checkoutQuoteFailed'),
              style: AppTypography.caption.copyWith(color: ShopColors.errorText)),
          trailing: TextButton(
            key: const ValueKey('checkoutQuoteRetry'),
            onPressed: _locked ? null : () => _refresh(heavy: true),
            child: Text(l10n.commonRetry),
          ),
        );
      }
      return hint(l10n.checkoutNoCourier, key: const ValueKey('checkoutNoCourier'));
    }
    final eta = c.etaText == null ? '' : ' · ${c.etaText}';
    final sub = !c.insuranceSupported
        ? l10n.checkoutCourierNoInsurance
        : (c.cheapest ? l10n.checkoutCourierCheapest : null);
    return ShopPressable(
      key: const ValueKey('checkoutCourierRow'),
      onTap: () => _pickCourier(p),
      child: _row(
        icon: Icons.local_shipping_outlined,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${c.courierName} ${c.serviceName}$eta',
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
            if (sub != null)
              Text(sub,
                  key: const ValueKey('checkoutCourierSub'),
                  style: AppTypography.caption.copyWith(
                      color: c.insuranceSupported ? ShopColors.saving : ShopColors.text3)),
          ],
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(formatIdr(c.price),
                key: const ValueKey('checkoutCourierPrice'),
                style: AppTypography.body.copyWith(fontFeatures: _tabular)),
            const Icon(Icons.chevron_right_rounded, color: ShopColors.text4),
          ],
        ),
      ),
    );
  }

  Widget _insuranceRow(AppLocalizations l10n, CheckoutPreview p) => _row(
        key: const ValueKey('checkoutInsuranceRow'),
        icon: Icons.verified_user_outlined,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.checkoutInsuranceTitle, style: AppTypography.body),
            Text(l10n.checkoutInsuranceRate, style: AppTypography.caption),
          ],
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (p.insurance && p.pricing.insuranceFee > 0)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text(formatIdr(p.pricing.insuranceFee),
                    style: AppTypography.body.copyWith(fontFeatures: _tabular)),
              ),
            ShopSwitch(
              key: const ValueKey('checkoutInsuranceSwitch'),
              value: _insurance,
              large: false,
              semanticLabel: l10n.checkoutInsuranceTitle,
              onChanged: _locked
                  ? null
                  : (v) {
                      setState(() => _insurance = v);
                      _refresh();
                    },
            ),
          ],
        ),
      );

  // ================================================================ 失效行（试算阶段即提示）

  /// 3.1：库内仍勾着的失效行会挡结算（`UNAVAILABLE_LINES`），而购物车对外 selected=false ——
  /// 用户在购物车里看不到它们，必须在这里引导清除，否则永远提交不了。
  Widget _unavailableCard(AppLocalizations l10n, CheckoutPreview p) => ShopCard(
        key: const ValueKey('checkoutUnavailableCard'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.checkoutUnavailableTitle,
                style: AppTypography.body
                    .copyWith(fontWeight: FontWeight.w600, color: ShopColors.errorText)),
            const SizedBox(height: 6),
            for (final l in p.unavailableLines)
              Text(_unavailableText(l10n, l.toUnavailable()), style: AppTypography.caption),
            if (!_isBuyNow) ...[
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: ShopButton(
                  key: const ValueKey('checkoutRemoveUnavailableInline'),
                  label: l10n.checkoutUnavailableRemove,
                  variant: ShopButtonVariant.ink,
                  onTap: () => _removeFromCart(
                      [for (final l in p.unavailableLines) l.skuToken]),
                ),
              ),
            ],
          ],
        ),
      );

  String _unavailableText(AppLocalizations l10n, UnavailableLine l) =>
      '${l.productName ?? l.skuToken} · '
      '${l.isDelisted ? l10n.checkoutUnavailableDelisted : l.isNotSellable ? l10n.cartReasonUnavailable : l10n.checkoutUnavailableStock(l.available, l.requested)}';

  // ================================================================ 商品折叠（D1 / D18）

  Widget _itemsCard(AppLocalizations l10n, CheckoutPreview p) {
    final count = p.lines.fold<int>(0, (s, l) => s + l.qty);
    final header = ShopPressable(
      key: const ValueKey('checkoutItemsToggle'),
      onTap: () => setState(() => _expanded = !_expanded),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            if (!_expanded)
              for (final l in p.lines.take(3))
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: Stack(
                    children: [
                      ShopImage(url: l.mainImageUrl, size: 44, radius: 8),
                      if (l.qty > 1)
                        Positioned(
                          right: 0,
                          bottom: 0,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 3),
                            color: ShopColors.imageCapsuleScrim,
                            child: Text('×${l.qty}',
                                style: AppTypography.micro.copyWith(color: ShopColors.surface)),
                          ),
                        ),
                    ],
                  ),
                ),
            Expanded(
              child: Text(l10n.checkoutItemsCount(count),
                  style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
            ),
            Text(formatIdr(p.pricing.goodsAmount),
                key: const ValueKey('checkoutGoodsAmount'),
                style: AppTypography.body.copyWith(fontFeatures: _tabular)),
            Icon(_expanded ? Icons.expand_less_rounded : Icons.expand_more_rounded,
                color: ShopColors.text4),
          ],
        ),
      ),
    );
    return ShopCard(
      key: const ValueKey('checkoutItemsCard'),
      child: Column(
        children: [
          header,
          if (_expanded)
            for (final l in p.lines)
              Padding(
                key: ValueKey('checkoutItemLine_${l.skuToken}'),
                padding: const EdgeInsets.only(top: 12),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ShopImage(url: l.mainImageUrl, size: 72, radius: 8),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(l.productName ?? l.specName,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: AppTypography.body),
                          if (l.productName != null && l.specName.isNotEmpty)
                            Text(l.specName, style: AppTypography.caption),
                          Row(
                            children: [
                              Expanded(
                                child: Text(formatIdr(l.price),
                                    style: AppTypography.body.copyWith(
                                        color: ShopColors.purple, fontFeatures: _tabular)),
                              ),
                              Text('×${l.qty}', style: AppTypography.caption),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
        ],
      ),
    );
  }

  // ================================================================ 优惠卡（D10–D12）

  Widget _couponCard(AppLocalizations l10n, CheckoutPreview p) {
    final known = _amountsKnown(p);
    final pr = p.pricing;
    final saved = pr.savedAmount ?? 0;
    final dGroup = p.discountCoupons;
    final fGroup = p.freeShippingCoupons;

    final String dValue;
    if (dGroup.selectedToken != null && pr.discountCouponAmount > 0) {
      dValue = '−${formatIdr(pr.discountCouponAmount)}';
    } else if (dGroup.available.isEmpty) {
      dValue = l10n.checkoutCouponNoneAvailable;
    } else {
      dValue = l10n.checkoutCouponNotUsed;
    }

    final rule = pr.shippingDiscountSource == ShippingDiscountSource.freeShippingRule &&
        (pr.shippingDiscount ?? 0) > 0;
    final threshold = pr.freeShippingProgress?.threshold ?? 0;
    final String fLabel = rule && known ? l10n.checkoutAutoShipDiscount : l10n.checkoutCouponFreeShipping;
    final String fValue;
    if (known && (rule || (fGroup.selectedToken != null && (pr.shippingDiscount ?? 0) > 0))) {
      fValue = '−${formatIdr(pr.shippingDiscount!)}';
    } else if (!known && fGroup.selectedToken != null) {
      fValue = '—';
    } else if (fGroup.available.isEmpty) {
      fValue = l10n.checkoutCouponNoneAvailable;
    } else {
      fValue = l10n.checkoutCouponNotUsed;
    }
    final progress = pr.freeShippingProgress;

    return ShopCard(
      key: const ValueKey('checkoutCouponCard'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (saved > 0 && !_quoting)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  const Icon(Icons.savings_outlined, size: 18, color: ShopColors.saving),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(l10n.checkoutSavedOnOrder(formatIdrAmount(saved)),
                        key: const ValueKey('checkoutSavedOnOrder'),
                        style: AppTypography.caption.copyWith(
                            color: ShopColors.saving, fontWeight: FontWeight.w600)),
                  ),
                ],
              ),
            ),
          ShopPressable(
            key: const ValueKey('checkoutDiscountCouponRow'),
            onTap: () => _pickCoupon(CouponKind.discount, dGroup),
            child: _row(
              icon: Icons.local_offer_outlined,
              title: Text(l10n.checkoutCouponDiscount, style: AppTypography.body),
              trailing: _valueWithChevron(dValue, const ValueKey('checkoutDiscountCouponValue'),
                  highlight: dValue.startsWith('−')),
            ),
          ),
          ShopPressable(
            key: const ValueKey('checkoutFreeShippingCouponRow'),
            onTap: () => _pickCoupon(CouponKind.freeShipping, fGroup),
            child: _row(
              icon: Icons.local_shipping_outlined,
              title: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(fLabel, style: AppTypography.body),
                  // D12：两行，不挤一行
                  if (rule && known)
                    Text(l10n.checkoutAutoShipDiscountHint(formatIdrAmount(threshold)),
                        key: const ValueKey('checkoutAutoShipDiscountHint'),
                        style: AppTypography.caption),
                ],
              ),
              trailing: _valueWithChevron(fValue, const ValueKey('checkoutFreeShippingCouponValue'),
                  highlight: fValue.startsWith('−')),
            ),
          ),
          // AC8.5 免运进度（PRD FR-127.3；UI D 组未画，见待确认 2）
          if (progress != null && progress.showRemaining && !_quoting)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  const Icon(Icons.local_shipping_outlined, size: 16, color: ShopColors.purple),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(l10n.tokoFreeShipRemaining(formatIdrAmount(progress.remaining)),
                        key: const ValueKey('checkoutFreeShipRemaining'),
                        style: AppTypography.caption.copyWith(color: ShopColors.purpleText)),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// 右侧值 + 箭头。长文案（「Belum ada yang bisa dipakai」）限宽换行，不挤爆左侧标签。
  Widget _valueWithChevron(String text, Key key, {bool highlight = false}) => ConstrainedBox(
      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .45),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: Text(text,
                key: key,
                textAlign: TextAlign.end,
                style: AppTypography.body.copyWith(
                    fontFeatures: _tabular,
                    color: highlight ? ShopColors.purple : ShopColors.text3)),
          ),
          const Icon(Icons.chevron_right_rounded, color: ShopColors.text4),
        ],
      ));

  // ================================================================ 支付方式（D13–D15）

  Widget _paymentCard(AppLocalizations l10n, CheckoutPreview p) {
    final known = _amountsKnown(p);
    final pr = p.pricing;
    final coin = known ? (pr.coinAmount ?? 0) : 0;
    final cash = pr.cashAmount;
    final usingCoin = p.useCoin && coin > 0;
    final fullCoin = known && usingCoin && cash == 0;
    final canToggleCoin = p.coinBalance > 0 && p.coinEnabled && known && !_locked;
    final capped = p.useCoin &&
        known &&
        (p.coinCapReached || (p.coinCap > 0 && p.coinBalance > p.coinCap));

    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 4),
          child: Text(l10n.checkoutPaymentMethod,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
        ),
        _row(
          key: const ValueKey('checkoutCoinRow'),
          icon: Icons.pets_outlined,
          iconColor: ShopColors.purple,
          title: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.checkoutUseCoin, style: AppTypography.body),
              Text(l10n.checkoutCoinBalanceShort(formatIdrAmount(p.coinBalance)),
                  style: AppTypography.caption),
            ],
          ),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (usingCoin)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: Text(formatIdrAmount(coin),
                      key: const ValueKey('checkoutCoinAmount'),
                      style: AppTypography.body
                          .copyWith(color: ShopColors.purple, fontFeatures: _tabular)),
                ),
              ShopSwitch(
                key: const ValueKey('checkoutCoinSwitch'),
                value: p.coinBalance > 0 && _useCoin,
                large: false,
                semanticLabel: l10n.checkoutUseCoin,
                onChanged: canToggleCoin
                    ? (v) {
                        setState(() => _useCoin = v);
                        _refresh();
                      }
                    : null,
              ),
            ],
          ),
        ),
        if (capped)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.info_outline_rounded, size: 16, color: ShopColors.purple),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(l10n.checkoutCoinCapped(formatIdrAmount(p.coinCap)),
                      key: const ValueKey('checkoutCoinCapped'),
                      style: AppTypography.caption.copyWith(color: ShopColors.purpleText)),
                ),
              ],
            ),
          ),
        if (fullCoin)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(l10n.checkoutPaidFullCoinNote,
                key: const ValueKey('checkoutPaidFullCoinNote'), style: AppTypography.caption),
          )
        else ...[
          const ShopDivider(),
          _row(
            key: const ValueKey('checkoutQrisRow'),
            icon: Icons.qr_code_2_rounded,
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(usingCoin ? l10n.checkoutRestViaQris : l10n.checkoutPayFullQris,
                    key: const ValueKey('checkoutQrisTitle'), style: AppTypography.body),
                Text(l10n.checkoutQrisApps, style: AppTypography.caption),
              ],
            ),
            trailing: Text(known && cash != null ? formatIdr(cash) : '—',
                key: const ValueKey('checkoutCashAmount'),
                style: AppTypography.body.copyWith(fontFeatures: _tabular)),
          ),
        ],
        // 🔴 防套现提示：用到 PawCoin 时必须在支付前可见（合规位点）；没用到不画（D15）
        if (usingCoin)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: ShopWarnBlock(
              key: const ValueKey('checkoutPawcoinNote'),
              title: l10n.checkoutPawcoin,
              body: l10n.checkoutPawcoinNote,
            ),
          ),
      ],
    );
    return ShopCard(
      key: const ValueKey('checkoutPaymentCard'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      // D2 / D3 / D8：金额未知时整块置灰
      child: Opacity(opacity: known ? 1 : .5, child: content),
    );
  }

  // ================================================================ 金额明细（FR-130 顺序）

  Widget _detailsCard(AppLocalizations l10n, CheckoutPreview p) {
    final known = _amountsKnown(p);
    final pr = p.pricing;
    final shipDiscount = pr.shippingDiscount ?? 0;
    final threshold = pr.freeShippingProgress?.threshold ?? 0;
    return ShopCard(
      key: const ValueKey('checkoutDetailsCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutPaymentDetails,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          _amountRow(l10n.checkoutSubtotal, formatIdr(pr.goodsAmount),
              key: const ValueKey('checkoutDetailSubtotal')),
          if (pr.discountCouponAmount > 0)
            _amountRow(l10n.checkoutCouponDiscount, '−${formatIdr(pr.discountCouponAmount)}',
                key: const ValueKey('checkoutDetailCoupon'), color: ShopColors.purple),
          _amountRow(l10n.checkoutShippingFee,
              known && pr.shippingFee != null ? formatIdr(pr.shippingFee!) : '—',
              key: const ValueKey('checkoutDetailShipping')),
          if (known && shipDiscount > 0)
            switch (pr.shippingDiscountSource) {
              ShippingDiscountSource.coupon => _amountRow(
                  l10n.checkoutFreeShippingCouponRow, '−${formatIdr(shipDiscount)}',
                  key: const ValueKey('checkoutDetailShipDiscount'), color: ShopColors.purple),
              ShippingDiscountSource.freeShippingRule => _amountRow(
                  l10n.checkoutShipRuleRow(formatIdrAmount(threshold)),
                  '−${formatIdr(shipDiscount)}',
                  key: const ValueKey('checkoutDetailShipDiscount'), color: ShopColors.purple),
              ShippingDiscountSource.none => const SizedBox.shrink(),
            },
          if (p.insurance && pr.insuranceFee > 0)
            _amountRow(l10n.checkoutInsuranceTitle, formatIdr(pr.insuranceFee),
                key: const ValueKey('checkoutDetailInsurance')),
          // 🔴 止于应付之前，不出现 PawCoin 行（FR-128.1 / FR-131.3）；合计在底栏
        ],
      ),
    );
  }

  Widget _amountRow(String label, String value, {Key? key, Color? color}) => Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            Expanded(child: Text(label, style: AppTypography.caption)),
            Text(value,
                style: AppTypography.caption.copyWith(
                    fontFeatures: _tabular,
                    color: color ?? ShopColors.text2,
                    fontWeight: color == null ? FontWeight.w400 : FontWeight.w600)),
          ],
        ),
      );

  // ================================================================ 开封不退（FR-104）

  Widget _agreementCard(AppLocalizations l10n, CheckoutPreview p) {
    final body = switch (p.strictestReturnPolicy) {
      ReturnPolicy.returnable => l10n.tokoReturnableBody,
      ReturnPolicy.noReturnAfterOpen => l10n.tokoNoReturnAfterOpenBody,
      ReturnPolicy.noReturn => l10n.tokoNoReturnBody,
    };
    // 整行可点（勾选框 15px 太小）
    return ShopCard(
      padding: const EdgeInsets.fromLTRB(8, 4, 16, 4),
      child: ShopPressable(
        onTap: _locked ? null : () => setState(() => _agreed = !_agreed),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ShopCheckbox(
              key: const ValueKey('checkoutAgreeNoReturn'),
              value: _agreed,
              size: 15,
              semanticLabel: body,
              onChanged: (v) {
                if (!_locked) setState(() => _agreed = v);
              },
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(body, style: AppTypography.disclaimer),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ================================================================ 已开通城市（D3）

  Widget _serviceAreaCard(AppLocalizations l10n) {
    final regions = ref.watch(regionTreeProvider).maybeWhen(data: (t) => t, orElse: () => null);
    // 取不到整块不画：列不出地方的「我们送这些地方」比没有更让人困惑
    if (regions == null) return const SizedBox.shrink();
    final kota = <String>{
      for (final prov in regions.provinsi)
        for (final k in prov.kota) k.name,
    }.toList()
      ..sort();
    if (kota.isEmpty) return const SizedBox.shrink();
    const maxShown = 8;
    final shown = kota.take(maxShown).toList();
    final rest = kota.length - shown.length;
    return ShopCard(
      key: const ValueKey('checkoutServiceArea'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutServiceAreaTitle,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
          const SizedBox(height: 9),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final k in shown) ShopChip(label: k, selected: false),
              if (rest > 0) ShopChip(label: l10n.checkoutMoreAreas(rest), selected: false),
            ],
          ),
        ],
      ),
    );
  }

  // ================================================================ 底栏（D1 / D14 / D15）

  Widget _bottomBar(AppLocalizations l10n, CheckoutPreview p) {
    final known = _amountsKnown(p);
    final pr = p.pricing;
    final saved = pr.savedAmount ?? 0;
    final coin = known ? (pr.coinAmount ?? 0) : 0;
    final cash = known ? pr.cashAmount : null;
    final allowed = _canSubmit(p);
    final String? subline;
    if (known && p.useCoin && coin > 0 && (cash ?? 0) > 0) {
      subline = l10n.checkoutRemainingQris(formatIdrAmount(cash!));
    } else if (known && p.useCoin && coin > 0 && cash == 0) {
      subline = l10n.checkoutPaidFullCoin;
    } else {
      subline = null;
    }
    final safeBottom = MediaQuery.viewPaddingOf(context).bottom;
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        border: Border(top: BorderSide(color: ShopColors.border2)),
      ),
      child: Padding(
        padding: EdgeInsets.fromLTRB(16, 10, 16, 10 + safeBottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Wrap(
                        spacing: 6,
                        children: [
                          Text(l10n.checkoutPayable, style: AppTypography.caption),
                          if (known && saved > 0)
                            Text(l10n.tokoSavedAmount(formatIdrAmount(saved)),
                                key: const ValueKey('checkoutBottomSaved'),
                                style: AppTypography.caption.copyWith(
                                    color: ShopColors.saving, fontWeight: FontWeight.w600)),
                        ],
                      ),
                      Text(known && pr.payable != null ? formatIdr(pr.payable!) : '—',
                          key: const ValueKey('checkoutPayableV3'),
                          style: AppTypography.title.copyWith(
                              fontWeight: FontWeight.w700,
                              fontFeatures: _tabular,
                              color: known ? ShopColors.purple : ShopColors.text4)),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: 48),
                  child: ShopButton(
                    key: const ValueKey('checkoutSubmitV3'),
                    label: l10n.checkoutSubmit,
                    // 提交中保持强调色 + 转圈，不置灰（置灰 = 这单不能提交）
                    variant: allowed || _submitting
                        ? ShopButtonVariant.pay
                        : ShopButtonVariant.disabled,
                    loading: _submitting,
                    padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
                    onTap: allowed ? () => _submit(l10n, p) : null,
                  ),
                ),
              ],
            ),
            if (subline != null)
              Align(
                alignment: Alignment.centerLeft,
                child: Text(subline,
                    key: const ValueKey('checkoutBottomSubline'),
                    style: AppTypography.caption.copyWith(fontFeatures: _tabular)),
              ),
          ],
        ),
      ),
    );
  }

  // ================================================================ 交互

  Future<void> _pickAddress() async {
    if (_locked) return;
    final pick = await showAddressPickerSheet(context, currentToken: _addressToken);
    if (!mounted || pick == null || _locked) return;
    switch (pick) {
      case AddressPicked(:final token):
        // 只对本单生效，不调设默认
        setState(() => _addressToken = token);
        _refresh(heavy: true);
      case AddressAddNew():
        await _addAddress();
    }
  }

  Future<void> _addAddress() async {
    if (_locked) return;
    final saved = await showAddAddressSheet(context);
    if (!mounted || saved == null || _locked) return;
    // AddressFormBody 已 invalidate(addressListProvider)
    setState(() => _addressToken = saved.token);
    _refresh(heavy: true);
  }

  Future<void> _pickCourier(CheckoutPreview p) async {
    if (_locked) return;
    final key = await showCourierPickerSheet(context,
        couriers: p.couriers, selectedKey: p.selectedCourierKey);
    if (!mounted || key == null || key == p.selectedCourierKey || _locked) return;
    setState(() => _courierKey = key);
    _refresh();
  }

  Future<void> _pickCoupon(CouponKind kind, CouponGroup group) async {
    if (_locked) return;
    final pick = await showCouponPickerSheet(context, kind: kind, group: group);
    if (!mounted || pick == null || _locked) return;
    // 用户任一操作后改 MANUAL，另一张沿用上一次响应的采用值
    setState(() {
      _manualCoupons = true;
      if (kind == CouponKind.discount) {
        _discountToken = pick.token;
      } else {
        _freeShippingToken = pick.token;
      }
    });
    _refresh();
  }

  Future<void> _removeFromCart(List<String> skuTokens) async {
    if (_locked) return;
    for (final sku in skuTokens) {
      try {
        // 不可购买行（下单 409 / 预览里的不可购买行，用户确认后移除）→ 埋点口径算「清空失效」。
        await ref.read(cartProvider.notifier).remove(sku, reason: CartRemoveReason.invalidCleared);
      } on CartMutationError {
        // 单行移除失败不阻断其余行；重新试算后用户会看到真实状态
      }
    }
    if (mounted) _refresh(heavy: true);
  }

  void _toast(String msg) => showAppToast(context, msg, extraBottom: _kBottomBarToastLift);

  Future<void> _submit(AppLocalizations l10n, CheckoutPreview p) async {
    final courier = p.selectedCourier;
    final address = p.address;
    final quote = p.quoteToken;
    if (courier == null || address == null || quote == null || _locked) return;
    Analytics.capture('toko_checkout_submit_tapped');
    setState(() => _submitting = true);
    final key = _idemKey ??= 'shop-order-${DateTime.now().microsecondsSinceEpoch}';
    try {
      final order = await ref.read(checkoutRepositoryProvider).placeOrder(
            PlaceOrderRequest(
              source: widget.source,
              buyNowSkuToken: widget.buyNowSkuToken,
              buyNowQty: widget.buyNowQty,
              buyNowEntrySource: widget.buyNowEntrySource,
              addressToken: address.token,
              quoteToken: quote,
              courierKey: courier.courierKey,
              insurance: p.insurance,
              discountCouponToken: p.discountCoupons.selectedToken,
              freeShippingCouponToken: p.freeShippingCoupons.selectedToken,
              useCoin: p.useCoin,
            ),
            idempotencyKey: key,
          );
      // 🔴 单已落库：此后任何失败都不能把页面退回「可再次提交」
      _placed = true;
      _idemKeyPinned = false;
      // 币在提交时已扣（S-3）
      ref.invalidate(pawCoinProvider);
      if (!_isBuyNow) {
        unawaited(ref.read(cartProvider.notifier).refresh().catchError((_) {}));
      }
      if (!mounted) return;
      _reportSubmitted(p, courier);
      if (order.settled) {
        // 纯 PawCoin / 零元单：服务端提交事务内已付款，不调 /pay，直达 E6
        final channel = order.payChannel;
        Analytics.capture('toko_order_payment_succeeded',
            {'pay_channel': channel == null || channel.isEmpty ? 'PAWCOIN' : channel});
        growthPopupTrigger.afterAction();
        context.pushReplacement('/shop/orders/${order.orderToken}/paid');
      } else {
        // 需扫码：落在待付款详情并自动弹出现有支付面板（关掉面板 = 留在详情、倒计时继续）
        context.pushReplacement('/shop/orders/${order.orderToken}?autopay=1');
      }
    } on CheckoutFailure catch (e) {
      // 结果不明确 → 钉住幂等键；明确业务错误（含 4xx 通用）→ 单没建，解钉
      _idemKeyPinned = e.outcomeUnknown;
      if (!mounted) return;
      setState(() => _submitting = false);
      switch (e.kind) {
        case CheckoutFailureKind.unavailableLines:
          await _showUnavailable(l10n, e.unavailableLines);
        case CheckoutFailureKind.couponInvalid:
          // D17：按最优组合重选重算，停在本页，不自动再提交
          _toast(l10n.checkoutCouponInvalidReselected);
          setState(() {
            _manualCoupons = false;
            _discountToken = null;
            _freeShippingToken = null;
          });
          _refresh();
        case CheckoutFailureKind.quoteExpired:
          // 静默重算，保留其余选择
          _refresh();
        case CheckoutFailureKind.notPlaceable || CheckoutFailureKind.skuNotSellable:
          _toast(l10n.checkoutNotPlaceable);
        case CheckoutFailureKind.generic:
          _toast(l10n.checkoutGenericError);
      }
    } catch (_) {
      // 非 HTTP 层异常（如响应解析失败）：服务端可能已建单 → 同样钉住幂等键
      if (!_placed) _idemKeyPinned = true;
      if (!mounted) return;
      if (!_placed) {
        setState(() => _submitting = false);
        _toast(l10n.checkoutGenericError);
      }
    }
  }

  /// `toko_order_submitted`：原属性（item_count / items）上追加 coupon_ids / coin_used / courier /
  /// shipping_fee / insurance（PRD §4）。🔒 不放订单号、地址、手机号与自由文本。
  void _reportSubmitted(CheckoutPreview p, CourierOption courier) {
    final couponIds = <String>[
      for (final g in [p.discountCoupons, p.freeShippingCoupons])
        if (g.selected?.couponToken != null) g.selected!.couponToken!,
    ];
    final courierId = '${courier.courierCode}_${courier.serviceCode}'.toLowerCase();
    final coinUsed = (p.pricing.coinAmount ?? 0) > 0;
    Analytics.capture('toko_order_submitted', {
      'item_count': p.lines.length,
      // 🔒 items 里只有受控标识与数量：sku_id 是不可枚举 token，无价格、无名称、无 PII
      'items': [
        for (final l in p.lines)
          {
            'sku_id': l.skuToken,
            'qty': l.qty,
            // v1.4.2 8.2 复审 #1：立即购买的行 QuoteLine 不带来源 → 用路由透传的 buyNowEntrySource 兜底。
            'entry_source': l.entrySource ??
                (widget.source == CheckoutSource.buyNow ? widget.buyNowEntrySource : null) ??
                'unknown',
            'trigger_type': l.triggerType ?? 'none',
          },
      ],
      'coupon_ids': couponIds,
      'coin_used': coinUsed,
      'courier': courierId,
      'shipping_fee': courier.price,
      'insurance': p.insurance,
    });
  }

  /// D16：🔴 停在本页，不跳转、不清空选择。CART 只移除被挡住的那几行；BUY_NOW 没有车行可删，
  /// 只给「Batal」并回详情页（待确认 5）。
  Future<void> _showUnavailable(AppLocalizations l10n, List<UnavailableLine> lines) async {
    final removed = await showShopConfirm(
      context,
      dialogKey: const ValueKey('checkoutUnavailableDialogV3'),
      confirmKey: const ValueKey('checkoutRemoveUnavailableV3'),
      title: l10n.checkoutUnavailableTitle,
      confirmLabel: _isBuyNow ? null : l10n.checkoutUnavailableRemove,
      cancelLabel: l10n.commonCancel,
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final l in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(_unavailableText(l10n, l), style: AppTypography.caption),
            ),
        ],
      ),
    );
    if (!mounted) return;
    if (_isBuyNow) {
      context.pop();
      return;
    }
    if (removed) await _removeFromCart([for (final l in lines) l.skuToken]);
  }

  // ================================================================ 杂项

  Widget _row({
    Key? key,
    required IconData icon,
    required Widget title,
    Widget? trailing,
    Color iconColor = ShopColors.text3,
  }) =>
      Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Icon(icon, size: 20, color: iconColor),
            const SizedBox(width: 12),
            Expanded(child: title),
            if (trailing != null) ...[const SizedBox(width: 8), trailing],
          ],
        ),
      );
}

/// 本单选择快照（record 结构相等，用于「当前选择 = 最近一次成功试算」判定与失败回滚）。
typedef _Selection = ({
  String? address,
  String? courier,
  bool insurance,
  bool manual,
  String? discount,
  String? freeShipping,
  bool useCoin,
});
