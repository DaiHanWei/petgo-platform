import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 驱动 [FeedLikeBurst] 播一次（V1.5.0 FR-211）。持有者（Feed 卡图片区）双击时调 [play]；
/// `FeedImage` 只负责挂层，不感知点赞逻辑。
class FeedLikeBurstController extends ChangeNotifier {
  bool _stopRequested = false;

  /// 播一次（播放中再调 = 从 0.27 处重播）。
  void play() {
    _stopRequested = false;
    notifyListeners();
  }

  /// 立即停掉并清空（同位置的卡被复用给另一条帖子时调用，不得把上一条的红心带过去）。
  void stop() {
    _stopRequested = true;
    notifyListeners();
  }
}

/// Feed 双击点赞的大红心动效层（V1.5.0 FR-211，架构 F-7 / F-8；数值照 UI 稿 D1 / D4）。
///
/// 挂在 `FeedImage` 的 `Stack` 最上层（三个角位之上），整层 [IgnorePointer]（不拦截点击）。只在播放时有内容，
/// 播完即移除，不常驻、不占角位（AD-8 Rule 6 增补）。每张卡各自一个实例，互不干扰。
///
/// - 大心 75dp，中心在可见图片区 `(宽 × 0.6, 高 × 0.5)`；右上小心 34dp 晚 70ms、歪 14°；5 颗圆点 80ms 起 450ms 内散开淡出。
/// - 播放中再 [FeedLikeBurstController.play] → 从「放大到 1.12」那一刻（0.27）重播，屏上始终只有一组心。
/// - 减少动态效果：原尺寸只淡入淡出约 400ms，无缩放、无上飘、无小心与圆点。
/// - Flutter 原生动画，不用 Rive。
class FeedLikeBurst extends StatefulWidget {
  const FeedLikeBurst({super.key, required this.controller});

  final FeedLikeBurstController controller;

  /// 完整时长（UI 稿 D1）。
  static const Duration duration = Duration(milliseconds: 670);

  /// 减少动态效果时的时长。
  static const Duration reducedDuration = Duration(milliseconds: 400);

  /// 重播起点：0.27 × 670 ≈ 181ms，大心刚放大到 1.12 的那一刻。
  static const double replayFrom = 0.27;

  static const double bigSize = 75;
  static const double smallSize = 34;
  static const double dotDiameter = 6;

  /// 心中心在可见图片区的相对位置（UI 稿 D3）。
  static const double centerX = 0.6;
  static const double centerY = 0.5;

  /// 小心中心相对大心中心的偏移（右 36、上 32）与旋转。
  static const Offset smallOffset = Offset(36, -32);
  static const double smallRotationDeg = 14;

  /// 5 颗圆点的终点位移（UI 稿 DOT_SPEC，dp）与颜色。
  static const List<(Offset, Color)> dots = [
    (Offset(-53, -12), AppColors.coral),
    (Offset(-32, -53), AppColors.gold),
    (Offset(54, 10), AppColors.dashedViolet),
    (Offset(41, 43), AppColors.coral),
    (Offset(-43, 37), AppColors.gold),
  ];

  @override
  State<FeedLikeBurst> createState() => _FeedLikeBurstState();
}

const Cubic _easeOutQuint = Cubic(0.23, 1, 0.32, 1);
const Cubic _easeInOutQuart = Cubic(0.77, 0, 0.175, 1);

/// 一颗心 600ms 的节奏（大心 0–600ms；小心 70–670ms，同节奏）。权重单位 = ms。
TweenSequence<double> _heartScale() => TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 0.3, end: 1.12).chain(CurveTween(curve: _easeOutQuint)), weight: 180),
      TweenSequenceItem(tween: Tween(begin: 1.12, end: 0.96).chain(CurveTween(curve: _easeInOutQuart)), weight: 75),
      TweenSequenceItem(tween: Tween(begin: 0.96, end: 1.0).chain(CurveTween(curve: _easeInOutQuart)), weight: 75),
      TweenSequenceItem(tween: ConstantTween(1.0), weight: 100),
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.92).chain(CurveTween(curve: _easeOutQuint)), weight: 170),
    ]);

TweenSequence<double> _heartOpacity() => TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 0.0, end: 1.0).chain(CurveTween(curve: _easeOutQuint)), weight: 180),
      TweenSequenceItem(tween: ConstantTween(1.0), weight: 250),
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.0).chain(CurveTween(curve: _easeOutQuint)), weight: 170),
    ]);

TweenSequence<double> _heartRise(double rise) => TweenSequence<double>([
      TweenSequenceItem(tween: ConstantTween(0.0), weight: 430),
      TweenSequenceItem(tween: Tween(begin: 0.0, end: -rise).chain(CurveTween(curve: _easeOutQuint)), weight: 170),
    ]);

