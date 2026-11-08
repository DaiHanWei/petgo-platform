import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 证件卡预览防截图水印层（bug 20260728-383；V1.4.2「KTP 高清防绕过」加强）。
///
/// 目的：预览阶段卡面恒盖水印，防止用户不付款直接截图 / 免费分享后去水印拿到可用的卡。
///
/// 用法约束：作为 [Stack] 兄弟盖在卡面**外层**——尤其详情页必须放在
/// `RepaintBoundary` **之外**：HD 导出走 `boundary.toImage` 只截 boundary 子树，
/// 水印不在其中 → 付费导出的 PNG 天然无水印，预览/系统截图/免费分享恒带水印。
/// **不要**把本层挪进 RepaintBoundary（会污染付费导出图）。
///
/// ## V1.4.2 改版（需求「KTP 高清防绕过」§2）
/// 旧版是一张淡紫 + 白字贴图（覆盖约 27.6%、透明度 0.45），压在浅蓝卡面上对比度低，修图 App 一键可去。
/// 改为**代码绘制**：
/// - **深色**：品牌紫 [AppColors.mint]（#845EC9），与浅色卡面形成明显对比；
/// - **斜向平铺**（[angleDeg]）：每行「TailTopia」字样错位排列，避免规则网格被批量擦除；
/// - **覆盖率 ≥ 50%**：每行文字下垫一条淡色斜条带（[bandOpacity]），条带占画面约 [bandCoverage]，
///   整张卡（含宠物头像、名字、证件号）都在覆盖范围内；
/// - 按**画布坐标**绘制、随实际宽度等比缩放 —— 预览、免费分享导出在任何尺寸下花纹一致。
///
/// ⚠️ 数值待 UI 定稿（需求 §2.3：UI 提供新素材后可整体替换本绘制）。原则：卡面文字仍清晰可读，
/// 但截图明显不可直接使用。
class IdCardWatermark extends StatelessWidget {
  const IdCardWatermark({super.key, required this.canvas, required this.canvasRadius});

  /// 卡面画布尺寸（如 [kIdCardCanvas]），用于把画布坐标换算到 widget 尺度。
  final Size canvas;

  /// 卡面圆角（画布坐标系，如 KTP/学生卡 80、护照 44）。
  final double canvasRadius;

  /// 水印文字的不透明度（品牌紫）。
  ///
  /// 🔴 **这层水印是 HD 付费点唯一的护栏**（bug 20260826）：淡到与 HD 无异 ⇒ 没人有理由付费；
  /// 但堆到接近不透明会让卡面本身看不清，免费分享就做废了。测试按名钉住 0.4~0.6。
  static const double opacity = 0.5;

  /// 条带的不透明度（压在卡面上改变底色，修图 App 难以干净还原；低到不影响读字）。
  static const double bandOpacity = 0.14;

  /// 条带占行距的比例 —— 即条带在画面上的覆盖率（需求：≥ 50%）。
  static const double bandCoverage = 0.56;

  /// 斜向角度（度，负 = 左低右高）。
  static const double angleDeg = -25;

  /// 字号与行距（画布坐标）。KTP 画布 1988 宽 ⇒ 每行约 10 个字样、约 11 行。
  static const double fontSize = 76;
  static const double lineGap = 150;

  static const String text = 'TailTopia';

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: IgnorePointer(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final scale = constraints.maxWidth / canvas.width;
            return ClipRRect(
              // 圆角按实际渲染宽度等比缩放，与 FittedBox 缩放后的卡面圆角贴合。
              borderRadius: BorderRadius.circular(canvasRadius * scale),
              child: CustomPaint(
                size: Size(constraints.maxWidth, constraints.maxHeight),
                painter: IdCardWatermarkPainter(scale: scale),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// 斜向平铺水印的绘制（画布坐标 × [scale]）。
@visibleForTesting
class IdCardWatermarkPainter extends CustomPainter {
  IdCardWatermarkPainter({required this.scale});

  final double scale;

  @override
  void paint(Canvas canvas, Size size) {
    final double gap = IdCardWatermark.lineGap * scale;
    final double band = gap * IdCardWatermark.bandCoverage;
    final textPainter = TextPainter(
      text: TextSpan(
        text: IdCardWatermark.text,
        style: TextStyle(
          color: AppColors.mint.withValues(alpha: IdCardWatermark.opacity),
          fontSize: IdCardWatermark.fontSize * scale,
          fontWeight: FontWeight.w800,
          letterSpacing: 2 * scale,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final double step = textPainter.width + IdCardWatermark.fontSize * scale * 0.9;
    final bandPaint = Paint()..color = AppColors.mint.withValues(alpha: IdCardWatermark.bandOpacity);

    // 绕中心旋转后铺满：铺设范围取对角线长度的正方形，确保旋转后四角都被覆盖。
    final double diag = math.sqrt(size.width * size.width + size.height * size.height);
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(IdCardWatermark.angleDeg * math.pi / 180);
    int row = 0;
    for (double y = -diag / 2; y < diag / 2; y += gap, row++) {
      canvas.drawRect(Rect.fromLTWH(-diag / 2, y, diag, band), bandPaint);
      // 隔行错开半个字样，避免竖向对齐成规则网格。
      final double offset = (row.isOdd ? step / 2 : 0) - diag / 2;
      final double ty = y + (band - textPainter.height) / 2;
      for (double x = offset; x < diag / 2; x += step) {
        textPainter.paint(canvas, Offset(x, ty));
      }
    }
    canvas.restore();
    textPainter.dispose();
  }

  @override
  bool shouldRepaint(IdCardWatermarkPainter oldDelegate) => oldDelegate.scale != scale;
}
