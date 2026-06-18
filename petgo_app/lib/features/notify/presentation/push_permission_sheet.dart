import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 通知权限「前置说明」底部抽屉（原型 P-09，pre-prompt rationale）。
///
/// 在调用系统权限弹窗**之前**出现，说明开启好处以提高授予率；不替代系统弹窗。
/// 由 [PushPermissionGate] 在双时机（首次问诊 / 建档且从未问诊）触发，仅一次。
/// 返回：true=点「开启」（继续请求系统权限）；false=点「暂不」或下滑关闭（不请求，但门控仍记为已问）。
Future<bool> showPushPermissionSheet(BuildContext context) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: true,
    builder: (ctx) => const _PushPermissionSheet(),
  );
  return result ?? false;
}

class _PushPermissionSheet extends StatelessWidget {
  const _PushPermissionSheet();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('pushPermissionSheet'),
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, AppSpacing.xxl),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 拖拽条
            Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(bottom: AppSpacing.xl),
              decoration: BoxDecoration(
                color: AppColors.border,
                borderRadius: BorderRadius.circular(AppRounded.full),
              ),
            ),
            // 铃铛图标
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: AppColors.mintTint,
                borderRadius: BorderRadius.circular(19),
              ),
              child: const Icon(Icons.notifications_outlined,
                  color: AppColors.mint700, size: 32),
            ),
            const SizedBox(height: AppSpacing.lg),
            Text(l10n.pushPromptTitle,
                textAlign: TextAlign.center,
                style: AppTypography.headline),
            const SizedBox(height: AppSpacing.sm),
            Text(l10n.pushPromptSubtitle,
                textAlign: TextAlign.center,
                style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
            const SizedBox(height: AppSpacing.xl),
            _benefit('🎂', l10n.pushPromptBenefitBirthdayTitle,
                l10n.pushPromptBenefitBirthdayBody),
            const SizedBox(height: AppSpacing.sm),
            _benefit('💬', l10n.pushPromptBenefitVetTitle, l10n.pushPromptBenefitVetBody),
            const SizedBox(height: AppSpacing.sm),
            _benefit('🏅', l10n.pushPromptBenefitMilestoneTitle,
                l10n.pushPromptBenefitMilestoneBody),
            const SizedBox(height: AppSpacing.xl),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('pushPromptAllow'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppRounded.lg)),
                ),
                onPressed: () => Navigator.of(context).pop(true),
                child: Text(l10n.pushPromptAllow, style: AppTypography.button),
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            TextButton(
              key: const ValueKey('pushPromptDismiss'),
              onPressed: () => Navigator.of(context).pop(false),
              child: Text(l10n.pushPromptDismiss,
                  style: AppTypography.body.copyWith(color: AppColors.textTertiary)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _benefit(String emoji, String title, String body) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(AppRounded.md),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(emoji, style: const TextStyle(fontSize: 18)),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: AppTypography.caption
                        .copyWith(fontWeight: FontWeight.w700, color: AppColors.textPrimary)),
                Text(body,
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
