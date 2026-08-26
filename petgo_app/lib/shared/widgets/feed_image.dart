import 'package:flutter/material.dart';

import '../../features/content/domain/feed_image_layout.dart';
import 'app_image.dart';
import 'post_cover.dart';

/// Feed 卡片的图片区（V1.1.6 Story 3.3 定高 / Story 3.4 轮播与叠加层）。
///
/// 职责三件：**按三段口径定高**、**加载期给出占位**、**承载叠加层**。
///
/// ## 🛡 叠加层的三个角位是写死的（AD-8 Rule 6）
/// - **底边居中** = 轮播圆点（本组件自己出）
/// - **右上** = 顶置角标（[topRight]，Epic 4 挂）
/// - **左下** = 装饰标签（[bottomLeft]，Epic 5 挂）
///
/// 🔴 Epic 4 与 Epic 5 **只往这两个入参里挂内容，不得再改图片区结构**。
/// 三处分居三角、互不重叠，这是架构层面定死的，不是本组件的实现自由度。
/// `feed_overlay_test.dart` 会钉住这件事。
///
/// ## 为什么要有占位（必做项，不是优化）
/// 改版前图片区是写死的 4:3，图片没下载完高度就已经定了 —— 不存在布局跳动。
/// 改成按实际比例渲染之后，**跳动是这次改版新引入的问题**：
/// - **新内容**（发布时记了宽高）：加载前就知道高度，**零跳动**。
/// - **存量内容**（永远没有宽高）：先按 1:1 预留，解码出来后再调整到真实比例；
///   因为有 clamp，这次调整的幅度**有硬上下界**。
///
/// ## ⚠️ 为什么不用统一的图片组件
/// 统一组件返回的就是一个图片对象，而它的既有测试直接把返回值当图片对象用；
/// 要拿到"解码后的真实尺寸"必须监听图片流，这里改用「取 provider + 自己渲染」的写法
/// （项目里宠物资料卡已有同款先例），**不动统一组件的签名**，那批测试因此不受影响。
class FeedImage extends StatefulWidget {
  const FeedImage({
    super.key,
    required this.urls,
    required this.type,
    required this.declaredSize,
    required this.width,
    required this.maxImageHeight,
    this.thumbWidth = 800,
    this.topRight,
    this.bottomLeft,
  });

  /// 整组图片（上传顺序）。空表示无图，本组件不该被渲染。
  final List<String> urls;

  /// 内容类型：占位彩块按它取色与 emoji。
  final String type;

  /// 后端下发的**首图**原始尺寸；存量内容为 null。
  ///
  /// 🛡 只看首图：高度按首图锁定，翻页不改变高度。
  final ImageSize? declaredSize;

  /// 图片区显示宽度（通栏 = 屏宽）。
  final double width;

  /// 高度护栏上限。
  final double maxImageHeight;

  /// OSS 缩略图宽度（物理像素）。Feed 全宽封面取缩略图省流量、滚动更顺。
  final int thumbWidth;

  /// 🛡 右上角位 —— 顶置角标（Epic 4）。本 story 只交付空位。
  final Widget? topRight;

  /// 🛡 左下角位 —— 装饰标签（Epic 5）。本 story 只交付空位。
  final Widget? bottomLeft;

  @override
  State<FeedImage> createState() => _FeedImageState();
}

class _FeedImageState extends State<FeedImage> {
  final PageController _pager = PageController();

  /// 首图的 provider —— 监听它拿解码尺寸（仅在后端没给尺寸时）。
  ImageProvider? _firstProvider;
  ImageStream? _stream;
  ImageStreamListener? _listener;

  /// 解码后量到的首图真实尺寸。
  ImageSize? _decoded;

  int _current = 0;

