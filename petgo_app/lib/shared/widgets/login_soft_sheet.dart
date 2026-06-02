import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 软性登录推荐浮层（FR-0B）。
///
/// 底部半屏；主 CTA「Google 登录」显著（区域色实心大按钮）；关闭按钮小号浅色弱化。
/// 「每 session 最多一次」去重由 `LoginGuideController` 控制，本组件只负责呈现。
class LoginSoftSheet extends StatelessWidget {
  const LoginSoftSheet({super.key, required this.onLogin, required this.onClose});

  /// 主 CTA 回调（调用 Story 1.3 登录流程，由协调器注入）。
  final VoidCallback onLogin;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRounded.lg)),
      ),
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, AppSpacing.xl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 顶部小拖拽条
          Center(
            child: Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.divider,
                borderRadius: BorderRadius.circular(AppRounded.full),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          Text(l10n.loginGuideSoftTitle, style: AppTypography.title),
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.loginGuideSoftSubtitle, style: AppTypography.caption),
          const SizedBox(height: AppSpacing.xl),
          // 主 CTA：显著
          FilledButton.icon(
            key: const ValueKey('softSheetGoogleCta'),
            onPressed: onLogin,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.accentGrowth,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            ),
            icon: const Icon(Icons.login),
            label: Text(l10n.loginGoogle, style: AppTypography.button),
          ),
          const SizedBox(height: AppSpacing.sm),
          // 关闭：小号浅色弱化（软性）
          Center(
            child: TextButton(
              key: const ValueKey('softSheetClose'),
              onPressed: onClose,
              child: Text(
                l10n.commonClose,
                style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
