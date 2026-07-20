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

/// 屏1 选退款方式（Story 4.5 + 0718 改版）。
/// - **真钱订单（QRIS/DANA）**：用户**二选一**——退真钱（填账户，两段审批）/ 转 PawCoin（即时 + bonus 溢价预览）。
/// - **PawCoin 原路订单**：仅即时原路退币（无选择、无 bonus）。
class RefundChoosePage extends ConsumerStatefulWidget {
  const RefundChoosePage({super.key, required this.refund});

  final MyRefund refund;

  @override
  ConsumerState<RefundChoosePage> createState() => _RefundChoosePageState();
}

enum _Choice { realMoney, pawcoin }

class _RefundChoosePageState extends ConsumerState<RefundChoosePage> {
  bool _submitting = false;

  /// 真钱订单的选择（默认退真钱，与参考一致）。PawCoin 原路订单不用。
  _Choice _choice = _Choice.realMoney;

  bool get _isPawcoinOrigin => widget.refund.payChannel == 'PAWCOIN';

  Future<void> _confirmPawcoin() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _submitting = true);
    try {
      final result =
          await ref.read(refundRepositoryProvider).refundToPawCoin(widget.refund.refundToken);
      ref.invalidate(myRefundsProvider);
      if (!mounted) return;
      // 转 PawCoin 成功页（含 bonus 明细 + 新余额，0718 屏6）。
      context.pushReplacement('/me/refunds/pawcoin-success', extra: result);
    } catch (_) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.refundSubmitFailed)));
    }
  }

  void _onConfirm() {
    if (_choice == _Choice.pawcoin) {
      _confirmPawcoin();
    } else {
      context.push('/me/refunds/account', extra: widget.refund);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = widget.refund;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundChooseTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.screenEdge),
          child: _isPawcoinOrigin ? _pawcoinOnly(l10n, r) : _choiceLayout(l10n, r),
        ),
      ),
    );
  }

  /// PawCoin 原路订单：单一即时退币。
  Widget _pawcoinOnly(AppLocalizations l10n, MyRefund r) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _OptionCard(
          title: l10n.refundPawcoinTitle,
          desc: l10n.refundPawcoinDesc,
          icon: Icons.savings_outlined,
          selected: true,
          onTap: null,
        ),
        const Spacer(),
        _confirmButton(l10n.refundPawcoinCta),
      ],
    );
  }

  /// 真钱订单：二选一（退真钱 / 转 PawCoin+bonus）+ 确认。
  Widget _choiceLayout(AppLocalizations l10n, MyRefund r) {
    final creditPreview = l10n.refundPawcoinCreditPreview(thousandsFmt(r.pawcoinCreditPreview));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(l10n.refundChooseTitle,
            style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
        const SizedBox(height: AppSpacing.sm),
        Text(l10n.refundChooseSubtitle,
            style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
        const SizedBox(height: AppSpacing.lg),
        _OptionCard(
          title: l10n.refundRealMoneyTitle,
          desc: l10n.refundRealMoneyDesc,
          icon: Icons.account_balance_outlined,
          selected: _choice == _Choice.realMoney,
          onTap: () => setState(() => _choice = _Choice.realMoney),
        ),
        const SizedBox(height: AppSpacing.sm),
        _OptionCard(
          title: l10n.refundPawcoinTitle,
          desc: l10n.refundPawcoinDesc,
          icon: Icons.savings_outlined,
          selected: _choice == _Choice.pawcoin,
          onTap: () => setState(() => _choice = _Choice.pawcoin),
          highlight: creditPreview,
        ),
        const Spacer(),
        _confirmButton(l10n.refundConfirmChoiceCta),
      ],
    );
  }

  Widget _confirmButton(String label) => FilledButton(
        onPressed: _submitting ? null : _onConfirm,
        child: _submitting
            ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : Text(label),
      );
}

/// 可选退款方式卡（radio 高亮边框 + 图标 + 标题/描述 + 可选强调行如 bonus 预览）。
class _OptionCard extends StatelessWidget {
  const _OptionCard({
    required this.title,
    required this.desc,
    required this.icon,
    required this.selected,
    required this.onTap,
    this.highlight,
  });

  final String title;
  final String desc;
  final IconData icon;
  final bool selected;
  final VoidCallback? onTap;
  final String? highlight;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.md),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
                color: selected ? AppColors.mint : AppColors.line,
                width: selected ? 1.6 : 1),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: AppColors.mintTint,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, size: 22, color: AppColors.mint),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 3),
                    Text(desc,
                        style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                    if (highlight != null) ...[
                      const SizedBox(height: 6),
                      Text(highlight!,
                          style: AppTypography.caption
                              .copyWith(color: AppColors.mint600, fontWeight: FontWeight.w700)),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
