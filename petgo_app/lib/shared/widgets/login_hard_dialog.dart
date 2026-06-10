import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';
import 'login_guide_outcome.dart';

/// 强登录引导弹窗（FR-0C）。
///
/// 视觉强度高于软浮层（居中 modal、遮罩更重）；含说明「登录后继续使用该功能」+ Google 按钮 + 关闭。
/// 不受「每 session 一次」限制（强弹窗按触发即弹，是功能门控）。
///
/// **R2 / AC3（决策 F13）**：[onLogin] 返回 [LoginGuideOutcome]——失败时弹窗内联展示
/// 「登录失败，请重试」失败态（主 CTA 即重试入口）；取消静默保持；成功由协调器关闭弹窗。
class LoginHardDialog extends StatefulWidget {
  const LoginHardDialog({super.key, required this.onLogin, required this.onClose});

  final Future<LoginGuideOutcome> Function() onLogin;
  final VoidCallback onClose;

  @override
  State<LoginHardDialog> createState() => _LoginHardDialogState();
}

class _LoginHardDialogState extends State<LoginHardDialog> {
  bool _loading = false;
  bool _failed = false;

  Future<void> _handleLogin() async {
    setState(() {
      _loading = true;
      _failed = false; // 重试时先清失败态
    });
    final outcome = await widget.onLogin();
    if (!mounted) return; // 成功 → 协调器已关闭弹窗
    setState(() {
      _loading = false;
      _failed = outcome == LoginGuideOutcome.failed;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      backgroundColor: AppColors.surface,
      title: Text(l10n.appTitle, style: AppTypography.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.loginGuideHardMessage, style: AppTypography.body),
          // 失败态：内联「登录失败，请重试」（决策 F13；主 CTA 即重试入口）
          if (_failed) ...[
            const SizedBox(height: AppSpacing.md),
            Text(
              l10n.loginFailed,
              key: const ValueKey('hardDialogError'),
              style: AppTypography.caption.copyWith(color: AppColors.danger),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          key: const ValueKey('hardDialogClose'),
          onPressed: _loading ? null : widget.onClose,
          child: Text(
            l10n.commonClose,
            style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
          ),
        ),
        FilledButton.icon(
          key: const ValueKey('hardDialogGoogleCta'),
          onPressed: _loading ? null : _handleLogin,
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
