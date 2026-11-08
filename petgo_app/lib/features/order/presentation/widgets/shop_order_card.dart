/// 电商订单卡（v1.4.2 shop Story 9.1 · UI F1 / F2）—— 订单列表「Belanja」一级下专用。
///
/// 🔴 「Semua」混排仍用 `OrderCardV2`（通用卡服务五类订单，粒度不同，拍板点 1），本卡不进混排。
///
/// 卡上操作（最多两个，主右次左）：
/// - 待付款且 `canPay`：左「Bayar dalam mm:ss」（服务端 `paymentDeadline`）+ 右「Bayar sekarang」→ **直接拉起支付面板**
///   （调用方用 7.2 的 `launchShopPayment`，不再先跳详情）；
/// - 待收货：右「Konfirmasi terima」（**无二次确认**）；配送异常时左侧加「Hubungi CS」；
/// - 其余无按钮；点卡片任意处进 `/shop/orders/{token}`。
library;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/shop_tokens.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../shop/domain/shop_order_detail.dart' show ShopOrderStatus;
import '../../../shop/domain/shop_order_summary.dart';
import '../../../shop/domain/shop_product.dart' show formatIdr;
import '../../../shop/presentation/widgets/shop_buttons.dart';
import '../../../shop/presentation/widgets/shop_countdown.dart';
import '../../../shop/presentation/widgets/shop_decor.dart';

/// 列表状态文案（PRD §3.0）。按 `displayStatus`（缺失回落 `status`）；配送异常优先。
String shopOrderCardStatusLabel(AppLocalizations l10n, ShopOrderSummary o) {
  final s = o.shownStatus;
  if (o.deliveryIssue && (s == ShopOrderStatus.shipped || s == ShopOrderStatus.delivered)) {
    return l10n.shopOrderDeliveryIssue;
  }
  return switch (s) {
    ShopOrderStatus.pendingPayment => l10n.shopOrderStatusPendingPayment,
    ShopOrderStatus.pendingShipment => l10n.shopOrderStatusPendingShipment,
    ShopOrderStatus.shipped ||
    ShopOrderStatus.delivered =>
      _logisticsLabel(l10n, o.logisticsStatusKey) ??
          (s == ShopOrderStatus.shipped ? l10n.shopOrderStatusShipped : l10n.shopOrderDeliveredTitle),
    ShopOrderStatus.completed => l10n.shopOrderStatusCompleted,
    ShopOrderStatus.cancelled => l10n.shopOrderStatusCancelled,
    ShopOrderStatus.refunding => l10n.orderStatusRefunding,
    ShopOrderStatus.refunded => l10n.orderStatusRefunded,
    ShopOrderStatus.unknown => l10n.shopOrderStatusOther,
  };
}

String? _logisticsLabel(AppLocalizations l10n, String? key) => switch (key) {
      'awaiting_pickup' => l10n.shopOrderLogisticsAwaitingPickup,
      'picked' => l10n.shopOrderLogisticsPicked,
      'in_transit' => l10n.shopOrderPackageInTransit,
      'out_for_delivery' => l10n.shopOrderLogisticsOutForDelivery,
      'delivered' => l10n.shopOrderLogisticsDelivered,
      _ => null,
    };

class ShopOrderCard extends StatelessWidget {
  const ShopOrderCard({
    super.key,
    required this.order,
    this.onPay,
    this.onConfirmReceipt,
    this.onContactCs,
    this.onCountdownExpired,
    this.busy = false,
  });

  final ShopOrderSummary order;

  /// 「Bayar sekarang」：调用方拉起支付面板（列表上下文先取详情）。
  final VoidCallback? onPay;

  /// 「Konfirmasi terima」：调用方直接调 `confirm-receipt`（无弹窗）。
  final VoidCallback? onConfirmReceipt;

  /// 「Hubungi CS」（配送异常）。
  final VoidCallback? onContactCs;

  /// 倒计时归零（调用方刷新列表，由服务端决定分组，S-7）。
  final VoidCallback? onCountdownExpired;

  /// 本卡动作进行中：按钮转圈、不可重复点。
  final bool busy;

  bool get _canPay => order.shownStatus.isPendingPayment && order.canPay;

