import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 启动屏（原型 P-01，1:1 还原 splash.html）。
///
/// 深墨底 #141019 + 4 层辉光 + 6 浮动粒子 + 3 脉冲方环 + Pop Art 红错位 +6px
/// + violet 渐变图标(#9E83DA→#845EC9→#6C48AE) + 白爪印 + 字标 + 三点 loader + 版本号。
/// ~2.2s 后转 /home。reduce-motion 降级静态。数值全部照搬原型 px（390 宽逻辑像素对齐）。
class SplashPage extends StatefulWidget {
  const SplashPage({super.key, this.onComplete});

  final VoidCallback? onComplete;
  static const Duration hold = Duration(milliseconds: 2200);
  static const String version = 'v 1.0.0';

  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> with TickerProviderStateMixin {
  late final AnimationController _enter =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 750));
  late final AnimationController _loop =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 2500))..repeat();
  late final AnimationController _breath =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 3000))..repeat(reverse: true);
  Timer? _timer;
  bool _started = false;
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (_reduceMotion) {
      _enter.value = 1.0;
      _loop.stop();
      _breath.stop();
    } else {
      _enter.forward();
    }
    // Debug-only：DEV_ROUTE=/splash 时定屏不跳转，供视觉验收截图。
    const devPinSplash =
        kDebugMode && String.fromEnvironment('DEV_ROUTE') == '/splash';
    if (!devPinSplash) {
      _timer = Timer(SplashPage.hold, () {
        if (!mounted) return;
        (widget.onComplete ?? () => context.go('/home'))();
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _enter.dispose();
    _loop.dispose();
    _breath.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const white = Color(0xFFFFFFFF);
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.splashInk,
      body: Stack(
        children: [
          // ── 4 层大气辉光（原型 radial-gradient 层）──
          // L1 底部紫色大泛光
          const _GlowLayer(
            alignment: Alignment(0, 1.2), wScale: 3.0, hScale: 0.58,
            color: Color(0xFF845EC9), opacity: 0.62,
          ),
          // L2 右上薄荷微光
          const _GlowLayer(
            alignment: Alignment(1.12, -1.08), wScale: 1.3, hScale: 0.36,
            color: Color(0xFF5BCBBB), opacity: 0.16,
          ),
          // L3 左上 Pop Art 红微光
          const _GlowLayer(
            alignment: Alignment(-1.08, -0.96), wScale: 0.9, hScale: 0.28,
            color: Color(0xFFF0425A), opacity: 0.09,
          ),
          // L4 中心呼吸泛光（在图标后）
          Align(
            alignment: const Alignment(0, -0.38),
            child: AnimatedBuilder(
              animation: _breath,
              builder: (_, _) {
                final t = _reduceMotion ? 0.4 : _breath.value;
                return Container(
                  width: 220, height: 220,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(colors: [
                      const Color(0xFF845EC9).withValues(alpha: 0.28 + 0.27 * t),
                      Colors.transparent,
                    ], stops: const [0.0, 0.68]),
                  ),
                );
              },
            ),
          ),
          // ── 6 浮动粒子 ──
          if (!_reduceMotion) ..._particles(),
          // ── 中心块：图标组 + 字标 ──
          Align(
            alignment: const Alignment(0, -0.42),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _iconGroup(white),
                const SizedBox(height: 28),
                FadeTransition(
                  opacity: _enter,
                  child: Column(children: [
                    const Text('TailTopia',
                        style: TextStyle(
                            fontSize: 36, fontWeight: FontWeight.w700, color: white,
                            letterSpacing: -1.5, height: 1.0)),
                    const SizedBox(height: 11),
                    Text(l10n.splashTagline,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            fontSize: 12, height: 1.7, letterSpacing: 0.12,
                            color: const Color(0xFFDCD2F7).withValues(alpha: 0.45))),
                  ]),
                ),
              ],
            ),
          ),
          // ── 三点 loader（底部 110）──
          Positioned(left: 0, right: 0, bottom: 110, child: Center(child: _DotLoader(_loop, _reduceMotion))),
          // ── 版本号（底部 88）──
          Positioned(
            left: 0, right: 0, bottom: 88,
            child: Text(SplashPage.version,
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 10, letterSpacing: 0.5, color: white.withValues(alpha: 0.18))),
          ),
        ],
      ),
    );
  }

  // 图标组：3 脉冲方环 + Pop Art 红错位层 + violet 渐变图标 + 白爪印
  Widget _iconGroup(Color white) {
    return SizedBox(
      width: 84, height: 84,
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.center,
        children: [
          if (!_reduceMotion)
            for (final d in const [0.0, 0.33, 0.66]) _pulseRing(d),
          // Pop Art 红错位层 +6px
          Positioned(
            top: 6, left: 6,
            child: Container(
              width: 84, height: 84,
              decoration: BoxDecoration(
                color: const Color(0xFFF0425A).withValues(alpha: 0.85),
                borderRadius: BorderRadius.circular(26),
              ),
            ),
          ),
          // 主图标（violet 渐变 + 弹入）
          ScaleTransition(
            scale: CurvedAnimation(parent: _enter, curve: Curves.easeOutBack),
            child: Container(
              width: 84, height: 84,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(26),
                gradient: const LinearGradient(
                  begin: Alignment.topLeft, end: Alignment.bottomRight,
                  colors: [Color(0xFF9E83DA), Color(0xFF845EC9), Color(0xFF6C48AE)],
                  stops: [0.0, 0.55, 1.0],
                ),
                boxShadow: [
                  BoxShadow(color: const Color(0xFF845EC9).withValues(alpha: 0.55), blurRadius: 52),
                  BoxShadow(color: Colors.black.withValues(alpha: 0.55), blurRadius: 36, offset: const Offset(0, 18)),
                ],
              ),
              child: Center(
                child: SvgPicture.asset('assets/icons/paw.svg', width: 46, height: 46),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _pulseRing(double delay) {
    return AnimatedBuilder(
      animation: _loop,
      builder: (_, _) {
        final t = (_loop.value + delay) % 1.0;
        return Opacity(
          opacity: (0.65 * (1 - t)).clamp(0.0, 1.0),
          child: Transform.scale(
            scale: 1.0 + t * 1.8,
            child: Container(
              width: 84, height: 84,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(26),
                border: Border.all(color: const Color(0xFF845EC9).withValues(alpha: 0.6), width: 1.5),
              ),
            ),
          ),
        );
      },
    );
  }

  List<Widget> _particles() {
    // 原型 6 粒子：bottom%/left|right%/size/color/旋转/时长/延迟
    const data = [
      [0.28, 0.07, true, 9.0, 0xFF845EC9, 3.0, 5.8, 0.0],
      [0.20, 0.20, true, 7.0, 0xFFF0425A, 2.0, 4.9, 0.85],
      [0.32, 0.35, true, 5.0, 0xFF5BCBBB, 99.0, 6.4, 0.4],
      [0.24, 0.14, false, 8.0, 0xFF9E83DA, 3.0, 5.2, 1.5],
      [0.30, 0.27, false, 6.0, 0xFFF6A609, 2.0, 5.9, 0.2],
      [0.18, 0.07, false, 10.0, 0xFF845EC9, 2.0, 4.6, 1.2],
    ];
    return [
      for (final p in data)
        _Particle(
          loop: _loop,
          bottomFrac: p[0] as double,
          sideFrac: p[1] as double,
          fromLeft: p[2] as bool,
          size: p[3] as double,
          color: Color(p[4] as int),
          radius: p[5] as double,
          period: p[6] as double,
          delay: p[7] as double,
        ),
    ];
  }
}

class _GlowLayer extends StatelessWidget {
  const _GlowLayer({required this.alignment, required this.wScale, required this.hScale, required this.color, required this.opacity});
  final Alignment alignment;
  final double wScale, hScale, opacity;
  final Color color;
  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: RadialGradient(
            center: alignment, radius: wScale,
            colors: [color.withValues(alpha: opacity), Colors.transparent],
            stops: const [0.0, 0.6],
          ),
        ),
      ),
    );
  }
}

