/// 电商订单详情 v3（v1.4.2 shop Story 9.1 · UI 稿 F4 / F6 / F7 / F9 / F10 / F11 / F12 / F17 / F20 / F21）。
///
/// 取代 `shop_order_detail_page_v2.dart`（同 story 删除，P-4）。版式自上而下：
/// **状态头** → 物流（有运单时）→ 商品 + 金额明细 → 支付方式 → 收货地址 → 单号与下单时间 → 客服 → 底栏。
///
/// ## 🔴 从 v2 原样搬来、不得破坏的行为
/// - 倒计时由服务端下发到期时刻，前端只渲染（[ShopCountdown]）；归零只刷新一次（`_refreshedOnExpiry`）。
/// - 支付走单一入口 [launchShopPayment]（7.2）：`_lastPaymentDeclined` 记账、到账 `pushReplacement` 到 E6、
///   启动器外异常兜底 toast；`autoPay`（7.1 `?autopay=1`）首次拿到数据后自动触发一次。
/// - 取消二次确认（确认键墨色）、复制运单号、跳承运商官网，埋点事件名一律不变。
/// - 订单号展示 `displayNo`（空则回落 `orderToken`），客服深链预填同一个号（Story 3-3 / 4-3）。
/// - SD-5：任何状态都不出现退货入口。
///
/// ## 🔴 v3 新规则（9.1）
/// - **App 不算钱**（P-1）：金额明细、F17 退款合计全部取服务端字段；金额明细止于应付，**不出现 PawCoin**
///   （FR-131.3），币 / 现金只在支付方式卡单列，状态小字按服务端状态位。
/// - 状态头按 `displayStatus`（S-7：宽限期显示已取消），判定在纯函数 [resolveShopOrderHeader]。
/// - 物流：BITESHIP 无异常画 5 段；MANUAL / 存量不画；人工时间线与「非自动追踪」免责行删除（接入 Biteship 后不再人工更新）。
/// - 确认收货**不弹二次确认**（PRD 11-05 拍板）；底栏不再有「Lacak」（物流卡里有）。
///
/// ⚠️ ValueKey 沿用 v2 的 `…V2` 名（`shopOrderPayV2` 等）：它们是支付 / 埋点 / E6 测试的稳定钩子，
/// 改名只会制造无关 diff。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../order/presentation/order_list_controller.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../../support/presentation/support_whatsapp_button.dart';
import '../data/cart_repository.dart';
import '../data/shop_order_repository.dart';
import '../domain/shop_order_detail.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';
import 'shop_order_header.dart';
import 'shop_payment_launcher.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_countdown.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_dialog.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_surface.dart';

/// 订单号 → 客服深链与埋点的 `screen` 属性（沿用 v2）。
const String _kSupportScreen = 'shop_order_detail';

/// `formatIdr` 去掉 `Rp ` 前缀后的数字串（arb 句式里已写了 `Rp {amount}`）。
String _idrDigits(int v) => formatIdr(v).replaceFirst('Rp ', '');

class ShopOrderDetailPageV3 extends ConsumerStatefulWidget {
  const ShopOrderDetailPageV3({super.key, required this.orderToken, this.autoPay = false});

  final String orderToken;

  /// v1.4.2 shop Story 7.1：结算页提交需扫码单后 `?autopay=1` 进来 → 首次拿到订单数据后自动执行一次支付。
  /// 订单已非待付款 / 已过期 / `canPay == false` 不触发。
  final bool autoPay;

  @override
  ConsumerState<ShopOrderDetailPageV3> createState() => _ShopOrderDetailPageV3State();
}

/// 正在进行中的动作 —— 转圈画在**被点的那个按钮**上。
enum _OrderAction { pay, cancel, receipt }

class _ShopOrderDetailPageV3State extends ConsumerState<ShopOrderDetailPageV3> {
  _OrderAction? _busyAction;

  bool get _busy => _busyAction != null;

  /// 倒计时归零后只刷新一次。
  bool _refreshedOnExpiry = false;

  /// autoPay 只触发一次（防重入）。
  bool _autoPayDone = false;

