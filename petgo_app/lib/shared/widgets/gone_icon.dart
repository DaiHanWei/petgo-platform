import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 「已失效 / 不存在」组合图标（V1.4.2 UI 稿 A14、C10）：放大镜，左下角压一枚实心圆 + 白色 ×。
///
/// 稿子是两层图形，Material 的 `search_off` 只是细线小 ×，单图标页上差异显眼，故单独画。
class GoneIcon extends StatelessWidget {
  const GoneIcon({super.key, this.size = 48, this.color = AppColors.muted});

  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final badge = size * 0.4;
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(
            right: 0,
            top: 0,
            child: Icon(Icons.search_rounded, size: size * 0.92, color: color),
          ),
          Positioned(
            left: 0,
            bottom: size * 0.06,
            child: Container(
              width: badge,
              height: badge,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              child: Icon(Icons.close_rounded, size: badge * 0.7, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}