class _Particle extends StatelessWidget {
  const _Particle({
    required this.loop, required this.bottomFrac, required this.sideFrac, required this.fromLeft,
    required this.size, required this.color, required this.radius, required this.period, required this.delay,
  });
  final AnimationController loop;
  final double bottomFrac, sideFrac, size, radius, period, delay;
  final bool fromLeft;
  final Color color;
  @override
  Widget build(BuildContext context) {
    final h = MediaQuery.of(context).size.height;
    return AnimatedBuilder(
      animation: loop,
      builder: (_, _) {
        // 用 loop(2.5s) 推进，按各自 period 取相位（近似原型 floatUp 错落）
        final t = ((loop.value * 2.5 / period) + delay / period) % 1.0;
        final dy = -160.0 * t;
        final op = (0.55 * (1 - t)).clamp(0.0, 0.55);
        return Positioned(
          bottom: h * bottomFrac - dy,
          left: fromLeft ? MediaQuery.of(context).size.width * sideFrac : null,
          right: fromLeft ? null : MediaQuery.of(context).size.width * sideFrac,
          child: Opacity(
            opacity: op,
            child: Container(
              width: size, height: size,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(radius),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _DotLoader extends StatelessWidget {
  const _DotLoader(this.loop, this.reduce);
  final AnimationController loop;
  final bool reduce;
  @override
  Widget build(BuildContext context) {
    const colors = [Color(0xFF845EC9), Color(0xFF9E83DA), Color(0xFFC2B0EC)];
    return Row(mainAxisSize: MainAxisSize.min, children: [
      for (var i = 0; i < 3; i++) ...[
        if (i > 0) const SizedBox(width: 8),
        AnimatedBuilder(
          animation: loop,
          builder: (_, _) {
            final t = reduce ? 0.0 : (loop.value + i * 0.18) % 1.0;
            final lift = t < 0.4 ? (t / 0.4) : (t < 0.75 ? (1 - (t - 0.4) / 0.35) : 0.0);
            return Transform.translate(
              offset: Offset(0, -9 * lift),
              child: Container(width: 6, height: 6, decoration: BoxDecoration(color: colors[i], shape: BoxShape.circle)),
            );
          },
        ),
      ],
    ]);
  }
}
