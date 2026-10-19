import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import 'gift_style.dart';

/// 送出动效总时长（UI 稿 D1：1.6 秒，架构 §0.6「1–2 秒」）。
const Duration giftSendAnimationDuration = Duration(milliseconds: 1600);

/// 送出动效 D1（V1.4.0 gift Story 1.3 · AC5）：礼物图标从屏幕底部中央飞向目标 + 落点粒子 + 「×N」弹跳。
///
/// - 挂 [overlay]（根 Overlay）上的一次性 `OverlayEntry`，播完自己移除，Future 随之完成；
/// - 粒子用 **CustomPainter**（参照 `milestone_celebration.dart` `_Confetti` 的 `AnimatedBuilder + CustomPaint`
///   写法，但**一次性播放、不 repeat**），不引 Lottie（架构 §0.6「不引新依赖」）；
/// - [disableAnimations]（系统「减少动态效果」）为 true → 不飞行、不出粒子，立即完成（调用方直接出 Toast）。
/// - [targetKey] 取不到（未挂载 / 已离屏）→ 落点为屏幕水平中线、高 30% 处。
Future<void> playGiftSendAnimation({
  required OverlayState overlay,
  required String? iconUrl,
  required int quantity,
  GlobalKey? targetKey,
  bool disableAnimations = false,
}) {
  if (disableAnimations || !overlay.mounted) return Future<void>.value();
  final done = Completer<void>();
  late final OverlayEntry entry;
  entry = OverlayEntry(
    builder: (_) => GiftSendAnimation(
      iconUrl: iconUrl,
      quantity: quantity,
      targetKey: targetKey,
      onDone: () {
        if (entry.mounted) entry.remove();
        if (!done.isCompleted) done.complete();
      },
      // 根 Overlay 被拆（App 退出等）时不再 remove，只放行等待方。
      onDisposedEarly: () {
        if (!done.isCompleted) done.complete();
      },
    ),
  );
  overlay.insert(entry);
  return done.future;
}

/// 动效主体（公开仅为测试可按类型查找）。
class GiftSendAnimation extends StatefulWidget {
  const GiftSendAnimation({
    super.key,
    required this.iconUrl,
    required this.quantity,
    required this.onDone,
    this.onDisposedEarly,
    this.targetKey,
  });

  final String? iconUrl;
  final int quantity;
  final GlobalKey? targetKey;
  final VoidCallback onDone;

  /// 播完之前就被拆掉（未走到 [onDone]）时回调。
  final VoidCallback? onDisposedEarly;

  @override
  State<GiftSendAnimation> createState() => _GiftSendAnimationState();
}

