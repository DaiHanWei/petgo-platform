import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/analytics/analytics.dart';
import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import '../widgets/app_image.dart';
import 'lightbox_gestures.dart';

/// 全屏图片查看器（灯箱）。V1.3.0 批次 A · Story 3.1（FR-115 · AD-A14）。
///
/// ## 为什么在 `shared/` 而不是 `content/`
/// 改前它是 `content_detail_page.dart` 里的私有 `_Lightbox`。批次 B1 的场所照片（FR-112）
/// 要用同一个查看器 —— 留在 `content` 里，`places` 就得反向依赖 `content` 才能复用，
/// 否则只能再写一个。**本组件是批次 B1 的前置交付物，接口不是内部实现细节。**
///
/// ## 🔴 接口只认「URL 列表 + 下标 + 两个通用参数」（AD-A14.2）
/// **不接受也不感知**内容帖、场所或任何业务实体：没有 postId、没有 placeId、
/// 没有「这是什么东西的图」。给它一串 URL，它就放一串图。
///
/// 两个参数**是必需的，不是耦合**（AD-A14.2b，均为通用字符串形状）：
/// - [heroTagPrefix] —— 飞入动画要 Hero tag，而 **tag 从 URL 推导会炸**：
///   B1 场所页是「头图 + 照片网格」，同一张图出现两次 → 同 tag Hero 同屏 →
///   Flutter 直接抛异常。tag 必须由调用方给前缀来保证唯一。
/// - [source] —— 否则 `lightbox_opened` 分不清帖子与场所，FR-115 的指标从 B1 上线起就断裂。
///   这是对 AD-A23「不得增删属性」的**已登记显式例外**。
///
/// ## 本 story 的边界（AC7）
/// - 四个手势（下滑关闭 / 双击缩放 / 放大态防误翻页 / 图内平移）属 **Story 3.2**；
/// - 过渡动画（Hero 飞入飞出）、加载态与失败重试、埋点属 **Story 3.3**
///   —— [heroTagPrefix] 与 [source] 在本 story 只是**被接住并记下**，3.3 才开始消费；
/// - **不为场所场景做任何特化**（B1 工程师在其基础上接入）；
/// - 病例图查看器（`shared/widgets/case_image_viewer.dart`）与分享卡预览**不动**。
///
/// ## 明确不做（AC6 · AD-A15.7）
/// **长按保存到相册**。盗用顾虑 + 相册权限 + 与分享卡定位冲突：要保存只能走带水印的分享卡。
class ImageLightbox extends StatefulWidget {
  const ImageLightbox({
    super.key,
    required this.urls,
    required this.initialIndex,
    required this.heroTagPrefix,
    required this.source,
  });

  /// 要展示的**原图** URL（不是缩略图 —— 「看清楚」正是打开它的唯一理由）。
  final List<String> urls;

  /// 初始页：用户点的是哪张就从哪张开始（AC2）。越界自动夹回合法范围。
  final int initialIndex;

  /// Hero tag 前缀，由调用方保证唯一（见类注释）。
  final String heroTagPrefix;

  /// 埋点来源。本批次值域只有 `content_detail`，B1 接入时增补（AD-A26.3）。
  final String source;

  /// 打开查看器。调用方一律走这个入口，不要自己 push ——
  /// 路由形态（是否透明、是否全屏）是本组件的事，Story 3.3 还会改它。
  /// Hero 飞行时长（进出场同一档）。
  static const Duration _flight = Duration(milliseconds: 260);

