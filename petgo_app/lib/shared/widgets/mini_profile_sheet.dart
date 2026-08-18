import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/spacing.dart';
import '../../core/theme/typography.dart';
import '../../features/content/data/mini_profile_repository.dart';
import '../../l10n/app_localizations.dart';
import 'app_image.dart';
import 'user_tag_row.dart';

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
    // ⚠️ `width: double.infinity` 不可省（bug 2026-08-07：卡片只有半屏宽）。
    //
    // 根因在 Material 的 BottomSheet：M3 默认 `constraints = maxWidth 640`**非空**，于是
    // `BottomSheet.build` 恒把内容包进 `Align(alignment: bottomCenter)` —— 而 Align 会把
    // 宽度约束**放松**（loosen）后再传给子树。约束一松，这张卡就按内容的固有宽度收缩，
    // 宽度变成「最宽的那个子元素」= 那行个性签名的文字宽度（签名越短卡越窄）。
    // 屏宽 640dp 以下都命中，与设备无关，不是模拟器的问题。
    //
    // 本仓其余 20+ 处底部弹层都在最外层显式撑满（见 `confirm_sheet.dart` 的同款写法），
    // 只有本文件漏了。新增弹层照抄这一层，别指望 sheet 自己会满宽。
    return SizedBox(
      width: double.infinity,
      child: SafeArea(
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
              // V1.1.6 Story 5.1：迷你主页预览卡挂运营标签（四处展示位之一）。
              UserTagRow(
                name: profile.nickname ?? '',
                nameStyle: AppTypography.title,
                tags: profile.isDeactivated ? const [] : profile.tags,
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.miniProfilePostCount(profile.postCount), style: AppTypography.caption),
              const SizedBox(height: AppSpacing.md),
              // 有签名就展示签名，没有才退回「主页筹备中」占位（2026-08-07 用户反馈）。
              //
              // 为什么是二选一而不是两句都显示：占位文案的存在意义就是「这里暂时没内容可看」，
              // 签名一旦在场，这句话既多余又自相矛盾（明明有东西看）。
              if (profile.hasSignature)
                Text(
                  profile.signature!.trim(),
                  key: const ValueKey('miniProfileSignature'),
                  style: AppTypography.body.copyWith(color: AppColors.textSecondary),
                  textAlign: TextAlign.center,
                )
              else
                Text(
                  l10n.miniProfileComingSoon,
                  key: const ValueKey('miniProfileComingSoon'),
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
      ),
    );
  }
}

