import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../features/growth/domain/growth_badge.dart';
import '../../features/growth/domain/growth_models.dart';

/// 头像直径 ≥ 该值显示数字角标，否则只显示段位色圆点（UI 稿 A1：Feed 32 / 详情 36 / 主页 84 → 数字；评论 30 / 回复 24 → 圆点）。
const double kGrowthBadgeNumberMinDiameter = 32;

/// 头像等级角标 + 头像框（V1.4.0 ① Story 3.2 · UX-DR1 / A1 · 架构 5.1 / 5.6）。三个头像组件与主页 / 关注条只包这一层。
///
/// - [badge] 为 null → 原样返回 [child]（注销 / 停用 / 字段缺失，A7）。
/// - 框画在头像**外面**（占位 = [diameter] + 2 × 框厚），外细环 / 柔光外溢绘制、不占布局；角标相对「头像 + 框」右下，压在最上层。
/// - 角标不吃点击（`IgnorePointer`）、不进读屏（`ExcludeSemantics`）：点角标 = 点头像。
/// - 动效框 8 秒一圈；`MediaQuery.disableAnimations` 为 true 时静止（不建 controller）。颜色只取 `AppColors`。
class GrowthAvatarDecoration extends StatelessWidget {
  const GrowthAvatarDecoration({
    super.key,
    required this.child,
    required this.badge,
    required this.diameter,
    this.showLevelBadge = true,
  });

  final Widget child;
  final GrowthBadge? badge;

  /// 头像本体直径（不含框）。
  final double diameter;

  /// false：只戴框、不放角标（「我的」页 A6）。
  final bool showLevelBadge;

  /// 框厚（包装层 padding）：基础 3、升级 / 动效 4。
  static double frameThickness(GrowthFrame? frame) => switch (frame) {
        null => 0,
        GrowthFrame.basic => 3,
        GrowthFrame.upgraded || GrowthFrame.animated => 4,
      };

  /// 含框的布局外径。
  static double outerDiameter(double diameter, GrowthFrame? frame) => diameter + 2 * frameThickness(frame);

  @override
  Widget build(BuildContext context) {
    final b = badge;
    if (b == null) return child;
    final frame = b.frame;
    Widget core = frame == null
        ? child
        : _FramedAvatar(frame: frame, diameter: diameter, thickness: frameThickness(frame), child: child);
    if (!showLevelBadge) return core;
    final number = diameter >= kGrowthBadgeNumberMinDiameter;
    final offset = number ? -3.0 : -1.0;
    return Stack(
      clipBehavior: Clip.none,
      children: [
        core,
        Positioned(
          right: offset,
          bottom: offset,
          child: IgnorePointer(
            child: ExcludeSemantics(
              child: number ? _LevelNumber(badge: b, diameter: diameter) : _LevelDot(tier: b.tier, diameter: diameter),
            ),
          ),
        ),
      ],
    );
  }
}

/// 段位底色：Bintang 渐变，其余单色。
BoxDecoration _tierFill(GrowthTier tier, {required double borderWidth, bool shadow = true}) {
  const shadows = [BoxShadow(color: AppColors.growthBadgeShadow, blurRadius: 3, offset: Offset(0, 1))];
  final border = Border.all(color: Colors.white, width: borderWidth);
  if (tier == GrowthTier.star) {
    return BoxDecoration(
      borderRadius: BorderRadius.circular(999),
      border: border,
      boxShadow: shadow ? shadows : null,
      gradient: const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [AppColors.tierBintangStart, AppColors.tierBintangEnd],
      ),
    );
  }
  final color = switch (tier) {
    GrowthTier.buddy => AppColors.tierSahabat,
    GrowthTier.guardian => AppColors.tierPenjaga,
    _ => AppColors.tierPemula,
  };
  return BoxDecoration(
    borderRadius: BorderRadius.circular(999),
    border: border,
    boxShadow: shadow ? shadows : null,
    color: color,
  );
}

class _LevelNumber extends StatelessWidget {
  const _LevelNumber({required this.badge, required this.diameter});

  final GrowthBadge badge;
  final double diameter;

