import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 强登录引导弹窗（FR-0C）。
///
/// 视觉强度高于软浮层（居中 modal、遮罩更重）；含说明「登录后继续使用该功能」+ Google 按钮 + 关闭。
/// 不受「每 session 一次」限制（强弹窗按触发即弹，是功能门控）。
class LoginHardDialog extends StatelessWidget {
  const LoginHardDialog({super.key, required this.onLogin, required this.onClose});

  final VoidCallback onLogin;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      backgroundColor: AppColors.surface,
      title: Text(l10n.appTitle, style: AppTypography.title),
      content: Text(l10n.loginGuideHardMessage, style: AppTypography.body),
      actions: [
        TextButton(
          key: const ValueKey('hardDialogClose'),
          onPressed: onClose,
          child: Text(
            l10n.commonClose,
            style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
          ),
        ),
        FilledButton.icon(
          key: const ValueKey('hardDialogGoogleCta'),
          onPressed: onLogin,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.accentGrowth,
            foregroundColor: AppColors.onAccent,
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
          ),
          icon: const Icon(Icons.login),
          label: Text(l10n.loginGoogle, style: AppTypography.button),
        ),
      ],
    );
  }
}
