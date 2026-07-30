import 'package:flutter/foundation.dart' show defaultTargetPlatform;
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
  const LoginHardDialog(
      {super.key,
      required this.onLogin,
      required this.onClose,
      this.onAppleLogin,
      this.onVet});

  final Future<LoginGuideOutcome> Function() onLogin;

  /// Apple 登录入口（FR-44）：非空且当前为 iOS 时，在 Google 上方多显示一个 Apple 按钮。
  final Future<LoginGuideOutcome> Function()? onAppleLogin;
  final VoidCallback onClose;

  /// 兽医登录入口（可选）：游客无需先登普通用户，直接进 /vet/login（单 App 双角色）。
  final VoidCallback? onVet;

  @override
  State<LoginHardDialog> createState() => _LoginHardDialogState();
}

class _LoginHardDialogState extends State<LoginHardDialog> {
  bool _loading = false;
  bool _failed = false;

  Future<void> _run(Future<LoginGuideOutcome> Function() runner) async {
    setState(() {
      _loading = true;
      _failed = false; // 重试时先清失败态
    });
    final outcome = await runner();
    if (!mounted) return; // 成功 → 协调器已关闭弹窗
    setState(() {
      _loading = false;
      _failed = outcome == LoginGuideOutcome.failed;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 原型 login-gate：居中定制 modal（紫浅底 icon 区 + 标题 + 利益文案 + 纵向按钮）。
    return Dialog(
      backgroundColor: AppColors.surface,
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 图标区：紫浅底圆角盒 + lock 图标。
            Container(
              width: 68,
              height: 68,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                  color: AppColors.mintTint2, borderRadius: BorderRadius.circular(20)),
              child: const Icon(Icons.lock_outline_rounded, size: 34, color: AppColors.mint),
            ),
            const SizedBox(height: 18),
            Text(l10n.loginGateTitle,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 19,
                    height: 1.3,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink)),
            const SizedBox(height: 8),
            // 利益文案（原型 login-gate：说明建号好处，不说「功能不支持」）。
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Text(l10n.loginGateBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, height: 1.65, color: AppColors.ink2)),
            ),
            if (_failed) ...[
              const SizedBox(height: AppSpacing.md),
              Text(
                l10n.loginFailed,
                key: const ValueKey('hardDialogError'),
                style: AppTypography.caption.copyWith(color: AppColors.danger),
              ),
            ],
            const SizedBox(height: 24),
            // Apple 登录（FR-44）：仅 iOS 且协调器注入了 Apple 入口时显示，置于 Google 上方
            // （App Store 4.8：与 Google 同级且置顶）。黑底白字。
            if (widget.onAppleLogin != null &&
                defaultTargetPlatform == TargetPlatform.iOS) ...[
              _AppleButton(
                loading: _loading,
                onTap: _loading ? null : () => _run(widget.onAppleLogin!),
              ),
              const SizedBox(height: 10),
            ],
            // 主按钮：Google 登录（白底 + 描边 + Google G）。V1 无独立注册流（Google 即建号），
            // 原「Daftar Gratis」次按钮与 Google 同源、纯冗余，2026-07-23 按产品要求移除。
            _GoogleButton(loading: _loading, onTap: _loading ? null : () => _run(widget.onLogin)),
            const SizedBox(height: 16),
            // 继续看（文字链）。
            TextButton(
              key: const ValueKey('hardDialogClose'),
              onPressed: _loading ? null : widget.onClose,
              child: Text(l10n.loginGateContinue,
                  style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            ),
            // 兽医登录入口（游客可达，单 App 双角色）。
            if (widget.onVet != null)
              TextButton(
                key: const ValueKey('hardDialogVetLink'),
                onPressed: _loading ? null : widget.onVet,
                child: Text(l10n.vetLoginLink,
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ),
          ],
        ),
      ),
    );
  }
}

/// Google 登录按钮（原型：白底 + #E6E6E6 描边 + 多色 G + 文案）。加载态显示进度环。
class _GoogleButton extends StatelessWidget {
  const _GoogleButton({required this.loading, required this.onTap});

  final bool loading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton(
        key: const ValueKey('hardDialogGoogleCta'),
        onPressed: onTap,
        style: OutlinedButton.styleFrom(
          backgroundColor: AppColors.mintTint, // 淡紫底（violet-50）
          foregroundColor: AppColors.ink,
          side: const BorderSide(color: AppColors.lineViolet, width: 1.5),
          padding: const EdgeInsets.symmetric(vertical: 13),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
        ),
        child: loading
            ? const SizedBox(
                width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : Row(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // 五彩「G」字标（避免引入 Google 图标资源）：ShaderMask 把 Google 四色
                  // 横向渐变刷到字上 → 蓝/红/黄/绿一字排开。
                  ShaderMask(
                    shaderCallback: (Rect bounds) => const LinearGradient(
                      colors: <Color>[
                        AppColors.brandGoogleBlue,
                        AppColors.brandGoogleRed,
                        AppColors.brandGoogleYellow,
                        AppColors.brandGoogleGreen,
                      ],
                    ).createShader(bounds),
                    child: const Text('G',
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w800,
                            color: Colors.white)), // ShaderMask 需非透明底色承接渐变
                  ),
                  const SizedBox(width: 10),
                  Text(l10n.loginGoogle,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                ],
              ),
      ),
    );
  }
}

/// Apple 登录按钮（FR-44，仅 iOS）：黑底白字 + Apple 标 + 文案。加载态显示进度环。
class _AppleButton extends StatelessWidget {
  const _AppleButton({required this.loading, required this.onTap});

  final bool loading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: double.infinity,
      child: FilledButton(
        key: const ValueKey('hardDialogAppleCta'),
        onPressed: onTap,
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xFF000000),
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(vertical: 13),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
        ),
        child: loading
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : Row(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.apple, size: 20, color: Colors.white),
                  const SizedBox(width: 10),
                  Text(l10n.loginApple,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                ],
              ),
      ),
    );
  }
}
