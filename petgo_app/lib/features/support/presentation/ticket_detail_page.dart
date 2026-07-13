import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/support_repository.dart';
import '../domain/support_ticket.dart';
import 'support_l10n.dart';
import 'widgets/ticket_status_badge.dart';

/// 工单详情（Story 4.2）。展示用户可见字段；附件只显数量（D-4，4-1 返 objectKey 非签名 URL）。
/// 非本人 → 后端 404 → 友好「工单不存在」。绝不展示内部字段（DTO 本就无）。
class TicketDetailPage extends ConsumerWidget {
  const TicketDetailPage({super.key, required this.token});

  final String token;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(ticketDetailProvider(token));
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.ticketDetailTitle)),
      body: SafeArea(
        child: async.when(
          data: (t) => _detail(context, l10n, t),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: Text(l10n.ticketNotFound,
                style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
          ),
        ),
      ),
    );
  }

  Widget _detail(BuildContext context, AppLocalizations l10n, SupportTicket t) {
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.md),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                (t.subject != null && t.subject!.isNotEmpty) ? t.subject! : t.body,
                style: AppTypography.title.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            TicketStatusBadge(status: t.status),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        _section(l10n.ticketBodyLabel, t.body),
        _section(l10n.ticketContactLabel, '${contactTypeLabel(l10n, t.contactType)} · ${t.contactValue}'),
        if (t.labels.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.md),
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final lab in t.labels)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration:
                        BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(6)),
                    child: Text(ticketLabelText(l10n, lab),
                        style: AppTypography.micro.copyWith(color: AppColors.mint600)),
                  ),
              ],
            ),
          ),
        if (t.attachmentCount > 0)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.md),
            child: Row(
              children: [
                const Icon(Icons.image_outlined, size: 18, color: AppColors.textTertiary),
                const SizedBox(width: 6),
                Text(l10n.ticketAttachmentCount(t.attachmentCount),
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ],
            ),
          ),
        if (t.contactedCustomer)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.md),
            child: Text(l10n.ticketContactedNote,
                style: AppTypography.caption.copyWith(color: AppColors.momenBadgeText)),
          ),
      ],
    );
  }

  Widget _section(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: 4),
          Text(value, style: AppTypography.body.copyWith(color: AppColors.textPrimary, height: 1.5)),
        ],
      ),
    );
  }
}
