import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../domain/card_link.dart';
import '../domain/share_service.dart';

/// 建档「创建成功」庆祝页（Story 1.7 R2 / AC4 · FR-0G · 决策 F15）。
///
/// 宠物头像 + 名字 +「[宠物名] 的专属档案已创建！」；副 CTA「分享宠物名片」（调系统分享传
/// FR-14 名片链接）+ 主 CTA「开始探索」。主 CTA 串接推送权限时机（庆祝页后、进首页前）由
/// [onStartExplore] 注入（路由侧接 Story 6.4 推送闸门后 `context.go('/home')`）。
/// 经 FR-16/FR-12 触发的建档**不路由到本页**（跳过庆祝页，见 [showsBuildCelebration]）。
class ProfileCreatedCelebrationPage extends ConsumerWidget {
  const ProfileCreatedCelebrationPage({
    super.key,
    required this.petName,
    required this.cardToken,
    required this.onStartExplore,
    this.avatarUrl,
  });

  final String petName;

  /// 不可枚举名片 token，拼 FR-14 对外分享链接（绝不暴露顺序 id）。
  final String cardToken;

  /// 主 CTA「开始探索」：路由侧注入「触发推送权限时机 → 进首页」。
  final Future<void> Function() onStartExplore;

  final String? avatarUrl;

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
              CircleAvatar(
                key: const ValueKey('celebrationAvatar'),
                radius: 48,
                backgroundColor: AppColors.surface,
                backgroundImage: AppImage.provider(avatarUrl, thumbWidth: 240),
                child: avatarUrl == null
                    ? const Icon(Icons.pets_rounded, size: 44, color: AppColors.accentGrowth)
                    : null,
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(petName, style: AppTypography.headline, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              Text(
                l10n.profileCreatedTitle(petName),
                style: AppTypography.body,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: AppSpacing.section),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('celebrationStartExplore'),
                  onPressed: onStartExplore,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.accentGrowth,
                    foregroundColor: AppColors.onAccent,
                    padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
                  ),
                  child: Text(l10n.profileCreatedStartExplore, style: AppTypography.button),
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextButton.icon(
                key: const ValueKey('celebrationShare'),
                onPressed: () =>
                    ref.read(shareServiceProvider)(petCardShareUrl(cardToken)),
                icon: const Icon(Icons.ios_share, size: 18),
                label: Text(l10n.shareFabLabel, style: AppTypography.button),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
