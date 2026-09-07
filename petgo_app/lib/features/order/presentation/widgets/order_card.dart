import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/order_summary.dart';
import '../order_l10n.dart';
import 'order_status_badge.dart';

/// 订单卡片（Story 5.2，DESIGN.delta 组件①）。4 类型图标 + 本地化 title/subtitle + 金额/占位 + 状态徽章。
/// 点击跳详情 `/me/orders/{token}`（5-3 详情页）。
///
/// 🔴 **电商（第 5 类，Story 3.9 / FR-101）走两处不同**，其余四类一行未改：
/// 1. 左侧方块换成**商品主图**（缺图回落到类型图标，绝不白块），标题给「商品名 · 规格」
///    与「等 N 件」—— 一列订单卡若只有类型和金额，用户找不到自己要的那一单；
/// 2. 点击跳 **`/shop/orders/{token}`**（Story 3.8 的专用详情页，那里有倒计时与支付入口），
///    不是通用详情页。待支付时卡片直接给「Bayar sekarang」，少一次跳转。
class OrderCard extends StatelessWidget {
  const OrderCard({super.key, required this.order});

  final OrderSummary order;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => context.push(order.orderType == OrderType.ecommerce
            ? '/shop/orders/${order.orderToken}'
            : '/me/orders/${order.orderToken}'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 顶部状态色条（DESIGN 0718：金=待/进行 · 绿=完成 · 蓝=退款中）。
            Container(height: 4, color: orderStatusStripe(order.statusColor)),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _leading(),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(orderTypeLabel(l10n, order.orderType),
                            style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
                        // 电商：商品名 · 规格（+「等 N 件」）
                        if (order.itemTitle != null) ...[
                          const SizedBox(height: 2),
                          Text(_itemLine(l10n),
                              key: const ValueKey('orderCardItemTitle'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: AppTypography.caption
                                  .copyWith(fontWeight: FontWeight.w600)),
                        ],
                        const SizedBox(height: 3),
                        Text(orderCardSubtitle(context, l10n, order),
                            style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                        // 🔴 待支付电商订单的主操作：倒计时与支付都在详情页（3.8），
                        //    这里只给入口 —— 卡片上再放一个倒计时会有两处时间源。
                        if (order.orderType == OrderType.ecommerce &&
                            order.statusCode == 'PENDING_PAYMENT') ...[
                          const SizedBox(height: AppSpacing.xs),
                          SizedBox(
                            height: 30,
                            child: FilledButton(
                              key: const ValueKey('orderCardPayNow'),
                              onPressed: () =>
                                  context.push('/shop/orders/${order.orderToken}'),
                              child: Text(l10n.orderPayNow,
                                  style: const TextStyle(fontSize: 12)),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: OrderStatusBadge(
                        statusCode: order.statusCode, statusColor: order.statusColor),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 左侧方块：电商用商品主图，其余类型用图标。🔴 缺图/加载失败回落到图标，不留白块。
  Widget _leading() {
    final url = order.thumbnailUrl;
    if (order.orderType == OrderType.ecommerce && url != null && url.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Image.network(url,
            width: 44,
            height: 44,
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => _iconBox()),
      );
    }
    return _iconBox();
  }

  Widget _iconBox() => Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Icon(orderTypeIcon(order.orderType), size: 22, color: AppColors.mint),
      );

  /// 「商品名 · 规格」+ 多件时的「等 N 件」。
  ///
  /// 🔴 N 是**除首件之外**还有几件，不是总件数 —— 「Royal Canin 等 3 件」在只有 3 件时
  /// 读起来像还有另外 3 件。
  String _itemLine(AppLocalizations l10n) {
    final title = order.itemTitle ?? '';
    final count = order.itemCount ?? 0;
    if (count <= 1) return title;
    return '$title · ${l10n.orderCardMoreItems(count - 1)}';
  }
}
