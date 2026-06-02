import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 状态 A 档案创建引导页（Story 1.7 F1，FR-0G）。
///
/// 「立即创建」→ 跳 Epic 2 档案创建表单（本 Story 期占位/路由锚点）；
/// 「跳过，稍后创建」→ 进首页 + 激活提示条逻辑。
/// mock/真实「完成创建」→ 进首页 + 永不显示提示条（置 petProfileCompleted）。
class ProfileOnboardingPage extends ConsumerWidget {
  const ProfileOnboardingPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.pets_rounded, size: 64, color: AppColors.accentGrowth),
              const SizedBox(height: AppSpacing.lg),
              Text(l10n.profileOnboardingTitle, style: AppTypography.headline, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.profileOnboardingBody, style: AppTypography.caption, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.section),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('profileOnboardingCreate'),
                  // 立即创建 → 宠物档案创建表单（Story 2.2）。
                  onPressed: () => context.go('/profile/create'),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.accentGrowth,
                    foregroundColor: AppColors.onAccent,
                    padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                  ),
                  child: Text(l10n.profileOnboardingCreate, style: AppTypography.button),
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextButton(
                key: const ValueKey('profileOnboardingSkip'),
                // 跳过 → 进首页 + 激活提示条逻辑（提示条由首页据状态机渲染）。
                onPressed: () => context.go('/home'),
                child: Text(
                  l10n.profileOnboardingSkip,
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
