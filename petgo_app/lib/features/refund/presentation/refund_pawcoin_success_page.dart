import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../domain/refund_request.dart';
import 'refund_format.dart';

/// 屏6 转 PawCoin 退款成功页（0718 新增）。绿头成功态 + 金额明细（含 bonus 拆分）+ 新余额 + 订单卡 + 双 CTA。
/// 退款仅针对兽医订单 → 订单标题用 [AppLocalizations.orderTypeVet] 本地化。
class RefundPawcoinSuccessPage extends StatelessWidget {
  const RefundPawcoinSuccessPage({super.key, required this.result});

  final RefundPawcoinResult result;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.orderDetailTitle)),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: EdgeInsets.zero,
                children: [
                  _header(context, l10n),
                  Padding(
                    padding: const EdgeInsets.all(AppSpacing.screenEdge),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _detailCard(context, l10n),
                        const SizedBox(height: AppSpacing.md),
                        _balanceCard(l10n),
                        const SizedBox(height: AppSpacing.md),
                        _orderCard(l10n),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            _footer(context, l10n),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context, AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.xl, AppSpacing.lg, AppSpacing.xl),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFF1E9E6A), Color(0xFF33C47D)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Column(
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.22),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.check_rounded, color: Colors.white, size: 34),
          ),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.refundSuccessTitle,
              textAlign: TextAlign.center,
              style: AppTypography.title
                  .copyWith(color: Colors.white, fontWeight: FontWeight.w800)),
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.refundSuccessSubtitle,
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(color: Colors.white.withValues(alpha: 0.92))),
        ],
      ),
    );
  }

  Widget _detailCard(BuildContext context, AppLocalizations l10n) {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle(l10n.refundSuccessDetailSection),
          const SizedBox(height: AppSpacing.md),
          _kv(l10n.refundSuccessAmountLabel, '+${thousandsFmt(result.totalCredited)} koin',
              valueColor: const Color(0xFF1E9E6A), bold: true),
          if (result.bonusAmount > 0)
            _kv(l10n.refundSuccessBonusLabel, '+${thousandsFmt(result.bonusAmount)} koin',
                valueColor: AppColors.mint600),
          _kv(l10n.refundSuccessTimeLabel,
              l10n.refundSuccessTimeValue(formatDayMonthTime(context, DateTime.now())),
              bold: true),
        ],
      ),
    );
  }

  Widget _balanceCard(AppLocalizations l10n) {
    return _card(
      color: AppColors.mintTint,
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Icon(Icons.savings_outlined, color: AppColors.mint, size: 22),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.refundSuccessBalanceLabel,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(l10n.refundSuccessBalanceHint,
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ],
            ),
          ),
          Text(thousandsFmt(result.newBalance),
              style: AppTypography.title
                  .copyWith(color: AppColors.mint, fontWeight: FontWeight.w800)),
        ],
      ),
    );
  }

  Widget _orderCard(AppLocalizations l10n) {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _sectionTitle(l10n.refundSuccessOrderSection),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              Expanded(
                child: Text(l10n.orderTypeVet,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                    color: AppColors.momenBadgeBg, borderRadius: BorderRadius.circular(999)),
                child: Text(l10n.refundSuccessOrderBadge,
                    style: TextStyle(
                        color: AppColors.momenBadgeText, fontSize: 12, fontWeight: FontWeight.w600)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _footer(BuildContext context, AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: () => context.go('/me/pawcoin'),
              child: Text(l10n.refundSuccessViewBalanceCta),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          TextButton(
            onPressed: () => context.go('/me/orders'),
            child: Text(l10n.refundSuccessBackToOrdersCta),
          ),
        ],
      ),
    );
  }

  Widget _card({required Widget child, Color? color}) => Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: BoxDecoration(
          color: color ?? AppColors.surface,
          borderRadius: BorderRadius.circular(16),
        ),
        child: child,
      );

  Widget _sectionTitle(String t) => Text(t,
      style: AppTypography.micro.copyWith(
          color: AppColors.textTertiary, letterSpacing: 0.6, fontWeight: FontWeight.w700));

  Widget _kv(String label, String value, {Color? valueColor, bool bold = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Flexible(
                child: Text(label,
                    style: AppTypography.body.copyWith(color: AppColors.textSecondary))),
            const SizedBox(width: AppSpacing.md),
            Text(value,
                style: AppTypography.body.copyWith(
                    color: valueColor, fontWeight: bold ? FontWeight.w800 : FontWeight.w600)),
          ],
        ),
      );
}
