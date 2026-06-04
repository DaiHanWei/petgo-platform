import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';

/// 条纹占位图（对应 prototype `.ph` / `Photo`）。
///
/// 135° 重复斜条纹 + 虚线描边 + 居中等宽字标签，表示「此处放真实照片」。
class StripedPhoto extends StatelessWidget {
  const StripedPhoto({
    super.key,
    this.label = 'foto',
    this.height = 160,
    this.radius = 24,
    this.width,
  });

  final String label;
  final double height;
  final double radius;
  final double? width;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width ?? double.infinity,
      height: height,
      child: CustomPaint(
        painter: _StripePainter(radius: radius),
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Text(
              label,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppColors.muted,
                fontSize: 11,
                letterSpacing: 0.4,
                fontFamily: 'monospace',
                fontFamilyFallback: ['SF Mono', 'Menlo'],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _StripePainter extends CustomPainter {
  _StripePainter({required this.radius});

  final double radius;

  static const Color _stripeA = Color(0x1A98948A); // .10（条纹带；带间露 cream2 底 ≈ .04 效果）
  static const Color _dash = Color(0x4798948A); // .28

  @override
  void paint(Canvas canvas, Size size) {
    final rrect = RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(radius));
    canvas.save();
    canvas.clipRRect(rrect);

    // 底色
    canvas.drawColor(AppColors.cream2, BlendMode.srcOver);

    // 135° 斜条纹：每 20px 一组（10px A + 10px B）
    final paintA = Paint()..color = _stripeA;
    const period = 20.0, bandA = 10.0;
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.rotate(135 * 3.1415926 / 180);
    canvas.translate(-size.width, -size.height);
    final span = size.width * 2 + size.height * 2;
    for (double x = 0; x < span; x += period) {
      canvas.drawRect(Rect.fromLTWH(x, 0, bandA, span), paintA);
    }
    canvas.restore();
    canvas.restore();

    // 虚线描边
    final dashPaint = Paint()
      ..color = _dash
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    _drawDashedRRect(canvas, rrect.deflate(0.75), dashPaint, dash: 6, gap: 5);
  }

  void _drawDashedRRect(Canvas canvas, RRect rrect, Paint paint,
      {required double dash, required double gap}) {
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
  bool shouldRepaint(covariant _StripePainter old) => old.radius != radius;
}