class _FeedLikeBurstState extends State<FeedLikeBurst> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: FeedLikeBurst.duration)
    ..addStatusListener((s) {
      if (s == AnimationStatus.completed && mounted) setState(() => _visible = false);
    });

  bool _visible = false;
  bool _reduced = false;

  // 大心：0–600ms（之后 70ms 保持淡出状态）。
  static const Interval _bigWindow = Interval(0, 600 / 670);
  // 小心：晚 70ms，到 670ms。
  static const Interval _smallWindow = Interval(70 / 670, 1.0);
  // 圆点：80ms 起 450ms。位置走 easeOut，透明度线性淡出（同送礼粒子 painter 的做法）。
  static const Interval _dotWindow = Interval(80 / 670, 530 / 670, curve: _easeOutQuint);
  static const Interval _dotFade = Interval(80 / 670, 530 / 670);

  // 曲线与数值全卡共用：静态一份，不随每张卡分配。
  static final TweenSequence<double> _scale = _heartScale();
  static final TweenSequence<double> _opacity = _heartOpacity();
  static final TweenSequence<double> _bigRise = _heartRise(12);
  static final TweenSequence<double> _smallRise = _heartRise(22);
  static final TweenSequence<double> _reducedOpacity = TweenSequence<double>([
    TweenSequenceItem(tween: Tween(begin: 0.0, end: 1.0), weight: 35),
    TweenSequenceItem(tween: ConstantTween(1.0), weight: 30),
    TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.0), weight: 35),
  ]);

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_play);
  }

  @override
  void didUpdateWidget(covariant FeedLikeBurst oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_play);
      widget.controller.addListener(_play);
    }
  }

  void _play() {
    if (!mounted) return;
    if (widget.controller._stopRequested) {
      _c.stop();
      if (_visible) setState(() => _visible = false);
      return;
    }
    final reduced = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    final replay = _c.isAnimating && reduced == _reduced;
    setState(() {
      _visible = true;
      _reduced = reduced;
    });
    _c.duration = reduced ? FeedLikeBurst.reducedDuration : FeedLikeBurst.duration;
    _c.forward(from: replay && !reduced ? FeedLikeBurst.replayFrom : 0);
  }

  /// 离开屏幕（滑出 / 销毁）：停住、清掉可见态，不残留（下次 play 从头播）。
  /// ⚠️ 不在这里 `reset()`：deactivate 发生在 build 阶段，reset 会通知 AnimatedBuilder 重建 → 「build 中 setState」。
  @override
  void deactivate() {
    _c.stop();
    _visible = false;
    super.deactivate();
  }

  @override
  void dispose() {
    widget.controller.removeListener(_play);
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: !_visible
          ? const SizedBox.shrink()
          : LayoutBuilder(builder: (context, box) {
              final center = Offset(box.maxWidth * FeedLikeBurst.centerX, box.maxHeight * FeedLikeBurst.centerY);
              return AnimatedBuilder(
                animation: _c,
                builder: (context, _) => Stack(
                  clipBehavior: Clip.none,
                  children: _reduced ? [_reducedHeart(center)] : _fullBurst(center),
                ),
              );
            }),
    );
  }

  Widget _reducedHeart(Offset center) => Positioned(
        key: const ValueKey('feedLikeBurstHeart'),
        left: center.dx - FeedLikeBurst.bigSize / 2,
        top: center.dy - FeedLikeBurst.bigSize / 2,
        width: FeedLikeBurst.bigSize,
        height: FeedLikeBurst.bigSize,
        child: Opacity(
          opacity: _reducedOpacity.transform(_c.value).clamp(0.0, 1.0),
          child: const CustomPaint(painter: FeedLikeHeartPainter()),
        ),
      );

  List<Widget> _fullBurst(Offset center) {
    final v = _c.value;
    final big = _bigWindow.transform(v);
    final small = _smallWindow.transform(v);
    final dot = _dotWindow.transform(v);
    final dotFade = _dotFade.transform(v);
    final dotsAlive = v >= 80 / 670 && v < 530 / 670;
    return [
      _heart(
        key: const ValueKey('feedLikeBurstHeart'),
        center: center,
        size: FeedLikeBurst.bigSize,
        scale: _scale.transform(big),
        opacity: _opacity.transform(big),
        rise: _bigRise.transform(big),
      ),
      if (v >= 70 / 670)
        _heart(
          key: const ValueKey('feedLikeBurstSmallHeart'),
          center: center + FeedLikeBurst.smallOffset,
          size: FeedLikeBurst.smallSize,
          scale: _scale.transform(small),
          opacity: _opacity.transform(small),
          rise: _smallRise.transform(small),
          rotationDeg: FeedLikeBurst.smallRotationDeg,
        ),
      if (dotsAlive)
        Positioned.fill(
          key: const ValueKey('feedLikeBurstDots'),
          child: CustomPaint(painter: _DotsPainter(center: center, progress: dot, fade: dotFade)),
        ),
    ];
  }

  Widget _heart({
    required Key key,
    required Offset center,
    required double size,
    required double scale,
    required double opacity,
    required double rise,
    double rotationDeg = 0,
  }) =>
      Positioned(
        key: key,
        left: center.dx - size / 2,
        top: center.dy - size / 2 + rise,
        width: size,
        height: size,
        child: Opacity(
          opacity: opacity.clamp(0.0, 1.0),
          child: Transform.rotate(
            angle: rotationDeg * math.pi / 180,
            child: Transform.scale(scale: scale, child: const CustomPaint(painter: FeedLikeHeartPainter())),
          ),
        ),
      );
}

