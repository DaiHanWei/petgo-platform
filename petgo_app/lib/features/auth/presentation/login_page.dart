import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/auth_repository.dart';
import '../domain/auth_routing.dart';
import '../domain/auth_state.dart';

/// 法务 H5 链接（env 注入，默认占位；正式页本体由运营/法务产出）。
const String _kTermsUrl =
    String.fromEnvironment('PETGO_TERMS_URL', defaultValue: 'https://petgo.example/terms');
const String _kPrivacyUrl =
    String.fromEnvironment('PETGO_PRIVACY_URL', defaultValue: 'https://petgo.example/privacy');

/// 最小登录入口页（Story 1.3 自测入口）。
///
/// 「Google 登录」按钮 + 按钮下方《服务条款》《隐私政策》Text Link（FR-0D，无勾选框）。
/// 正式触发场景（软浮层/强弹窗）由 Story 1.4 提供，本页可被其复用或仅作自测。
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
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(l10n.appTitle, style: AppTypography.display),
              const SizedBox(height: AppSpacing.section),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: const ValueKey('googleLoginButton'),
                  onPressed: _busy ? null : _onGoogleLogin,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.accentGrowth,
                    foregroundColor: AppColors.onAccent,
                    padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                  ),
                  icon: const Icon(Icons.login),
                  label: Text(l10n.loginGoogle, style: AppTypography.button),
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              // 兽医登录入口（Story 5.1 F1）：Google 按钮下方小字链接，走独立账密登录页。
              TextButton(
                key: const ValueKey('vetLoginLink'),
                onPressed: _busy ? null : () => context.push('/vet/login'),
                child: Text(l10n.vetLoginLink, style: AppTypography.caption),
              ),
              const SizedBox(height: AppSpacing.md),
              _AgreementLinks(
                prefix: l10n.loginAgreementPrefix,
                terms: l10n.termsOfService,
                and: l10n.loginAgreementAnd,
                privacy: l10n.privacyPolicy,
                onTerms: () => _open(_kTermsUrl),
                onPrivacy: () => _open(_kPrivacyUrl),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 协议链接（Text Link 模式，FR-0D：两份可点链接，**无勾选框**）。
///
/// 用离散可点 [Text] 而非 RichText 内联 span：① 无 recognizer 生命周期；② widget test 可直接定位链接。
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
    final linkStyle = AppTypography.disclaimer.copyWith(
      color: AppColors.accentConsult,
      decoration: TextDecoration.underline,
    );
    return Wrap(
      alignment: WrapAlignment.center,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text(prefix, style: AppTypography.disclaimer),
        GestureDetector(
          key: const ValueKey('termsLink'),
          onTap: onTerms,
          child: Text(terms, style: linkStyle),
        ),
        Text(and, style: AppTypography.disclaimer),
        GestureDetector(
          key: const ValueKey('privacyLink'),
          onTap: onPrivacy,
          child: Text(privacy, style: linkStyle),
        ),
      ],
    );
  }
}
