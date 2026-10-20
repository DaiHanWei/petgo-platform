import 'package:flutter/painting.dart';

import '../../../core/theme/colors.dart';
import 'growth_models.dart';

/// 每级颜色（Story 1.5 · AC7 / 架构 §5.6）：取 UI 稿 A1 的逐级色表（`AppColors.growthLevelColors`），
/// 段位由 [levels] 里的 `tier` 决定、不写死等级区间；段内第 n 级取该段第 n 个色，后台追加的 Lv21+ 取所属段位
/// 最后一个色（不插值出新色）。只用于等级页进度条；角标 / 段位名 / 胶囊用段位色。
Color growthLevelColor(int level, List<GrowthLevelDef> levels) {
  final tier = _tierOf(level, levels);
  final palette = _palette(tier);
  var first = level;
  var found = false;
  for (final d in levels) {
    if (d.tier == tier && d.level < first) first = d.level;
    if (d.tier == tier) found = true;
  }
  // 等级表里没有该段位的行（如表为空）：按上线初始分段的段首级兜底，保证段内仍逐级变色。
  if (!found) first = _defaultFirstLevel(tier);
  final index = (level - first).clamp(0, palette.length - 1);
  return palette[index];
}

GrowthTier _tierOf(int level, List<GrowthLevelDef> levels) {
  for (final d in levels) {
    if (d.level == level && d.tier != null) return d.tier!;
  }
  // 等级表缺这一级：按最近的低一级段位兜底；再不行按 Lv1–20 的初始分段。
  GrowthTier? best;
  var bestLevel = -1;
  for (final d in levels) {
    if (d.tier != null && d.level <= level && d.level > bestLevel) {
      best = d.tier;
      bestLevel = d.level;
    }
  }
  if (best != null) return best;
  if (level <= 4) return GrowthTier.newbie;
  if (level <= 9) return GrowthTier.buddy;
  if (level <= 14) return GrowthTier.guardian;
  return GrowthTier.star;
}

int _defaultFirstLevel(GrowthTier tier) => switch (tier) {
      GrowthTier.newbie => 1,
      GrowthTier.buddy => 5,
      GrowthTier.guardian => 10,
      GrowthTier.star => 15,
    };

List<Color> _palette(GrowthTier tier) {
  const all = AppColors.growthLevelColors;
  return switch (tier) {
    GrowthTier.newbie => all.sublist(0, 4),
    GrowthTier.buddy => all.sublist(4, 9),
    GrowthTier.guardian => all.sublist(9, 14),
    GrowthTier.star => all.sublist(14, 20),
  };
}