  /// 返回**关闭时停留的那一张**的下标（被系统返回键关掉时为 null）。
  ///
  /// 调用方拿它把自己的缩略图轮播同步过去 —— 否则用户滑到第 5 张再关闭，
  /// 图会缩回第 1 张缩略图的位置，那看上去像"飞错地方了"（AC1）。
  static Future<int?> open(
    BuildContext context, {
    required List<String> urls,
    required int initialIndex,
    required String heroTagPrefix,
    required String source,
  }) {
    if (urls.isEmpty) return Future<int?>.value();
    // 🔴 bug 507：键盘弹着（评论框在输入）时点图，这一下只负责**收键盘**，不开大图 ——
    // 用户的意图多半是「我不打了」，直接盖一个全屏灯箱上去像误触。再点一次才开。
    // ⚠️ 用 View 的 viewInsets，不用 MediaQuery.viewInsetsOf(context)：调用点在
    //    Scaffold body 里，resizeToAvoidBottomInset 会把 body 的 viewInsets.bottom 抹成 0。
    // 放在这个统一入口里，帖子详情与场所详情两个调用方一起覆盖。
    final FocusNode? focus = FocusManager.instance.primaryFocus;
    final bool keyboardUp = View.of(context).viewInsets.bottom > 0;
    final BuildContext? focusCtx = focus?.context;
    final bool editing = focusCtx != null &&
        (focusCtx.widget is EditableText ||
            focusCtx.findAncestorWidgetOfExactType<EditableText>() != null);
    if (keyboardUp || editing) {
      focus?.unfocus();
      // 键盘已收起、只是焦点还留在输入框（如 Android 返回键收了键盘）时照常打开：
      // 先 unfocus 清掉所在 scope 的焦点记录，灯箱关闭 pop 回来时就不会把
      // 焦点还给评论框、把键盘又顶起来。
      if (keyboardUp) return Future<int?>.value();
    }
    return Navigator.of(context).push(PageRouteBuilder<int>(
      // 🔴 `opaque: false` 是 Story 3.2 下滑关闭（AC1「背景随拖拽渐透明」）的**前提**：
      // 不透明路由之下的那一页根本不参与绘制，把黑底调淡只会露出一片虚空，
      // 而用户期待看见的是自己刚才那一页正在露出来。
      opaque: false,
      barrierColor: null,
      // Story 3.3 · AC1：Hero 双向飞行需要一段非零的路由过渡时长，
      // 否则「从缩略图原位放大飞入 / 关闭时缩回原位」两头都没有时间发生。
      transitionDuration: _flight,
      reverseTransitionDuration: _flight,
      pageBuilder: (_, _, _) => ImageLightbox(
        urls: urls,
        initialIndex: initialIndex,
        heroTagPrefix: heroTagPrefix,
        source: source,
      ),
    ));
  }

  @override
  State<ImageLightbox> createState() => _ImageLightboxState();
}

/// 第 [index] 张图的 Hero tag。
///
/// 🔴 **tag 由前缀 + 下标拼出，绝不从 URL 推导**（AD-A14.2b）：同一张图在一个页面里
/// 出现两次（场所页的「头图 + 照片网格」）时，URL 相同 → tag 相同 → 同屏两个同 tag Hero →
/// Flutter 抛异常。缩略图一侧与查看器一侧必须用**这同一个函数**算 tag，否则飞不起来。
String lightboxHeroTag(String prefix, int index) => '$prefix#$index';

class _ImageLightboxState extends State<ImageLightbox> with SingleTickerProviderStateMixin {
  late final PageController _controller;

  /// 回弹动画（下滑没过阈值时把图送回原位）。直接 setState 归零是"啪"地跳回去。
  late final AnimationController _rebound;

  /// 每一页各自的缩放/平移矩阵。**按页存**：翻到下一张时上一张的放大态要么带过去、
  /// 要么各记各的 —— 各记各的更符合直觉（回头翻回去还是你离开时的样子）。
  final Map<int, TransformationController> _transforms = {};

  late int _current;

  /// 下滑关闭的当前位移（>0 向下）。
  double _dragDy = 0;

  /// 当前页是否已放大。**手势优先级的第一个输入**，所以它必须随矩阵实时更新。
  bool _zoomed = false;

  /// 当前页在横向上是否已经贴边（拖不动了）——「到边缘续拖才翻页」的判据。
  bool _atHorizontalEdge = true;

  /// 双击落点，**全局坐标**（用于以双击点为中心缩放）。
  ///
  /// 🔴 不直接拿 GestureDetector 的 localPosition 算矩阵，统一经 InteractiveViewer 的
  /// RenderBox 换算：L3 之后它撑满整页（图在其内居中），两套坐标恰好重合，
  /// 但下拖缩放（Transform.scale）期间仍可能有偏差，换算一次最稳。
  Offset _doubleTapGlobal = Offset.zero;

  /// 每页 InteractiveViewer 的锚点：量它的尺寸与位置，把手势坐标换算进矩阵坐标系。
  final Map<int, GlobalKey> _viewerKeys = {};

  RenderBox? _viewerBoxOf(int index) {
    final ro = _viewerKeys[index]?.currentContext?.findRenderObject();
    return ro is RenderBox && ro.hasSize ? ro : null;
  }

