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

  /// 半透明强度：产品要求「不要太明显」；素材本身已是淡紫描边+白字。
  /// 0.3 在学生卡浅底上偏显眼，0.25 三种卡面均衡（模拟器实截对比后定）。
  static const double opacity = 0.25;

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
