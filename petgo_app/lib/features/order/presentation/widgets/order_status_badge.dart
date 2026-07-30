import 'package:flutter/material.dart';

import '../../../../l10n/app_localizations.dart';
import '../../domain/order_summary.dart';
import '../order_l10n.dart';

/// 订单状态徽章（Story 5.2，DESIGN.delta 组件②）。配色由 [orderStatusColors] 决定；
/// **退款处理中(INFO)→紫柔底非红**（纯展示不可点，UX-DR2）。
class OrderStatusBadge extends StatelessWidget {
  const OrderStatusBadge({super.key, required this.statusCode, required this.statusColor});

  final String statusCode;
  final OrderStatusColor statusColor;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = orderStatusColors(statusColor);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.bg, borderRadius: BorderRadius.circular(999)),
      child: Text(
        orderStatusLabel(l10n, statusCode),
        style: TextStyle(color: c.fg, fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}
