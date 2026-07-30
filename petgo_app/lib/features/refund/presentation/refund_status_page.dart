import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/refund_request.dart';

/// 屏5 退款审核中·状态步进页（0718 新增；QRIS 真钱退款提交后）。
/// **零 PII**：只展步进 + 估时，**绝不显收款账号/户名/选定渠道**（守零 PII 回显红线；参考显账号明文不采纳）。
class RefundStatusPage extends StatelessWidget {
  const RefundStatusPage({super.key, required this.refund});

  final MyRefund refund;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 提交后到达；approvalStatus 可能为提交前快照(null)→按 PENDING_APPROVAL 处理。
    final status = refund.approvalStatus ?? 'PENDING_APPROVAL';
    // 步进态：step1 恒完成；step2 审批；step3 打款。
    final step2Done = status == 'APPROVED' || status == 'PROCESSING' || status == 'DONE';
    final step3Done = status == 'DONE';
    final step3Current = status == 'APPROVED' || status == 'PROCESSING';

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.refundStatusTitle)),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: EdgeInsets.zero,
                children: [
                  _header(l10n),
                  Padding(
                    padding: const EdgeInsets.all(AppSpacing.screenEdge),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _progressCard(l10n, step2Done, step3Done, step3Current),
                        const SizedBox(height: AppSpacing.md),
                        _infoBanner(l10n),
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

  Widget _header(AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.xl, AppSpacing.lg, AppSpacing.xl),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFF1665B0), Color(0xFF2E8BD0)],
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
            child: const Icon(Icons.schedule_rounded, color: Colors.white, size: 32),
          ),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.refundStatusVerifyingTitle,
              textAlign: TextAlign.center,
              style: AppTypography.title.copyWith(color: Colors.white, fontWeight: FontWeight.w800)),
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.refundStatusVerifyingSubtitle,
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(color: Colors.white.withValues(alpha: 0.92))),
        ],
      ),
    );
  }

  Widget _progressCard(AppLocalizations l10n, bool step2Done, bool step3Done, bool step3Current) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.refundStatusProgressSection,
              style: AppTypography.micro.copyWith(
                  color: AppColors.textTertiary, letterSpacing: 0.6, fontWeight: FontWeight.w700)),
          const SizedBox(height: AppSpacing.md),
          _step(l10n.refundStatusStep1, null, _StepState.done, isLast: false),
          _step(l10n.refundStatusStep2, step2Done ? null : l10n.refundStatusStep2Hint,
              step2Done ? _StepState.done : _StepState.current,
              isLast: false),
          _step(l10n.refundStatusStep3, null,
              step3Done
                  ? _StepState.done
                  : step3Current
                      ? _StepState.current
                      : _StepState.pending,
              isLast: true),
        ],
      ),
    );
  }

  Widget _step(String title, String? hint, _StepState state, {required bool isLast}) {
    final Color dotColor = switch (state) {
      _StepState.done => const Color(0xFF1E9E6A),
      _StepState.current => const Color(0xFF1665B0),
      _StepState.pending => AppColors.line2,
    };
    final Color titleColor =
        state == _StepState.current ? const Color(0xFF1665B0) : AppColors.textPrimary;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Column(
            children: [
              Container(
                width: 26,
                height: 26,
                decoration: BoxDecoration(color: dotColor, shape: BoxShape.circle),
                child: state == _StepState.done
                    ? const Icon(Icons.check, color: Colors.white, size: 16)
                    : state == _StepState.current
                        ? const Center(
                            child: Icon(Icons.circle, color: Colors.white, size: 8))
                        : null,
              ),
              if (!isLast)
                Expanded(child: Container(width: 2, color: AppColors.line2)),
            ],
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Padding(
              padding: EdgeInsets.only(bottom: isLast ? 0 : AppSpacing.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: AppTypography.body
                          .copyWith(fontWeight: FontWeight.w700, color: titleColor)),
                  if (hint != null) ...[
                    const SizedBox(height: 2),
                    Text(hint,
                        style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _infoBanner(AppLocalizations l10n) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: const Color(0xFFEAF3FB),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(l10n.refundStatusInfoBanner,
          style: AppTypography.caption.copyWith(color: const Color(0xFF1665B0), height: 1.5)),
    );
  }

  Widget _footer(BuildContext context, AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      child: SizedBox(
        width: double.infinity,
        child: OutlinedButton(
          onPressed: () => context.push('/me/support-tickets'),
          child: Text(l10n.refundStatusContactCta),
        ),
      ),
    );
  }
}

enum _StepState { done, current, pending }
