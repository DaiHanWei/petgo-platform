import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

import '../../../core/storage/prefs.dart';
import '../../../l10n/app_localizations.dart';

/// 启动屏（V1.1.2 Story 7.2 · FR-87/FR-88/FR-90 · 方案 B「写名字」）。
///
/// **叙事**：原生启动屏是动画的第 0 帧 —— 原生显示紫底白 mark（可视宽 = 屏宽 42%，系统渲染在
/// 50% 正中），Flutter 首帧接住**同位同尺寸**的 mark，再由它开始变化：
///
/// | 拍 | 时点 | 内容 |
/// |---|---|---|
/// | B1 | 0–480ms | mark 由 50% 抬到设计位 43%，同时 `scale 1→.70` / `opacity 1→0` |
/// | B2 | 480–1380ms | 字标 11 条路径分 **9 拍**逐组写出，每拍 `+85ms`，`blur 4→0` / `y 10→0` |
/// | B3 | 1540ms | 终态：字标（可视宽 60%）+ 标语。**终态不出现 mark** |
///
/// **终态不出现 mark**：品牌母文件里没有「mark 在上 / 字标在下」的竖排 lockup（那是实现期自行
/// 拼装的，见设计侧挑战 X-1），本版本废止。视觉重量全部给品牌名。
///
/// **尺寸一律相对屏宽**（NFR-17）：mark 42% / 字标 60% / 光晕 66.7% / 标语 70%。改前多处写死
/// 绝对像素（按 390×844），小屏大屏构图漂移。
///
/// **视觉中心 43% 的口径**：`logo 中心`居中，**标语不参与居中计算**（设计侧挑战 X-2；改前
/// 「整块含标语居中」会把字标推到光晕亮心之上）。
class SplashPage extends StatefulWidget {
  const SplashPage({super.key, this.onComplete});

  final VoidCallback? onComplete;

  /// 品牌紫。原生启动屏底色（`pubspec.yaml` 的 `flutter_native_splash.color`）必须与此一致，
  /// 否则交接处会闪一下（缺陷 B-1/B-2）。
  static const Color brandViolet = Color(0xFF7D45F6);

  /// 入场总时长（B1 480 + B2 900，B3 终态落在 1540）。上限 1.8s（NFR-13）。
  static const Duration animatedTotal = Duration(milliseconds: 1540);

  /// 播完动效后再停留多久才交给 [onComplete]。
  static const Duration animatedHold = Duration(milliseconds: 1720);
  static const Duration staticHold = Duration(milliseconds: 1400);

  /// 各元素可视宽占屏宽的比例（NFR-17：不写死绝对像素）。
  static const double markWidthRatio = 0.42;
  static const double wordmarkWidthRatio = 0.60;
  static const double glowWidthRatio = 0.667;
  static const double taglineWidthRatio = 0.70;

  /// 视觉中心（logo 中心落此高度；标语不参与该计算）。
  static const double centerYRatio = 0.43;

  /// 原生启动屏把 mark 渲染在正中 50% —— Flutter 首帧必须落同一位置才能无缝接住。
  /// 「50% → 43%」的抬升是 B1 拍的动作，不可省（省了交接处会跳位）。
  static const double handoffYRatio = 0.50;

  /// 底部容器起始位置（进度线与慢网提示共用，均由 Story 7.3 填充）。
  static const double bottomInset = 92;