  /// 每页**图片本体**（contain 后的大小，居中于整页 viewer 内）的锚点：
  /// 双击落在黑边上时，据它把落点夹回图片边缘。
  final Map<int, GlobalKey> _imageKeys = {};

  RenderBox? _imageBoxOf(int index) {
    final ro = _imageKeys[index]?.currentContext?.findRenderObject();
    return ro is RenderBox && ro.hasSize ? ro : null;
  }

  /// 单击关闭的延时器：窗口内来了第二下就是双击（见 [kLightboxSingleTapDelay]）。
  Timer? _singleTapTimer;

  static const double _maxScale = 4;

  /// 正常尺寸下两指收缩退出（bug 506）。
  ///
  /// ⚠️ 不靠 InteractiveViewer 的 minScale 缩到 1 以下：它的有效下限还受 boundary 约束
  /// （boundaryMargin 为 0 时 = 视口 / child = 1，调 minScale 无效），而放开 boundary
  /// 又会破坏放大态「贴边才翻页」的平移范围。所以改为读手势自身的相对倍数
  /// （[ScaleUpdateDetails.scale]），在外层 Transform 上把图跟手缩小；
  /// 松手时低于 [_pinchCloseScale] 即关闭，否则弹回 1。
  static const double _pinchMinScale = 0.6;
  static const double _pinchCloseScale = 0.9;

  /// 双指收缩的跟手倍数（仅从正常尺寸开始的收缩才会 < 1）。
  double _pinchScale = 1;
  bool _pinching = false;

  /// 屏上按下的指针（bug 506）。≥2 指 = 双指缩放，**优先于**翻页与下滑关闭：
  /// 这两个拖拽识别器与 InteractiveViewer 的缩放抢同一手势，不让位的话拖拽先赢，
  /// 双指根本缩放不了。
  final Set<int> _pointers = {};
  bool _multiTouch = false;

  /// 本次缩放交互开始时的倍数：只有从「正常尺寸」开始的双指收缩才算关闭手势，
  /// 从放大态缩回来不能顺手把灯箱关掉。
  double _interactionStartScale = 1;

  void _onPointerDown(PointerDownEvent e) {
    _pointers.add(e.pointer);
    if (_pointers.length >= 2 && !_multiTouch) setState(() => _multiTouch = true);
  }

  void _onPointerGone(PointerEvent e) {
    _pointers.remove(e.pointer);
    if (_pointers.isEmpty && _multiTouch) setState(() => _multiTouch = false);
  }

  void _onInteractionStart(ScaleStartDetails d) {
    _interactionStartScale = _transformOf(_current).value.getMaxScaleOnAxis();
  }

  void _onInteractionUpdate(ScaleUpdateDetails d) {
    // 只认「从正常尺寸开始」的双指收缩；从放大态缩回来是 InteractiveViewer 自己的事。
    if (d.pointerCount < 2 || _interactionStartScale > 1.01) return;
    final double next = d.scale.clamp(_pinchMinScale, 1.0);
    if (next != _pinchScale || !_pinching) {
      setState(() {
        _pinching = true;
        _pinchScale = next;
      });
    }
  }

  void _onInteractionEnd(ScaleEndDetails d) {
    if (!_pinching) return;
    if (_pinchScale < _pinchCloseScale) {
      // 保持缩小态直接关：Hero 从缩小后的位置飞回缩略图。
      _close(LightboxDismissGesture.pinch);
      return;
    }
    // 没到阈值：弹回正常尺寸（外层 AnimatedScale 负责过渡）。
    setState(() {
      _pinching = false;
      _pinchScale = 1;
    });
  }
  static const double _doubleTapScale = 2.5;

  /// 本次会话用到的**最大放大倍数**（埋点 `max_zoom_used`，Story 3.3 · AC5）。
  double _maxZoomUsed = 1;

  /// 关闭方式（埋点 `dismiss_gesture`）。默认 [LightboxDismissGesture.systemBack]：
  /// 🔴 凡是没被 ✕ / 单击 / 下滑显式认领的退出，都是从系统那边走掉的 ——
  /// 这条路径此前从不上报，关闭方式的分布因此一直是错的。
  LightboxDismissGesture _dismissGesture = LightboxDismissGesture.systemBack;

  /// 每页的重试计数：+1 就换掉 Image 的 key，强制重新发起加载（AC4）。
  final Map<int, int> _retryTicks = {};