  bool get _toReceive =>
      order.shownStatus == ShopOrderStatus.shipped || order.shownStatus == ShopOrderStatus.delivered;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pending = order.shownStatus.isPendingPayment;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: Material(
        color: ShopColors.surface,
        borderRadius: BorderRadius.circular(16),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('shopOrderCard_${order.orderToken}'),
          onTap: () => context.push('/shop/orders/${order.orderToken}'),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    ShopBadge.toko(l10n.orderBadgeToko),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(order.shownNo,
                          maxLines: 1, overflow: TextOverflow.ellipsis, style: ShopText.serialNo),
                    ),
                    Text(shopOrderCardStatusLabel(l10n, order),
                        key: ValueKey('shopOrderCardStatus_${order.orderToken}'),
                        style: AppTypography.caption.copyWith(
                            fontWeight: FontWeight.w600,
                            color: order.deliveryIssue && _toReceive
                                ? ShopColors.errorText
                                : (pending ? ShopColors.accent : ShopColors.text2))),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ShopImage(url: order.thumbnailUrl, size: 52, radius: 12),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(order.itemTitle ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: AppTypography.caption
                                  .copyWith(color: ShopColors.ink, fontWeight: FontWeight.w500)),
                          if (_metaLine(l10n) != null) ...[
                            const SizedBox(height: 2),
                            Text(_metaLine(l10n)!, style: AppTypography.disclaimer),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(l10n.orderAmountLabel, style: AppTypography.disclaimer),
                        Text(formatIdr(order.totalAmount),
                            key: ValueKey('shopOrderCardAmount_${order.orderToken}'),
                            style: AppTypography.body.copyWith(
                                fontWeight: FontWeight.w700,
                                color: pending ? ShopColors.accent : ShopColors.ink)),
                      ],
                    ),
                  ],
                ),
                ..._actions(l10n),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String? _metaLine(AppLocalizations l10n) {
    final n = order.itemCount;
    if (n != null && n > 1) return l10n.orderCardMoreItems(n - 1);
    final d = order.createdAt;
    if (d == null) return null;
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(d.day)}/${two(d.month)}';
  }

  List<Widget> _actions(AppLocalizations l10n) {
    final deadline = order.paymentDeadline;
    if (_canPay) {
      // 「Bayar dalam {time}」：句式取 arb，时间段由 [ShopCountdown] 渲染（服务端到期时刻）。
      final parts = l10n.shopOrderCountdown('\u0001').split('\u0001');
      return [
        const SizedBox(height: 12),
        Row(
          children: [
            if (deadline != null)
              Expanded(
                child: Row(
                  key: ValueKey('shopOrderCardCountdown_${order.orderToken}'),
                  children: [
                    Text(parts.first, style: AppTypography.caption.copyWith(color: ShopColors.accent)),
                    ShopCountdown(
                      expiresAt: deadline,
                      style: ShopText.countdownInline.copyWith(color: ShopColors.accent),
                      onExpired: onCountdownExpired,
                    ),
                    if (parts.length > 1)
                      Text(parts.last, style: AppTypography.caption.copyWith(color: ShopColors.accent)),
                  ],
                ),
              )
            else
              const Spacer(),
            ShopButton(
              key: ValueKey('shopOrderCardPay_${order.orderToken}'),
              label: l10n.orderPayNow,
              variant: ShopButtonVariant.pay,
              dense: true,
              loading: busy,
              onTap: busy ? null : onPay,
            ),
          ],
        ),
      ];
    }
    if (_toReceive) {
      return [
        const SizedBox(height: 12),
        Row(
          children: [
            const Spacer(),
            if (order.deliveryIssue) ...[
              ShopButton(
                key: ValueKey('shopOrderCardContactCs_${order.orderToken}'),
                label: l10n.shopOrderContactCs,
                variant: ShopButtonVariant.outlinePurple,
                dense: true,
                onTap: onContactCs,
              ),
              const SizedBox(width: 8),
            ],
            ShopButton(
              key: ValueKey('shopOrderCardConfirm_${order.orderToken}'),
              label: l10n.shopOrderConfirmReceipt,
              variant: order.deliveryIssue ? ShopButtonVariant.outlineMuted : ShopButtonVariant.ink,
              dense: true,
              loading: busy,
              onTap: busy ? null : onConfirmReceipt,
            ),
          ],
        ),
      ];
    }
    return const [];
  }
}