  bool get _multi => widget.urls.length > 1;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _firstProvider = _providerAt(0);
    _subscribeIfNeeded();
    _precacheNext();
  }

  @override
  void didUpdateWidget(covariant FeedImage old) {
    super.didUpdateWidget(old);
    if (!identical(old.urls, widget.urls) &&
        (old.urls.isEmpty || widget.urls.isEmpty || old.urls.first != widget.urls.first)) {
      _decoded = null;
      _unsubscribe();
      _firstProvider = _providerAt(0);
      _subscribeIfNeeded();
    }
  }

  ImageProvider? _providerAt(int i) => (i < 0 || i >= widget.urls.length)
      ? null
      : AppImage.provider(widget.urls[i], thumbWidth: widget.thumbWidth);

  /// 🔴 只预加载**下一张**。
  ///
  /// 反面是"整组一次性预载"：一屏 5 条多图帖就会放大成几十个请求，
  /// 首屏请求量随多图帖数量线性膨胀。
  void _precacheNext() {
    final next = _providerAt(_current + 1);
    if (next == null || !mounted) return;
    precacheImage(next, context, onError: (_, _) {});
  }

  /// 🔴 后端给了尺寸就**不监听** —— 高度已经确定，去解码里再量一遍毫无意义。
  ///
  /// 监听用的是与渲染**同一个** provider，因此共用图片缓存，不会多下载一次。
  void _subscribeIfNeeded() {
    if (widget.declaredSize != null || _firstProvider == null) return;
    final stream = _firstProvider!.resolve(createLocalImageConfiguration(context));
    if (stream.key == _stream?.key) return;
    _unsubscribe();
    _listener = ImageStreamListener(
      (info, _) {
        final size = ImageSize(info.image.width, info.image.height);
        if (!mounted || !size.isUsable) return;
        if (_decoded?.w == size.w && _decoded?.h == size.h) return;
        setState(() => _decoded = size);
      },
      onError: (_, _) {
        // 失败态由 errorBuilder 出占位；吞掉以免冒泡成未捕获异常。
      },
    );
    _stream = stream..addListener(_listener!);
  }

  void _unsubscribe() {
    if (_stream != null && _listener != null) _stream!.removeListener(_listener!);
    _stream = null;
    _listener = null;
  }

  @override
  void dispose() {
    _unsubscribe();
    _pager.dispose();
    super.dispose();
  }

  Widget _imageAt(int i) {
    final placeholder = PostCoverPlaceholder(type: widget.type);
    final provider = i == 0 ? _firstProvider : _providerAt(i);
    if (provider == null) return placeholder;
    return Image(
      image: provider,
      // 🛡 非首图**居中裁剪填满** —— 容器高度由首图锁定，其余图只负责把这个框填满。
      fit: BoxFit.cover,
      width: double.infinity,
      height: double.infinity,
      // 换图时先留住上一帧，避免中间闪一下白（同款先例见宠物资料卡）。
      gaplessPlayback: true,
      frameBuilder: (context, child, frame, wasSynchronouslyLoaded) =>
          (wasSynchronouslyLoaded || frame != null) ? child : placeholder,
      errorBuilder: (context, error, stack) => placeholder,
    );
  }

  @override
  Widget build(BuildContext context) {
    // 🛡 高度只看**首图**：翻页时容器不变高。
    // Feed 是长列表，滑图时改变高度会把下方所有卡片整体推动。
    final aspect = resolveFeedImageAspect(
      size: widget.declaredSize ?? _decoded,
      width: widget.width,
      maxImageHeight: widget.maxImageHeight,
    );

    return AspectRatio(
      aspectRatio: aspect,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // 底层：单图直接渲染、多图走轮播。
          //
          // 🔴 单图**压根不接横向手势**（而不是"接了但滑不动"）——
          // 单图帖若仍挂着横向识别器，用户斜着滑一下就可能被截胡、列表滚不动。
          // 这类"偶尔滚不动"最难复现也最恼人。
          if (_multi)
            PageView.builder(
              controller: _pager,
              itemCount: widget.urls.length,
              onPageChanged: (i) {
                setState(() => _current = i);
                _precacheNext();
              },
              itemBuilder: (context, i) => _imageAt(i),
            )
          else
            _imageAt(0),

          // 右缘极窄暗渐变：「可横滑」的暗示。单图不给暗示。
          if (_multi)
            const Positioned(
              top: 0,
              bottom: 0,
              right: 0,
              width: 9,
              child: IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [Color(0x00000000), Color(0x0F000000)]),
                  ),
                ),
              ),
            ),

          // 🛡 角位一：底边居中 —— 轮播圆点。
          //
          // 圆点**同时承担"有几张图"与"当前第几张"两个信息**，
          // 所以首页不再需要独立的数字角标（详情页那套数字角标是另一回事，产品明示不必统一）。
          if (_multi)
            Positioned(
              left: 0,
              right: 0,
              bottom: 9,
              child: IgnorePointer(
                child: _CarouselDots(count: widget.urls.length, current: _current),
              ),
            ),

          // 🛡 角位二：右上 —— 顶置角标（Epic 4）。
          // UI 稿 `.pin-corner`：top/right 均为 9（原实现写的 8，2026-08-25 比对订正）。
          if (widget.topRight != null) Positioned(top: 9, right: 9, child: widget.topRight!),

          // 🛡 角位三：左下 —— 装饰标签（Epic 5）。
          // UI 稿 `.deco-on-card`：left/bottom 均为 10。
          if (widget.bottomLeft != null)
            Positioned(left: 10, bottom: 10, child: widget.bottomLeft!),
        ],
      ),
    );
  }
}

/// 轮播圆点（UI 稿 屏 09b）：距底 9、间距 5、直径 5，当前点 5.5 且纯白。
class _CarouselDots extends StatelessWidget {
  const _CarouselDots({required this.count, required this.current});

  final int count;
  final int current;

  @override
  Widget build(BuildContext context) {
    return Row(
      key: const ValueKey('feedCarouselDots'),
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++)
          // ⚠️ 间距走外层 Padding、不用 Container 的 margin ——
          // margin 会被算进控件自身的尺寸，让"圆点多大 / 在哪"没法被测量断言。
          Padding(
            padding: EdgeInsets.only(left: i == 0 ? 0 : 5),
            child: Container(
              key: ValueKey('feedCarouselDot_$i'),
              width: i == current ? 5.5 : 5,
              height: i == current ? 5.5 : 5,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: i == current ? Colors.white : Colors.white.withValues(alpha: 0.55),
                boxShadow: const [BoxShadow(color: Color(0x4D000000), blurRadius: 3)],
              ),
            ),
          ),
      ],
    );
  }
}
