import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';

/// Emoji 头像（对应 prototype `Avatar`）。
///
/// 圆形柔底 + 居中 emoji + 实色描边环（默认白）。宠物头像 V1 一律用 emoji。
class EmojiAvatar extends StatelessWidget {
  const EmojiAvatar({
    super.key,
    this.emoji = '🐱',
    this.size = 48,
    this.ring = Colors.white,
    this.tone,
  });

  final String emoji;
  final double size;

  /// 外描边环色（null = 无环，仅柔阴影）。
  final Color? ring;

  /// 底色（默认薄荷柔填充）。
  final Color? tone;

  @override
  Widget build(BuildContext context) {
    final ringW = (size * 0.045).clamp(2.0, 999.0);
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tone ?? AppColors.mintTint,
        shape: BoxShape.circle,
        boxShadow: [
          if (ring != null) BoxShadow(color: ring!, spreadRadius: ringW, blurRadius: 0),
          ...AppShadows.sm,
        ],
      ),
      child: Text(emoji, style: TextStyle(fontSize: size * 0.56, height: 1.0)),
    );
  }
}
