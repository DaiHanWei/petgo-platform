import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../data/vet_repository.dart';

/// 兽医账密登录页（Story 5.1 F1/F2/F3）。
///
/// 与用户侧 Google 流程隔离：账号 + 密码表单 → role=VET JWT → 跳兽医工作台壳。
/// **无「忘记密码」入口**（重置走运营），仅文案提示联系运营。
class VetLoginPage extends ConsumerStatefulWidget {
  const VetLoginPage({super.key});

  @override
  ConsumerState<VetLoginPage> createState() => _VetLoginPageState();
}

class _VetLoginPageState extends ConsumerState<VetLoginPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _onLogin() async {
    if (_busy) return;
    setState(() => _busy = true);
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(vetRepositoryProvider).login(_username.text.trim(), _password.text);
      ref.read(authControllerProvider.notifier).applyVetLogin();
      if (!mounted) return;
      context.go('/vet/workbench');
    } on DioException catch (e) {
      // 429 限流 → 专用文案；其余（含 401）统一「账号或密码错误」防枚举。
      final msg = e.response?.statusCode == 429 ? l10n.vetLoginRateLimited : l10n.vetLoginFailed;
      _showBanner(msg);
    } catch (_) {
      _showBanner(l10n.vetLoginFailed);
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

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.vetLoginTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const ValueKey('vetUsernameField'),
                controller: _username,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(labelText: l10n.vetUsernameLabel),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const ValueKey('vetPasswordField'),
                controller: _password,
                obscureText: true,
                decoration: InputDecoration(labelText: l10n.vetPasswordLabel),
              ),
              const SizedBox(height: AppSpacing.section),
              FilledButton(
                key: const ValueKey('vetLoginButton'),
                onPressed: _busy ? null : _onLogin,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.vetPrimary,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                ),
                child: Text(l10n.vetLoginButton, style: AppTypography.button),
              ),
              const SizedBox(height: AppSpacing.md),
              // 无自助「忘记密码」流程——仅提示联系运营（FR-29 / NFR-12）。
              Text(
                l10n.vetForgotHint,
                style: AppTypography.disclaimer,
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
