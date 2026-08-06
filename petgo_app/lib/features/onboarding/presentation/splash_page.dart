import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';

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
  const SplashPage({super.key, this.onComplete, this.prepareSession});

  final VoidCallback? onComplete;

  /// 启动期会话恢复（`/me`）的触发口 —— 由本页在首帧即调用（FR-89 / 决策 C-4）。
  ///
  /// ⚠️ **注释订正（code-review 2026-08-04）**：原文写「改前是串行：动效播完（4.32s）才发请求」，
  /// 这个立论不成立。`AuthController.build()` 本来就 `_restoreFuture = _restoreSession()`
  /// （fire-and-forget），而 `app.dart` 在 app 首次 build 时（**比本页存在更早**）就通过
  /// `ref.listen`/`ref.watch(routerProvider)` 初始化了它 ⇒ 改前 `onComplete` 里的
  /// `ensureRestored()` 只是 `await` 一条**早已在飞**的共享 future，「动效与取数并行」原本就成立。
  ///
  /// 本参数真正新增的能力是「**观测它何时完成**」—— 而这正是等待反馈（进度线 / 慢网提示）
  /// 与 5s 兜底所必需的。别按旧注释理解成「splash 持有 `/me` 的触发权」，
  /// 进而把它挪走或另起一条恢复路径。
  ///
  /// ⚠️ 传进来的应当是**返回共享 future** 的函数（`ensureRestored()` 即是）——本页只负责
  /// 「提早触发 + 观测何时完成」，**不新造第二条恢复路径**，否则会打两次 `/me`。
  final Future<void> Function()? prepareSession;

  /// 品牌紫。原生启动屏底色（`pubspec.yaml` 的 `flutter_native_splash.color`）必须与此一致，
  /// 否则交接处会闪一下（缺陷 B-1/B-2）。
  static const Color brandViolet = Color(0xFF7D45F6);

  /// 入场总时长（B1 480 + B2 900，B3 终态落在 1540）。上限 1.8s（NFR-13）。
  static const Duration animatedTotal = Duration(milliseconds: 1540);

  /// 从 splash 首帧起停留多久才交给 [onComplete]（**不是"动效播完之后再停"**，计时自首帧起算）。
  ///
  /// 2026-08-06 由 1720 → **2266**（用户实机反馈：标语来不及看）。标语淡入落在 1240–1540ms
  /// （见 `_tagline`），1720 的档位只给它留下 `1720−1540 = 180ms` 的完全不透明时间 ——
  /// 一行 6 词的标语根本读不完。2266 把这段拉到 **726ms**。
  ///
  /// ⚠️ **为什么偏偏是 2266 而不是取整**：慢网提示在 [slowHintAt]=2290ms 淡入，且只在"尚未就绪"
  /// 时出现。停留一旦越过 2290，就会出现「会话 2.4s 才就绪 → 用户先被『网络较慢』吓一下 →
  /// 200ms 后画面就走了」的假警报。2266 卡在它前面 24ms，等待反馈那一串时点因此一个都不用动。
  /// **再往上调必须连 [progressLineAt] / [slowHintAt] / [readyDeadline] 一起重算。**
  static const Duration animatedHold = Duration(milliseconds: 2266);
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

  /// 底部容器起始位置（进度线与慢网提示共用同一容器）。
  static const double bottomInset = 92;

  /// 字标容器的测试锚点：资产未载入时这个 `SizedBox` 也存在，
  /// 故「宽度随屏宽等比」的断言不依赖异步资产是否已到（code-review 2026-08-04）。
  static const Key wordmarkBoxKey = ValueKey('splashWordmarkBox');

  /// 底部容器预留高度 —— 进度线与提示的出现/消失**不得引起布局位移**（AC4）。
  static const double bottomSlotHeight = 56;

  // ── 等待反馈的三个时点：全部由 [animatedTotal] 派生，勿各自硬编码 ──
  //
  // 动效结束 1540 → 进度线 1860（+320ms 缓冲）→ 慢网提示 2290（+430ms，决策 C-5）
  // → 兜底跳转 5000（决策 D-2）⇒ 提示可读时间 5000 − 2290 = **2710ms**。
  // ⚠️ 改了 animatedTotal 就要重算这一串，别只改一个。

  /// 进度线出现：动效播完仍未就绪才出现 —— **它的出现本身就是信息**（设计侧挑战 X-4）。
  static const Duration progressLineAt = Duration(milliseconds: 1860);

  /// 慢网提示淡入：进度线之后再等 430ms 仍未就绪。
  static const Duration slowHintAt = Duration(milliseconds: 2290);

  /// 会话恢复的兜底上限（决策 D-2：3s → 5s）。到点无论就绪与否都交给 [onComplete]。
  static const Duration readyDeadline = Duration(milliseconds: 5000);

  /// 字标资产的 viewBox 宽高比（`37.05 183.5 413.74 128.1`），用于按宽度算高度。
  static const double wordmarkAspect = 413.74 / 128.1;

  /// mark 资产的 viewBox 宽高比（`67.23 84.79 389.32 321.81`）—— mark 是**宽扁**的，
  /// 按宽度渲染时高度只有宽度的 `1/1.21`。
  ///
  /// ⚠️ 居中必须用**高度**的一半抵扣，不能用宽度（code-review 2026-08-04）：原先
  /// `top: … - w / 2` 会让 mark 整块上移 `(w−h)/2 = 0.087w`，411dp 机型即 **15dp** ——
  /// 而「首帧与原生那一帧同位」正是本 Epic 存在的理由，偏 15dp 就等于交接处跳一下。
  /// 字标（用 `wordmarkAspect`）与光晕一直是对的，只有 mark 曾用错。
  static const double markAspect = 389.32 / 321.81;

  /// 原生启动屏里 mark 的**可视宽度**，逻辑像素，**与屏宽无关**（决策 D2，2026-08-04）。
  ///
  /// 由三端资产反推得出、彼此一致：`splash_mark_white.png` 的 alpha bbox 占画布 66.67%，
  /// 而 Android 12+ 的 `windowSplashScreenAnimatedIcon` 画布是 288dp、Android<12 的
  /// mdpi `splash.png` 是 288px、iOS storyboard 的 imageView 是 `contentMode=center` 的
  /// 288pt 1x 资产 ⇒ 三端可视宽都是 288 × 2/3 = **192**。
  ///
  /// ⚠️ 为什么首帧不能直接用 `markWidthRatio`（42% 屏宽）：两者只在屏宽 457dp 时相等。
  /// 411dp 机型是 172.6dp（小 10%）、iPhone 15 是 165pt（小 14%）、iPhone SE 是 134pt
  /// （**小 30%**）—— 「同尺寸交接」在任何常见机型上都不成立，交接处会跳一下尺寸。
  /// 现在首帧用这个绝对值与原生对齐，再在 B1 拍里插值到相对尺寸（NFR-17 的相对化在终态成立）。
  static const double markHandoffWidth = 192;

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

  Timer? _holdTimer;
  Timer? _progressTimer;
  Timer? _hintTimer;
  Timer? _deadlineTimer;
  bool _reduceMotion = false;
  bool _started = false;

  /// 会话恢复是否已完成（成功或失败都算完成）。
  bool _ready = false;

  /// 入场动效的停留时长是否已走完。
  bool _holdDone = false;

  /// 是否已把控制权交给 [SplashPage.onComplete]（只交一次）。
  bool _completed = false;

  /// app 是否不在前台。
  ///
  /// 为什么需要它（code-review 2026-08-04）：**Dart 的 `Timer` 在后台照常触发，而 ticker
  /// 因为没有帧而停滞**。于是「用户在动效期间切后台」会导致：hold 到点即交棒 → 上报 T-1
  /// 与 `$screen` 并 `ctx.go` **全发生在用户看不见的时候**（启动埋点因此虚高、落地页在后台
  /// 就换掉了）；回到前台时 `_master` 又因单帧巨大 elapsed 直接跳到终态 ⇒ 入场叙事整段丢失，
  /// 「动效播完 1.54s 仍未就绪才出现进度线」的语义也随之失去意义。
  bool _paused = false;

  /// 后台期间到点的交棒被压住了，回前台后要补做。
  bool _completePending = false;

  late final AppLifecycleListener _lifecycle;

  /// 等待指示的可见性。**快网路径下两者恒为 false** —— 阈值设计的全部意义就在这（AC6）。
  bool _showProgressLine = false;
  bool _showSlowHint = false;

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
    _start();
  }

  /// 载入字标资产并按 9 拍切分：`[T+猫尾+2 条动感短划]` 为第 1 拍，其后 `a i l t o p i a`
  /// 各 1 拍（母文件共 11 条可见路径 → 9 拍）。失败则整块一次性显示，不阻断启动。
  Future<void> _loadWordmark(AssetBundle bundle) async {
    // ⚠️ 三条兜底路径都必须落到「整块一次性显示」，**不能落到「什么都不显示」**
    // （code-review 2026-08-04）：`_wordmarkBeats` 为 null 时 `_wordmark()` 返回空盒，
    // 而终态 mark 的 opacity 已是 0 ⇒ 整个启动屏只剩一行标语、**品牌名全程缺席**，
    // 且线上只有一行 debugPrint、没有任何可见告警。文件原注释声称的
    // 「失败则整块一次性显示」当时并未实现。
    String? raw;
    try {
      raw = await bundle.loadString('assets/brand/wordmark_brand.svg');
      // ① 连 `<svg …>` 头都匹配不到 → 整块显示（原先这里的 `!` 会直接抛进 catch）
      final head = RegExp(r'<svg[^>]*>').firstMatch(raw)?.group(0);
      if (head == null) {
        _fallbackToWholeWordmark(raw, 'svg 头未匹配');
        return;
      }
      final paths = RegExp(r'<path\b[^>]*/>')
          .allMatches(raw)
          .map((m) => m.group(0)!)
          .toList(growable: false);
      // ② 一条 path 都没抓到（例如资产改成非自闭合的 `<path …></path>` 写法）→ 整块显示。
      //    原先会走进下面的 else 分支，用空列表拼出一个**没有任何路径的空 SVG**，
      //    既不进 catch 也不打日志，静默变成空白字标。
      if (paths.isEmpty) {
        _fallbackToWholeWordmark(raw, 'path 零命中（资产写法可能变了）');
        return;
      }
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
      _beginAnimation(); // 资产就位 → 立刻起拍（若宽限期还没到）
    } catch (e) {
      // ③ 载入/解析异常 → 若已拿到原文就整块显示；连原文都没有才无字标可显。
      _fallbackToWholeWordmark(raw, '分拍失败: $e');
    }
  }

  /// 起拍前等字标资产的宽限期上限。到点无论资产是否就位都起拍，绝不让启动屏被一个资产卡住。
  static const Duration _wordmarkGrace = Duration(milliseconds: 260);

  Timer? _graceTimer;

  /// 字标资产就位（或宽限期到点）后起拍。两条路径都只起一次。
  void _startAnimationWhenWordmarkReady() {
    if (_wordmarkBeats != null) {
      _master.forward();
      return;
    }
    _graceTimer = Timer(_wordmarkGrace, _beginAnimation);
  }

  void _beginAnimation() {
    _graceTimer?.cancel();
    _graceTimer = null;
    if (!mounted || _reduceMotion || _master.isAnimating || _master.value > 0) return;
    _master.forward();
  }

  /// 退化为「整块一次性显示」：把整份 SVG 当作**唯一一拍**。
  /// 动效退化成一次淡入，但**品牌名一定出现** —— 这才是原注释承诺的行为。
  void _fallbackToWholeWordmark(String? raw, String why) {
    debugPrint('[Splash] wordmark 退化为整块显示（$why）');
    if (!mounted || raw == null) {
      _beginAnimation(); // 连原文都没有 → 也不能卡住启动屏
      return;
    }
    setState(() => _wordmarkBeats = <String>[raw]);
    _beginAnimation();
  }

  /// 启动：**并行**发起会话恢复 + 播动效 + 排三个等待反馈时点。
  ///
  /// 与改前的差异（Story 7.3 / FR-89）：
  /// - **取消「当天只播一次」门控**（决策 C-3）：不再读 prefs 判断 `splashLastShownDate`，
  ///   每次冷启动都播。`reduce-motion` 仍直落终态（那是无障碍要求，与门控无关）。
  /// - ✅ **连带修掉缺陷 B-7**：改前 `_decided` 标志要等 `AppPrefs.create()`（超时 300ms）
  ///   返回后才渲染 mark，造成首帧最多 300ms 空窗。现在 prefs 已不是启动依赖，空窗消失。
  void _start() {
    _lifecycle = AppLifecycleListener(onStateChange: _onLifecycleChange);
    final animate = !_reduceMotion;
    if (animate) {
      // ⚠️ **等字标资产就位（或极短的宽限期到点）再起拍**（code-review 2026-08-04）：
      // `_loadWordmark` 与动效原先同帧发起、互不等待。低端机首次冷启动若 `loadString` 落地时
      // `_master` 已越过 B2 的 480/1540 区间，前几拍的 t 已接近 1 ⇒ 字标会「半途整段冒出」
      // 而不是逐拍写出，AC2 的 9 拍叙事在慢机上不成立。
      //
      // 宽限期上限 [_wordmarkGrace]：资产万一迟迟不来（或失败），照常起拍 ——
      // 那时 `_wordmark()` 会退化为整块显示，绝不让启动屏因为一个资产而卡住。
      // 期间 mark 停在原生交接位不动，视觉上就是「原生启动屏又持续了一小会儿」，不突兀。
      _startAnimationWhenWordmarkReady();
    } else {
      _master.value = 1.0; // reduce-motion 直落终态（AC7）
    }

    // 并行发起会话恢复。失败也算「完成」—— 兜底分流由路由层按当时已知态处理。
    //
    // ⚠️ 用 `Future.sync` 包一层（code-review 2026-08-04）：`prepareSession` 实体是
    // `ref.read(authControllerProvider.notifier).ensureRestored()`，若 notifier **构建期**
    // 同步抛错，异常会直接从 `_start()` 冒出 `didChangeDependencies` —— `.catchError` 接不到，
    // 启动屏当场红屏/黑屏，`_onReady` 永不被调、5s 兜底也随之失效（连兜底都没了）。
    // 用 `Future.sync` 而不是 `Future(...)`：后者会额外排一个零延时 Timer，
    // 让「首帧只 pump 一次」的用例报 pending timer；`Future.sync` 立即执行、同样接住同步抛出。
    // `whenComplete`：成功与失败都算「就绪」（失败时兜底分流由路由层按当时已知态处理）。
    // 原写法 `.then(...).catchError(...)` 里的 catchError 在恢复内部已 catch-all 的情况下是死路径，
    // 且若 `_onReady()` 自己抛错还会被再调一次（code-review 2026-08-04）。
    Future<void>.sync(() => widget.prepareSession?.call() ?? Future<void>.value())
        .whenComplete(_onReady);

    // Debug-only：DEV_ROUTE=/splash 时定屏不跳转，供逐帧对稿。
    const devPin = kDebugMode && String.fromEnvironment('DEV_ROUTE') == '/splash';

    final hold = animate ? SplashPage.animatedHold : SplashPage.staticHold;
    _holdTimer = Timer(hold, () {
      if (!mounted) return;
      _holdDone = true;
      if (!devPin) _maybeComplete();
    });

    // 进度线 / 慢网提示：到点时若仍未就绪才显示。快网路径下这两个回调什么也不做。
    _progressTimer = Timer(SplashPage.progressLineAt, () {
      if (!mounted || _ready) return;
      setState(() => _showProgressLine = true);
    });
    _hintTimer = Timer(SplashPage.slowHintAt, () {
      if (!mounted || _ready) return;
      setState(() => _showSlowHint = true);
    });

    // 兜底：到 5s 无论就绪与否都放行（决策 D-2）。
    _deadlineTimer = Timer(SplashPage.readyDeadline, () {
      if (!mounted) return;
      if (!devPin) _maybeComplete(force: true);
    });
  }

  void _onReady() {
    if (!mounted || _ready) return;
    setState(() {
      _ready = true;
      // 已就绪 → 收掉等待指示（若曾出现）。
      _showProgressLine = false;
      _showSlowHint = false;
    });
    const devPin = kDebugMode && String.fromEnvironment('DEV_ROUTE') == '/splash';
    if (!devPin) _maybeComplete();
  }

  /// 交棒条件：**动效停留走完** 且（**已就绪** 或 **到了 5s 兜底**）。
  ///
  /// ⚠️ 不改 [SplashPage.onComplete] 的调用契约 —— 路由层仍在其中做分流
  /// （pending 深链 > 落地矩阵）。本 Story 只改「什么时候交棒、交棒前显示什么」。
  void _maybeComplete({bool force = false}) {
    if (_completed || !_holdDone || !(force || _ready)) return;
    // 后台不交棒（见 [_paused]）：压到回前台再做，避免在用户看不见时上报落地埋点并换页。
    if (_paused) {
      _completePending = true;
      return;
    }
    _completed = true;
    (widget.onComplete ?? () => context.go('/home'))();
  }

  /// 前后台切换。后台期间**压住交棒**，回前台后把入场从头补播一次再走原流程。
  void _onLifecycleChange(AppLifecycleState state) {
    final wasPaused = _paused;
    _paused = state != AppLifecycleState.resumed;
    if (!wasPaused || _paused || _completed || !mounted) return;

    // 回到前台。后台期间 ticker 停滞、单帧巨大 elapsed 会把 `_master` 直接推到终态 ——
    // 用户其实一帧动效都没看到。从头补播，并重新计 hold（reduce-motion 保持直落终态）。
    if (!_reduceMotion) {
      _master.value = 0;
      _master.forward();
      _holdDone = false;
      _holdTimer?.cancel();
      _holdTimer = Timer(SplashPage.animatedHold, () {
        if (!mounted) return;
        _holdDone = true;
        _maybeComplete();
      });
      // 已就绪但还没交棒 ⇒ 等补播的 hold 到点自然交棒；压住的那次不必立刻放行。
      _completePending = false;
      return;
    }
    if (_completePending) {
      _completePending = false;
      _maybeComplete(force: true);
    }
  }

  @override
  void dispose() {
    _holdTimer?.cancel();
    _progressTimer?.cancel();
    _hintTimer?.cancel();
    _deadlineTimer?.cancel();
    _graceTimer?.cancel();
    if (_started) _lifecycle.dispose(); // `_started` 为假时 _start() 未跑过、listener 未建
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
          _markHandoff(size, centerY, handoffY),
          // B2/B3 · 字标：logo 中心落 43%（标语不参与该计算，AC5/X-2）。
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
          Positioned(
              top: centerY + wordmarkH / 2 + _taglineGap,
              left: 0,
              right: 0,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [_taglineWidget(l10n, size.width * SplashPage.taglineWidthRatio)],
              ),
            ),
          // 底部容器：进度线 + 慢网提示共用。**高度预留，出现/消失不引起布局位移**（AC4）。
          Positioned(
            bottom: SplashPage.bottomInset,
            left: 0,
            right: 0,
            child: _waitingSlot(l10n, size.width * SplashPage.taglineWidthRatio),
          ),
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
    // 决策 D2（2026-08-04）：**首帧宽度与原生一致（绝对 192），B1 期间插值到相对尺寸**。
    // 屏宽 457dp 时两者相等、无插值；窄屏表现为「起始略大再收到相对尺寸」，
    // 与既有的 1→0.7 缩小是同向的，不会看成两段动作。
    final relativeW = size.width * SplashPage.markWidthRatio;
    return AnimatedBuilder(
      animation: _b1,
      builder: (_, _) {
        final t = _b1.value;
        final w = SplashPage.markHandoffWidth +
            (relativeW - SplashPage.markHandoffWidth) * t;
        // 用**渲染高度**的一半抵扣才是真居中（见 [SplashPage.markAspect] 的说明）。
        final h = w / SplashPage.markAspect;
        return Positioned(
          top: (handoffY + (centerY - handoffY) * t) - h / 2,
          left: 0,
          right: 0,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Opacity(
                // 终态 opacity 0 —— 「终态不出现 mark」（AC3）由此实现，而非移除节点。
                opacity: (1 - t).clamp(0.0, 1.0),
                child: Transform.scale(
                  scale: 1 - 0.30 * t,
                  // 宽度每帧在变，故不能再用 AnimatedBuilder 的 `child` 缓存（那是为「子树不随
                  // 动画变化」准备的优化）。SVG 已被 flutter_svg 缓存，重建成本是布局层的。
                  child: SvgPicture.asset('assets/brand/mark_brand.svg', width: w),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// B2 · 字标 9 拍逐组写出：每组 `blur 4→0` / `y 10→0`，错开 85ms。
  Widget _wordmark(double w) {
    final beats = _wordmarkBeats;
    if (beats == null) {
      return SizedBox(
          key: SplashPage.wordmarkBoxKey, width: w, height: w / SplashPage.wordmarkAspect);
    }
    return SizedBox(
      key: SplashPage.wordmarkBoxKey,
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

  /// 等待反馈容器（进度线 + 慢网提示）。
  ///
  /// **「出现即信息」**（设计侧挑战 X-4）：改前是一条从头转到尾的常驻 spinner，不反映任何
  /// 真实进度（真实等待由 `Timer` 控制），属假反馈 —— Story 7.2 已把它删掉。现在只有
  /// 「动效播完（1.54s）仍未就绪」才出现进度线，故**它的出现本身就是信息**。
  ///
  /// 两个元素靠 opacity 显隐、且提示 `Text` **恒在树中** ⇒ 高度与是否可见无关 ⇒ 无布局位移（AC4）。
  ///
  /// ⚠️ 高度是 `minHeight` 而**不是**写死的 `height`（code-review 2026-08-04）：
  /// [SplashPage.bottomSlotHeight] = 56 是按「12.5px × 行高 1.4 的单行」算出来的，没给
  /// 系统字号放大（NFR-13 支持到 1.3）和窄屏换行留任何余量。实测溢出：
  /// 360dp + 印尼语 + **默认字号** 就溢出 14px、360dp ×1.3 溢出 29px、411dp 印尼语 ×1.15 溢出 20px。
  /// 而且提示恒在树中 ⇒ **快网用户从没看见过这条提示，也一样溢出**。
  /// 本容器由 `Positioned(bottom+left+right)` 承载 ⇒ 高度方向无界，向上长不会撞到标语。
  Widget _waitingSlot(AppLocalizations l10n, double w) {
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: SplashPage.bottomSlotHeight),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedOpacity(
            opacity: _showProgressLine ? 1 : 0,
            duration: const Duration(milliseconds: 200),
            child: SizedBox(
              width: w * 0.5,
              height: 2,
              // ⚠️ **只在真要显示时才构造**（code-review 2026-08-04）：`_IndeterminateLine`
              // 的控制器在字段初始化就 `..repeat()`，而它内部是 `AnimatedBuilder` 包
              // `LayoutBuilder` ⇒ 每帧触发一次 layout 失效。快网用户 100% 走「这条线永不显示」
              // 的路径（AC6），原先却让它从首帧起一直转、开销正压在冷启动最吃紧的头 1.7 秒。
              // 高度仍由外层 SizedBox 固定，所以出现/消失依旧不产生布局位移（AC4）。
              child: _showProgressLine ? const _IndeterminateLine() : null,
            ),
          ),
          const SizedBox(height: 14),
          AnimatedOpacity(
            opacity: _showSlowHint ? 1 : 0,
            duration: const Duration(milliseconds: 300),
            child: SizedBox(
              width: w,
              child: Text(
                l10n.splashSlowNetworkHint,
                textAlign: TextAlign.center,
                // 同族 Fraunces，比标语更小更淡（决策 C-9）：`opsz` 已被固化进子集字体
                // （fvar 只剩 SOFT/WONK 两轴）⇒ 只设这两轴。
                // ⚠️ 7-3 AC5 要求的 **opsz 12 不可达**：提示实际以标语的光学尺寸渲染。
                //    决策 D5（code-review 2026-08-04）：不为一行 12.5px 的提示重新产字体，接受偏差。
                //    原注释写「opsz 12 已烘入」与 pubspec 的「opsz 16」互相矛盾，两处均已订正。
                // splash 内只允许出现一款字体（NFR-16），故不另引字型；
                // `fontFamilyFallback` 仅为**缺字兜底**（子集只有 97 码位，见 pubspec 注释）——
                // 掉到系统字体总比显示豆腐块好，但那一行会出现两种字形，属退化不是设计。
                style: TextStyle(
                  fontFamily: 'Fraunces',
                  fontFamilyFallback: const <String>['Poppins'],
                  fontVariations: const [
                    FontVariation('SOFT', 100),
                    FontVariation('WONK', 1),
                  ],
                  fontWeight: FontWeight.w500,
                  fontSize: 12.5,
                  height: 1.4,
                  color: const Color(0xFFFFFFFF).withValues(alpha: 0.55),
                ),
              ),
            ),
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
          // wght 500 与 opsz 已烘死进字体（见 pubspec 说明；**具体 opsz 值无法从文件反查**，
          // 原注释写的 16 与 splash 慢网提示处写的 12 互相矛盾，两处已于 2026-08-04 订正），
          // 故此处只设 SOFT / WONK 两轴。
          style: TextStyle(
            fontFamily: 'Fraunces',
            // 缺字兜底（子集仅 97 码位，见 pubspec 注释）—— 换文案时若带入破折号或弯双引号，
            // 至少掉到系统字体而不是豆腐块。code-review 2026-08-04 补。
            fontFamilyFallback: const <String>['Poppins'],
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

/// 细长的不确定进度线（承载「正在加载」的真实语义，故 reduce-motion 下**保留**动画 ——
/// 无限动画只允许用于 loading 指示，而它正是 loading 指示，不是装饰，见设计侧挑战 X-3）。
class _IndeterminateLine extends StatefulWidget {
  const _IndeterminateLine();

  @override
  State<_IndeterminateLine> createState() => _IndeterminateLineState();
}

class _IndeterminateLineState extends State<_IndeterminateLine>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1100))
        ..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(1),
      child: ColoredBox(
        color: const Color(0xFFFFFFFF).withValues(alpha: 0.18),
        child: AnimatedBuilder(
          animation: _c,
          builder: (context, _) {
            return LayoutBuilder(
              builder: (context, c) {
                final w = c.maxWidth;
                const frac = 0.38;
                // 往复扫动：0→1 走一趟，1→0 再回来（用三角波，避免"跳回起点"的突变）。
                final t = _c.value <= 0.5 ? _c.value * 2 : (1 - _c.value) * 2;
                return Stack(
                  children: [
                    Positioned(
                      left: (w * (1 - frac)) * t,
                      child: Container(
                        width: w * frac,
                        height: 2,
                        decoration: BoxDecoration(
                          color: const Color(0xFFFFFFFF).withValues(alpha: 0.92),
                          borderRadius: BorderRadius.circular(1),
                        ),
                      ),
                    ),
                  ],
                );
              },
            );
          },
        ),
      ),
    );
  }
}
