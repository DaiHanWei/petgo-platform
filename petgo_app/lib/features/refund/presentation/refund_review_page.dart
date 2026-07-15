import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/refund_repository.dart';
import 'refund_account_page.dart';
import 'refund_format.dart';

/// 屏3 账户核实（Story 4.5，QRIS）。只读复核渠道/账号/户名/净额 + **不可逆提交**。
/// 提交调后端 `payout-info`（净额后端权威、进 PENDING_APPROVAL 等 4-6）；成功后回列表，**提交后不可逆**（后端 409 兜底）。
class RefundReviewPage extends ConsumerStatefulWidget {
  const RefundReviewPage({super.key, required this.draft});

  final RefundPayoutDraft draft;

  @override
  ConsumerState<RefundReviewPage> createState() => _RefundReviewPageState();
}

class _RefundReviewPageState extends ConsumerState<RefundReviewPage> {
  bool _submitting = false;

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    final d = widget.draft;
    setState(() => _submitting = true);
    try {
      await ref.read(refundRepositoryProvider).submitPayoutInfo(
            refundToken: d.refund.refundToken,
            channel: d.option.channel,
            payoutAccount: d.account,
            accountHolderName: d.holder,
          );
      ref.invalidate(myRefundsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.refundSubmittedToast)));
      context.go('/me/refunds'); // 不可逆：回列表，不留返回改
    } catch (_) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.refundSubmitFailed)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = widget.draft;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundReviewTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.screenEdge),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                padding: const EdgeInsets.all(AppSpacing.lg),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: AppColors.line),
                ),
                child: Column(
                  children: [
                    _row(l10n.refundChannelLabel, channelLabel(l10n, d.option.channel)),
                    _row(l10n.refundAccountNumberLabel, d.account),
                    _row(l10n.refundAccountHolderLabel, d.holder),
                    const Divider(height: AppSpacing.xl),
                    _row(l10n.refundFeeLabel, rpFmt(d.option.fee)),
                    _row(l10n.refundNetLabel, rpFmt(d.option.net), emphasize: true),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.refundReviewIrreversibleHint,
                  style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              const Spacer(),
              FilledButton(
                onPressed: _submitting ? null : _submit,
                child: _submitting
                    ? const SizedBox(
                        height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(l10n.refundSubmitCta),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(String label, String value, {bool emphasize = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
            Text(value,
                style: emphasize
                    ? AppTypography.title.copyWith(fontWeight: FontWeight.w700, color: AppColors.mint)
                    : AppTypography.body),
          ],
        ),
      );
}
