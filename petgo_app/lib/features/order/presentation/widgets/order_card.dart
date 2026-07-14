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
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => context.push('/me/orders/${order.orderToken}'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: AppColors.mintTint,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(orderTypeIcon(order.orderType), size: 22, color: AppColors.mint),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(orderTypeLabel(l10n, order.orderType),
                        style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(orderAmountText(l10n, order.amount),
                        style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              OrderStatusBadge(statusCode: order.statusCode, statusColor: order.statusColor),
            ],
          ),
        ),
      ),
    );
  }
}