  /// 已进入失败态的页。失败时要把底下那张模糊缩略图撤掉（纯黑底）——
  /// 模糊图还垫在下面的话，提示文字压在一团色块上，既难读又像「其实加载出来了」。
  final Set<int> _failed = {};

  int get _safeInitialIndex =>
      widget.urls.isEmpty ? 0 : widget.initialIndex.clamp(0, widget.urls.length - 1);

  TransformationController _transformOf(int index) =>
      _transforms.putIfAbsent(index, () {
        final c = TransformationController();
        c.addListener(() {
          if (index == _current) _syncZoomState(c);
        });
        return c;
      });

  /// 把矩阵状态折算成手势判定需要的两个布尔量。
  void _syncZoomState(TransformationController c) {
    final m = c.value;
    final double scale = m.getMaxScaleOnAxis();
    final double tx = m.getTranslation().x;
    // 🔴 用 InteractiveViewer 自己的宽，不是整页宽：竖图比屏幕窄时两者不等，
    //    拿整页宽算永远判不到「贴右边」，放大后没法续拖翻页。
    final double width = _viewerBoxOf(_current)?.size.width ??
        context.size?.width ??
        MediaQuery.sizeOf(context).width;
    // InteractiveViewer 的平移量落在 [-width*(scale-1), 0]：0 = 贴左边，最小值 = 贴右边。
    final double maxPan = width * (scale - 1);
    final bool zoomed = scale > 1.01;
    if (scale > _maxZoomUsed) _maxZoomUsed = scale;
    final bool atEdge = !zoomed || tx >= -0.5 || tx <= -maxPan + 0.5;
    if (zoomed != _zoomed || atEdge != _atHorizontalEdge) {
      setState(() {
        _zoomed = zoomed;
        _atHorizontalEdge = atEdge;
      });
    }
  }

  @override
  void initState() {
    super.initState();
    _current = _safeInitialIndex;
    _controller = PageController(initialPage: _current);
    _rebound = AnimationController(vsync: this, duration: const Duration(milliseconds: 180))
      ..addListener(() {
        if (!mounted) return;
        setState(() => _dragDy = _reboundFrom * (1 - _rebound.value));
      });
    // AC1 全屏沉浸态：把系统栏收起来，图片铺满整块屏幕。
    // 改前这里是一个黑色 AppBar + 保留状态栏 —— 用户对比小红书后的原话是
    // 「点开放大不是全屏放大」，说的就是这一层。
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
    // AC5：打开即上报。source 由调用方给（AD-A14.2b 登记的显式例外）——
    // 没有它，B1 的场所照片上线后这两个事件就分不清帖子与场所。
    Analytics.capture('lightbox_opened', {'source': widget.source});
  }

  double _reboundFrom = 0;