  @override
  Widget build(BuildContext context) {
    // A1 尺寸表：≥ 100 → 34 / 15；84–99 → 26 / 12；32–83 → 17 / 9.5。
    final (double height, double fontSize) = diameter >= 100
        ? (34, 15)
        : diameter >= 84
            ? (26, 12)
            : (17, 9.5);
    return Container(
      key: const ValueKey('growthLevelBadge'),
      height: height,
      constraints: BoxConstraints(minWidth: height),
      padding: EdgeInsets.symmetric(horizontal: badge.level >= 10 ? 3 : 0),
      alignment: Alignment.center,
      decoration: _tierFill(badge.tier, borderWidth: 2),
      child: Text(
        '${badge.level}',
        // 角标尺寸固定，不随系统字号放大（否则溢出小圆）。
        textScaler: TextScaler.noScaling,
        style: TextStyle(
          fontSize: fontSize,
          height: 1,
          fontWeight: FontWeight.w700,
          color: Colors.white,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}

class _LevelDot extends StatelessWidget {
  const _LevelDot({required this.tier, required this.diameter});

  final GrowthTier tier;
  final double diameter;

  @override
  Widget build(BuildContext context) {
    final size = diameter >= 28 ? 9.0 : 8.0;
    return Container(
      key: const ValueKey('growthLevelDot'),
      width: size,
      height: size,
      decoration: _tierFill(tier, borderWidth: 1.5, shadow: false),
    );
  }
}

/// 头像 + 外圈框（含 2dp 白隔圈）；动效档自带 ticker。
class _FramedAvatar extends StatefulWidget {
  const _FramedAvatar({required this.frame, required this.diameter, required this.thickness, required this.child});

  final GrowthFrame frame;
  final double diameter;
  final double thickness;
  final Widget child;

  @override
  State<_FramedAvatar> createState() => _FramedAvatarState();
}

// TickerProviderStateMixin（非 Single）：动效档会随「移除动画」开关 / 换框反复建、销 controller。
class _FramedAvatarState extends State<_FramedAvatar> with TickerProviderStateMixin {
  AnimationController? _spin;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncSpin();
  }

  @override
  void didUpdateWidget(_FramedAvatar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.frame != widget.frame) _syncSpin();
  }

  void _syncSpin() {
    // 只订阅这一项（键盘弹起等其它 MediaQuery 变化不触发重建）。
    final still = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    final wantSpin = widget.frame == GrowthFrame.animated && !still;
    if (wantSpin && _spin == null) {
      _spin = AnimationController(vsync: this, duration: const Duration(seconds: 8))..repeat();
    } else if (!wantSpin && _spin != null) {
      _spin!.dispose();
      _spin = null;
    }
  }

  @override
  void dispose() {
    _spin?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final outer = widget.diameter + 2 * widget.thickness;
    final spin = _spin;
    final framed = CustomPaint(
      key: ValueKey('growthFrame_${widget.frame.name}'),
      // 动效档只重绘框（painter 监听 controller），头像子树不随每帧重建。
      painter: _FramePainter(frame: widget.frame, avatarDiameter: widget.diameter, spin: spin),
      child: SizedBox(
        width: outer,
        height: outer,
        child: Center(
          child: SizedBox(
            width: widget.diameter,
            height: widget.diameter,
            // 动效档每帧重绘框时头像不跟着重绘。
            child: RepaintBoundary(child: widget.child),
          ),
        ),
      ),
    );
    // 恒包一层（树形不随动效开关变化，开关时头像不重挂载）。
    return RepaintBoundary(child: framed);
  }
}

class _FramePainter extends CustomPainter {
  _FramePainter({required this.frame, required this.avatarDiameter, this.spin}) : super(repaint: spin);

  final GrowthFrame frame;
  final double avatarDiameter;

  /// 动效档的旋转进度（0–1）；null = 静止。
  final Animation<double>? spin;

  // CSS 160deg 线性渐变方向。
  static const _begin160 = Alignment(-0.342, -0.940);
  static const _end160 = Alignment(0.342, 0.940);

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final r = size.width / 2;
    final rect = Rect.fromCircle(center: center, radius: r);

    // 外饰（不占布局，外溢绘制）。
    switch (frame) {
      case GrowthFrame.upgraded:
        canvas.drawCircle(center, r + 3, Paint()..color = AppColors.frameUpgradedOuter);
        canvas.drawCircle(center, r + 1.5, Paint()..color = Colors.white);
      case GrowthFrame.animated:
        canvas.drawCircle(
            center,
            r + 1.5,
            Paint()
              ..color = AppColors.frameAnimatedGlow
              ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 7));
        canvas.drawCircle(center, r + 1.5, Paint()..color = Colors.white);
      case GrowthFrame.basic:
        break;
    }

    final Gradient ring = switch (frame) {
      GrowthFrame.basic => const LinearGradient(
          begin: _begin160, end: _end160, colors: [AppColors.frameBasicStart, AppColors.tierSahabat]),
      GrowthFrame.upgraded => const LinearGradient(
          begin: _begin160,
          end: _end160,
          colors: [AppColors.frameUpgradedStart, AppColors.tierPenjaga, AppColors.frameUpgradedEnd],
          stops: [0, 0.55, 1]),
      // CSS conic-gradient(from 200deg) 从 12 点钟起，SweepGradient 从 3 点钟起 → 起始 110°，再叠加旋转。
      GrowthFrame.animated => SweepGradient(
          colors: const [
            AppColors.tierBintangStart,
            AppColors.tierBintangEnd,
            AppColors.frameAnimatedHighlight,
            AppColors.tierBintangEnd,
            AppColors.tierBintangStart,
          ],
          transform: GradientRotation((200 - 90) * math.pi / 180 + 2 * math.pi * (spin?.value ?? 0)),
        ),
    };
    canvas.drawCircle(center, r, Paint()..shader = ring.createShader(rect));
    // 白隔圈（贴头像外 2dp）。
    canvas.drawCircle(center, avatarDiameter / 2 + 2, Paint()..color = Colors.white);
  }

  @override
  bool shouldRepaint(_FramePainter old) =>
      old.frame != frame || old.avatarDiameter != avatarDiameter || old.spin != spin;
}
