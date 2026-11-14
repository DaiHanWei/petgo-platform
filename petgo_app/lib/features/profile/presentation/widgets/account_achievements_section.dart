import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';

/// 「Pencapaian akun」账号成就区（V1.5.0 onboarding-recall Story 3.2 · FR-178 · UI 稿 C1 / D2）。
///
/// 宠物里程碑页（L / M / S 上方）与账号级里程碑页共用：标题 + 右侧 `x/3` + 3 枚徽章（顺序 Teman pertama /
/// Simpan pertama / Lulus Pemula）。样式同 L / M / S 分组；徽章由调用方给（宠物页沿用 S 组徽章的点击交互）。
class AccountAchievementsSection extends StatelessWidget {
  const AccountAchievementsSection({
    super.key,
    required this.completed,
    required this.total,
    required this.badges,
  });

  final int completed;
  final int total;
  final List<Widget> badges;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      key: const ValueKey('accountAchievementsSection'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(l10n.accountAchievementsTitle,
                style: const TextStyle(
                    fontWeight: FontWeight.w700, fontSize: 13, color: AppColors.mint, letterSpacing: 0.3)),
            Text('$completed/$total',
                key: const ValueKey('accountAchievementsCount'),
                style: const TextStyle(
                    color: AppColors.muted,
                    fontWeight: FontWeight.w600,
                    fontSize: 12,
                    fontFeatures: [FontFeature.tabularFigures()])),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Wrap(spacing: AppSpacing.md, runSpacing: AppSpacing.md, children: badges),
      ],
    );
  }
}
