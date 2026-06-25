import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/storage/prefs.dart';
import '../../../l10n/app_localizations.dart';

/// 启动屏（原型 preview-new-splash-auth-0622.html · P-01，品牌重塑版）。
///
/// 紫底 #7D45F6 + 单层呼吸光晕 + Logo mark（白狗/T 标 + 猫跑遍全屏归位翻紫成镂空）
/// + 字标淡入上浮 + 标语 + 圆形 spinner + 版本号。
/// **当天首次打开**播放完整入场动效（~4.3s）；当天再次打开 / reduce-motion → 直接静止终态
/// （spinner 仍转）。完整时间线见原型 CSS 关键帧，数值照搬（390 宽逻辑像素对齐）。
class SplashPage extends StatefulWidget {
  const SplashPage({super.key, this.onComplete});

  final VoidCallback? onComplete;
  static const String version = 'v 1.0.0';

  /// 品牌紫（logo mark 专用，非全局 token；本轮重塑只动 splash mark）。
  static const Color brandViolet = Color(0xFF7D45F6);

  /// 入场总时长（狗 0–.42 / 猫 .60–3.60 / 字标 3.72–4.22 / 标语 3.82–4.32）。
  static const Duration animatedTotal = Duration(milliseconds: 4320);

  /// 播完动效后再停留多久跳转。
  static const Duration animatedHold = Duration(milliseconds: 4500);
  static const Duration staticHold = Duration(milliseconds: 1400);

  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> with TickerProviderStateMixin {
  // 入场主控制器（走整条时间线，子动效用 Interval 切片）。
  late final AnimationController _master =
      AnimationController(vsync: this, duration: SplashPage.animatedTotal);
  // 光晕呼吸：静/动态都常驻。
  late final AnimationController _halo =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 3200))
        ..repeat(reverse: true);

  Timer? _timer;
  bool _decided = false; // prefs 决策完成前不渲染 mark，避免静→动闪烁
  bool _reduceMotion = false;
  bool _started = false;

  // —— 时间线切片（master.value 0..1，对应 0..4320ms）——
  static const double _t = 4320;
  late final Animation<double> _dog = CurvedAnimation(
      parent: _master, curve: const Interval(0, 420 / _t, curve: Cubic(.16, 1, .3, 1)));
  late final Animation<double> _catT = CurvedAnimation(
      parent: _master, curve: const Interval(600 / _t, 3600 / _t));
  late final Animation<double> _wordmark = CurvedAnimation(
      parent: _master, curve: const Interval(3720 / _t, 4220 / _t, curve: Cubic(0, 0, .2, 1)));
  late final Animation<double> _tagline = CurvedAnimation(
      parent: _master, curve: const Interval(3820 / _t, 4320 / _t, curve: Cubic(0, 0, .2, 1)));

  // 猫 6 路点（屏幕绝对位移 px，相对 logo 终位；390×844）：左下→顶中→右1/3→左2/3→右下→微冲→归位。
  static const List<Offset> _catWaypoints = [
    Offset(-180, 447), Offset(0, -313), Offset(180, -82),
    Offset(-180, 200), Offset(180, 447), Offset(8, -8), Offset.zero,
  ];
  late final Animation<Offset> _catPath = TweenSequence<Offset>([
    _catSeg(0, 1, 18, Curves.easeInOut),
    _catSeg(1, 2, 18, Curves.easeInOut),
    _catSeg(2, 3, 18, Curves.easeInOut),
    _catSeg(3, 4, 18, Curves.easeInOut),
    _catSeg(4, 5, 16, Curves.easeInOut),
    _catSeg(5, 6, 12, Curves.easeOut),
  ]).animate(_catT);

  static TweenSequenceItem<Offset> _catSeg(int a, int b, double w, Curve c) =>
      TweenSequenceItem(
        tween: Tween(begin: _catWaypoints[a], end: _catWaypoints[b]).chain(CurveTween(curve: c)),
        weight: w,
      );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    _decide();
  }

  Future<void> _decide() async {
    bool animate;
    try {
      // 超时护栏：prefs 不可用/慢（如测试环境无 mock）→ 不阻塞跳转，保守走静止终态。
      final prefs = await AppPrefs.create().timeout(const Duration(milliseconds: 300));
      final today = _todayKey();
      final shownToday = prefs.splashLastShownDate == today;
      if (!shownToday) await prefs.setSplashLastShownDate(today);
      animate = !shownToday && !_reduceMotion;
    } catch (_) {
      animate = false; // prefs 不可用 → 静止终态
    }
    if (!mounted) return;
    setState(() => _decided = true);
    if (animate) {
      _master.forward();
    } else {
      _master.value = 1.0; // 直达终态
    }
    // Debug-only：DEV_ROUTE=/splash 时定屏不跳转，供视觉验收。
    const devPin = kDebugMode && String.fromEnvironment('DEV_ROUTE') == '/splash';
    if (!devPin) {
      _timer = Timer(animate ? SplashPage.animatedHold : SplashPage.staticHold, () {
        if (!mounted) return;
        (widget.onComplete ?? () => context.go('/home'))();
      });
    }
  }

  String _todayKey() {
    final n = DateTime.now();
    return '${n.year}-${n.month}-${n.day}';
  }

  @override
  void dispose() {
    _timer?.cancel();
    _master.dispose();
    _halo.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final size = MediaQuery.of(context).size;
    final centerY = size.height * 0.43; // 原型 top:43%
    return Scaffold(
      backgroundColor: SplashPage.brandViolet,
      body: Stack(
        clipBehavior: Clip.hardEdge,
        children: [
          // 呼吸光晕（260×260，中心 43%）。
          Positioned(
            top: centerY - 130,
            left: size.width / 2 - 130,
            child: AnimatedBuilder(
              animation: _halo,
              builder: (_, _) {
                final v = _reduceMotion ? 0.5 : _halo.value;
                return Opacity(
                  opacity: 0.55 + 0.45 * v,
                  child: Transform.scale(
                    scale: 1.0 + 0.08 * v,
                    child: Container(
                      width: 260, height: 260,
                      decoration: const BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: RadialGradient(
                          colors: [Color(0x33FFFFFF), Color(0x00FFFFFF)],
                          stops: [0.0, 0.68],
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
          // 中心块：mark + 字标 + 标语（垂直居中于 43%，gap 22）。
          if (_decided)
            Positioned(
              top: centerY,
              left: 0, right: 0,
              child: FractionalTranslation(
                translation: const Offset(0, -0.5),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _markStage(),
                    const SizedBox(height: 22),
                    _wordmarkWidget(),
                    const SizedBox(height: 22),
                    _taglineWidget(l10n),
                  ],
                ),
              ),
            ),
          // 圆形 spinner（底部 108）。
          Positioned(
            bottom: 108, left: 0, right: 0,
            child: Center(
              child: SizedBox(
                width: 22, height: 22,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: const Color(0xFFFFFFFF).withValues(alpha: 0.92),
                  backgroundColor: const Color(0xFFFFFFFF).withValues(alpha: 0.22),
                ),
              ),
            ),
          ),
          // 版本号（底部 84）。
          Positioned(
            bottom: 84, left: 0, right: 0,
            child: Text(SplashPage.version,
                textAlign: TextAlign.center,
                style: TextStyle(
                    fontFamily: 'Poppins',
                    fontSize: 10, letterSpacing: 0.5,
                    color: const Color(0xFFFFFFFF).withValues(alpha: 0.30))),
          ),
        ],
      ),
    );
  }

  // Logo mark：白狗/T 标 + 猫（跑屏归位翻紫）。108×108，内层 Stack 不裁切（猫飞出框）。
  Widget _markStage() {
    return SizedBox(
      width: 108, height: 108,
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.center,
        children: [
          // 狗/T 底标：缩放淡入。
          AnimatedBuilder(
            animation: _dog,
            builder: (_, child) => Opacity(
              opacity: _dog.value,
              child: Transform.scale(scale: 0.86 + 0.14 * _dog.value, child: child),
            ),
            child: SvgPicture.asset('assets/brand/mark_dog.svg', width: 108, height: 108),
          ),
          // 猫：跑屏路径 + 脚步弹跳 + 归位时填充由白翻紫（成镂空）。
          AnimatedBuilder(
            animation: _catT,
            builder: (_, _) {
              final t = _catT.value;
              final path = _catPath.value;
              // 脚步弹跳：~11 次 -7px 上抬（abs sin）。
              final bounce = -7.0 * math.sin(t * math.pi * 11).abs();
              // 归位翻紫：3.58s 起 .12s 内 白→紫（master 0.829..0.856）。
              final flip = ((_master.value - 0.829) / (0.856 - 0.829)).clamp(0.0, 1.0);
              final catColor = Color.lerp(
                  const Color(0xFFFFFFFF), SplashPage.brandViolet, flip)!;
              return Transform.translate(
                offset: path + Offset(0, bounce),
                child: SvgPicture.asset(
                  'assets/brand/mark_cat.svg',
                  width: 108, height: 108,
                  colorFilter: ColorFilter.mode(catColor, BlendMode.srcIn),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _wordmarkWidget() {
    return AnimatedBuilder(
      animation: _wordmark,
      builder: (_, child) => Opacity(
        opacity: _wordmark.value,
        child: Transform.translate(offset: Offset(0, 16 * (1 - _wordmark.value)), child: child),
      ),
      child: SvgPicture.asset('assets/brand/wordmark.svg', width: 172),
    );
  }

  Widget _taglineWidget(AppLocalizations l10n) {
    return AnimatedBuilder(
      animation: _tagline,
      builder: (_, child) => Opacity(
        opacity: _tagline.value,
        child: Transform.translate(offset: Offset(0, 16 * (1 - _tagline.value)), child: child),
      ),
      child: SizedBox(
        width: 240,
        child: Text(
          l10n.splashTagline,
          textAlign: TextAlign.center,
          // 原型 p-01：Quicksand 600（圆角字体）+ 12.5 / 1.65 / .15。
          style: TextStyle(
              fontFamily: 'Quicksand',
              fontVariations: const [FontVariation('wght', 600)],
              fontWeight: FontWeight.w600,
              fontSize: 12.5, height: 1.65, letterSpacing: 0.15,
              color: const Color(0xFFFFFFFF).withValues(alpha: 0.62)),
        ),
      ),
    );
  }
}
