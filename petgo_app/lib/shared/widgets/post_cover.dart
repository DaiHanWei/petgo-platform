import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 帖子封面占位（无真实首图时）：按内容类型取柔彩底 + emoji。
///
/// 对齐设计稿 S03 Home Feed / S17 我的发布——image-led 卡片在缺图时不退化成纯文字白卡，
/// 而是显示柔彩色块 + 类型 emoji，保持瀑布流的视觉层次。
class PostCoverPlaceholder extends StatelessWidget {
  const PostCoverPlaceholder({
    super.key,
    required this.type,
    this.height,
    this.emojiSize = 40,
  });

  /// 内容类型线格式：DAILY / GROWTH_MOMENT / KNOWLEDGE。
  final String type;

  /// 固定高度；为 null 时由外层约束（如 Home 卡用宽高比 16:10）。
  final double? height;
  final double emojiSize;

  static (Color, String) _styleFor(String type) {
    switch (type) {
      case 'GROWTH_MOMENT':
        return (AppColors.coverGrowth, '🌱');
      case 'KNOWLEDGE':
        return (AppColors.coverKnowledge, '📖');
      case 'DAILY':
      default:
        return (AppColors.coverDaily, '🐾');
    }
  }

  @override
  Widget build(BuildContext context) {
    final (color, emoji) = _styleFor(type);
    return Container(
      height: height,
      width: double.infinity,
      color: color,
      alignment: Alignment.center,
      child: Text(emoji, style: TextStyle(fontSize: emojiSize)),
    );
  }
}
