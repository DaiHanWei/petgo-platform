import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/me_repository.dart';
import '../domain/auth_state.dart';

/// 昵称确认页（Story 1.6 F1，FR-0E）。
///
/// 默认填充 Google displayName；实时计数 ≤20（客户端预校验体验，服务端权威）；
/// 超出禁用「继续」。确认 → PATCH 昵称 → 进宠物状态选择页。
const int kNicknameMaxLength = 20;

class NicknamePage extends ConsumerStatefulWidget {
  const NicknamePage({super.key});

  @override
  ConsumerState<NicknamePage> createState() => _NicknamePageState();
}

class _NicknamePageState extends ConsumerState<NicknamePage> {
  late final TextEditingController _controller;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final displayName = ref.read(authControllerProvider).profile?.displayName ?? '';
    _controller = TextEditingController(text: displayName);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _valid {
    final t = _controller.text.trim();
    return t.isNotEmpty && t.length <= kNicknameMaxLength;
  }

  Future<void> _onContinue() async {
    if (!_valid || _busy) return;
    setState(() => _busy = true);
    try {
      final profile = await ref.read(meRepositoryProvider).updateNickname(_controller.text.trim());
      ref.read(authControllerProvider.notifier).applyProfile(profile);
      if (!mounted) return;
      context.go('/onboarding/pet-status');
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).loginFailed)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final count = _controller.text.characters.length;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.onboardingNicknameTitle), backgroundColor: AppColors.base),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const ValueKey('nicknameField'),
                controller: _controller,
                maxLength: kNicknameMaxLength + 5, // 允许超出以触发禁用态，由计数提示
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: l10n.onboardingNicknameLabel,
                  counterText: '$count/$kNicknameMaxLength',
                  errorText: count > kNicknameMaxLength ? '$count/$kNicknameMaxLength' : null,
                ),
              ),
              const SizedBox(height: AppSpacing.xl),
              FilledButton(
                key: const ValueKey('nicknameContinue'),
                onPressed: _valid && !_busy ? _onContinue : null,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.accentGrowth,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                ),
                child: Text(l10n.onboardingContinue, style: AppTypography.button),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
