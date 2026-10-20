import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../features/growth/domain/growth_badge.dart';
import 'app_image.dart';
import 'growth_avatar_decoration.dart';

/// 作者头像圆：有 URL → 网络图；注销/空名 → 灰底 person；否则按昵称 hash 取彩底 + 首字母。
///
/// 列表卡片（[MasonryCard]）与内容详情页共用，保证同一用户在两处颜色一致
/// （曾各自实现导致列表/详情底色不一致）。
///
/// V1.4.0 ① Story 3.2：[growth] 非空且未注销时外包 [GrowthAvatarDecoration]（角标 + 头像框）；不传时像素不变。
class LetterAvatar extends StatelessWidget {
  const LetterAvatar({
    super.key,
    this.url,
    required this.name,
    this.deleted = false,
    this.size = 34,
    this.growth,
  });

  final String? url;
  final String name;
  final bool deleted;
  final double size;

  /// 作者等级展示；注销时忽略（A7）。
  final GrowthBadge? growth;

  static const List<Color> _palette = [
    AppColors.mint,
    AppColors.mint500,
    AppColors.triageGreen,
    AppColors.gold,
    AppColors.coral,
  ];

  @override
  Widget build(BuildContext context) {
    final g = deleted ? null : growth;
    if (g == null) return _avatar();
    return GrowthAvatarDecoration(badge: g, diameter: size, child: _avatar());
  }

  Widget _avatar() {
    final radius = size / 2;
    if (url != null && url!.isNotEmpty) {
      return CircleAvatar(radius: radius, backgroundImage: AppImage.provider(url, thumbWidth: 120));
    }
    final trimmed = name.trim();
    // 注销作者 → 默认 person 头像（Story 3.8），不用昵称首字母。
    if (deleted || trimmed.isEmpty) {
      return CircleAvatar(
        radius: radius,
        backgroundColor: AppColors.border,
        child: Icon(Icons.person_rounded, size: size * 0.53, color: AppColors.textTertiary),
      );
    }
    final color = _palette[trimmed.codeUnits.fold<int>(0, (a, b) => a + b) % _palette.length];
    return CircleAvatar(
      radius: radius,
      backgroundColor: color,
      child: Text(trimmed.characters.first.toUpperCase(),
          style: TextStyle(
              fontSize: size * 0.41, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
    );
  }
}