/// UI 稿 `HEART_D` 心形（viewBox 0 0 24 24，按绘制尺寸缩放）：对角渐变填充 + 同色 1.4 圆角描边 + 左上高光 + 柔光阴影。
class FeedLikeHeartPainter extends CustomPainter {
  const FeedLikeHeartPainter();

  /// `M12 20.6C6.6 16.9 3 13.6 3 9.4 3 6.5 5.2 4.4 7.9 4.4c1.7 0 3.2.8 4.1 2.2.9-1.4 2.4-2.2 4.1-2.2
  /// 2.7 0 4.9 2.1 4.9 5 0 4.2-3.6 7.5-9 11.2z`（相对坐标已换算成绝对坐标）。
  static Path heartPath() => Path()
    ..moveTo(12, 20.6)
    ..cubicTo(6.6, 16.9, 3, 13.6, 3, 9.4)
    ..cubicTo(3, 6.5, 5.2, 4.4, 7.9, 4.4)
    ..cubicTo(9.6, 4.4, 11.1, 5.2, 12, 6.6)
    ..cubicTo(12.9, 5.2, 14.4, 4.4, 16.1, 4.4)
    ..cubicTo(18.8, 4.4, 21, 6.5, 21, 9.4)
    ..cubicTo(21, 13.6, 17.4, 16.9, 12, 20.6)
    ..close();

  @override
  void paint(Canvas canvas, Size size) {
    final k = size.width / 24;
    final path = heartPath();
    // 同色柔光（offset 0/6、blur 14、32%，单位 dp）：在缩放后的坐标里画，偏移与模糊按比例换算。
    canvas.save();
    canvas.translate(0, 6);
    canvas.scale(k);
    canvas.drawPath(
        path,
        Paint()
          ..color = AppColors.likeHeart.withValues(alpha: 0.32)
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, Shadow.convertRadiusToSigma(14) / k));
    canvas.restore();
    canvas.save();
    canvas.scale(k);
    final gradient = const LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [AppColors.likeHeartLight, AppColors.likeHeart, AppColors.likeHeartDeep],
      stops: [0, 0.55, 1],
    ).createShader(path.getBounds()); // 同 SVG 默认 objectBoundingBox：渐变铺在心形自身的外框上
    canvas.drawPath(path, Paint()..shader = gradient);
    canvas.drawPath(
        path,
        Paint()
          ..shader = gradient
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.4
          ..strokeJoin = StrokeJoin.round);
    // 左上高光：椭圆（7.4, 8.3）rx 1.9 ry 1.15 旋 -38°，65%；小白点（9.6, 6.5）r 0.55，55%。
    canvas.save();
    canvas.translate(7.4, 8.3);
    canvas.rotate(-38 * math.pi / 180);
    canvas.drawOval(const Rect.fromLTRB(-1.9, -1.15, 1.9, 1.15),
        Paint()..color = AppColors.onAccent.withValues(alpha: 0.65));
    canvas.restore();
    canvas.drawCircle(const Offset(9.6, 6.5), 0.55, Paint()..color = AppColors.onAccent.withValues(alpha: 0.55));
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant FeedLikeHeartPainter oldDelegate) => false;
}

/// 5 颗圆点从大心中心散开淡出（画法参照送礼落点粒子 painter）。
class _DotsPainter extends CustomPainter {
  _DotsPainter({required this.center, required this.progress, required this.fade});

  final Offset center;

  /// 散开进度（已过 easeOut 曲线）。
  final double progress;

  /// 淡出进度（线性）。
  final double fade;

  @override
  void paint(Canvas canvas, Size size) {
    final alpha = (1 - fade).clamp(0.0, 1.0);
    for (final (offset, color) in FeedLikeBurst.dots) {
      canvas.drawCircle(center + offset * progress, FeedLikeBurst.dotDiameter / 2,
          Paint()..color = color.withValues(alpha: alpha));
    }
  }

  @override
  bool shouldRepaint(covariant _DotsPainter old) =>
      old.progress != progress || old.fade != fade || old.center != center;
}
