import 'package:flutter/material.dart';

/// 证件卡预览防截图水印层（bug 20260728-383）。
///
/// 目的：预览阶段（无论是否已付费解锁 HD）卡面恒盖半透明 TailTopia 平铺水印，
/// 防止用户不付款直接截图裁出干净卡片。
///
/// 用法约束：作为 [Stack] 兄弟盖在卡面**外层**——尤其详情页必须放在
/// `RepaintBoundary` **之外**：HD 导出走 `boundary.toImage` 只截 boundary 子树，
/// 水印不在其中 → 付费导出/分享的 PNG 天然无水印，预览/系统截图恒带水印。
/// **不要**把本层挪进 RepaintBoundary（会污染付费导出图）。
class IdCardWatermark extends StatelessWidget {
  const IdCardWatermark({super.key, required this.canvas, required this.canvasRadius});

  /// 卡面画布尺寸（如 [kIdCardCanvas]），用于把画布圆角换算到 widget 尺度。
  final Size canvas;

  /// 卡面圆角（画布坐标系，如 KTP/学生卡 80、护照 44）。
  final double canvasRadius;

  /// 半透明强度。
  ///
  /// 🔴 **2026-08-26 产品调整：0.25 → 0.45。** 免费分享出去的卡（Story 18.2 AC2 截的是
  /// 含水印那层）在实机上「看着和高清也没区别」—— 水印淡到起不了区分作用，
  /// 而这层水印正是 HD 付费点唯一的护栏：分不出来，就没人有理由付费。
  ///
  /// ⚠️ 提高透明度的收益有上限：贴图本身是**淡紫（202,187,255）+ 白字**、覆盖率仅 27.6%，
  /// 而 KTP 卡面是浅蓝底 —— 浅色画在浅色上，再怎么加 alpha 对比度也有限。
  /// 若 0.45 仍不够，下一步应当**换深色贴图**，而不是继续往上堆这个数
  /// （堆到接近 1.0 会让卡面本身看不清，等于把免费分享做废）。
  ///
  /// 历史：0.3 在学生卡浅底上偏显眼、0.25 三种卡面均衡（模拟器实截对比后定），
  /// 但那次比较只看了「预览屏好不好看」，没看「分享出去还分不分得出免费版」。
  static const double opacity = 0.45;

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: IgnorePointer(
        child: LayoutBuilder(
          builder: (context, constraints) => ClipRRect(
            // 圆角按实际渲染宽度等比缩放，与 FittedBox 缩放后的卡面圆角贴合。
            borderRadius:
                BorderRadius.circular(canvasRadius * constraints.maxWidth / canvas.width),
            child: Opacity(
              opacity: opacity,
              child: Image.asset(
                'assets/ktp/watermark_tile.png',
                fit: BoxFit.cover,
                filterQuality: FilterQuality.medium,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