  /// 字标资产的 viewBox 宽高比（`37.05 183.5 413.74 128.1`），用于按宽度算高度。
  static const double wordmarkAspect = 413.74 / 128.1;

  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> with TickerProviderStateMixin {
  late final AnimationController _master =
      AnimationController(vsync: this, duration: SplashPage.animatedTotal);

  /// 光晕呼吸。**reduce-motion 下不启动**（无限动画只允许用于 loading 指示；装饰性元素常驻
  /// 动画属反模式 —— 设计侧挑战 X-3）。改前仅固定了取值、动画本身仍在跑。
  late final AnimationController _halo =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 3200));

  Timer? _timer;
  bool _decided = false;
  bool _reduceMotion = false;
  bool _started = false;

  /// 字标按拍分组后的 SVG 片段（每组一个独立 SVG 字符串，共用同一 viewBox 故可叠放对齐）。
  /// 从**单一资产文件**运行时切分 —— 不拆成 9 个资产文件（AC2）。
  List<String>? _wordmarkBeats;

  // —— 时间线切片（master.value 0..1 对应 0..1540ms）——
  static const double _t = 1540;

  /// B1 · mark 交接：0–480ms。一段位移同时完成「从原生 50% 就位到 43%」与「把舞台让给字标」。
  late final Animation<double> _b1 = CurvedAnimation(
      parent: _master, curve: const Interval(0, 480 / _t, curve: Cubic(.16, 1, .3, 1)));

  /// B3 · 标语淡入上浮：1240–1540ms（终态最后落位）。
  late final Animation<double> _tagline = CurvedAnimation(
      parent: _master, curve: const Interval(1240 / _t, 1540 / _t, curve: Cubic(0, 0, .2, 1)));

  /// B2 · 字标 9 拍：480ms 起，每拍 `+85ms` 错开，单拍时长 220ms（末拍 480+8×85+220 = 1380ms）。
  static const double _b2Start = 480;
  static const double _b2Stagger = 85;
  static const double _b2BeatDur = 220;
  static const int _b2Beats = 9;

  Animation<double> _beat(int i) => CurvedAnimation(
        parent: _master,
        curve: Interval(
          (_b2Start + _b2Stagger * i) / _t,
          (_b2Start + _b2Stagger * i + _b2BeatDur) / _t,
          curve: const Cubic(0, 0, .2, 1),
        ),
      );
  late final List<Animation<double>> _beats =
      List.generate(_b2Beats, _beat, growable: false);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (!_reduceMotion) _halo.repeat(reverse: true);
    _loadWordmark(DefaultAssetBundle.of(context));
    _decide();
  }

  /// 载入字标资产并按 9 拍切分：`[T+猫尾+2 条动感短划]` 为第 1 拍，其后 `a i l t o p i a`
  /// 各 1 拍（母文件共 11 条可见路径 → 9 拍）。失败则整块一次性显示，不阻断启动。
  Future<void> _loadWordmark(AssetBundle bundle) async {
    try {
      final raw = await bundle.loadString('assets/brand/wordmark_brand.svg');
      final head = RegExp(r'<svg[^>]*>').firstMatch(raw)!.group(0)!;
      final paths = RegExp(r'<path\b[^>]*/>')
          .allMatches(raw)
          .map((m) => m.group(0)!)
          .toList(growable: false);
      // 11 条 → 9 组：前 3 条（T+尾+2 短划）合成第 1 组，其余各自成组。
      final groups = <List<String>>[];
      if (paths.length >= _b2Beats + 2) {
        groups.add(paths.sublist(0, 3));
        for (final p in paths.sublist(3)) {
          groups.add([p]);
        }
      } else {
        groups.add(paths); // 资产结构意外变化 → 退化为一次性显示
      }
      if (!mounted) return;
      setState(() {
        _wordmarkBeats =
            groups.map((g) => '$head${g.join()}</svg>').toList(growable: false);
      });
    } catch (e) {
      debugPrint('[Splash] wordmark 分拍失败，退化为整块显示: $e');
    }
  }

  Future<void> _decide() async {
    bool animate;
    try {
      // 超时护栏：prefs 不可用/慢 → 不阻塞跳转，保守走静止终态。
      // NOTE(Story 7.3)：「当天只播一次」门控将被取消，届时本段与 `_decided` 一并移除。
      final prefs = await AppPrefs.create().timeout(const Duration(milliseconds: 300));
      final today = _todayKey();
      final shownToday = prefs.splashLastShownDate == today;
      if (!shownToday) await prefs.setSplashLastShownDate(today);
      animate = !shownToday && !_reduceMotion;
    } catch (_) {
      animate = false;
    }
    if (!mounted) return;
    setState(() => _decided = true);
    if (animate) {
      _master.forward();
    } else {
      _master.value = 1.0; // 直达终态（reduce-motion 亦走此路，AC7）
    }
    // Debug-only：DEV_ROUTE=/splash 时定屏不跳转，供逐帧对稿。
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
    // 用 LayoutBuilder 而非 MediaQuery.size：冷启动瞬间 Flutter 画面可能还是 0×0
    // （logcat: `FlutterRenderer: Width is zero. 0,0`），此时按 MediaQuery 算出的尺寸全为 0；
    // LayoutBuilder 拿的是本次布局的真实约束，且约束变化会自动重建（2026-08-04 实测踩坑）。
    return LayoutBuilder(builder: (context, c) {
      final size = Size(c.maxWidth, c.maxHeight);
      if (size.width <= 0 || size.height <= 0) {
        // 约束尚未就绪 —— 只铺底色，等下一帧拿到真实尺寸再画内容。
        return const ColoredBox(color: SplashPage.brandViolet);
      }
      return _content(l10n, size);
    });
  }

  Widget _content(AppLocalizations l10n, Size size) {
    final centerY = size.height * SplashPage.centerYRatio;
    final handoffY = size.height * SplashPage.handoffYRatio;
    final glow = size.width * SplashPage.glowWidthRatio;
    final wordmarkW = size.width * SplashPage.wordmarkWidthRatio;
    final wordmarkH = wordmarkW / SplashPage.wordmarkAspect;

    return Scaffold(
      backgroundColor: SplashPage.brandViolet,
      // 不设 clipBehavior：新动效没有越出边界的元素（旧实现因猫要跑出屏幕才需要 hardEdge），
      // 而 hardEdge 在尺寸异常为 0 时会把整个 Stack 裁没（同上踩坑）。
      body: Stack(
        children: [
          _glow(centerY, glow),
          // B1 · mark：由交接位 50% 抬到 43%，同时缩小淡出。终态不出现（AC3）。
          if (_decided) _markHandoff(size, centerY, handoffY),
          // B2/B3 · 字标：logo 中心落 43%（标语不参与该计算，AC5/X-2）。
          if (_decided)
            Positioned(
              top: centerY - wordmarkH / 2,
              left: 0,
              right: 0,
              // 用 Column(min) 而非 Center：Positioned(top+left+right) 给的是「宽度紧、
              // 高度无界」的约束，Center 在无界高度下撑不出尺寸导致整块不绘制（2026-08-04 实测）。
              // Column 横向由 crossAxisAlignment.center 居中、纵向 min 自适应 —— 本文件旧实现即此法。
              child: Column(mainAxisSize: MainAxisSize.min, children: [_wordmark(wordmarkW)]),
            ),
          // B3 · 标语：挂在字标下方，不参与居中。
          if (_decided)
            Positioned(
              top: centerY + wordmarkH / 2 + _taglineGap,
              left: 0,
              right: 0,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [_taglineWidget(l10n, size.width * SplashPage.taglineWidthRatio)],
              ),
            ),
          // 底部容器：进度线与慢网提示由 Story 7.3 填充（本 Story 已移除常驻 spinner 与版本号）。
          const Positioned(bottom: SplashPage.bottomInset, left: 0, right: 0, child: SizedBox.shrink()),
        ],
      ),
    );
  }

  /// 标语与字标的间距。决策 C-9：20→24（字号变大需要更多留白）。
  static const double _taglineGap = 24;

  Widget _glow(double centerY, double d) {
    return Positioned(
      top: centerY - d / 2,
      left: 0,
      right: 0,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedBuilder(
          animation: _halo,
          builder: (_, _) {
            // reduce-motion：停在中间值不呼吸（AC7）。控制器本身也未启动。
            final v = _reduceMotion ? 0.5 : _halo.value;
            return Opacity(
              opacity: 0.55 + 0.45 * v,
              child: Transform.scale(
                scale: 1.0 + 0.08 * v,
                child: Container(
                  width: d,
                  height: d,
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
        ],
      ),
    );
  }

  /// B1 · mark 交接拍：位置 50%→43%、`scale 1→.70`、`opacity 1→0`（≈480ms）。
  ///
  /// ⚠️ **本方法必须恒定返回 `Positioned`，不得在终态改返回非定位子元素**（如
  /// `SizedBox.shrink()`）。Stack 一旦出现**任何**非定位子元素，就会把自身尺寸收缩为这些
  /// 子元素的最大尺寸 —— 返回 0×0 的非定位子元素会让整个 Stack 变 0×0，**所有兄弟元素
  /// 一起消失**。2026-08-04 实测踩坑：动效播完（t≥1）才空屏，而单测只 pump 60ms（t<1）
  /// 恰好躲过，故 L0 全绿却在真机上一片空白。终态隐藏一律用 `opacity`，不换节点类型。
  Widget _markHandoff(Size size, double centerY, double handoffY) {
    final w = size.width * SplashPage.markWidthRatio;
    return AnimatedBuilder(
      animation: _b1,
      builder: (_, child) {
        final t = _b1.value;
        return Positioned(
          top: (handoffY + (centerY - handoffY) * t) - w / 2,
          left: 0,
          right: 0,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Opacity(
                // 终态 opacity 0 —— 「终态不出现 mark」（AC3）由此实现，而非移除节点。
                opacity: (1 - t).clamp(0.0, 1.0),
                child: Transform.scale(scale: 1 - 0.30 * t, child: child),
              ),
            ],
          ),
        );
      },
      child: SvgPicture.asset('assets/brand/mark_brand.svg', width: w),
    );
  }

  /// B2 · 字标 9 拍逐组写出：每组 `blur 4→0` / `y 10→0`，错开 85ms。
  Widget _wordmark(double w) {
    final beats = _wordmarkBeats;
    if (beats == null) return SizedBox(width: w, height: w / SplashPage.wordmarkAspect);
    return SizedBox(
      width: w,
      height: w / SplashPage.wordmarkAspect,
      child: Stack(
        children: [
          for (var i = 0; i < beats.length; i++)
            AnimatedBuilder(
              animation: _beats[i.clamp(0, _beats.length - 1)],
              builder: (_, child) {
                final t = _beats[i.clamp(0, _beats.length - 1)].value;
                final blur = 4.0 * (1 - t);
                final dy = 10.0 * (1 - t);
                final inner = Transform.translate(
                  offset: Offset(0, dy),
                  child: Opacity(opacity: t, child: child),
                );
                // blur 归零后不再包 ImageFiltered，省一层合成。
                return blur < 0.05
                    ? inner
                    : ImageFiltered(
                        imageFilter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
                        child: inner,
                      );
              },
              child: SvgPicture.string(beats[i], width: w),
            ),
        ],
      ),
    );
  }

  Widget _taglineWidget(AppLocalizations l10n, double w) {
    return AnimatedBuilder(
      animation: _tagline,
      builder: (_, child) => Opacity(
        opacity: _tagline.value,
        child: Transform.translate(offset: Offset(0, 16 * (1 - _tagline.value)), child: child),
      ),
      child: SizedBox(
        width: w,
        child: Text(
          l10n.splashTagline,
          textAlign: TextAlign.center,
          // 决策 C-9：Quicksand 600 / 12.5px → **Fraunces 500 / 16px**。换字体与改字号不是两个
          // 独立参数 —— 下面 8 项是连带重调的结果，勿单独改其中一项：
          //   font-size 12.5→16 / height 1.65→1.5（1.65 在 16px 下两行断开）
          //   letterSpacing .15→0（16px 下已无视觉意义，衬线本不需额外字距）
          //   颜色 白62%→白68%（衬线笔画比圆体细，同透明度下显得更弱）
          //   宽度 屏宽 62%→70%（62% 在 16px 下会挤成三行）
          //   与字标间距 20→24（见 _taglineGap）
          // wght 500 与 opsz 16 已烘死进字体（见 pubspec 说明），故此处只设 SOFT / WONK 两轴。
          style: TextStyle(
            fontFamily: 'Fraunces',
            fontVariations: const [
              FontVariation('SOFT', 100), // 推满 → 圆润衬线末端，呼应字标的圆
              FontVariation('WONK', 1), // personality；OQ-21 待真机确认，过头则改 0
            ],
            fontWeight: FontWeight.w500,
            fontSize: 16,
            height: 1.5,
            letterSpacing: 0,
            color: const Color(0xFFFFFFFF).withValues(alpha: 0.68),
          ),
        ),
      ),
    );
  }
}
