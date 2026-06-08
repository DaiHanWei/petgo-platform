import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/content/domain/home_refresh_provider.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/pet_status_selector.dart';
import '../data/me_repository.dart';
import '../domain/auth_state.dart';
import '../domain/onboarding_branch.dart';

/// 宠物状态选择页（Story 1.6 F2/F3，FR-0F）。
///
/// 三选一必选、不可跳过；写 petStatus（后端同时置 onboarding 完成）。
/// 分叉：A → 档案创建引导（Story 1.7）；B/C → 首页（不显示提示条）。改状态后发首页刷新信号。
class PetStatusPage extends ConsumerStatefulWidget {
  const PetStatusPage({super.key});

  @override
  ConsumerState<PetStatusPage> createState() => _PetStatusPageState();
}

class _PetStatusPageState extends ConsumerState<PetStatusPage> {
  String? _selected;
  bool _busy = false;

  Future<void> _onComplete() async {
    final status = _selected;
    if (status == null || _busy) return;
    setState(() => _busy = true);
    try {
      final profile = await ref.read(meRepositoryProvider).updatePetStatus(status);
      // onboarding 完成 → 转已登录 + 回填 profile；发首页刷新信号（FR-17 在 Epic 3 消费）。
      ref.read(authControllerProvider.notifier).completeOnboarding(profile);
      ref.read(homeRefreshProvider.notifier).bump();
      if (!mounted) return;
      context.go(petStatusBranchLocation(status));
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

  /// FR-0F 返回键语义（AC4）：在状态选择页按返回 → 回昵称确认页（已填昵称保留），
  /// **不退出登录流程、不跳过状态选择**（账号在状态选择完成前不置 onboarding 完成）。
  void _onBackToNickname() => context.go('/onboarding/nickname');

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PopScope(
      canPop: false, // 返回键语义自定义（AC4）：状态页返回=回昵称页、不退出流程
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onBackToNickname();
      },
      child: Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.onboardingPetStatusTitle), backgroundColor: AppColors.base),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.onboardingPetStatusSubtitle, style: AppTypography.caption),
              const SizedBox(height: AppSpacing.xl),
              PetStatusSelector(
                selected: _selected,
                onChanged: (v) => setState(() => _selected = v),
              ),
              const Spacer(),
              FilledButton(
                key: const ValueKey('petStatusComplete'),
                // 必选不可跳过：未选时禁用
                onPressed: _selected != null && !_busy ? _onComplete : null,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.accentGrowth,
                  foregroundColor: AppColors.onAccent,
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                ),
                child: Text(l10n.onboardingDone, style: AppTypography.button),
              ),
            ],
          ),
        ),
      ),
      ),
    );
  }
}