class _GiftSendAnimationState extends State<GiftSendAnimation> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: giftSendAnimationDuration);

  /// 飞行缓动 cubic-bezier(.32,.72,0,1)。
  static const Curve _flight = Cubic(0.32, 0.72, 0, 1);

  bool _finished = false;

  @override
  void initState() {
    super.initState();
    _c.forward().then((_) {
      _finished = true;
      widget.onDone();
    });
  }

  @override
  void dispose() {
    if (!_finished) widget.onDisposedEarly?.call();
    _c.dispose();
    super.dispose();
  }

  /// 目标中心（全局坐标）；取不到 → 屏幕水平中线、高 30%。
  Offset _target(Size screen) {
    final ctx = widget.targetKey?.currentContext;
    final box = ctx?.findRenderObject();
    if (box is RenderBox && box.attached && box.hasSize) {
      final c = box.localToGlobal(box.size.center(Offset.zero));
      if (c.dx >= 0 && c.dy >= 0 && c.dx <= screen.width && c.dy <= screen.height) return c;
    }
    return Offset(screen.width / 2, screen.height * 0.3);
  }

  /// 毫秒区间 [from, to] 内的 0..1 进度。
  double _seg(double ms, double from, double to) => ((ms - from) / (to - from)).clamp(0.0, 1.0);

  @override
  Widget build(BuildContext context) {
    final screen = MediaQuery.sizeOf(context);
    final bottomPad = MediaQuery.paddingOf(context).bottom;
    final start = Offset(screen.width / 2, screen.height - bottomPad - 96);
    final end = _target(screen);
    // 二次贝塞尔控制点：顶点高于起终点连线 80（控制点在中点上方 160，曲线顶点即上方 80）。
    final mid = Offset((start.dx + end.dx) / 2, (start.dy + end.dy) / 2);
    final control = Offset(mid.dx, mid.dy - 160);

    return IgnorePointer(
      child: AnimatedBuilder(
        animation: _c,
        builder: (context, _) {
          final ms = _c.value * giftSendAnimationDuration.inMilliseconds;
          // 0–200ms 出现并 0.6 → 1；200–900ms 沿弧线飞；1300–1600ms 淡出。
          final appear = _seg(ms, 0, 200);
          final fly = _flight.transform(_seg(ms, 200, 900));
          final fade = 1 - _seg(ms, 1300, 1600);
          final t = fly;
          final pos = Offset(
            (1 - t) * (1 - t) * start.dx + 2 * (1 - t) * t * control.dx + t * t * end.dx,
            (1 - t) * (1 - t) * start.dy + 2 * (1 - t) * t * control.dy + t * t * end.dy,
          );
          final scale = 0.6 + 0.4 * appear;
          final burst = _seg(ms, 900, 1300);
          // ×N：900–1300ms 弹性 1 → 1.25 → 1。
          final pop = 1 + 0.25 * math.sin(math.pi * burst);
          return Stack(
            children: [
              if (ms >= 900 && ms <= 1300)
                Positioned.fill(
                  child: CustomPaint(
                    key: const ValueKey('giftSendParticles'),
                    painter: _GiftBurstPainter(center: end, progress: burst),
                  ),
                ),
              Positioned(
                left: pos.dx - 24,
                top: pos.dy - 24,
                child: Opacity(
                  opacity: fade,
                  child: Transform.scale(
                    scale: scale,
                    child: Container(
                      key: const ValueKey('giftSendFlyingIcon'),
                      width: 48,
                      height: 48,
                      alignment: Alignment.center,
                      decoration: const BoxDecoration(
                        color: Colors.white,
                        shape: BoxShape.circle,
                        boxShadow: [
                          BoxShadow(color: Color(0x33845EC9), offset: Offset(0, 4), blurRadius: 12),
                        ],
                      ),
                      child: giftIcon(widget.iconUrl, size: 32),
                    ),
                  ),
                ),
              ),
              if (widget.quantity >= 2 && ms >= 900)
                Positioned(
                  left: end.dx + 18,
                  top: end.dy - 30,
                  child: Opacity(
                    opacity: fade,
                    child: Transform.scale(
                      scale: pop,
                      child: Text('×${widget.quantity}',
                          key: const ValueKey('giftSendQuantity'),
                          style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w800,
                              color: AppColors.mint,
                              decoration: TextDecoration.none)),
                    ),
                  ),
                ),
            ],
          );
        },
      ),
    );
  }
}

/// 落点粒子：8 颗、三色（#845EC9 / #C2B0EC / #F6A609），扩散半径 36、400ms 淡出。
class _GiftBurstPainter extends CustomPainter {
  _GiftBurstPainter({required this.center, required this.progress});

  final Offset center;
  final double progress;

  static const List<Color> _colors = [AppColors.mint, AppColors.dashedViolet, AppColors.gold];

  @override
  void paint(Canvas canvas, Size size) {
    final eased = Curves.easeOut.transform(progress);
    final alpha = (1 - progress).clamp(0.0, 1.0);
    final paint = Paint();
    for (var i = 0; i < 8; i++) {
      final angle = i * math.pi / 4;
      final p = center + Offset(math.cos(angle), math.sin(angle)) * (36 * eased);
      paint.color = _colors[i % _colors.length].withValues(alpha: alpha);
      canvas.drawCircle(p, 3.5, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _GiftBurstPainter oldDelegate) =>
      oldDelegate.progress != progress || oldDelegate.center != center;
}
