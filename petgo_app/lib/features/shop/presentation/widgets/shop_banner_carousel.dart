/// Toko 首页 banner 轮播（v1.4.2 shop Story 8.1 AC2，FR-132、D-16，UI A1 / A2 / A5）。
///
/// 🔴 **`PageView` 自实现，不引轮播库**（cloud-rules）：
/// - 自动轮播每 [kShopBannerAutoplay]（拍板 4 秒）；任何翻页（含用户手滑）后从当前页**重新计时**；
/// - 只有 1 张时不自动轮播、不画页码点；
/// - 页面不在前台（`TickerMode` 关：切到别的 Tab / 被详情页盖住）时暂停；
/// - 图按比例显示**不裁切**（[BoxFit.contain]，盒子比例取各张里最「高」的一张，bug 600 规则：
///   单张时盒子比例 = 图比例，一个像素都不裁）。
///
/// 埋点 `toko_banner_shown{banner_id, position}`：当前页**实际露出**超过 50% 时报一次；
/// 同一次进入页面同一张只报一次，离开页面（TickerMode 关）再回来重新计；`position` = 实际位次（1 起）。
/// 点击埋点与跳转由页面做（[ShopBannerCarousel.onTap]），本组件只负责 800ms 内连点只算一次。
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../domain/shop_banner.dart';
import 'shop_product_card.dart' show kShopCardRadius;

/// 自动轮播间隔（story 拍板 4：稿与 PRD 未定，取常见值）。
const Duration kShopBannerAutoplay = Duration(seconds: 4);

/// 连点去重用的时钟（测试注入；默认真实时间）。
@visibleForTesting
DateTime Function() shopBannerTapClock = DateTime.now;

class ShopBannerCarousel extends StatefulWidget {
  const ShopBannerCarousel({super.key, required this.banners, required this.onTap});

  /// 非空（空列表由页面整块不渲染，A2）。
  final List<ShopBanner> banners;

  /// 点击可点的 banner；[position] 为实际位次（1 起）。
  final void Function(ShopBanner banner, int position) onTap;

  @override
  State<ShopBannerCarousel> createState() => _ShopBannerCarouselState();
}

class _ShopBannerCarouselState extends State<ShopBannerCarousel> with WidgetsBindingObserver {
  final PageController _controller = PageController();
  int _page = 0;
  Timer? _timer;
  bool _tickerOn = true;

  /// 本次进入页面已报过曝光的 banner_id。
  final Set<String> _shown = {};
  ScrollPosition? _scroll;
  DateTime? _lastTap;

  /// App 是否在前台（8.1 复审 #4：paused / inactive / hidden 时停表，resumed 重新计时）。
  bool _appActive = true;

  /// 用户正按住拖动（拖动期间不自动翻页，松手后从当前页重新计时）。
  bool _dragging = false;

