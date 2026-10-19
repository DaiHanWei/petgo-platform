import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';

/// 送礼模块私有视觉常量（V1.4.0 gift Story 1.3，UI 稿 B / C 泳道定稿值）。
///
/// 只放主题 token（`AppColors`）里没有的值；主色 #845EC9 / #C2B0EC / #F6A609 一律用 `AppColors`。
abstract final class GiftStyle {
  /// 浅紫底（礼物格图标底、选中格底、步进器容器、详情页礼物区）。
  static const Color tint = Color(0xFFF8F6FF);

  /// 礼物格外圈。
  static const Color cellBorder = Color(0xFFF0EDF5);

  /// 专属格描边。
  static const Color exclusiveBorder = Color(0xFFE9DDFB);

  /// 置灰（步进器到边界）。
  static const Color disabled = Color(0xFFB6B6B6);

  /// 面板标题头像底。
  static const Color avatarBg = Color(0xFFEDE3FF);

  /// 面板遮罩 rgba(20,16,25,.45)。
  static const Color barrier = Color(0x73141019);

  // ① 段位色，对齐时改用 ① 的 token（本分支 ① 不存在，先作私有常量）。
  static const Color levelTeal = Color(0xFF0A7FA3); // Lv5–9
  static const Color levelBlue = Color(0xFF2B4FBF); // Lv10–14
  static const List<Color> levelTop = [Color(0xFF4A2E9E), Color(0xFFB5307F)]; // Lv15+

  /// 段位底色（标签 / 锁圈外环）：Lv15+ 为渐变，其余纯色。
  static Gradient levelGradient(int level) {
    if (level >= 15) {
      return const LinearGradient(
          begin: Alignment.topLeft, end: Alignment.bottomRight, colors: levelTop);
    }
    final c = level >= 10 ? levelBlue : levelTeal;
    return LinearGradient(colors: [c, c]);
  }

  /// 锁圈外环（纯色；Lv15+ 取渐变起点色）。
  static Color levelRing(int level) =>
      level >= 15 ? levelTop.first : (level >= 10 ? levelBlue : levelTeal);

  /// 专属格图标底渐变（160deg）。
  static Gradient exclusiveIconBg(int level) => LinearGradient(
        begin: const Alignment(-0.34, -0.94),
        end: const Alignment(0.34, 0.94),
        colors: level >= 15
            ? const [Color(0xFFF1E8FB), Color(0xFFFBEAF3)]
            : const [Color(0xFFEAF6FA), Color(0xFFF8F6FF)],
      );
}

/// 价位 → 档名（PRD §3.1 五档）；非五档价位 → null（只显示价格）。
String? giftTierName(AppLocalizations l10n, int price) => switch (price) {
      1000 => l10n.giftTierSnack,
      5000 => l10n.giftTierTreat,
      10000 => l10n.giftTierToy,
      25000 => l10n.giftTierStyle,
      50000 => l10n.giftTierBig,
      _ => null,
    };

/// 礼物图标（`AppImage`；面板、选渠道摘要卡、送出动效用同一个 URL，图片缓存命中）。
/// 加载失败 / 无 URL → 回退礼盒图标。
Widget giftIcon(String? url, {required double size, Key? key}) {
  final fallback = SizedBox(
      width: size,
      height: size,
      child: Icon(Icons.redeem, size: size * 0.8, color: AppColors.mint));
  if (url == null || url.isEmpty) return KeyedSubtree(key: key, child: fallback);
  return AppImage.widget(
    url,
    key: key,
    width: size,
    height: size,
    fit: BoxFit.contain,
    errorBuilder: (_, _, _) => fallback,
  );
}
