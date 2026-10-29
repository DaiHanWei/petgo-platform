import 'package:flutter/widgets.dart';

import '../../../core/analytics/analytics.dart';
import 'video_nav.dart';

/// 一次刷新内的视频卡片曝光去重（专题线 video Story 2.3 · FR-21）。
///
/// 列表每次被**整页替换**（下拉刷新 / 重新加载）就换一个新实例 —— 「同一次刷新内只计一次」。
class VideoImpressionTracker {
  final Set<int> _shown = <int>{};

  /// 第一次见到 [postId] 返回 true（并记下），之后同一实例内返回 false。
  bool markShown(int postId) => _shown.add(postId);
}

/// 给没有「下拉刷新」的网格页（我的发布 / 他人主页）提供一个**页面级**曝光去重实例：
/// 这两页一次进入就是一次刷新；加载更多不换实例（不重复上报）。
class VideoImpressionScope extends StatefulWidget {
  const VideoImpressionScope({super.key, required this.child});

  final Widget child;

  /// 最近的作用域里的实例；没有作用域时返回一个一次性实例（不去重，但不崩）。
  static VideoImpressionTracker of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_VideoImpressionScopeData>()?.tracker ??
      VideoImpressionTracker();

  @override
  State<VideoImpressionScope> createState() => _VideoImpressionScopeState();
}

class _VideoImpressionScopeState extends State<VideoImpressionScope> {
  final VideoImpressionTracker _tracker = VideoImpressionTracker();

  @override
  Widget build(BuildContext context) => _VideoImpressionScopeData(tracker: _tracker, child: widget.child);
}

class _VideoImpressionScopeData extends InheritedWidget {
  const _VideoImpressionScopeData({required this.tracker, required super.child});

  final VideoImpressionTracker tracker;

  @override
  bool updateShouldNotify(_VideoImpressionScopeData old) => !identical(old.tracker, tracker);
}

/// 包住一张视频卡片：在**可视区**内露出一半以上时上报一次 `video_card_shown{post_id, source}`。
///
/// 不引入 `visibility_detector`（架构：本期只新增两个依赖）：首帧之后、以及**所有祖先滚动容器**
/// 滚动时（网格页是 shrinkWrap 网格嵌在外层列表里，真正在滚的是外层），按自身矩形与
/// 「屏幕 ∩ 各层滚动视口」求可见比例 —— 视口天然扣掉了 AppBar 与底部 Tab 栏。
/// 被 Offstage 藏起来的页面（底部 Tab 切走）不上报。
class VideoCardImpression extends StatefulWidget {
  const VideoCardImpression({
    super.key,
    required this.postId,
    required this.source,
    required this.tracker,
    required this.child,
  });

  final int postId;
  final VideoSource source;
  final VideoImpressionTracker tracker;
  final Widget child;

  /// 露出比例阈值（一半以上）。
  static const double threshold = 0.5;

  /// 可见比例：控件矩形与可视区 [viewport] 的交集高度 ÷ 控件高度。纯函数，L0 可测。
  static double visibleFraction(Rect box, Rect viewport) {
    if (box.height <= 0 || box.width <= 0) return 0;
    final inter = box.intersect(viewport);
    if (inter.height <= 0 || inter.width <= 0) return 0;
    return inter.height / box.height;
  }

  @override
  State<VideoCardImpression> createState() => _VideoCardImpressionState();
}

class _VideoCardImpressionState extends State<VideoCardImpression> {
  final List<ScrollPosition> _positions = [];
  final List<ScrollableState> _scrollables = [];
  bool _reported = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _detach();
    // 逐层向上收集滚动容器：嵌套时（shrinkWrap 网格在外层列表里）外层才是真正在滚的那个。
    var ctx = context;
    while (true) {
      final s = Scrollable.maybeOf(ctx);
      if (s == null) break;
      _scrollables.add(s);
      _positions.add(s.position);
      s.position.addListener(_scheduleCheck);
      ctx = s.context;
    }
    _scheduleCheck();
  }

  @override
  void didUpdateWidget(covariant VideoCardImpression oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.tracker, widget.tracker) || oldWidget.postId != widget.postId) {
      _reported = false; // 新的一次刷新：重新计
      _scheduleCheck();
    }
  }

  /// 滚动回调可能落在布局阶段：一律推到帧后再量。
  void _scheduleCheck() {
    if (_reported) return;
    WidgetsBinding.instance.addPostFrameCallback((_) => _check());
  }

  void _check() {
    if (_reported || !mounted || !TickerMode.valuesOf(context).enabled) return;
    final box = context.findRenderObject();
    if (box is! RenderBox || !box.hasSize || !box.attached) return;
    final rect = box.localToGlobal(Offset.zero) & box.size;
    var viewport = Offset.zero & MediaQuery.sizeOf(context);
    for (final s in _scrollables) {
      final vb = s.context.findRenderObject();
      if (vb is RenderBox && vb.hasSize && vb.attached) {
        viewport = viewport.intersect(vb.localToGlobal(Offset.zero) & vb.size);
      }
    }
    if (VideoCardImpression.visibleFraction(rect, viewport) < VideoCardImpression.threshold) return;
    _reported = true;
    if (widget.tracker.markShown(widget.postId)) {
      Analytics.capture('video_card_shown', {
        'post_id': widget.postId,
        'source': widget.source.wire,
      });
    }
  }

  void _detach() {
    for (final p in _positions) {
      p.removeListener(_scheduleCheck);
    }
    _positions.clear();
    _scrollables.clear();
  }

  @override
  void dispose() {
    _detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
