import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/support_repository.dart';
import '../domain/support_ticket.dart';
import 'support_l10n.dart';
import 'widgets/ticket_status_badge.dart';

/// 我的工单列表（Story 4.2）。倒序卡片 + 下拉刷新 + 空态 + 点进详情。
class MyTicketsPage extends ConsumerWidget {
  const MyTicketsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(myTicketsProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.ticketMyTitle)),
      body: RefreshIndicator(
        onRefresh: () async => ref.refresh(myTicketsProvider.future),
        child: async.when(
          data: (tickets) => tickets.isEmpty
              ? _empty(l10n)
              : ListView.separated(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  itemCount: tickets.length,
                  separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (_, i) => _TicketCard(ticket: tickets[i]),
                ),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => _error(l10n),
        ),
      ),
    );
  }

  Widget _empty(AppLocalizations l10n) => ListView(
        children: [
          const SizedBox(height: 160),
          Icon(Icons.inbox_outlined, size: 56, color: AppColors.muted),
          const SizedBox(height: AppSpacing.md),
          Center(child: Text(l10n.ticketEmpty, style: AppTypography.body.copyWith(color: AppColors.textSecondary))),
        ],
      );

  Widget _error(AppLocalizations l10n) => ListView(
        children: [
          const SizedBox(height: 160),
          Center(
              child: Text(l10n.ticketListLoadFailed,
                  style: AppTypography.body.copyWith(color: AppColors.textSecondary))),
        ],
      );
}

class _TicketCard extends StatelessWidget {
  const _TicketCard({required this.ticket});

  final SupportTicket ticket;

  String _fmtDate(DateTime? d) {
    if (d == null) return '';
    String two(int n) => n.toString().padLeft(2, '0');
    return '${d.year}/${two(d.month)}/${two(d.day)}';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final title = (ticket.subject != null && ticket.subject!.isNotEmpty) ? ticket.subject! : ticket.body;
    return InkWell(
      key: ValueKey('ticketCard_${ticket.ticketToken}'),
      borderRadius: BorderRadius.circular(14),
      onTap: () => context.push('/me/support-tickets/${ticket.ticketToken}'),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.border),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                ),
                const SizedBox(width: AppSpacing.sm),
                TicketStatusBadge(status: ticket.status),
              ],
            ),
            if (ticket.labels.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final t in ticket.labels)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(
                        color: AppColors.mintTint,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(ticketLabelText(l10n, t),
                          style: AppTypography.micro.copyWith(color: AppColors.mint600)),
                    ),
                ],
              ),
            ],
            const SizedBox(height: AppSpacing.sm),
            Text(_fmtDate(ticket.createdAt),
                style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
          ],
        ),
      ),
    );
  }
}
