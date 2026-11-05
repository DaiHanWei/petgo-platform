import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';

/// 「Resmi」官方话题徽章（V1.4.2 social-topic · Story 4.3 · AC5，UI 稿 `.lC-resmi`）。
///
/// 🔴 **唯一一份**：C1 首页话题行、C7 话题页标题、C9 推荐行、（4.4）C2 联想行共用，不要每处各画一个。
/// violet100 底 · verified 12（mint600）+ 2 + 「Resmi」10/1.0 w600 mint600 · 内边距 上下 2 / 左右 4 · 圆角 4。
class TopicOfficialBadge extends StatelessWidget {
  const TopicOfficialBadge({super.key});

  static const double iconSize = 12;
  static const double fontSize = 10;
  static const double radius = 4;
  static const EdgeInsets padding = EdgeInsets.symmetric(vertical: 2, horizontal: 4);

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('topicOfficialBadge'),
      padding: padding,
      decoration: BoxDecoration(
        color: AppColors.violet100,
        borderRadius: BorderRadius.circular(radius),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.verified_rounded, size: iconSize, color: AppColors.mint600),
          const SizedBox(width: 2),
          Text(
            AppLocalizations.of(context).topicOfficialBadge,
            style: const TextStyle(
              fontSize: fontSize,
              height: 1.0,
              fontWeight: FontWeight.w600,
              color: AppColors.mint600,
            ),
          ),
        ],
      ),
    );
  }
}
