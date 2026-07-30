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
        onTap: () => context.push('/me/orders/${order.orderToken}'),
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
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: AppColors.mintTint,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(orderTypeIcon(order.orderType), size: 22, color: AppColors.mint),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(orderTypeLabel(l10n, order.orderType),
                            style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 3),
                        Text(orderCardSubtitle(context, l10n, order),
                            style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
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
}
