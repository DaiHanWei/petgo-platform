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

/// 屏1 选退款方式（Story 4.5）。按订单原支付渠道分流（非用户自由选）：
/// PawCoin 付→即时退币确认（无手续费，调即时退端点）；QRIS 付→进屏2 填真钱收款账户。
class RefundChoosePage extends ConsumerStatefulWidget {
  const RefundChoosePage({super.key, required this.refund});

  final MyRefund refund;

  @override
  ConsumerState<RefundChoosePage> createState() => _RefundChoosePageState();
}

class _RefundChoosePageState extends ConsumerState<RefundChoosePage> {
  bool _submitting = false;

  Future<void> _confirmPawcoin() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _submitting = true);
    try {
      await ref.read(refundRepositoryProvider).refundToPawCoin(widget.refund.refundToken);
      ref.invalidate(myRefundsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.refundPawcoinDoneToast)));
      context.go('/me/refunds');
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
    final r = widget.refund;
    final isPawcoin = r.method == RefundMethod.instantPawcoin;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundChooseTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.screenEdge),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _MethodCard(
                title: isPawcoin ? l10n.refundPawcoinTitle : l10n.refundRealMoneyTitle,
                desc: isPawcoin ? l10n.refundPawcoinDesc : l10n.refundRealMoneyDesc,
                amount: rpFmt(r.orderAmount),
                amountLabel: l10n.refundAmountLabel,
              ),
              const Spacer(),
              if (isPawcoin)
                FilledButton(
                  onPressed: _submitting ? null : _confirmPawcoin,
                  child: _submitting
                      ? const SizedBox(
                          height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(l10n.refundPawcoinCta),
                )
              else
                FilledButton(
                  onPressed: () => context.push('/me/refunds/account', extra: r),
                  child: Text(l10n.refundFillAccountCta),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MethodCard extends StatelessWidget {
  const _MethodCard({
    required this.title,
    required this.desc,
    required this.amount,
    required this.amountLabel,
  });

  final String title;
  final String desc;
  final String amount;
  final String amountLabel;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: AppSpacing.sm),
          Text(desc, style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
          const SizedBox(height: AppSpacing.lg),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(amountLabel,
                  style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              Text(amount, style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
            ],
          ),
        ],
      ),
    );
  }
}
