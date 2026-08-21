import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 内容类型徽章的**唯一**口径：Momen 绿 / Tips 黄 / Cerita 紫。
///
/// ⚠️ 详情页与分享卡都要显示这个徽章。两处各写一份 `switch` 迟早会打架
/// （改了一处忘另一处 → 同一条内容在详情页是紫的、在分享卡上是绿的），
/// 所以映射只在这里存在一份。
class ContentTypeBadge {
  const ContentTypeBadge(this.label, this.fg, this.bg);

  final String label;
  final Color fg;
  final Color bg;

  static ContentTypeBadge of(String type, AppLocalizations l10n) => switch (type) {
        'GROWTH_MOMENT' =>
          ContentTypeBadge(l10n.mePostTypeMomen, AppColors.momenBadgeText, AppColors.momenBadgeBg),
        'KNOWLEDGE' =>
          ContentTypeBadge(l10n.mePostTypeTips, AppColors.tipsBadgeText, AppColors.goldTint),
        _ => ContentTypeBadge(l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint),
      };
}
