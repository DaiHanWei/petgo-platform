import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/rounded.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';
import 'login_guide_outcome.dart';

/// 软性登录推荐浮层（FR-0B）。
///
/// 底部半屏；主 CTA「Google 登录」显著（区域色实心大按钮）；关闭按钮小号浅色弱化。
/// 「每 session 最多一次」去重由 `LoginGuideController` 控制，本组件只负责呈现。
///
/// **R2 / AC3（决策 F13）**：[onLogin] 返回 [LoginGuideOutcome]——失败时浮层内联展示
/// 「登录失败，请重试」失败态（主 CTA 即重试入口，再点重新发起）；取消则静默保持；
/// 成功由协调器关闭浮层。失败/取消均不清空 pendingAction（仅「关闭」清）。
class LoginSoftSheet extends StatefulWidget {
  const LoginSoftSheet({super.key, required this.onLogin, required this.onClose});

  /// 主 CTA 回调（调用 Story 1.3 登录流程，由协调器注入）；返回本次尝试结果。
  final Future<LoginGuideOutcome> Function() onLogin;
  final VoidCallback onClose;

  @override
  State<LoginSoftSheet> createState() => _LoginSoftSheetState();
}

class _LoginSoftSheetState extends State<LoginSoftSheet> {
  bool _loading = false;
  bool _failed = false;

  Future<void> _handleLogin() async {
    setState(() {
      _loading = true;
      _failed = false; // 重试时先清失败态
    });
    final outcome = await widget.onLogin();
    if (!mounted) return; // 成功 → 协调器已关闭浮层
    setState(() {
      _loading = false;
      _failed = outcome == LoginGuideOutcome.failed;
    });
  }

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
          // 失败态：内联「登录失败，请重试」（决策 F13；主 CTA 即重试入口）
          if (_failed) ...[
            const SizedBox(height: AppSpacing.md),
            Text(
              l10n.loginFailed,
              key: const ValueKey('softSheetError'),
              style: AppTypography.caption.copyWith(color: AppColors.danger),
            ),
          ],
          const SizedBox(height: AppSpacing.xl),
          // 主 CTA：显著（失败后即重试入口）
          FilledButton.icon(
            key: const ValueKey('softSheetGoogleCta'),
            onPressed: _loading ? null : _handleLogin,
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
              onPressed: _loading ? null : widget.onClose,
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
