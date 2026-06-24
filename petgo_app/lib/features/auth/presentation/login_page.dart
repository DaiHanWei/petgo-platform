import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/config/legal_urls.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/auth_repository.dart';
import '../domain/auth_routing.dart';
import '../domain/auth_state.dart';
import '../domain/login_response.dart';

/// 登录入口页（Story 1.3/1.4 · login.html 1:1 还原）。
///
/// 紫渐变品牌头（光晕 + Pop Art + logo + 欢迎语）+ 白色主体：Google 一键登录 +
/// 「自动建号」提示 + 社区数字背书 + 兽医登录入口（FR-29）+ 条款/隐私 Text Link（FR-0D，无勾选框）。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  bool _busy = false;

  void _onGoogleLogin() => _login(() => ref.read(authRepositoryProvider).loginWithGoogle());
  void _onAppleLogin() => _login(() => ref.read(authRepositoryProvider).loginWithApple());

  /// 统一登录链路（Google / Apple 共用）：成功落态 + 新老分流；取消/失败内联提示。
  Future<void> _login(Future<LoginResponse> Function() runner) async {
    if (_busy) return;
    setState(() => _busy = true);
    final l10n = AppLocalizations.of(context);
    try {
      final resp = await runner();
      ref.read(authControllerProvider.notifier).applyLogin(resp);
      if (!mounted) return;
      // 新老分流：老用户进 App；新用户进引导占位（Story 1.6 本体）。
      switch (decidePostLoginRoute(resp)) {
        case PostLoginRoute.toApp:
          context.go('/home');
        case PostLoginRoute.toOnboarding:
          context.go('/onboarding');
      }
    } on LoginCancelled {
      _showBanner(l10n.loginCancelled);
    } catch (_) {
      _showBanner(l10n.loginFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showBanner(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _open(String url) async {
    final uri = Uri.tryParse(url);
    if (uri != null) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: ListView(
        padding: EdgeInsets.zero,
        children: [
          _brandHeader(context),
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 28, 22, 16),
            child: Column(
              children: [
                // Apple 登录（FR-44）：仅 iOS 显示，与 Google 同级且置顶（App Store 4.8）。黑底白字。
                if (defaultTargetPlatform == TargetPlatform.iOS) ...[
                  _AuthButton(
                    key: const ValueKey('appleLoginButton'),
                    onPressed: _busy ? null : _onAppleLogin,
                    background: const Color(0xFF000000),
                    foreground: Colors.white,
                    border: false,
                    icon: SvgPicture.string(_kAppleLogo, width: 19, height: 19),
                    label: l10n.loginApple,
                  ),
                  const SizedBox(height: 12),
                ],
                // Google 登录（与 Apple 同高/同圆角/同字重，白底描边，不弱化）。
                _AuthButton(
                  key: const ValueKey('googleLoginButton'),
                  onPressed: _busy ? null : _onGoogleLogin,
                  background: AppColors.card,
                  foreground: AppColors.ink,
                  border: true,
                  icon: SvgPicture.string(_kGoogleG, width: 22, height: 22),
                  label: l10n.loginGoogle,
                ),
                const SizedBox(height: 12),
                // 自动建号提示（violet-50 底）
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                  decoration: BoxDecoration(
                    color: AppColors.cream2,
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.info_outline, size: 15, color: AppColors.mint),
                      const SizedBox(width: 9),
                      Expanded(
                        child: RichText(
                          text: TextSpan(
                            style: const TextStyle(fontSize: 12, color: AppColors.mint, height: 1.6),
                            children: [
                              TextSpan(text: l10n.loginAutoAccountPrefix),
                              TextSpan(
                                  text: l10n.loginAutoAccountEmphasis,
                                  style: const TextStyle(fontWeight: FontWeight.w700)),
                              TextSpan(text: l10n.loginAutoAccountSuffix),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 26),
                // 社区数字背书
                _trustStats(l10n),
                const SizedBox(height: 28),
                // 兽医登录入口（FR-29）
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  decoration: const BoxDecoration(
                    border: Border(top: BorderSide(color: AppColors.line2)),
                  ),
                  child: GestureDetector(
                    key: const ValueKey('vetLoginLink'),
                    onTap: _busy ? null : () => context.push('/vet/login'),
                    behavior: HitTestBehavior.opaque,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Flexible(
                          child: Text(l10n.loginVetPrompt,
                              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                        ),
                        Text(l10n.vetLoginLink,
                            style: const TextStyle(
                                fontSize: 12, color: AppColors.mint, fontWeight: FontWeight.w600)),
                        const Text(' →',
                            style: TextStyle(
                                fontSize: 12, color: AppColors.mint, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                // 条款与隐私（FR-0D：两份可点链接，无勾选框）
                _AgreementLinks(
                  prefix: l10n.loginAgreementPrefix,
                  terms: l10n.termsOfService,
                  and: l10n.loginAgreementAnd,
                  privacy: l10n.privacyPolicy,
                  onTerms: () => _open(kTermsUrl),
                  onPrivacy: () => _open(kPrivacyUrl),
                ),
                const SizedBox(height: 12),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 品牌头（原型 P-03 重塑）：纯紫实底 #7D45F6 + 白光晕圆/中心辉光 + 返回钮 + 新 logo 镂空标 + 欢迎语。
  Widget _brandHeader(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final topInset = MediaQuery.of(context).padding.top;
    return Container(
      width: double.infinity,
      color: AppColors.brandViolet,
      child: ClipRect(
        child: Stack(
          children: [
            // 装饰光晕圆（右上 / 左下）。
            Positioned(top: -50, right: -50, child: _glowCircle(190, 0.08)),
            Positioned(bottom: -70, left: -30, child: _glowCircle(160, 0.06)),
            // 中心径向辉光（呼应 splash）。
            Positioned.fill(
              child: Center(
                child: Container(
                  width: 240,
                  height: 240,
                  decoration: const BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(
                      colors: [Color(0x2EFFFFFF), Color(0x00FFFFFF)],
                      stops: [0.0, 0.68],
                    ),
                  ),
                ),
              ),
            ),
            // 内容
            Padding(
              padding: EdgeInsets.fromLTRB(24, topInset + 16, 24, 34),
              child: Column(
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Material(
                      color: Colors.white.withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(11),
                      child: InkWell(
                        key: const ValueKey('loginBack'),
                        borderRadius: BorderRadius.circular(11),
                        onTap: () =>
                            context.canPop() ? context.pop() : context.go('/home'),
                        child: const SizedBox(
                          width: 36,
                          height: 36,
                          child: Icon(Icons.arrow_back, size: 18, color: Colors.white),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 6),
                  // 新 logo 镂空标：白狗 + 紫猫（= 实底色，呈负空间镂空，同 splash 终态）。
                  const _BrandMark(size: 58),
                  const SizedBox(height: 14),
                  Text(l10n.loginWelcomeTitle,
                      style: const TextStyle(
                          fontSize: 23, fontWeight: FontWeight.w700, color: Colors.white)),
                  const SizedBox(height: 7),
                  Text(
                    l10n.loginWelcomeSubtitle,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        fontSize: 12.5, height: 1.6, color: Colors.white.withValues(alpha: 0.72)),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _glowCircle(double size, double opacity) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.white.withValues(alpha: opacity),
        ),
      );

  Widget _trustStats(AppLocalizations l10n) => Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _stat('10K+', l10n.loginStatActiveMembers),
          _statDivider(),
          _stat('50K+', l10n.loginStatMomentsRecorded),
          _statDivider(),
          _stat('100+', l10n.loginStatVets),
        ],
      );

  Widget _stat(String n, String label) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14),
        child: Column(
          children: [
            Text(n,
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.mint)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(fontSize: 10, color: AppColors.muted)),
          ],
        ),
      );

  Widget _statDivider() =>
      Container(width: 1, height: 32, color: AppColors.line);
}

/// 多色 Google「G」标（原型 P-03 svg 同 path）。
const String _kGoogleG =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">'
    '<path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>'
    '<path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>'
    '<path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>'
    '<path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>'
    '</svg>';

/// Apple 标（白色，原型 P-03 svg 同 path）。
const String _kAppleLogo =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="white">'
    '<path d="M16.365 1.43c0 1.14-.493 2.27-1.177 3.08-.744.9-1.99 1.57-2.987 1.57-.12 0-.23-.02-.3-.03-.01-.06-.04-.22-.04-.39 0-1.15.572-2.27 1.206-2.98.804-.94 2.142-1.64 3.248-1.68.03.13.05.28.05.43zm4.565 15.71c-.03.07-.463 1.58-1.518 3.12-.945 1.34-1.94 2.71-3.43 2.71-1.517 0-1.9-.88-3.63-.88-1.698 0-2.302.91-3.67.91-1.377 0-2.332-1.26-3.428-2.8-1.287-1.82-2.323-4.63-2.323-7.28 0-4.28 2.797-6.55 5.552-6.55 1.448 0 2.675.95 3.6.95.865 0 2.222-1.01 3.902-1.01.613 0 2.886.06 4.374 2.19-.13.09-2.383 1.37-2.383 4.19 0 3.26 2.854 4.42 2.955 4.45z"/>'
    '</svg>';

/// 新品牌 logo 镂空标：白狗 + 实底紫猫（猫 = 背景色 → 呈负空间镂空，同 splash 终态）。
class _BrandMark extends StatelessWidget {
  const _BrandMark({required this.size});

  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SvgPicture.asset('assets/brand/mark_dog.svg', width: size, height: size),
          SvgPicture.asset('assets/brand/mark_cat.svg',
              width: size,
              height: size,
              colorFilter: const ColorFilter.mode(AppColors.brandViolet, BlendMode.srcIn)),
        ],
      ),
    );
  }
}

/// 登录按钮（Apple/Google 共用）：等高/等圆角/等字重，图标 + 标签居中。
class _AuthButton extends StatelessWidget {
  const _AuthButton({
    super.key,
    required this.onPressed,
    required this.background,
    required this.foreground,
    required this.border,
    required this.icon,
    required this.label,
  });

  final VoidCallback? onPressed;
  final Color background;
  final Color foreground;
  final bool border;
  final Widget icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: background,
          foregroundColor: foreground,
          elevation: 0,
          padding: const EdgeInsets.symmetric(vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
            side: border
                ? const BorderSide(color: AppColors.line, width: 1.5)
                : BorderSide.none,
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            icon,
            const SizedBox(width: 11),
            Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

/// 协议链接（Text Link 模式，FR-0D：两份可点链接，**无勾选框**）。
class _AgreementLinks extends StatelessWidget {
  const _AgreementLinks({
    required this.prefix,
    required this.terms,
    required this.and,
    required this.privacy,
    required this.onTerms,
    required this.onPrivacy,
  });

  final String prefix;
  final String terms;
  final String and;
  final String privacy;
  final VoidCallback onTerms;
  final VoidCallback onPrivacy;

  @override
  Widget build(BuildContext context) {
    const baseStyle = TextStyle(fontSize: 10.5, color: AppColors.textDisclaimer, height: 1.7);
    final linkStyle = baseStyle.copyWith(
      color: AppColors.muted,
      decoration: TextDecoration.underline,
    );
    return Wrap(
      alignment: WrapAlignment.center,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(prefix, style: baseStyle),
        GestureDetector(
          key: const ValueKey('termsLink'),
          onTap: onTerms,
          child: Text(terms, style: linkStyle),
        ),
        Text(and, style: baseStyle),
        GestureDetector(
          key: const ValueKey('privacyLink'),
          onTap: onPrivacy,
          child: Text(privacy, style: linkStyle),
        ),
      ],
    );
  }
}
