/// 支付成功页（v1.4.2 shop Story 7.1 · UI E6；7.2 把 QRIS 到账也接到这里）。
///
/// 纯 PawCoin / 零元单提交即结清（S-3 / C-4），结算页直达本页。
///
/// - 金额取 `shopOrderDetailProvider(token)`（服务端值，不自算）；加载中骨架、失败只显示标题与两个按钮（**不编数字**）。
/// - 混合支付多一行「PawCoin Rp a + QRIS Rp b」，纯币 / 纯 QRIS 不画。
/// - 「Lihat pesanan」→ 订单详情；「Lanjut belanja」→ Toko（🔴 Tab 根一律 `go`）。
/// - **系统返回键等同「Lihat pesanan」**。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../data/shop_order_repository.dart';
import '../domain/shop_product.dart' show formatIdr;
import 'widgets/shop_buttons.dart';

class ShopPaySuccessPage extends ConsumerWidget {
  const ShopPaySuccessPage({super.key, required this.orderToken});

  final String orderToken;

  void _viewOrder(BuildContext context) =>
      context.pushReplacement('/shop/orders/$orderToken');

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopOrderDetailProvider(orderToken));
    final order = async.asData?.value;

    final Widget amount;
    if (async.isLoading && order == null) {
      amount = Container(
        key: const ValueKey('paySuccessAmountSkeleton'),
        width: 140,
        height: 28,
        decoration: BoxDecoration(
            color: ShopColors.border2, borderRadius: BorderRadius.circular(6)),
      );
    } else if (order == null) {
      amount = const SizedBox.shrink();
    } else {
      final coin = order.coinAmount ?? 0;
      final cash = order.cashAmount ?? 0;
      amount = Column(
        children: [
          Text(formatIdr(order.totalAmount),
              key: const ValueKey('paySuccessTotal'),
              style: AppTypography.headline
                  .copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
          if (coin > 0 && cash > 0)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                  l10n.shopOrderCoinCashSplit(formatIdrAmount(coin), formatIdrAmount(cash)),
                  key: const ValueKey('paySuccessSplit'),
                  style: AppTypography.caption
                      .copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
            ),
        ],
      );
    }

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _viewOrder(context);
      },
      child: Scaffold(
        backgroundColor: ShopColors.surface,
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(
              children: [
                const Spacer(),
                Container(
                  width: 72,
                  height: 72,
                  decoration:
                      const BoxDecoration(color: ShopColors.savingBg, shape: BoxShape.circle),
                  child: const Icon(Icons.check_rounded, size: 40, color: ShopColors.saving),
                ),
                const SizedBox(height: 16),
                Text(l10n.shopOrderPaid, style: AppTypography.title),
                const SizedBox(height: 12),
                amount,
                if (order != null) ...[
                  const SizedBox(height: 12),
                  Text(l10n.shopPaySuccessPreparing,
                      textAlign: TextAlign.center, style: AppTypography.caption),
                ],
                const Spacer(),
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: ShopButton(
                    key: const ValueKey('paySuccessViewOrder'),
                    label: l10n.shopPaySuccessViewOrder,
                    variant: ShopButtonVariant.purple,
                    onTap: () => _viewOrder(context),
                  ),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: ShopButton(
                    key: const ValueKey('paySuccessContinue'),
                    label: l10n.shopPaySuccessContinue,
                    variant: ShopButtonVariant.outlinePurple,
                    onTap: () => context.go('/shop'),
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
