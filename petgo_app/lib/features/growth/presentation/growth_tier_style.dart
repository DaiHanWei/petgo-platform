import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/growth_models.dart';

/// 段位的颜色与名称（Story 1.5 · §5.6）：段位色只来自 `AppColors`，段位名只来自 arb。未知段位按 Pemula 兜底。
class GrowthTierStyle {
  GrowthTierStyle._();

  static Color color(GrowthTier? tier) => switch (tier) {
        GrowthTier.buddy => AppColors.tierSahabat,
        GrowthTier.guardian => AppColors.tierPenjaga,
        GrowthTier.star => AppColors.tierBintangStart,
        _ => AppColors.tierPemula,
      };

  static Color tint(GrowthTier? tier) => switch (tier) {
        GrowthTier.buddy => AppColors.tierSahabatTint,
        GrowthTier.guardian => AppColors.tierPenjagaTint,
        GrowthTier.star => AppColors.tierBintangTint,
        _ => AppColors.tierPemulaTint,
      };

  /// Bintang 为渐变；其余段位为单色（两端同色）。
  static List<Color> gradient(GrowthTier? tier) => tier == GrowthTier.star
      ? const [AppColors.tierBintangStart, AppColors.tierBintangEnd]
      : [color(tier), color(tier)];

  static String name(AppLocalizations l10n, GrowthTier? tier) => switch (tier) {
        GrowthTier.buddy => l10n.growthTierBuddy,
        GrowthTier.guardian => l10n.growthTierGuardian,
        GrowthTier.star => l10n.growthTierStar,
        _ => l10n.growthTierNewbie,
      };
}

/// 按当前语言格式化千分位（印尼语点号：`1.420`）。
String growthNumber(BuildContext context, int n) =>
    NumberFormat.decimalPattern(Localizations.localeOf(context).toLanguageTag()).format(n);
