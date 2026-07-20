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

/// 屏2 填收款（Story 4.5，QRIS 专属；0718 改版）。金额卡 + **横排渠道选择** + 账号/户名（占位符+提示）+
/// **直接提交**（不可逆确认弹窗守 UX-DR14）→ 状态页 05。净额后端权威（不回传费/净额，FR-NFR-5）。
class RefundAccountPage extends ConsumerStatefulWidget {
  const RefundAccountPage({super.key, required this.refund});

  final MyRefund refund;

  @override
  ConsumerState<RefundAccountPage> createState() => _RefundAccountPageState();
}

class _RefundAccountPageState extends ConsumerState<RefundAccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _accountCtrl = TextEditingController();
  final _holderCtrl = TextEditingController();
  PayoutOption? _selected;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    if (widget.refund.payoutOptions.isNotEmpty) {
      _selected = widget.refund.payoutOptions.first;
    }
  }

  @override
  void dispose() {
    _accountCtrl.dispose();
    _holderCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate() || _selected == null) return;
    final l10n = AppLocalizations.of(context);
    // 不可逆确认（UX-DR14：提交后账户不可改）。
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.refundConfirmSubmitTitle),
        content: Text(l10n.refundConfirmSubmitBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(l10n.refundConfirmSubmitNo)),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(l10n.refundConfirmSubmitYes)),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _submitting = true);
    try {
      await ref.read(refundRepositoryProvider).submitPayoutInfo(
            refundToken: widget.refund.refundToken,
            channel: _selected!.channel,
            payoutAccount: _accountCtrl.text.trim(),
            accountHolderName: _holderCtrl.text.trim(),
          );
      ref.invalidate(myRefundsProvider);
      if (!mounted) return;
      // 状态页 05（审核中）。approvalStatus 提交后为 PENDING_APPROVAL。
      context.pushReplacement('/me/refunds/status', extra: widget.refund);
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
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundAccountTitleV2)),
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            children: [
              _amountCard(l10n),
              const SizedBox(height: AppSpacing.lg),
              Text(l10n.refundChannelSectionLabel,
                  style: AppTypography.micro.copyWith(
                      color: AppColors.textTertiary, letterSpacing: 0.6, fontWeight: FontWeight.w700)),
              const SizedBox(height: AppSpacing.sm),
              _channelRow(l10n),
              const SizedBox(height: AppSpacing.lg),
              _label('${l10n.refundAccountNumberLabel} *'),
              const SizedBox(height: 6),
              TextFormField(
                controller: _accountCtrl,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  hintText: l10n.refundAccountNumberPlaceholder,
                  border: const OutlineInputBorder(),
                ),
                validator: (v) {
                  final t = (v ?? '').trim();
                  if (t.length < 10 || t.length > 13 || int.tryParse(t) == null) {
                    return l10n.refundAccountNumberHint;
                  }
                  return null;
                },
              ),
              const SizedBox(height: 6),
              Text(l10n.refundAccountNumberHint,
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
              const SizedBox(height: AppSpacing.md),
              _label('${l10n.refundAccountHolderLabel} *'),
              const SizedBox(height: 6),
              TextFormField(
                controller: _holderCtrl,
                decoration: InputDecoration(
                  hintText: l10n.refundAccountHolderPlaceholder,
                  border: const OutlineInputBorder(),
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? l10n.refundAccountHolderLabel : null,
              ),
              const SizedBox(height: AppSpacing.xl),
              FilledButton(
                onPressed: _submitting ? null : _submit,
                child: _submitting
                    ? const SizedBox(
                        height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(l10n.refundSubmitRequestCta),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _amountCard(AppLocalizations l10n) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(14),
        border: const Border(left: BorderSide(color: AppColors.mint, width: 4)),
      ),
      child: Row(
        children: [
          const Icon(Icons.account_balance_wallet_outlined, color: AppColors.mint, size: 22),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.refundOrderAmountCardLabel,
                    style: AppTypography.caption.copyWith(color: AppColors.mint600, fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(rpFmt(widget.refund.orderAmount),
                    style: AppTypography.title.copyWith(color: AppColors.mint, fontWeight: FontWeight.w800)),
              ],
            ),
          ),
          Text(l10n.refundEstimateLabel,
              textAlign: TextAlign.right,
              style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
        ],
      ),
    );
  }

  /// 横排 3 渠道卡（BCA Gratis / OVO·GoPay +费）。
  Widget _channelRow(AppLocalizations l10n) {
    return Row(
      children: [
        for (int i = 0; i < widget.refund.payoutOptions.length; i++) ...[
          if (i > 0) const SizedBox(width: AppSpacing.sm),
          Expanded(child: _channelCard(l10n, widget.refund.payoutOptions[i])),
        ],
      ],
    );
  }

  Widget _channelCard(AppLocalizations l10n, PayoutOption o) {
    final selected = _selected?.channel == o.channel;
    final free = o.fee == 0;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => setState(() => _selected = o),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md, horizontal: 6),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
              color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.6 : 1),
        ),
        child: Column(
          children: [
            Text(channelLabel(l10n, o.channel),
                style: AppTypography.body.copyWith(
                    fontWeight: FontWeight.w700,
                    color: selected ? AppColors.mint : AppColors.textPrimary)),
            const SizedBox(height: 2),
            Text(free ? l10n.refundChannelFree : '+${rpFmt(o.fee)}',
                style: AppTypography.caption.copyWith(
                    color: free ? const Color(0xFF1E9E6A) : AppColors.danger,
                    fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }

  Widget _label(String t) =>
      Text(t, style: AppTypography.body.copyWith(fontWeight: FontWeight.w700));
}
