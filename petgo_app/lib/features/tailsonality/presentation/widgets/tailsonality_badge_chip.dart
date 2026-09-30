import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// Tailsonality 角色小标胶囊（V1.3.2 Story 3.3 · AC5）：只显 4 字母（如 `ENTJ`）。
///
/// 公开主页宠物卡与本人档案卡两处共用。`letters` 为 null / 空 → 不渲染任何东西（**不做占位**）。
class TailsonalityBadgeChip extends StatelessWidget {
  const TailsonalityBadgeChip({super.key, required this.letters, this.compact = false});

  final String? letters;

  /// 公开主页宠物卡行高更紧，用小一号。
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final l = letters;
    if (l == null || l.isEmpty) return const SizedBox.shrink();
    return Container(
      padding: EdgeInsets.symmetric(horizontal: compact ? 6 : 8, vertical: compact ? 1 : 2),
      decoration: BoxDecoration(
        color: AppColors.tailsonalityBannerBg,
        borderRadius: BorderRadius.circular(9999),
        border: Border.all(color: AppColors.mint500),
      ),
      child: Text(
        l,
        style: TextStyle(
          fontSize: compact ? 11 : 12,
          fontWeight: FontWeight.w800,
          letterSpacing: 0.6,
          color: AppColors.mint600,
          // NFR-8 统一写法：代号类文本一律等宽数字特性（对字母无影响，与结果列表同写法）。
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}