  /// 配送异常状态头曝光只报一次（同一页面实例，刷新不重复，AC4.4）。
  bool _exceptionShownReported = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_order_detail_viewed');
    if (widget.autoPay) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _payWhenLoaded());
    }
  }

  Future<void> _payWhenLoaded() async {
    if (_autoPayDone || !mounted) return;
    _autoPayDone = true;
    final ShopOrderDetail order;
    try {
      order = await ref.read(shopOrderDetailProvider(widget.orderToken).future);
    } catch (_) {
      return; // 详情加载失败由页面自己的重试态承接
    }
    if (!mounted || _busy) return;
    if (!_payable(order)) return;
    await _pay(AppLocalizations.of(context), order);
  }

  /// 「去支付」门控（AC5.2）：原始待支付 + 展示待支付 + 未过期 + 服务端 `canPay != false`。
  /// `canPay` 缺失（旧后端）→ 回落「待付款且到期时刻未过」。
  bool _payable(ShopOrderDetail order) {
    if (!order.status.isPendingPayment || !order.shownStatus.isPendingPayment) return false;
    if (order.canPay == false) return false;
    return !_expired(order);
  }

  bool _expired(ShopOrderDetail order) {
    final at = order.deadline;
    return at != null && !at.isAfter(DateTime.now().toUtc());
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopOrderDetailProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.shopOrderTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.shopOrderLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(shopOrderDetailProvider(widget.orderToken)),
        ),
        data: (order) => RefreshIndicator(
          onRefresh: () => ref.refresh(shopOrderDetailProvider(widget.orderToken).future),
          child: _content(l10n, order),
        ),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (order) => _bottomBar(l10n, order),
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ShopOrderDetail order) {
    final header = resolveShopOrderHeader(order);
    // 非惰性滚动：卡片固定 7 张以内，一次建好（复制单号 / 客服等页底元素不依赖滚动到位才存在）。
    return SingleChildScrollView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.only(top: 12, bottom: 12),
      child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _headerCard(l10n, order, header),
        if (_showsLogistics(order)) _logisticsCard(l10n, order),
        _itemsCard(l10n, order, header),
        _paymentCard(l10n, order),
        _shipToCard(l10n, order),
        _metaCard(l10n, order),
        _helpCard(l10n, order),
      ],
      ),
    );
  }

  // ================================================================ 状态头

  Widget _headerCard(AppLocalizations l10n, ShopOrderDetail order, ShopOrderHeader header) {
    if (header.kind == ShopHeaderKind.pendingPayment) return _countdownCard(l10n, order);

    final (String title, String? hint) = _headerText(l10n, order, header);
    final issue = header.kind == ShopHeaderKind.deliveryIssue;
    if (issue && !_exceptionShownReported) {
      _exceptionShownReported = true;
      Analytics.capture('toko_delivery_exception_shown',
          {'exception_type': deliveryExceptionTypeForAnalytics(order.logistics?.exceptionType)});
    }
    final fromNo = order.reorderedFromOrderNo;
    final warn = header.warnTone;
    final refund = header.refund;
    final refundText = header.refundTextMode != ShopRefundTextMode.none;
    // F19 退为 PawCoin：补偿与现金段两个服务端值并列显示，不相加（P-1，AC3.4 同口径）。
    final bonus = refundText &&
            refund != null &&
            header.refundStage == ShopRefundStage.paidOut &&
            refund.cashDestination == CashDestination.toPawcoin.api &&
            refund.compensationAmount > 0
        ? refund.compensationAmount
        : 0;
    return ShopCard(
      key: ValueKey(warn ? 'shopOrderHeaderCardWarn' : 'shopOrderHeaderCard'),
      color: warn ? ShopColors.warnBg : ShopColors.surface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              key: const ValueKey('shopOrderHeaderTitle'),
              style: AppTypography.body.copyWith(
                  fontWeight: FontWeight.w600, color: warn ? ShopColors.warnTitle : ShopColors.ink)),
          if (!refundText && header.hint == ShopHeaderHint.reorderedFrom && fromNo != null) ...[
            const SizedBox(height: 4),
            _reorderedFromHint(l10n, order, fromNo),
          ] else if (hint != null) ...[
            const SizedBox(height: 4),
            Text(hint,
                key: const ValueKey('shopOrderHeaderHint'),
                style: AppTypography.caption
                    .copyWith(color: warn ? ShopColors.warnText : ShopColors.text2)),
          ],
          if (bonus > 0) ...[
            const SizedBox(height: 2),
            Text(l10n.shopRefundBonus(_idrDigits(bonus)),
                key: const ValueKey('shopOrderRefundBonus'),
                style: AppTypography.caption.copyWith(color: ShopColors.purple, fontWeight: FontWeight.w600)),
          ],
          if (header.showsReenterAccount && refund != null) ...[
            const SizedBox(height: 12),
            ShopButton(
              key: const ValueKey('shopOrderRefundReenter'),
              label: l10n.shopRefundReenterAccount,
              variant: ShopButtonVariant.ink,
              onTap: () => _openRefundDestination(order, refund, retry: true),
            ),
          ],
          if (header.kind == ShopHeaderKind.reordered && order.reorderedToOrderToken != null) ...[
            const SizedBox(height: 8),
            ShopPressable(
              key: const ValueKey('shopOrderViewNewOrder'),
              onTap: () => context.push('/shop/orders/${order.reorderedToOrderToken}'),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(l10n.shopOrderViewNewOrder,
                        style: AppTypography.caption
                            .copyWith(color: ShopColors.accentDark, fontWeight: FontWeight.w600)),
                    const Icon(Icons.chevron_right_rounded, size: 18, color: ShopColors.accentDark),
                  ],
                ),
              ),
            ),
          ],
          if (issue) ...[
            const SizedBox(height: 12),
            ShopButton(
              key: const ValueKey('shopOrderHeaderContactCs'),
              label: l10n.shopOrderContactCs,
              variant: ShopButtonVariant.purple,
              onTap: () => _contactCs(order),
            ),
          ],
          if (_showsReturnNotice(order, header)) ...[
            const SizedBox(height: 10),
            Row(
              key: const ValueKey('shopOrderCoinCouponReturned'),
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.confirmation_number_outlined, size: 16, color: ShopColors.accent),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                      l10n.shopOrderCoinCouponReturned(
                          _idrDigits(order.paymentMethods?.coinAmount ?? 0)),
                      style: AppTypography.caption.copyWith(color: ShopColors.text2)),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  /// (标题, 副句)。副句为 null 时不渲染副句行。
  (String, String?) _headerText(AppLocalizations l10n, ShopOrderDetail order, ShopOrderHeader h) {
    final (String title, String? hint) = switch (h.kind) {
      ShopHeaderKind.pendingPayment => (l10n.shopOrderStatusPendingPayment, null),
      ShopHeaderKind.pendingShipment =>
        (l10n.shopOrderStatusPendingShipment, l10n.shopOrderPendingShipmentHint),
      ShopHeaderKind.shipped => (l10n.shopOrderShippedTitle, l10n.shopOrderShippedHint),
      ShopHeaderKind.delivered => (l10n.shopOrderDeliveredTitle, l10n.shopOrderDeliveredHintV3),
      ShopHeaderKind.deliveryIssue => (l10n.shopOrderDeliveryIssue, l10n.shopOrderDeliveryIssueHint),
      ShopHeaderKind.completed => (l10n.shopOrderStatusCompleted, l10n.shopOrderCompletedThanks),
      ShopHeaderKind.cancelledTimeout =>
        (l10n.shopOrderStatusCancelled, l10n.shopOrderTimeoutCancelledHint),
      // F13：副句与退款入口归 9.2。
      ShopHeaderKind.cancelledOutOfStock => (l10n.shopOrderCancelledOutOfStock, null),
      // 其余原因 §6 无对应句 → 只显示标题（待确认 4）。
      ShopHeaderKind.cancelled => (l10n.shopOrderStatusCancelled, null),
      ShopHeaderKind.reordered => (l10n.shopOrderReorderedTitle, l10n.shopOrderReorderedHint),
      ShopHeaderKind.refunding => (l10n.orderStatusRefunding, null),
      ShopHeaderKind.refunded => (l10n.orderStatusRefunded, null),
      ShopHeaderKind.other => (l10n.shopOrderStatusOther, null),
    };
    final baseHint = h.hint == ShopHeaderHint.partialCancel ? l10n.shopOrderPartialCancelHint : hint;
    // 9.2 退款分支：按覆盖程度换标题 / 副句（纯函数已判定，见 [ShopRefundTextMode]）。
    final r = h.refund;
    final stage = h.refundStage;
    if (r == null || stage == null || h.refundTextMode == ShopRefundTextMode.none) return (title, baseHint);
    final (String rTitle, String? rHint) = _refundText(l10n, r, stage);
    return h.refundTextMode == ShopRefundTextMode.full ? (rTitle, rHint) : (title, rHint ?? baseHint);
  }

  /// 退款态文案（PRD §6 F13 / F16 / F19 / F25）。金额全部取服务端字段；
  /// 尾号 / 渠道 / 日期任一缺失 → 副句为 null（不拼残句，AC1.3）。
  (String, String?) _refundText(AppLocalizations l10n, ShopOrderRefundSummary r, ShopRefundStage stage) {
    final channel = PayoutChannel.fromApi(r.payoutChannel);
    final last4 = r.payoutAccountLast4;
    switch (stage) {
      case ShopRefundStage.awaitingDestination:
        return (
          l10n.shopOrderCancelledOutOfStock,
          l10n.shopRefundOutOfStockHint(_idrDigits(r.coinRefund), _idrDigits(r.cashRefund)),
        );
      case ShopRefundStage.payoutPending:
        return (
          l10n.shopRefundProcessingTitle,
          channel != null && last4 != null ? l10n.shopRefundProcessingHint(channel.shortName, last4) : null,
        );
      case ShopRefundStage.paidOut:
        if (r.cashDestination == CashDestination.toPawcoin.api) {
          return (l10n.orderStatusRefunded, l10n.shopRefundToPawcoinDoneHint(_idrDigits(r.cashRefund)));
        }
        final at = r.paidOutAt;
        if (channel == null || last4 == null || at == null) return (l10n.orderStatusRefunded, null);
        // 日期按 WIB 自然日显示（UTC+7，不随手机时区）。
        final wib = at.toUtc().add(const Duration(hours: 7));
        final day = formatDayMonthYear(context, DateTime(wib.year, wib.month, wib.day));
        return (
          l10n.orderStatusRefunded,
          l10n.shopRefundPaidOutHint(
              _idrDigits(payoutNetForDisplay(r.cashRefund, r.payoutFee)), channel.shortName, last4, day),
        );
      case ShopRefundStage.payoutFailed:
        return (l10n.shopRefundPayoutFailedTitle, l10n.shopRefundPayoutFailedHint);
    }
  }

  /// 进退款方式页（AC2.1）。F25 重填：`?retry=1&channel=` 预选上次渠道（query 兼容冷启动深链）。
  void _openRefundDestination(ShopOrderDetail order, ShopOrderRefundSummary r, {bool retry = false}) {
    final base = '/shop/orders/${order.orderToken}/refunds/${r.refundToken}';
    if (!retry) {
      context.push(base);
      return;
    }
    final q = <String, String>{'retry': '1', 'channel': ?r.payoutChannel};
    context.push(Uri(path: base, queryParameters: q).toString());
  }

  /// F21：「Pesanan ini dibuat ulang dari pesanan {no}.」—— 单号紫色；有原单 token 时整句可点回原单。
  Widget _reorderedFromHint(AppLocalizations l10n, ShopOrderDetail order, String no) {
    final full = l10n.shopOrderReorderedFrom(no);
    final i = full.indexOf(no);
    final base = AppTypography.caption.copyWith(color: ShopColors.text2);
    final text = Text.rich(
      key: const ValueKey('shopOrderHeaderHint'),
      i < 0
          ? TextSpan(text: full)
          : TextSpan(children: [
              TextSpan(text: full.substring(0, i)),
              TextSpan(
                  text: no,
                  style: const TextStyle(color: ShopColors.accent, fontWeight: FontWeight.w600)),
              TextSpan(text: full.substring(i + no.length)),
            ]),
      style: base,
    );
    final fromToken = order.reorderedFromOrderToken;
    if (fromToken == null) return text;
    return ShopPressable(
      key: const ValueKey('shopOrderReorderedFromLink'),
      onTap: () => context.push('/shop/orders/$fromToken'),
      child: text,
    );
  }

  /// F12 退回说明条：券已退回 **且** 币段已退回才显示（EXPIRED §6 无文案，不显示，待确认 4）。
  bool _showsReturnNotice(ShopOrderDetail order, ShopOrderHeader h) {
    final cancelledKind = h.kind == ShopHeaderKind.cancelled ||
        h.kind == ShopHeaderKind.cancelledTimeout ||
        h.kind == ShopHeaderKind.cancelledOutOfStock;
    return cancelledKind &&
        order.couponReturn == 'RETURNED' &&
        order.paymentMethods?.coinStatus == 'RETURNED' &&
        (order.paymentMethods?.coinAmount ?? 0) > 0;
  }

  /// 待付款（F4）：倒计时块即状态头。
  Widget _countdownCard(AppLocalizations l10n, ShopOrderDetail order) {
    final deadline = order.deadline;
    if (deadline == null) {
      return ShopCard(
        key: const ValueKey('shopOrderHeaderCard'),
        child: Text(l10n.shopOrderStatusPendingPayment,
            key: const ValueKey('shopOrderHeaderTitle'),
            style: AppTypography.body.copyWith(fontWeight: FontWeight.w600, color: ShopColors.ink)),
      );
    }
    final expired = _expired(order);
    return ShopCard(
      key: const ValueKey('shopOrderHeaderCard'),
      child: SizedBox(
        width: double.infinity,
        child: Column(
          children: [
            Text(
              // 旧后端（无 displayStatus）到点后的过渡态：等服务端刷新告诉我们它真的关单了。
              expired ? l10n.shopOrderExpiredNotice : l10n.shopOrderCountdownTitle,
              key: const ValueKey('shopOrderHeaderTitle'),
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600, color: ShopColors.ink),
            ),
            if (!expired) ...[
              const SizedBox(height: 4),
              ShopCountdown(
                key: const ValueKey('shopOrderCountdownV2'),
                expiresAt: deadline,
                style: ShopText.countdownHero.copyWith(color: ShopColors.accent),
                // 🔴 前端不自行把状态改成过期：归零只拉一次服务端（S-7：60~65 分钟显示已取消由服务端决定）。
                onExpired: () {
                  if (_refreshedOnExpiry || !mounted) return;
                  _refreshedOnExpiry = true;
                  ref.invalidate(shopOrderDetailProvider(widget.orderToken));
                },
              ),
              const SizedBox(height: 4),
              Text(l10n.shopOrderCountdownHint,
                  textAlign: TextAlign.center,
                  style: AppTypography.caption.copyWith(color: ShopColors.text2)),
            ],
          ],
        ),
      ),
    );
  }

  // ================================================================ 物流

  /// 物流卡：已发货 / 已送达（含配送异常）/ 已完成，且有运单信息。待付款 / 待发货 / 已取消不显示。
  bool _showsLogistics(ShopOrderDetail order) {
    final s = order.shownStatus;
    final stage = s == ShopOrderStatus.shipped ||
        s == ShopOrderStatus.delivered ||
        s == ShopOrderStatus.completed;
    return stage && (order.logistics != null || order.packages.isNotEmpty);
  }

  Widget _logisticsCard(AppLocalizations l10n, ShopOrderDetail order) {
    final lg = order.logistics;
    // 🔴 有 `logistics`（最新活动运单）就只用它，不回落 packages（9.1 复审）：packages 可能含已被快递原因重发
    //    取代的旧运单，拿它的 trackingUrl 会把用户带去查一张作废的单。存量单才回落 packages ——
    //    取**未被取代的最后一条**。
    final pkg = lg != null || order.packages.isEmpty
        ? null
        : order.packages.lastWhere((p) => !p.superseded, orElse: () => order.packages.last);
    final courier = lg != null ? (lg.courierName ?? '') : (pkg?.carrierName ?? '');
    final service = lg?.courierServiceName;
    final waybill = lg != null ? (lg.waybillId ?? '') : (pkg?.trackingNo ?? '');
    final trackingUrl = lg != null ? (lg.trackingUrl ?? '') : (pkg?.trackingUrl ?? '');
    final step = lg != null && lg.isBiteship && !order.hasDeliveryIssue
        ? logisticsStepOf(lg.statusKey)
        : null;

    return ShopCard(
      key: const ValueKey('shopOrderLogisticsCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(l10n.shopOrderShippingSection,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
              ),
              if (trackingUrl.isNotEmpty)
                ShopPressable(
                  key: const ValueKey('shopOrderTrackSiteV2'),
                  onTap: () => _openCarrierSite(l10n, trackingUrl),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text('${l10n.shopOrderTrackOnCarrierSite} ↗',
                        style: AppTypography.caption
                            .copyWith(color: ShopColors.accent, fontWeight: FontWeight.w600)),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 6),
          Text(service == null || service.isEmpty ? courier : '$courier · $service',
              key: const ValueKey('shopOrderCourierLine'),
              style: AppTypography.caption.copyWith(color: ShopColors.text2)),
          if (waybill.isNotEmpty)
            Row(
              children: [
                Expanded(
                  // 🔴 运单号用等宽 —— 用户要逐位核对。
                  child: Text(waybill,
                      key: const ValueKey('shopOrderWaybill'),
                      style: const TextStyle(
                          fontFamily: ShopText.mono,
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          color: ShopColors.text)),
                ),
                ShopPressable(
                  key: const ValueKey('shopOrderCopyResiV2'),
                  onTap: () => _copyTracking(l10n, waybill),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
                    child: Text(l10n.shopOrderCopy,
                        style: AppTypography.caption
                            .copyWith(color: ShopColors.accent, fontWeight: FontWeight.w600)),
                  ),
                ),
              ],
            ),
          if (step != null) ...[
            const SizedBox(height: 12),
            _LogisticsTimeline(
              current: step,
              labels: [
                l10n.shopOrderLogisticsAwaitingPickup,
                l10n.shopOrderLogisticsPicked,
                l10n.shopOrderPackageInTransit,
                l10n.shopOrderLogisticsOutForDelivery,
                l10n.shopOrderLogisticsDelivered,
              ],
            ),
          ],
        ],
      ),
    );
  }

  // ================================================================ 商品与金额明细

  Widget _itemsCard(AppLocalizations l10n, ShopOrderDetail order, ShopOrderHeader header) {
    final pending = order.shownStatus.isPendingPayment;
    // F17 只在真·部分取消时画行标与降权（整单取消时 cancelledLines 含全部行，不画，见 [isPartialCancel]）。
    final partial = isPartialCancel(order);
    final cancelledByIndex = {
      if (partial)
        for (final c in order.cancelledLines) c.lineIndex: c
    };
    // 行标文案：行级退款单原因为缺货 →「Dibatalkan · stok habis」，否则只写「Dibatalkan」（AC6.1）。
    final outOfStock = order.refunds.any((r) => r.isLine && r.reason == 'OUT_OF_STOCK');
    return ShopCard(
      key: const ValueKey('shopOrderItemsCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < order.lines.length; i++) ...[
            _lineRow(l10n, order.lines[i], i,
                pending: pending, cancelled: cancelledByIndex[i], outOfStock: outOfStock),
            const SizedBox(height: 12),
          ],
          const ShopDivider(margin: EdgeInsets.only(bottom: 8)),
          ..._amountRows(l10n, order, header),
          ..._partialRefundRows(l10n, order),
        ],
      ),
    );
  }

  Widget _lineRow(AppLocalizations l10n, ShopOrderLine line, int index,
      {required bool pending, ShopCancelledLine? cancelled, bool outOfStock = false}) {
    final row = Row(
      key: ValueKey('shopOrderLine_$index'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ShopImage(url: line.mainImageUrl, size: 52, radius: 12),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // C-11：标题 → 价格。
              Text('${line.productName} · ${line.specName}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.caption.copyWith(color: ShopColors.ink, fontWeight: FontWeight.w500)),
              if (cancelled != null) ...[
                const SizedBox(height: 4),
                Container(
                  key: ValueKey('shopOrderLineCancelledTag_$index'),
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0xFFFDE7EB),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                      outOfStock ? l10n.shopOrderCancelledOutOfStock : l10n.shopOrderStatusCancelled,
                      style: const TextStyle(
                          fontSize: 11, fontWeight: FontWeight.w600, color: ShopColors.errorText)),
                ),
              ],
              const SizedBox(height: 4),
              Row(
                children: [
                  Expanded(
                    child: Text(formatIdr(line.unitPrice),
                        style: AppTypography.caption.copyWith(
                            fontWeight: FontWeight.w600,
                            color: pending ? ShopColors.accent : ShopColors.ink)),
                  ),
                  Text('×${line.qty}', style: AppTypography.caption),
                ],
              ),
            ],
          ),
        ),
      ],
    );
    return cancelled == null ? row : Opacity(opacity: 0.55, child: row);
  }

  /// 金额明细（FR-130 顺序）。🔴 只展示服务端字段，不做任何加减（P-1）；不出现 PawCoin（FR-131.3）。
  List<Widget> _amountRows(AppLocalizations l10n, ShopOrderDetail order, ShopOrderHeader header) {
    final a = order.amounts;
    final pending = order.shownStatus.isPendingPayment;
    final rows = <Widget>[];
    if (a != null) {
      rows.add(_amountRow(l10n.checkoutSubtotal, formatIdr(a.goodsAmount),
          key: const ValueKey('shopOrderAmountSubtotal')));
      if (a.discountCouponAmount > 0) {
        rows.add(_amountRow(l10n.checkoutCouponDiscount, '−${formatIdr(a.discountCouponAmount)}',
            key: const ValueKey('shopOrderAmountCoupon'), discount: true));
      }
      final courier = a.courierName;
      rows.add(_amountRow(
          courier == null ? l10n.checkoutShippingFee : '${l10n.checkoutShippingFee} ($courier)',
          formatIdr(a.shippingFee),
          key: const ValueKey('shopOrderAmountShipping')));
      if (a.shippingDiscount > 0) {
        final label = switch (a.shippingDiscountSource) {
          'COUPON' => l10n.checkoutFreeShippingCouponRow,
          'FREE_SHIPPING_RULE' => l10n.checkoutAutoShipDiscount,
          _ => l10n.checkoutFreeShipping,
        };
        rows.add(_amountRow(label, '−${formatIdr(a.shippingDiscount)}',
            key: const ValueKey('shopOrderAmountShipDiscount'), discount: true));
      }
      if (a.insuranceFee > 0) {
        rows.add(_amountRow(l10n.checkoutInsuranceTitle, formatIdr(a.insuranceFee),
            key: const ValueKey('shopOrderAmountInsurance')));
      }
    } else {
      // 存量 / 旧后端：回落旧字段（subtotal / 运费 / 免运费 / 合计）。
      rows.add(_amountRow(l10n.checkoutSubtotal, formatIdr(order.goodsSubtotal),
          key: const ValueKey('shopOrderAmountSubtotal')));
      rows.add(_amountRow(l10n.checkoutShippingFee, formatIdr(order.shippingFee),
          key: const ValueKey('shopOrderAmountShipping')));
      if (order.shippingDiscount != 0) {
        rows.add(_amountRow(l10n.checkoutFreeShipping, '−${formatIdr(order.shippingDiscount.abs())}',
            key: const ValueKey('shopOrderAmountShipDiscount'), discount: true));
      }
    }
    final cancelledKind = switch (header.kind) {
      ShopHeaderKind.cancelled ||
      ShopHeaderKind.cancelledTimeout ||
      ShopHeaderKind.cancelledOutOfStock ||
      ShopHeaderKind.reordered ||
      ShopHeaderKind.refunding ||
      ShopHeaderKind.refunded =>
        true,
      _ => false,
    };
    final label = pending
        ? l10n.checkoutPayable
        : cancelledKind
            ? l10n.shopOrderCancelledTotalLabel
            : l10n.shopOrderTotalPaid;
    rows.add(const SizedBox(height: 6));
    rows.add(Row(
      children: [
        Expanded(
          child: Text(label,
              key: const ValueKey('shopOrderTotalLabelV2'),
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
        ),
        // 🔴 合计恒为应付（`amounts.payable`，旧后端 `totalAmount`）；币 / 现金只在支付方式卡。
        Text(formatIdr(a?.payable ?? order.totalAmount),
            key: const ValueKey('shopOrderTotalV2'),
            style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                color: pending ? ShopColors.accent : ShopColors.ink)),
      ],
    ));
    return rows;
  }

  /// F17：「Dikembalikan (n produk dibatalkan)」+ 金额 = `refunds[scope=LINE].totalRefund` 之和；
  /// 小字拆分 = 同上 coin / cash 之和。🔴 全部是服务端值，App 只做同类字段求和展示，不分摊不取整。
  List<Widget> _partialRefundRows(AppLocalizations l10n, ShopOrderDetail order) {
    if (!isPartialCancel(order)) return const [];
    final lineRefunds = order.refunds.where((r) => r.isLine);
    final n = order.cancelledLines.fold<int>(0, (s, c) => s + c.cancelledQty);
    final total = lineRefunds.fold<int>(0, (s, r) => s + r.totalRefund);
    final coin = lineRefunds.fold<int>(0, (s, r) => s + r.coinRefund);
    final cash = lineRefunds.fold<int>(0, (s, r) => s + r.cashRefund);
    return [
      const SizedBox(height: 8),
      _amountRow(l10n.shopOrderPartialRefundTotal(n), formatIdr(total),
          key: const ValueKey('shopOrderPartialRefundTotal'), discount: true),
      Align(
        alignment: Alignment.centerRight,
        child: Text(l10n.shopOrderPartialRefundSplit(_idrDigits(coin), _idrDigits(cash)),
            key: const ValueKey('shopOrderPartialRefundSplit'),
            style: AppTypography.disclaimer.copyWith(color: ShopColors.text3)),
      ),
    ];
  }

  Widget _amountRow(String label, String value, {Key? key, bool discount = false}) => Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            Expanded(
                child: Text(label, style: AppTypography.caption.copyWith(color: ShopColors.text2))),
            Text(value,
                style: AppTypography.caption.copyWith(
                    color: discount ? ShopColors.accentDark : ShopColors.ink,
                    fontWeight: discount ? FontWeight.w600 : FontWeight.w500)),
          ],
        ),
      );

  // ================================================================ 支付方式

  Widget _paymentCard(AppLocalizations l10n, ShopOrderDetail order) {
    final pm = order.paymentMethods;
    final int? coin;
    final String? coinStatus;
    final int? cash;
    final String? cashStatus;
    if (pm != null) {
      coin = pm.coinAmount;
      cash = pm.cashAmount;
      coinStatus = switch (pm.coinStatus) {
        'HELD' => l10n.shopOrderCoinHeld,
        'PAID' => l10n.shopOrderPaidLabel,
        'RETURNED' => l10n.shopOrderCoinReturned,
        _ => null,
      };
      cashStatus = switch (pm.cashStatus) {
        'AWAITING' => l10n.shopOrderQrisWaiting2,
        'PAID' => l10n.shopOrderPaidLabel,
        'UNPAID' => l10n.shopOrderQrisUnpaid,
        _ => null,
      };
    } else {
      // 旧后端：按 v2 旧逻辑由原始状态推（D-19：取消态说「已退回」，不说「已支付」）。
      final pending = order.status.isPendingPayment;
      final cancelled = order.status == ShopOrderStatus.cancelled;
      coin = (order.coinAmount ?? 0) > 0 ? order.coinAmount : null;
      cash = (order.cashAmount ?? 0) > 0 ? order.cashAmount : null;
      coinStatus = cancelled
          ? l10n.shopOrderCoinReturned
          : (pending ? l10n.shopOrderCoinHeld : l10n.shopOrderPaidLabel);
      cashStatus = cancelled
          ? l10n.shopOrderQrisUnpaid
          : (pending ? l10n.shopOrderQrisWaiting2 : l10n.shopOrderPaidLabel);
    }
    if (coin == null && cash == null) return const SizedBox.shrink();

    return ShopCard(
      key: const ValueKey('shopOrderPaymentCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutPaymentMethod,
              style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          if (coin != null)
            _payRow(
              rowKey: const ValueKey('shopOrderCoinRowV2'),
              statusKey: const ValueKey('shopOrderCoinStatusV2'),
              icon: Icons.pets,
              title: l10n.checkoutPawcoin,
              status: coinStatus,
              amount: coin,
            ),
          if (cash != null)
            _payRow(
              rowKey: const ValueKey('shopOrderCashRow'),
              statusKey: const ValueKey('shopOrderCashStatus'),
              icon: Icons.qr_code_2,
              title: l10n.checkoutQris,
              status: cashStatus,
              amount: cash,
            ),
          // V1.4.2 验收修复（BUG-142-03）：上次付款被网关拒绝、订单仍待付款 → 卡片里常驻说明。
          // 拒付 toast 很短、面板会自动关，之前页面上只剩「Menunggu」，用户不知道发生了什么。复用既有文案。
          if (order.status.isPendingPayment &&
              (_lastPaymentDeclined || order.paymentFailure == ShopPaymentFailure.gatewayDeclined))
            Padding(
              key: const ValueKey('shopOrderPaymentDeclinedNote'),
              padding: const EdgeInsets.only(top: 6),
              child: Text(l10n.shopPaymentDeclinedNotice,
                  style: AppTypography.disclaimer.copyWith(color: AppColors.danger)),
            ),
        ],
      ),
    );
  }

  Widget _payRow({
    required Key rowKey,
    required Key statusKey,
    required IconData icon,
    required String title,
    required String? status,
    required int amount,
  }) =>
      Padding(
        key: rowKey,
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            Icon(icon, size: 20, color: ShopColors.accent),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: AppTypography.caption.copyWith(color: ShopColors.ink)),
                  if (status != null)
                    Text(status, key: statusKey, style: AppTypography.disclaimer),
                ],
              ),
            ),
            Text(formatIdr(amount),
                style: AppTypography.caption
                    .copyWith(color: ShopColors.ink, fontWeight: FontWeight.w600)),
          ],
        ),
      );

  // ================================================================ 地址 / 单号 / 客服

  Widget _shipToCard(AppLocalizations l10n, ShopOrderDetail order) => ShopCard(
        key: const ValueKey('shopOrderShipToCard'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.shopOrderShipTo,
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Text('${order.receiverName} · ${order.receiverPhone}',
                style: AppTypography.caption
                    .copyWith(color: ShopColors.ink, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(order.addressText, style: AppTypography.caption),
          ],
        ),
      );

  Widget _metaCard(AppLocalizations l10n, ShopOrderDetail order) => ShopCard(
        key: const ValueKey('shopOrderMetaCard'),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(child: Text(l10n.orderNumberLabel, style: AppTypography.caption)),
                // 🔴 订单号用等宽 —— 用户要报给客服、要逐位核对。
                Text(_displayedOrderNo(order), style: ShopText.serialNo),
                ShopPressable(
                  key: const ValueKey('shopOrderCopyOrderNo'),
                  onTap: () => _copyOrderNo(l10n, order),
                  child: const Padding(
                    padding: EdgeInsets.all(8),
                    child: Icon(Icons.content_copy, size: 16, color: ShopColors.text3),
                  ),
                ),
              ],
            ),
            if (order.createdAt != null)
              Row(
                children: [
                  Expanded(child: Text(l10n.orderCreatedAtLabel, style: AppTypography.caption)),
                  Text(formatDayMonthYearTime(context, order.createdAt!.toLocal()),
                      style: AppTypography.caption),
                ],
              ),
          ],
        ),
      );

  /// 本页展示给用户的那个订单号，唯一一处（Story 3-3 / 4-3）：页面、复制、客服深链同源。
  /// `displayNo` 为空回落 `orderToken`（灰度期老后端）。
  String _displayedOrderNo(ShopOrderDetail order) =>
      order.displayNo.isEmpty ? order.orderToken : order.displayNo;

  Widget _helpCard(AppLocalizations l10n, ShopOrderDetail order) => ShopCard(
        key: const ValueKey('shopOrderHelpBlockV2'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.shopOrderHelpTitle,
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(l10n.shopOrderHelpBody, style: AppTypography.caption),
            const SizedBox(height: 10),
            SupportWhatsAppButton(orderNo: _displayedOrderNo(order), screen: _kSupportScreen),
          ],
        ),
      );

  // ================================================================ 底栏

  Widget? _bottomBar(AppLocalizations l10n, ShopOrderDetail order) {
    final header = resolveShopOrderHeader(order);
    final refund = header.refund;
    if (header.showsChooseRefund && refund != null) {
      // F13 / F17：「Pilih cara pengembalian dana」。仍可确认收货的单（S-9 签收后赔付）把确认收货降为次按钮。
      final choose = ShopButton(
        key: const ValueKey('shopOrderChooseRefund'),
        label: l10n.shopRefundChooseMethod,
        variant: ShopButtonVariant.ink,
        onTap: _busy ? null : () => _openRefundDestination(order, refund),
      );
      if (!order.status.canConfirmReceipt) return ShopBottomBarActions(primary: choose);
      return ShopBottomBarActions(
        secondary: ShopButton(
          key: const ValueKey('shopOrderConfirmReceiptV2'),
          label: l10n.shopOrderConfirmReceipt,
          variant: _busy && _busyAction != _OrderAction.receipt
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.outlineMuted,
          loading: _busyAction == _OrderAction.receipt,
          onTap: _busy ? null : () => _confirmReceipt(l10n),
        ),
        primary: choose,
      );
    }

    if (_payable(order)) {
      final pm = order.paymentMethods;
      // 🔴 按钮金额是**现在真要付的现金段**（服务端 `paymentMethods.cashAmount`；旧后端回落 shopCashSegment）。
      final cash = pm != null ? (pm.cashAmount ?? 0) : shopCashSegment(order);
      return ShopBottomBarActions(
        secondaryFlex: 1,
        primaryFlex: 2,
        secondary: ShopButton(
          key: const ValueKey('shopOrderCancelV2'),
          label: l10n.shopOrderCancelShort,
          variant: _busy && _busyAction != _OrderAction.cancel
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.outlineMuted,
          loading: _busyAction == _OrderAction.cancel,
          onTap: _busy ? null : () => _cancel(l10n, order),
        ),
        primary: ShopButton(
          key: const ValueKey('shopOrderPayV2'),
          label: '${l10n.shopOrderPayNow} ${formatIdr(cash)}',
          // 🔴 支付请求进行中保持强调色 + 转圈，**不置灰**（2026-08-27）。
          variant: _busy && _busyAction != _OrderAction.pay
              ? ShopButtonVariant.disabled
              : ShopButtonVariant.pay,
          loading: _busyAction == _OrderAction.pay,
          onTap: _busy ? null : () => _pay(l10n, order),
        ),
      );
    }

    if (order.status.canConfirmReceipt) {
      // F10：配送异常时主操作「Hubungi CS」在状态头内，确认收货降为次按钮样式（FR-172.7 仍可点）。
      final issue = order.hasDeliveryIssue;
      return ShopBottomBarActions(
        primary: ShopButton(
          key: const ValueKey('shopOrderConfirmReceiptV2'),
          label: l10n.shopOrderConfirmReceipt,
          variant: _busy && _busyAction != _OrderAction.receipt
              ? ShopButtonVariant.disabled
              : (issue ? ShopButtonVariant.outlineMuted : ShopButtonVariant.ink),
          loading: _busyAction == _OrderAction.receipt,
          onTap: _busy ? null : () => _confirmReceipt(l10n),
        ),
      );
    }

    // 🔴 SD-5：已完成态不给退货入口；其余状态（待发货 / 已取消 / 退款处理中 / 已退款）无底栏。
    return null;
  }

  // ================================================================ 动作

  Future<void> _copyTracking(AppLocalizations l10n, String waybill) async {
    await Clipboard.setData(ClipboardData(text: waybill));
    Analytics.capture('toko_order_tracking_copy_tapped');
    if (mounted) showAppToast(context, l10n.shopOrderCopied);
  }

  Future<void> _copyOrderNo(AppLocalizations l10n, ShopOrderDetail order) async {
    await Clipboard.setData(ClipboardData(text: _displayedOrderNo(order)));
    if (mounted) showAppToast(context, l10n.commonCopied);
  }

  Future<void> _openCarrierSite(AppLocalizations l10n, String url) async {
    Analytics.capture('toko_order_tracking_site_tapped');
    final uri = Uri.tryParse(url);
    final ok = uri == null ? false : await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) showAppToast(context, l10n.shopOrderTrackOpenFailed);
  }

  void _contactCs(ShopOrderDetail order) {
    Analytics.capture('toko_delivery_exception_cs_tapped',
        {'exception_type': deliveryExceptionTypeForAnalytics(order.logistics?.exceptionType)});
    openSupportWhatsApp(context, ref, orderNo: _displayedOrderNo(order), screen: _kSupportScreen);
  }

  /// 本次会话内看到过「被拒」（Story 1-4 AC2）。跨页面的那一半由服务端 `paymentFailureCategory` 补齐。
  bool _lastPaymentDeclined = false;

  bool _isRetry(ShopOrderDetail order) =>
      _lastPaymentDeclined || order.paymentFailure == ShopPaymentFailure.gatewayDeclined;

  /// 发起支付 —— 7.2 单一入口 [launchShopPayment]，本页只做 `_lastPaymentDeclined` 记账、到账跳 E6、刷新。
  Future<void> _pay(AppLocalizations l10n, ShopOrderDetail order) async {
    setState(() => _busyAction = _OrderAction.pay);
    try {
      final outcome = await launchShopPayment(context, ref, order, isRetry: _isRetry(order));
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      _invalidateLists();
      switch (outcome) {
        case ShopPaymentOutcome.declined:
          _lastPaymentDeclined = true;
        case ShopPaymentOutcome.dismissed:
        case ShopPaymentOutcome.failed:
          break;
        case ShopPaymentOutcome.settledByCoin:
        case ShopPaymentOutcome.paid:
        case ShopPaymentOutcome.expired:
        case ShopPaymentOutcome.userCancelled:
        case ShopPaymentOutcome.abortedUnknown:
        case ShopPaymentOutcome.abortedUnknownCategory:
          _lastPaymentDeclined = false;
      }
      if (outcome == ShopPaymentOutcome.paid || outcome == ShopPaymentOutcome.settledByCoin) {
        context.pushReplacement('/shop/orders/${widget.orderToken}/paid');
      }
    } catch (_) {
      if (mounted) {
        Analytics.capture('toko_order_payment_failed_shown');
        showAppToast(context, l10n.shopOrderPayFailed);
      }
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  /// 订单状态变了 → 订单列表与角标跟着刷（9.1）。
  void _invalidateLists() {
    ref.invalidate(orderListProvider);
    ref.invalidate(shopOrderCountsProvider);
  }

  /// 确认收货：**不弹二次确认**（PRD 11-05 拍板，AC4.5）。按钮转圈期间不可重复点。
  Future<void> _confirmReceipt(AppLocalizations l10n) async {
    if (_busy) return;
    Analytics.capture('toko_order_receipt_confirm_tapped');
    setState(() => _busyAction = _OrderAction.receipt);
    try {
      await ref.read(shopOrderRepositoryProvider).confirmReceipt(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      _invalidateLists();
      Analytics.capture('toko_order_receipt_confirm_succeeded');
      showAppToast(context, l10n.shopOrderReceiptConfirmed);
    } catch (e) {
      if (mounted) {
        showAppToast(
            context,
            isReturnInboundConfirmedError(e)
                ? l10n.shopOrderConfirmReceiptReturnInbound
                : l10n.shopOrderConfirmReceiptFailed);
      }
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }

  Future<void> _cancel(AppLocalizations l10n, ShopOrderDetail order) async {
    // F5：订单有币段或用了券才追加「PawCoin dan kupon akan dikembalikan.」。
    final hasCoin = (order.paymentMethods?.coinAmount ?? order.coinAmount ?? 0) > 0;
    final a = order.amounts;
    final usedCoupon = a != null &&
        (a.discountCouponAmount > 0 ||
            (a.shippingDiscountSource == 'COUPON' && a.shippingDiscount > 0));
    final body = hasCoin || usedCoupon
        ? '${l10n.shopOrderCancelConfirmBody} ${l10n.shopOrderCancelRefundHint}'
        : l10n.shopOrderCancelConfirmBody;
    // 🔴 确认按钮**不用 pay 变体**（2026-08-27）：确认「取消订单」不能长得像转化 CTA。
    final confirmed = await showShopConfirm(
      context,
      dialogKey: const ValueKey('shopOrderCancelDialogV2'),
      confirmKey: const ValueKey('shopOrderCancelConfirmYesV2'),
      title: l10n.shopOrderCancelConfirm,
      body: body,
      confirmLabel: l10n.shopOrderCancelConfirmYes,
      cancelLabel: l10n.shopOrderCancelConfirmNo,
    );
    if (!confirmed || !mounted) return;

    Analytics.capture('toko_order_cancel_tapped');
    setState(() => _busyAction = _OrderAction.cancel);
    try {
      await ref.read(shopOrderRepositoryProvider).cancel(widget.orderToken);
      if (!mounted) return;
      ref.invalidate(shopOrderDetailProvider(widget.orderToken));
      ref.invalidate(cartProvider);
      ref.invalidate(pawCoinProvider); // 币段退回余额
      _invalidateLists();
      _lastPaymentDeclined = false;
      Analytics.capture('toko_order_cancel_succeeded');
      showAppToast(context, l10n.shopOrderCancelled);
    } catch (_) {
      if (mounted) showAppToast(context, l10n.shopOrderCancelFailed);
    } finally {
      if (mounted) setState(() => _busyAction = null);
    }
  }
}

/// 物流 5 段竖排时间轴（AC4.1，UI `.tl .st`）：圆点 12、连线 2px、段间距 12；已过 / 当前紫色（当前加粗）、未到灰。
/// 🔴 第一版不做逐条轨迹、不显示时间戳。
class _LogisticsTimeline extends StatelessWidget {
  const _LogisticsTimeline({required this.current, required this.labels});

  /// 当前段下标（0..4）。
  final int current;
  final List<String> labels;

  @override
  Widget build(BuildContext context) => Column(
        key: const ValueKey('shopOrderLogisticsTimeline'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < labels.length; i++)
            IntrinsicHeight(
              key: ValueKey('shopOrderLogisticsStep_${i}_${_state(i)}'),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 16,
                    child: Column(
                      children: [
                        Container(
                          width: 12,
                          height: 12,
                          margin: const EdgeInsets.only(top: 3),
                          decoration: BoxDecoration(
                            color: i <= current ? ShopColors.accent : ShopColors.border2,
                            shape: BoxShape.circle,
                          ),
                        ),
                        if (i != labels.length - 1)
                          Expanded(
                            child: Container(
                              width: 2,
                              color: i < current ? ShopColors.accent : ShopColors.divider,
                            ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.only(bottom: i == labels.length - 1 ? 0 : 12),
                      child: Text(labels[i],
                          style: AppTypography.caption.copyWith(
                              fontWeight: i == current ? FontWeight.w700 : FontWeight.w400,
                              color: i <= current ? ShopColors.accentDark : ShopColors.text4)),
                    ),
                  ),
                ],
              ),
            ),
        ],
      );

  String _state(int i) => i < current ? 'done' : (i == current ? 'on' : 'todo');
}