  List<ShopBanner> get _banners => widget.banners;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _restartTimer();
    WidgetsBinding.instance.addPostFrameCallback((_) => _checkExposure());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final on = TickerMode.valuesOf(context).enabled;
    if (on != _tickerOn) {
      _tickerOn = on;
      if (!on) {
        _shown.clear();
        _cancelTimer();
      } else {
        _restartTimer();
        WidgetsBinding.instance.addPostFrameCallback((_) => _checkExposure());
      }
    }
    final pos = Scrollable.maybeOf(context)?.position;
    if (!identical(pos, _scroll)) {
      _scroll?.removeListener(_checkExposure);
      _scroll = pos;
      _scroll?.addListener(_checkExposure);
    }
  }

  @override
  void didUpdateWidget(covariant ShopBannerCarousel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.banners.length != _banners.length) {
      if (_page >= _banners.length) _page = 0;
      _restartTimer();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final active = state == AppLifecycleState.resumed;
    if (active == _appActive) return;
    _appActive = active;
    if (active) {
      _restartTimer();
    } else {
      _cancelTimer();
    }
  }

  void _cancelTimer() {
    _timer?.cancel();
    _timer = null;
  }

  /// 用户拖动开始（带 dragDetails 的 ScrollStart）停表；拖动结束（ScrollEnd）重启。
  bool _onPageScroll(ScrollNotification n) {
    if (n.depth != 0) return false;
    if (n is ScrollStartNotification && n.dragDetails != null) {
      _dragging = true;
      _cancelTimer();
    } else if (n is ScrollEndNotification && _dragging) {
      _dragging = false;
      _restartTimer();
    }
    return false; // 不吞：页面的预加载监听自己按 depth / 轴向过滤
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _scroll?.removeListener(_checkExposure);
    _controller.dispose();
    super.dispose();
  }

  void _restartTimer() {
    _cancelTimer();
    if (_banners.length < 2 || !_tickerOn || !_appActive || _dragging) return;
    _timer = Timer.periodic(kShopBannerAutoplay, (_) => _advance());
  }

  void _advance() {
    if (!mounted || !_controller.hasClients || _banners.length < 2) return;
    final next = (_page + 1) % _banners.length;
    _controller.animateToPage(next,
        duration: const Duration(milliseconds: 350), curve: Curves.easeInOut);
  }

  void _onPageChanged(int i) {
    setState(() => _page = i);
    _restartTimer(); // 手滑 / 自动翻页后都从当前页重新计时
    WidgetsBinding.instance.addPostFrameCallback((_) => _checkExposure());
  }

  /// 轮播区当前在滚动视口里的露出比例（0–1）。不在滚动区里（单测直挂）按全露出。
  double _visibleFraction() {
    final ro = context.findRenderObject();
    if (ro is! RenderBox || !ro.attached || !ro.hasSize || ro.size.height <= 0) return 0;
    final scrollable = Scrollable.maybeOf(context);
    final vp = scrollable?.context.findRenderObject();
    if (vp is! RenderBox || !vp.hasSize) return 1;
    final top = ro.localToGlobal(Offset.zero, ancestor: vp).dy;
    final h = ro.size.height;
    final visible = math.min(top + h, vp.size.height) - math.max(top, 0.0);
    return (visible / h).clamp(0.0, 1.0);
  }

  void _checkExposure() {
    if (!mounted || !_tickerOn || _banners.isEmpty) return;
    final i = _page.clamp(0, _banners.length - 1);
    final id = _banners[i].bannerId;
    if (id == null || id.isEmpty || _shown.contains(id)) return;
    if (_visibleFraction() <= 0.5) return;
    _shown.add(id);
    Analytics.capture('toko_banner_shown', {
      'banner_id': id,
      'position': i + 1,
      'placement_type': 'toko_banner',
      'placement_token': id,
    });
  }

  void _tap(ShopBanner b, int index) {
    final now = shopBannerTapClock();
    final last = _lastTap;
    // 800ms 内连点只算一次（否则商品详情会被连推两层）。
    if (last != null && now.difference(last) < const Duration(milliseconds: 800)) return;
    _lastTap = now;
    widget.onTap(b, index + 1);
  }

  @override
  Widget build(BuildContext context) {
    final n = _banners.length;
    // 盒子比例取最「高」的一张（宽高比最小）：任何一张都能完整放下，不裁切。
    final aspect = _banners.map((b) => b.aspect).reduce(math.min);
    return Padding(
      key: const ValueKey('tokoBannerCarousel'),
      padding: const EdgeInsets.fromLTRB(AppSpacing.screenEdge, AppSpacing.md, AppSpacing.screenEdge, 0),
      child: AspectRatio(
        aspectRatio: aspect,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(kShopCardRadius),
          child: Stack(
            fit: StackFit.expand,
            children: [
              NotificationListener<ScrollNotification>(
                onNotification: _onPageScroll,
                child: PageView.builder(
                key: const ValueKey('tokoBannerPageView'),
                controller: _controller,
                itemCount: n,
                onPageChanged: _onPageChanged,
                itemBuilder: (_, i) {
                  final b = _banners[i];
                  return GestureDetector(
                    key: ValueKey('tokoBannerImage_$i'),
                    behavior: HitTestBehavior.opaque,
                    onTap: b.tappable ? () => _tap(b, i) : null,
                    child: ColoredBox(
                      color: AppColors.mintTint,
                      child: SizedBox.expand(
                        child: Image.network(
                          b.imageUrl,
                          fit: BoxFit.contain,
                          errorBuilder: (_, _, _) => const SizedBox.expand(),
                        ),
                      ),
                    ),
                  );
                },
              ),
              ),
              if (n > 1)
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: AppSpacing.sm,
                  child: IgnorePointer(
                    child: Row(
                      key: const ValueKey('tokoBannerDots'),
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        for (var i = 0; i < n; i++)
                          AnimatedContainer(
                            duration: const Duration(milliseconds: 200),
                            margin: const EdgeInsets.symmetric(horizontal: 3),
                            width: i == _page ? 16 : 6,
                            height: 6,
                            decoration: BoxDecoration(
                              color: i == _page
                                  ? AppColors.onAccent
                                  : AppColors.onAccent.withValues(alpha: .55),
                              borderRadius: BorderRadius.circular(3),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
