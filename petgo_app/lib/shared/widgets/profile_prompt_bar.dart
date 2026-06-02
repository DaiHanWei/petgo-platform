import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 首页档案提示条（Story 1.7，FR-0H）。
///
/// 文案 + 「立即创建」CTA（跳 Epic 2 创建页占位）+ 关闭 X。无障碍 ≥44pt 触摸目标（NFR-13）。
class ProfilePromptBar extends StatelessWidget {
  const ProfilePromptBar({super.key, required this.onCreate, required this.onDismiss});

  final VoidCallback onCreate;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Material(
      color: AppColors.accentGrowth.withValues(alpha: 0.12),
      borderRadius: AppRounded.mdRadius,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.sm, AppSpacing.sm),
        child: Row(
          children: [
            Expanded(child: Text(l10n.profilePromptMessage, style: AppTypography.caption)),
            TextButton(
              key: const ValueKey('profilePromptCreate'),
              onPressed: onCreate,
              child: Text(l10n.profileOnboardingCreate,
                  style: AppTypography.caption.copyWith(
                      color: AppColors.accentGrowth, fontWeight: FontWeight.w600)),
            ),
            IconButton(
              key: const ValueKey('profilePromptClose'),
              onPressed: onDismiss,
              constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
              icon: const Icon(Icons.close, size: 18, color: AppColors.textTertiary),
            ),
          ],
        ),
      ),
    );
  }
}
