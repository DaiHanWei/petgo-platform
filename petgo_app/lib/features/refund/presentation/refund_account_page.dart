import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/refund_request.dart';
import 'refund_format.dart';

/// 屏2 → 屏3 传递的填收款草稿（提交前，可安全退回屏1/屏2）。
class RefundPayoutDraft {
  const RefundPayoutDraft({
    required this.refund,
    required this.option,
    required this.account,
    required this.holder,
  });

  final MyRefund refund;
  final PayoutOption option;
  final String account;
  final String holder;
}

/// 屏2 填收款（Story 4.5，QRIS 专属）。选出款渠道 + 账号 + 户名；**实时联动展示净额**（后端权威，接口下发的
/// `payoutOptions` 为准）。**返回键=提交前可安全退回屏1**（UX-DR14，本页仅 push 未提交，系统返回即 pop）。
class RefundAccountPage extends StatefulWidget {
  const RefundAccountPage({super.key, required this.refund});

  final MyRefund refund;

  @override
  State<RefundAccountPage> createState() => _RefundAccountPageState();
}

class _RefundAccountPageState extends State<RefundAccountPage> {
  final _formKey = GlobalKey<FormState>();
  final _accountCtrl = TextEditingController();
  final _holderCtrl = TextEditingController();
  PayoutOption? _selected;

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

  void _continue() {
    if (!_formKey.currentState!.validate() || _selected == null) return;
    context.push('/me/refunds/review',
        extra: RefundPayoutDraft(
          refund: widget.refund,
          option: _selected!,
          account: _accountCtrl.text.trim(),
          holder: _holderCtrl.text.trim(),
        ));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundAccountTitle)),
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            children: [
              Text(l10n.refundChannelLabel,
                  style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              const SizedBox(height: AppSpacing.sm),
              ...widget.refund.payoutOptions.map((o) => _channelTile(l10n, o)),
              const SizedBox(height: AppSpacing.lg),
              TextFormField(
                controller: _accountCtrl,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: l10n.refundAccountNumberLabel,
                  border: const OutlineInputBorder(),
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? l10n.refundAccountNumberLabel : null,
              ),
              const SizedBox(height: AppSpacing.md),
              TextFormField(
                controller: _holderCtrl,
                decoration: InputDecoration(
                  labelText: l10n.refundAccountHolderLabel,
                  border: const OutlineInputBorder(),
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? l10n.refundAccountHolderLabel : null,
              ),
              const SizedBox(height: AppSpacing.lg),
              _netRow(l10n),
              const SizedBox(height: AppSpacing.lg),
              FilledButton(onPressed: _continue, child: Text(l10n.refundContinueCta)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _channelTile(AppLocalizations l10n, PayoutOption o) {
    final selected = _selected?.channel == o.channel;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: () => setState(() => _selected = o),
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.md),
          decoration: BoxDecoration(
            color: selected ? AppColors.mintTint : AppColors.surface,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: selected ? AppColors.mint : AppColors.line),
          ),
          child: Row(
            children: [
              Icon(selected ? Icons.radio_button_checked : Icons.radio_button_off,
                  color: selected ? AppColors.mint : AppColors.textTertiary, size: 20),
              const SizedBox(width: AppSpacing.sm),
              Expanded(child: Text(channelLabel(l10n, o.channel), style: AppTypography.body)),
              Text('${l10n.refundFeeLabel} ${rpFmt(o.fee)}',
                  style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
            ],
          ),
        ),
      ),
    );
  }

  /// 实时净额（后端权威 net，接口下发值）。
  Widget _netRow(AppLocalizations l10n) {
    final net = _selected?.net ?? widget.refund.orderAmount;
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(l10n.refundNetLabel, style: AppTypography.body),
          Text(rpFmt(net),
              style: AppTypography.title.copyWith(fontWeight: FontWeight.w700, color: AppColors.mint)),
        ],
      ),
    );
  }
}
