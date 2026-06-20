import 'package:flutter/material.dart';

/// 虚线圆角矩形描边画笔（占位添加格用，避免引入 dotted_border 依赖）。
/// 原型 pcell-add：`border:2px dashed #C2B0EC`。
class DashedRRectPainter extends CustomPainter {
  DashedRRectPainter({
    required this.color,
    this.radius = 9,
    this.dash = 5,
    this.gap = 4,
    this.strokeWidth = 2,
  });

  final Color color;
  final double radius;
  final double dash;
  final double gap;
  final double strokeWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;
    final rrect = RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(radius));
    final path = Path()..addRRect(rrect);
    for (final metric in path.computeMetrics()) {
      double dist = 0;
      while (dist < metric.length) {
        final next = dist + dash;
        canvas.drawPath(metric.extractPath(dist, next.clamp(0, metric.length)), paint);
        dist = next + gap;
      }
    }
  }

  @override
  bool shouldRepaint(DashedRRectPainter old) =>
      old.color != color ||
      old.radius != radius ||
      old.dash != dash ||
      old.gap != gap ||
      old.strokeWidth != strokeWidth;
}
