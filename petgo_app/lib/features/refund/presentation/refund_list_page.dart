import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/refund_repository.dart';
import '../domain/refund_request.dart';
import 'refund_format.dart';

/// 「我的退款」列表页（Story 4.5）。解锁「选方式」入口——`actionable` 项（客服已批准、未提交）可进 3 屏流程。
/// AB-5B：批准不发通知，用户在此发现可操作退款。
class RefundListPage extends ConsumerWidget {
  const RefundListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(myRefundsProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundMyTitle)),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(myRefundsProvider),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => _error(context, l10n, ref),
          data: (items) => items.isEmpty
              ? _empty(l10n)
              : ListView.separated(
                  padding: const EdgeInsets.all(AppSpacing.screenEdge),
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (_, i) => _RefundCard(refund: items[i]),
                ),
        ),
      ),
    );
  }

  Widget _empty(AppLocalizations l10n) => ListView(
        children: [
          const SizedBox(height: 120),
          Center(child: Text(l10n.refundListEmpty, style: AppTypography.title)),
          const SizedBox(height: AppSpacing.sm),
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
              child: Text(l10n.refundListEmptyHint,
                  textAlign: TextAlign.center,
                  style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
            ),
          ),
        ],
      );

  Widget _error(BuildContext context, AppLocalizations l10n, WidgetRef ref) => ListView(
        children: [
          const SizedBox(height: 120),
          Center(child: Text(l10n.refundLoadFailed, style: AppTypography.body)),
          const SizedBox(height: AppSpacing.md),
          Center(
            child: OutlinedButton(
              onPressed: () => ref.invalidate(myRefundsProvider),
              child: Text(l10n.refundRetry),
            ),
          ),
        ],
      );
}

class _RefundCard extends StatelessWidget {
  const _RefundCard({required this.refund});

  final MyRefund refund;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: refund.actionable
            ? () => context.push('/me/refunds/choose', extra: refund)
            : null,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(rpFmt(refund.orderAmount),
                        style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: AppSpacing.xs),
                    Text(refundStatusLabel(l10n, refund),
                        style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                  ],
                ),
              ),
              if (refund.actionable)
                Row(
                  children: [
                    Text(l10n.refundActionChoose,
                        style: AppTypography.caption.copyWith(color: AppColors.mint)),
                    const Icon(Icons.chevron_right, color: AppColors.mint),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}
