import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/auth_repository.dart';
import '../domain/auth_routing.dart';
import '../domain/auth_state.dart';

/// 法务 H5 链接（env 注入，默认占位；正式页本体由运营/法务产出）。
const String _kTermsUrl =
    String.fromEnvironment('PETGO_TERMS_URL', defaultValue: 'https://petgo.example/terms');
const String _kPrivacyUrl =
    String.fromEnvironment('PETGO_PRIVACY_URL', defaultValue: 'https://petgo.example/privacy');

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

  Future<void> _onGoogleLogin() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l10n = AppLocalizations.of(context);
    try {
      final resp = await ref.read(authRepositoryProvider).loginWithGoogle();
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
                // Google 一键登录（白底主钮）
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    key: const ValueKey('googleLoginButton'),
                    onPressed: _busy ? null : _onGoogleLogin,
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.card,
                      foregroundColor: AppColors.ink,
                      elevation: 0,
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                        side: const BorderSide(color: AppColors.line, width: 1.5),
                      ),
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const _GoogleG(),
                        const SizedBox(width: 11),
                        Text(l10n.loginGoogle,
                            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
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
                  onTerms: () => _open(_kTermsUrl),
                  onPrivacy: () => _open(_kPrivacyUrl),
                ),
                const SizedBox(height: 12),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 紫渐变品牌头：光晕圆 + Pop Art 方块 + 返回钮 + logo + 欢迎语。
  Widget _brandHeader(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final topInset = MediaQuery.of(context).padding.top;
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint500],
        ),
      ),
      child: ClipRect(
        child: Stack(
          children: [
            // 装饰光晕圆
            Positioned(
              top: -50,
              right: -50,
              child: _glowCircle(190, 0.08),
            ),
            Positioned(
              bottom: -70,
              left: -30,
              child: _glowCircle(160, 0.06),
            ),
            // Pop Art 错位方块（右上）
            Positioned(
              top: topInset + 18,
              right: 22,
              child: Transform.rotate(
                angle: 0.26,
                child: Container(
                  width: 28,
                  height: 28,
                  decoration: BoxDecoration(
                      color: AppColors.popRed.withValues(alpha: 0.55),
                      borderRadius: BorderRadius.circular(7)),
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
                  // Logo 方块
                  Container(
                    width: 54,
                    height: 54,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: Colors.white.withValues(alpha: 0.25), width: 1.5),
                    ),
                    child: const Icon(Icons.pets, size: 28, color: Colors.white),
                  ),
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

/// Google「G」彩色字标（品牌简化呈现）。
class _GoogleG extends StatelessWidget {
  const _GoogleG();

  @override
  Widget build(BuildContext context) {
    return const Text('G',
        style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            color: AppColors.brandGoogleBlue,
            height: 1.0));
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
