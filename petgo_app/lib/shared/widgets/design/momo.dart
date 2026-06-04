import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';

/// Momo —— PetGo 吉祥物（占位 IP）。
///
/// 纯几何图形拼搭的圆润薄荷绿小猫，对应 prototype `components.jsx` 的 `Momo`。
/// - [size] 像素边长。
/// - [float] 开启上下漂浮（welcome / 空态）。
/// - [happy] true 时眼睛周期眨动。
class Momo extends StatefulWidget {
  const Momo({super.key, this.size = 96, this.float = false, this.happy = true});

  final double size;
  final bool float;
  final bool happy;

  @override
  State<Momo> createState() => _MomoState();
}

class _MomoState extends State<Momo> with TickerProviderStateMixin {
  late final AnimationController _float = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 3400),
  );
  late final AnimationController _blink = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 4200),
  );

  @override
  void initState() {
    super.initState();
    if (widget.float) _float.repeat(reverse: true);
    if (widget.happy) _blink.repeat();
  }

  @override
  void dispose() {
    _float.dispose();
    _blink.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.size;
    Widget face = SizedBox(width: s, height: s, child: _MomoFace(s: s, blink: _blink));
    if (widget.float) {
      face = AnimatedBuilder(
        animation: _float,
        builder: (_, child) {
          // 0→1→0：translateY 0 → -6 → 0（CurvedAnimation easeInOut）
          final t = Curves.easeInOut.transform(_float.value);
          return Transform.translate(offset: Offset(0, -6 * t), child: child);
        },
        child: face,
      );
    }
    return face;
  }
}

class _MomoFace extends StatelessWidget {
  const _MomoFace({required this.s, required this.blink});

  final double s;
  final Animation<double> blink;

  // 圆角 '60% 60% 50% 50%'（相对自身尺寸）
  BorderRadius _earRadius(double w, double h) => BorderRadius.only(
        topLeft: Radius.elliptical(w * 0.6, h * 0.6),
        topRight: Radius.elliptical(w * 0.6, h * 0.6),
        bottomLeft: Radius.elliptical(w * 0.5, h * 0.5),
        bottomRight: Radius.elliptical(w * 0.5, h * 0.5),
      );

  Widget _ear(bool right) {
    final w = s * 0.28;
    final cx = s * (right ? 0.84 : 0.16);
    return Positioned(
      left: cx - w / 2,
      top: s * 0.02,
      child: Transform.rotate(
        angle: (right ? 18 : -18) * 3.1415926 / 180,
        child: Container(
          width: w,
          height: w,
          decoration: BoxDecoration(color: AppColors.momoBody, borderRadius: _earRadius(w, w)),
          child: Padding(
            padding: EdgeInsets.symmetric(horizontal: w * 0.30, vertical: w * 0.32),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppColors.momoEarInner,
                borderRadius: _earRadius(w * 0.4, w * 0.36),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _eye(bool right) {
    final w = s * 0.10, h = s * 0.13;
    final cx = s * (right ? 0.64 : 0.36), cy = s * 0.46;
    return Positioned(
      left: cx - w / 2,
      top: cy - h / 2,
      child: AnimatedBuilder(
        animation: blink,
        builder: (_, _) {
          // 95% 处快速 scaleY→.1 模拟眨眼
          final v = blink.value;
          final sy = (v > 0.94 && v < 0.97) ? 0.12 : 1.0;
          return Transform(
            alignment: Alignment.center,
            transform: Matrix4.diagonal3Values(1, sy, 1),
            child: Container(
              width: w,
              height: h,
              decoration: const BoxDecoration(color: AppColors.momoEye, shape: BoxShape.circle),
              child: Align(
                alignment: const Alignment(-0.45, -0.45),
                child: FractionallySizedBox(
                  widthFactor: 0.38,
                  heightFactor: 0.38,
                  child: DecoratedBox(
                    decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _blush(bool right) {
    final w = s * 0.13, h = s * 0.08;
    final cx = s * (right ? 0.73 : 0.27), cy = s * 0.56;
    return Positioned(
      left: cx - w / 2,
      top: cy - h / 2,
      child: Container(
        width: w,
        height: h,
        decoration: BoxDecoration(
          color: AppColors.momoBlush,
          borderRadius: BorderRadius.all(Radius.elliptical(w / 2, h / 2)),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final faceW = s, faceH = s - s * 0.12 - s * 0.04; // top .12, bottom .04
    return Stack(
      clipBehavior: Clip.none,
      children: [
        _ear(false),
        _ear(true),
        // face
        Positioned(
          top: s * 0.12,
          left: 0,
          width: faceW,
          height: faceH,
          child: Container(
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [AppColors.momoBodyLight, AppColors.momoBody],
              ),
              borderRadius: BorderRadius.all(Radius.elliptical(faceW / 2, faceH / 2)),
            ),
          ),
        ),
        _eye(false),
        _eye(true),
        _blush(false),
        _blush(true),
        // nose
        Positioned(
          top: s * 0.56,
          left: s * 0.5 - s * 0.025,
          child: Container(
            width: s * 0.05,
            height: s * 0.04,
            decoration: const BoxDecoration(color: AppColors.momoNose, shape: BoxShape.circle),
          ),
        ),
        // smile
        Positioned(
          top: s * 0.60,
          left: s * 0.5 - s * 0.09,
          child: CustomPaint(
            size: Size(s * 0.18, s * 0.10),
            painter: _SmilePainter(stroke: (s * 0.022).clamp(2.0, 999.0)),
          ),
        ),
      ],
    );
  }
}

class _SmilePainter extends CustomPainter {
  _SmilePainter({required this.stroke});

  final double stroke;

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = AppColors.momoEye
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round;
    // 下半椭圆弧（嘴角微笑）
    final rect = Rect.fromLTWH(0, -size.height, size.width, size.height * 2);
    canvas.drawArc(rect, 0.35, 3.1415926 - 0.7, false, p);
  }

  @override
  bool shouldRepaint(covariant _SmilePainter old) => old.stroke != stroke;
}
