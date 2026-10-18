import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/growth_models.dart';
import 'growth_tier_style.dart';

/// 「我的」页等级胶囊（V1.4.0 ① Story 1.5 · AC1，UI 稿 A6 `.lvchip`）：段位浅色底圆角胶囊，内含段位色实心小胶囊
/// 「Lv{n}」（白字、tabular 数字）+ 段位名 + `chevron_right`。点击由调用方处理（进等级页 + 埋点）。
class GrowthLevelCapsule extends StatelessWidget {
  const GrowthLevelCapsule({super.key, required this.brief, required this.onTap});

  final GrowthBrief brief;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tierName = GrowthTierStyle.name(l10n, brief.tier);
    final level = l10n.growthLevelShort('${brief.level}');
    final color = GrowthTierStyle.color(brief.tier);
    return Semantics(
      container: true,
      button: true,
      label: '$level $tierName',
      excludeSemantics: true,
      child: GestureDetector(
        key: const ValueKey('meLevelCapsule'),
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.fromLTRB(4, 4, 10, 4),
          decoration: BoxDecoration(color: GrowthTierStyle.tint(brief.tier), borderRadius: BorderRadius.circular(999)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                decoration: BoxDecoration(
                  gradient: LinearGradient(colors: GrowthTierStyle.gradient(brief.tier)),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  level,
                  style: const TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    fontFeatures: [FontFeature.tabularFigures()],
                    color: AppColors.onAccent,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              Text(tierName, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: color)),
              Icon(Icons.chevron_right, size: 16, color: color),
            ],
          ),
        ),
      ),
    );
  }
}