  @override
  void dispose() {
    // 🔴 AC3：恢复系统栏**放在 dispose**，因为它对所有退出路径都会跑到 ——
    // ✕、单击图片/黑边、系统返回键、iOS 侧滑、上层把整条路由掀掉，都会走到这里。
    // 放进 ✕ 的 onTap 里只覆盖一条路，其余路径会把用户留在一个没有状态栏的界面上，
    // 而那个界面已经不是灯箱了。
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.manual, overlays: SystemUiOverlay.values);
    // 🔴 AC5：关闭上报同样放在 dispose —— 与恢复系统栏同一个理由。
    // 挂在 ✕ 的回调里就只有 ✕ 这一条会上报，系统返回键与 iOS 侧滑**静默丢失**，
    // 而那恰恰是本 AC 点名「此前没人管」的那条路径。
    Analytics.capture('lightbox_dismissed', {
      'source': widget.source,
      'dismiss_gesture': _dismissGesture.wire,
      'max_zoom_used': double.parse(_maxZoomUsed.toStringAsFixed(2)),
    });
    _singleTapTimer?.cancel();
    _rebound.dispose();
    for (final c in _transforms.values) {
      c.dispose();
    }
    _controller.dispose();
    super.dispose();
  }

  /// 统一的关闭出口：记下**是怎么关的**（埋点值域见 LightboxDismissGesture），并把停留页带回给调用方
  /// （调用方据此把缩略图轮播同步过去，Hero 才飞得回正确那一格）。
  void _close(LightboxDismissGesture gesture) {
    _dismissGesture = gesture;
    Navigator.of(context).pop(_current);
  }

  // ===== 手势：单击关闭 / 双击缩放（AC3 · AC6）=====

  /// 自己仲裁单击与双击，不用 `onTap` + `onDoubleTap`（那会让单击等满 300ms，AC6）。
  void _handleTapUp(TapUpDetails details) {
    final Timer? pending = _singleTapTimer;
    if (pending != null && pending.isActive) {
      // 窗口内的第二下 = 双击 → 撤销待执行的关闭，改为缩放。
      pending.cancel();
      _singleTapTimer = null;
      _doubleTapGlobal = details.globalPosition;
      _toggleZoom();
      return;
    }
    _singleTapTimer = Timer(kLightboxSingleTapDelay, () {
      _singleTapTimer = null;
      if (mounted) _close(LightboxDismissGesture.tap);
    });
  }

  /// AC3：在「适应屏幕」与 2.5 倍之间切换，**以双击点为中心**。
  void _toggleZoom() {
    final c = _transformOf(_current);
    if (_zoomed) {
      c.value = Matrix4.identity();
      return;
    }
    const double s = _doubleTapScale;
    // 双击点换算进 InteractiveViewer（整页）的坐标系；点在黑边上就夹到**图片**边缘 ——
    // 图片矩形在 viewer 之内，所以平移量仍落在 [-size*(s-1), 0] 内，不会越出视口，
    // 放大中心也总在图上，不会放大出一片黑边。
    final RenderBox? box = _viewerBoxOf(_current);
    Offset p = box?.globalToLocal(_doubleTapGlobal) ?? _doubleTapGlobal;
    if (box != null) {
      Rect bounds = Offset.zero & box.size;
      final RenderBox? img = _imageBoxOf(_current);
      if (img != null) {
        final Offset tl = box.globalToLocal(img.localToGlobal(Offset.zero));
        final Offset br = box.globalToLocal(img.localToGlobal(img.size.bottomRight(Offset.zero)));
        bounds = Rect.fromPoints(tl, br).intersect(bounds);
      }
      p = Offset(
        p.dx.clamp(bounds.left, bounds.right),
        p.dy.clamp(bounds.top, bounds.bottom),
      );
    }
    // 让双击点在缩放前后落在同一个屏幕位置：先把该点挪到原点，放大，再挪回去。
    final double x = -p.dx * (s - 1);
    final double y = -p.dy * (s - 1);
    c.value = Matrix4.identity()
      ..translateByDouble(x, y, 0, 1)
      ..scaleByDouble(s, s, 1, 1);
  }

  // ===== 手势：下滑关闭（AC1），且只在未放大时接线（AC2）=====

  /// 未放大时，纵向拖拽归「关闭」；已放大时这几个回调**一律不挂**，
  /// 拖拽因此落回 InteractiveViewer 的图内平移 —— 这就是优先级第 1 条的接线方式。
  bool get _dismissEnabled =>
      !_multiTouch &&
      resolveLightboxGesture(
        zoomed: _zoomed,
        horizontal: false,
        atEdgeInDragDirection: _atHorizontalEdge,
      ) ==
      LightboxGesture.dismiss;

  /// 横向：未放大直接翻页；已放大则要先贴边（优先级第 2 条）。
  bool get _pageScrollAllowed =>
      !_multiTouch &&
      resolveLightboxGesture(
        zoomed: _zoomed,
        horizontal: true,
        atEdgeInDragDirection: _atHorizontalEdge,
      ) ==
      LightboxGesture.changePage;

  void _onDragUpdate(DragUpdateDetails d) {
    setState(() => _dragDy += d.delta.dy);
  }

  void _onDragEnd(DragEndDetails d) {
    final double height = MediaQuery.sizeOf(context).height;
    if (LightboxDismissMetrics.shouldDismiss(
      dy: _dragDy,
      velocity: d.velocity.pixelsPerSecond.dy,
      viewportHeight: height,
    )) {
      _close(LightboxDismissGesture.swipeDown);
      return;
    }
    _reboundFrom = _dragDy;
    _rebound.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    final double height = MediaQuery.sizeOf(context).height;
    final double progress =
        LightboxDismissMetrics.progress(dy: _dragDy, viewportHeight: height);
    return Scaffold(
      // 背景在 Stack 里自己画：下滑时要随拖拽渐透明（AC1），Scaffold 的固定底色做不到。
      backgroundColor: Colors.transparent,
      // AC1：**没有 AppBar**。页码与关闭都是悬浮件，图片因此能延伸到状态栏区域。
      body: Stack(
        children: [
          Positioned.fill(
            child: ColoredBox(
              key: const ValueKey('lightboxBackdrop'),
              color: Colors.black
                  .withValues(alpha: 1 - progress * LightboxDismissMetrics.maxFade),
            ),
          ),
          // 图跟手走，同时随进度缩小一点（L4）：只平移的话像整张图"掉"下去，
          // 缩小才读得出「这张图正在被收起来」。
          Positioned.fill(
            child: Transform.translate(
              offset: Offset(0, _dragDy),
              child: Transform.scale(
                scale: 1 - _dragShrink * progress,
                // bug 506：数按下的指针，≥2 指时撤掉翻页与下滑关闭（见 _multiTouch）；
                // 正常尺寸下两指收缩时整页跟手缩小（松手弹回走 120ms 过渡）。
                child: Listener(
                  behavior: HitTestBehavior.translucent,
                  onPointerDown: _onPointerDown,
                  onPointerUp: _onPointerGone,
                  onPointerCancel: _onPointerGone,
                  child: AnimatedScale(
                    scale: _pinchScale,
                    duration: _pinching ? Duration.zero : const Duration(milliseconds: 120),
                    child: _pager(progress),
                  ),
                ),
              ),
            ),
          ),
          // 悬浮控件随拖拽一起淡出：它们钉在屏幕上不动会显得图"掉"下去了。
          Opacity(
            opacity: 1 - progress,
            // 悬浮控件套 SafeArea：系统栏虽已隐藏，刘海/挖孔仍在，不套会被切掉一角。
            child: SafeArea(
              child: Stack(
                children: [
                  // L3：放大时关闭钮退到半透明，不抢图的注意力（仍可点）。
                  Positioned(
                    top: 0,
                    left: 0,
                    child: Opacity(opacity: _zoomed ? 0.5 : 1, child: _closeButton()),
                  ),
                  if (widget.urls.length > 1)
                    Positioned(top: 0, left: 0, right: 0, child: Center(child: _counterPill())),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 下拖到底时图片缩小的比例（L4：scale = 1 - 0.07 × progress）。
  static const double _dragShrink = 0.07;

  /// 下拖到底时图片的圆角（L4：0 → 16）。
  static const double _dragRadius = 16;

  Widget _pager(double progress) => PageView.builder(
        key: const ValueKey('lightboxPager'),
        controller: _controller,
        // 🔴 优先级第 2 条的接线：放大且还没贴边时**禁掉翻页**，拖拽全部留给图内平移；
        // 贴边之后才把 PageView 放开。物理量只在拖拽开始时取一次 ——
        // 真机上的表现是「平移到头 → 松手 → 再拖一次翻页」，见 story 的待验收清单。
        physics: _pageScrollAllowed
            ? const PageScrollPhysics()
            : const NeverScrollableScrollPhysics(),
        // 🔴 **单图也用 PageView**（bug 20260727-372）：改前单图直接塞 InteractiveViewer，
        // 进灯箱后翻不到同一条内容的其余图。itemCount 是 1 时它退化成不可翻页，行为一致。
        itemCount: widget.urls.length,
        onPageChanged: (i) {
          // 离开的那一页复位，免得回头翻回来时它还停在某个奇怪的放大位置上。
          _transforms[_current]?.value = Matrix4.identity();
          setState(() {
            _current = i;
            _zoomed = false;
            _atHorizontalEdge = true;
          });
        },
        itemBuilder: (context, i) => GestureDetector(
          // 🔴 **单击图片或黑边即关闭**（bug 20260701-192 的修复，对齐主流看图 App）。
          // opaque 让黑边也算命中区。走 _handleTapUp 自己仲裁，不用 onTap + onDoubleTap
          // —— 后者会把单击拖慢到 300ms（AC6）。
          behavior: HitTestBehavior.opaque,
          onTapUp: _handleTapUp,
          // AC1/AC2：未放大才接下滑关闭；已放大时这三个回调为 null，
          // 拖拽落回 InteractiveViewer 的图内平移。
          onVerticalDragUpdate: _dismissEnabled ? _onDragUpdate : null,
          onVerticalDragEnd: _dismissEnabled ? _onDragEnd : null,
          // 缩放实现沿用 InteractiveViewer（AD-A15.4：不换实现）。
          // 🔴 L3：InteractiveViewer **撑满整页**，图在它里面居中。改前是 Center 把它收成
          // 图片 contain 后的大小，放大后图被裁在那个框里、上下黑边还在；稿是放大后铺满整屏。
          child: InteractiveViewer(
            key: _viewerKeys.putIfAbsent(i, GlobalKey.new),
            transformationController: _transformOf(i),
            // 未放大时不许平移：否则它会和"下滑关闭"抢同一个手势。
            panEnabled: _zoomed,
            maxScale: _maxScale,
            // bug 506：正常尺寸下两指收缩退出（见 _onInteractionUpdate）。
            onInteractionStart: _onInteractionStart,
            onInteractionUpdate: _onInteractionUpdate,
            onInteractionEnd: _onInteractionEnd,
            child: SizedBox.expand(
              child: Center(
                // AC1/AC2：Hero 包住这一页的图。tag 由**调用方前缀 + 下标**算出，
                // 与缩略图一侧共用 lightboxHeroTag —— 两边算法不一致就飞不起来。
                child: Hero(
                  key: _imageKeys.putIfAbsent(i, GlobalKey.new),
                  tag: lightboxHeroTag(widget.heroTagPrefix, i),
                  // 飞行途中用一张静态图，避免把加载态/重试按钮一起拖着飞。
                  flightShuttleBuilder: (_, _, _, _, _) =>
                      AppImage.widget(widget.urls[i], fit: BoxFit.contain, thumbWidth: _thumbWidth),
                  // 只有正在拖的那一页带圆角与阴影；其余页不在屏上，套了也是白算。
                  child: i == _current ? _dragDecor(_page(i), progress) : _page(i),
                ),
              ),
            ),
          ),
        ),
      );

  /// 缩略图取图宽度：**与 `_ImageCarousel` 现有口径一致，不另取一档**（AC3）。
  /// 另取一档等于让同一张图在两处各缓存一份，白白多下一次。
  static const int _thumbWidth = 1080;

  /// L4 下拖态的「卡片化」：圆角 0→16 + 随进度加深的阴影。
  /// 没拖时原样返回 —— ClipRRect 半径为 0 也照样开裁剪层，常驻它纯属浪费。
  Widget _dragDecor(Widget child, double progress) {
    if (progress <= 0) return child;
    final BorderRadius radius = BorderRadius.circular(_dragRadius * progress);
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: radius,
        // 阴影在黑底上本来看不见，是随背景变淡才浮出来的 —— 正好与「露出下面那一页」同步。
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.45 * progress),
            blurRadius: 32 * progress,
            offset: Offset(0, 12 * progress),
          ),
        ],
      ),
      child: ClipRRect(borderRadius: radius, child: child),
    );
  }

  /// 一页的内容：缩略图打底（模糊）→ 原图淡入（AC3）；加载失败给重试（AC4）。
  Widget _page(int i) {
    final String url = widget.urls[i];
    final int tick = _retryTicks[i] ?? 0;
    return Stack(
      fit: StackFit.passthrough,
      // 居中对齐：加载中的 spinner 要落在模糊缩略图正中，而不是左上角。
      alignment: Alignment.center,
      children: [
        // 打底的缩略图：详情页多半已经缓存过它，所以打开瞬间就有东西看，
        // 而不是一片黑等原图下完。模糊是为了让"还没清晰"这件事被看见，
        // 否则用户会以为原图就是这么糊。失败态撤掉它（L6：纯黑底）。
        if (!_failed.contains(i))
          // L5：模糊底图压到 0.7，让「还没清晰」与原图淡入的反差更明显。
          Opacity(
            opacity: 0.7,
            child: ImageFiltered(
              imageFilter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
              child: AppImage.widget(url, fit: BoxFit.contain, thumbWidth: _thumbWidth),
            ),
          ),
        AppImage.widget(
          url,
          // 🔴 key 带重试计数：点重试时 key 变了，Element 重建 → 重新发起加载。
          // 不换 key 的话 Image 会认为自己没变，失败态就此固化，按钮点了也没反应。
          key: ValueKey('lightboxImage_${i}_$tick'),
          fit: BoxFit.contain,
          // 原图解码完成后淡入，接住下面那张模糊缩略图。
          frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
            if (wasSynchronouslyLoaded) return child;
            final Widget fading = AnimatedOpacity(
              opacity: frame == null ? 0 : 1,
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOut,
              child: child,
            );
            if (frame != null) return fading;
            // L5：原图还在路上时叠一个小 spinner —— 只有模糊图的话，
            // 慢网下用户分不清「在加载」还是「就这么糊」。
            return Stack(
              alignment: Alignment.center,
              children: [
                fading,
                const SizedBox(
                  key: ValueKey('lightboxLoading'),
                  width: 28,
                  height: 28,
                  child: CircularProgressIndicator(
                    strokeWidth: 3,
                    backgroundColor: Colors.white30,
                    color: Colors.white,
                  ),
                ),
              ],
            );
          },
          errorBuilder: (context, error, stack) {
            // 失败是在 Image 的 build 里被告知的，不能就地 setState ——
            // 推到帧后再撤模糊缩略图。
            if (!_failed.contains(i)) {
              WidgetsBinding.instance.addPostFrameCallback((_) {
                if (mounted) setState(() => _failed.add(i));
              });
            }
            return _retryTile(i);
          },
        ),
      ],
    );
  }

  /// AC4：失败提示 + 重试按钮。改前这里只有一个灰色方块 —— 用户既不知道发生了什么，
  /// 也没有任何补救动作可做。
  Widget _retryTile(int i) {
    final l10n = AppLocalizations.of(context);
    return Center(
      key: ValueKey('lightboxRetry_$i'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.broken_image_outlined, size: 32, color: Colors.white70),
          const SizedBox(height: 10),
          Text(l10n.lightboxImageFailed,
              textAlign: TextAlign.center,
              // 次级文字用浅灰紫：纯白会和「重试」按钮抢主次。
              style: const TextStyle(fontSize: 12, color: AppColors.textTertiary)),
          const SizedBox(height: 14),
          // 描边胶囊按钮：纯文字按钮在黑底上几乎看不出是可点的。
          OutlinedButton.icon(
            key: ValueKey('lightboxRetryButton_$i'),
            // 重试要吃掉这次点击：不吞的话它会穿到底下的"单击关闭"，
            // 用户点重试反而把灯箱关了。重试期间把模糊缩略图放回来垫底。
            onPressed: () => setState(() {
              _failed.remove(i);
              _retryTicks[i] = (_retryTicks[i] ?? 0) + 1;
            }),
            style: OutlinedButton.styleFrom(
              foregroundColor: Colors.white,
              side: const BorderSide(color: Colors.white38),
              shape: const StadiumBorder(),
              minimumSize: const Size(0, 32),
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 7),
              textStyle: const TextStyle(fontSize: 12),
            ),
            icon: const Icon(Icons.refresh_rounded, size: 13),
            label: Text(l10n.feedRetry),
          ),
        ],
      ),
    );
  }

  /// 悬浮关闭 ✕（左上）。命中框 44×44，可见圆底只有 34 —— 扩热区不放大视觉（L1）。
  /// 外层 44 透明（opaque 命中），内层 34 才是看得见的圆。
  Widget _closeButton() => GestureDetector(
        key: const ValueKey('lightboxClose'),
        behavior: HitTestBehavior.opaque,
        onTap: () => _close(LightboxDismissGesture.closeButton),
        child: Container(
          width: 44,
          height: 44,
          // 44 命中区里 34 圆居中各缩进 5：外边距 11 → 可见圆落在 16（UI 稿 L1 的位置）。
          margin: const EdgeInsets.only(left: 11, top: 11),
          alignment: Alignment.center,
          child: Container(
            width: 34,
            height: 34,
            decoration: const BoxDecoration(color: Colors.black38, shape: BoxShape.circle),
            child: const Icon(Icons.close_rounded, size: 17, color: Colors.white),
          ),
        ),
      );

  /// 页码胶囊（顶部居中），替代改前那个黑色 AppBar 标题（AC2）。
  Widget _counterPill() => Container(
        key: const ValueKey('lightboxCounter'),
        // 与关闭圆钮中心对齐：圆心在 y=11+22=33，胶囊高约 26 → 顶边约 20（L2）。
        margin: const EdgeInsets.only(top: 20),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black38,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          '${_current + 1}/${widget.urls.length}',
          style: const TextStyle(fontSize: 12, color: Colors.white, fontWeight: FontWeight.w500),
        ),
      );
}
