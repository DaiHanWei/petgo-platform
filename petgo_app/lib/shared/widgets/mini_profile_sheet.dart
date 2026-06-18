import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/data/mini_profile_repository.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';

/// 他人迷你主页预览卡（Story 3.8，FR-26）。点他人头像/昵称从底部弹卡。
///
/// 含头像+昵称、发布数、「主页筹备中」措辞（**非技术性表达**）、关闭按钮；
/// **无「关注」「查看主页」按钮**。已注销用户（isDeactivated）**不弹卡**（NFR-8）。
Future<void> showMiniProfile(BuildContext context, WidgetRef ref, int userId) async {
  final MiniProfile profile;
  try {
    profile = await ref.read(miniProfileRepositoryProvider).getMiniProfile(userId);
  } catch (_) {
    return; // 拉取失败：静默不弹（非关键路径）
  }
  if (profile.isDeactivated) return; // 已注销：不触发迷你卡
  if (!context.mounted) return;
  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: AppColors.surface,
    showDragHandle: true,
    builder: (_) => _MiniProfileCard(profile: profile),
  );
}

class _MiniProfileCard extends StatelessWidget {
  const _MiniProfileCard({required this.profile});

  final MiniProfile profile;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final avatar = profile.avatarUrl;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.xl, AppSpacing.sm, AppSpacing.xl, AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircleAvatar(
              radius: 32,
              backgroundColor: AppColors.border,
              backgroundImage: AppImage.provider(avatar, thumbWidth: 200),
              child: (avatar == null || avatar.isEmpty)
                  ? const Icon(Icons.person_rounded, size: 32, color: AppColors.textTertiary)
                  : null,
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(profile.nickname ?? '', style: AppTypography.title),
            const SizedBox(height: AppSpacing.xs),
            Text(l10n.miniProfilePostCount(profile.postCount), style: AppTypography.caption),
            const SizedBox(height: AppSpacing.md),
            Text(
              l10n.miniProfileComingSoon,
              style: AppTypography.body.copyWith(color: AppColors.textSecondary),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: AppSpacing.lg),
            TextButton(
              key: const ValueKey('miniProfileClose'),
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.commonClose),
            ),
          ],
        ),
      ),
    );
  }
}
