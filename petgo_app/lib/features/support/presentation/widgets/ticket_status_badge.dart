import 'package:flutter/material.dart';

import '../../../../l10n/app_localizations.dart';
import '../../domain/support_ticket.dart';
import '../support_l10n.dart';

/// 工单状态徽章（列表/详情共用）。配色由 [ticketStatusColors] 决定。
class TicketStatusBadge extends StatelessWidget {
  const TicketStatusBadge({super.key, required this.status});

  final TicketStatus status;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = ticketStatusColors(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.bg, borderRadius: BorderRadius.circular(999)),
      child: Text(
        ticketStatusLabel(l10n, status),
        style: TextStyle(color: c.fg, fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}
