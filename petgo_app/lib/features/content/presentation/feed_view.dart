import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../shared/widgets/masonry_card.dart';
import '../domain/feed_image_layout.dart';
import '../domain/feed_item.dart';
import '../domain/pinned_slot.dart';
import 'pinned_badge.dart';
import 'promo_pinned_card.dart';
import '../domain/home_refresh_provider.dart';

/// Feed 单列视图（原型 feed.html：单列全宽卡片，非 2 列瀑布）。
///
/// 12px 卡间距、16px 屏边距；距底自动 [onLoadMore]；[onRefresh] 下拉刷新；底部 [loadingMore] 转圈。
class FeedMasonryView extends ConsumerStatefulWidget {
  const FeedMasonryView({
    super.key,
    required this.items,
    required this.hasMore,
    required this.loadingMore,
    required this.deletedUserLabel,
    required this.onLoadMore,
    required this.onRefresh,
    this.loadMoreFailed = false,
    this.loadMoreErrorLabel,
    this.onTapItem,
    this.onLongPressItem,
    this.onAuthorTap,
    this.onCommentItem,
    this.onMoreItem,
    this.header,
    this.pinned,
    this.onTapPinned,
    this.onTapPromo,
    this.footer,
    this.autoLoadMore = true,
    this.feedTab,
    this.rankMode,
  });

  final List<FeedItem> items;
  final bool hasMore;
  final bool loadingMore;

  /// 增量加载失败（AC5 · F13）：底部显「加载失败，点击重试」，点击沿用 nextCursor 续拉。
  final bool loadMoreFailed;
  final String? loadMoreErrorLabel;
  final String deletedUserLabel;
  final Future<void> Function() onLoadMore;
  final Future<void> Function() onRefresh;
  final ValueChanged<FeedItem>? onTapItem;

  /// 点评论按钮（V1.1.6 Story 3.2）：跳详情页并定位评论区。
  final ValueChanged<FeedItem>? onCommentItem;

  /// 作者行右侧「···」（V1.1.6 Story 3.2）。
  final ValueChanged<FeedItem>? onMoreItem;
  final ValueChanged<FeedItem>? onLongPressItem;
  final ValueChanged<FeedItem>? onAuthorTap;

  /// 可选全幅头部（随 Feed 同滚）。Beranda 用作问候/快捷入口/每日提示区。
  final Widget? header;

  /// 顶置坑位（V1.1.6 Story 4.2 · FR-68）。
  ///
  /// 🛡 为空 → **什么都不渲染、不留占位**，该位置由普通内容按正常排序填充。
  final PinnedSlot? pinned;

  /// 点顶置卡（进详情页）。埋点在本组件内上报，回调只负责跳转。
  final ValueChanged<FeedItem>? onTapPinned;

  /// 点推广卡（V1.1.6 Story 4.3）。回调负责"怎么跳"，埋点仍在本组件内上报。
  final ValueChanged<PromoCard>? onTapPromo;

  /// 可选全幅尾部（随 Feed 同滚）。feed-guest 用作底部登录引导横幅（访客翻页闸门）。
  final Widget? footer;

  /// 滚动到底是否自动翻页。默认 true；访客态置 false——翻页只由底部引导卡「Lanjut lihat dulu」手动触发。
  final bool autoLoadMore;

  /// 埋点 `feed_tab` / `rank_mode`（V1.1.6 Story 16.5）。
  ///
  /// 🔴 两个都要有：只有 `feed_tab` 不够 —— 降级链级别 4 会让 ALL Tab **也走时间倒序**，
  /// 那时 `feed_tab=all` 但 `rank_mode=chrono`，把它算进推荐序的效果里就是错的。
  /// 而这个 FR 的参数本来就要在发版后校准，归因不了等于校准也做不了。
  ///
  /// ⚠️ 非 Feed 场景不传（如「我的发布」复用本组件时）。
  final String? feedTab;
  final String? rankMode;

  @override
  ConsumerState<FeedMasonryView> createState() => _FeedMasonryViewState();
}

class _FeedMasonryViewState extends ConsumerState<FeedMasonryView> {
  final ScrollController _controller = ScrollController();

  /// 回到顶部（bug 20260709-278）：已在首页再点 Home → 信号 +1 → 动画滚回顶。
  void _scrollToTop() {
    if (!_controller.hasClients) return;
    _controller.animateTo(0,
        duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
  }

  /// 距底预加载阈值（≈3~5 卡）。
  static const double _preloadThreshold = 600;

  /// 顶置曝光只报一次（重建不重复报）。
  bool _pinExposureReported = false;

  /// 已报过"重复曝光"的内容 id —— 同一条不该因为滚动重建反复上报。
  final Set<int> _duplicateReported = <int>{};

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onScroll);
    _reportPinAnalytics();
  }

  @override
  void didUpdateWidget(covariant FeedMasonryView old) {
    super.didUpdateWidget(old);
    if (old.pinned?.pinConfigId != widget.pinned?.pinConfigId) {
      _pinExposureReported = false;
      _duplicateReported.clear();
    }
    _reportPinAnalytics();
  }

  /// 顶置相关的两个曝光事件（E-1 / E-3）。
  ///
  /// ⚠️ 本项目没有"进入视口才算曝光"的基建，这里按**渲染即曝光**上报 ——
  /// 顶置位是首屏第一条，渲染与看到之间的差距可以忽略；重复曝光那条本就是统计频率与位次，
  /// 精确到视口对判读没有实际影响。这条口径写在这里，免得日后有人以为漏做了。
  /// 本次 Feed 会话的排序上下文（V1.1.6 Story 16.5）。
  ///
  /// 🛡 规则（缺任一个就都不带）在 [RankMode.eventProps] 里，本处只是取值 ——
  /// 那条规则有自己的测试，别在这里另写一遍。
  Map<String, Object> get _rankContext =>
      RankMode.eventProps(widget.feedTab, widget.rankMode);

  void _reportPinAnalytics() {
    final pin = widget.pinned;
    if (pin == null) return;

    if (!_pinExposureReported) {
      _pinExposureReported = true;
      Analytics.capture('social_pinned_slot_viewed', {
        'pin_config_id': pin.pinConfigId,
        'pin_type': pin.analyticsType,
        if (pin.item != null) 'content_id': pin.item!.id,
        ..._rankContext,
      });
    }

    // 🛡 重复曝光：被顶置的内容**在后续页**出现时上报，带它在序列里的位次。
    //
    // ⚠️ 该事件的语义已随 AD-8 改写为"观测后续页的重复曝光频率与位次" ——
    // 第一页已经排除了，首屏不可能重复。它是下游版本判断"要不要为顶置做去重"的**唯一数据来源**，
    // 所以必须随本功能一并上线，不能推到埋点收尾。
    final pinnedId = pin.item?.id;
    if (pinnedId == null || _duplicateReported.contains(pinnedId)) return;
    for (var i = 0; i < widget.items.length; i++) {
      if (widget.items[i].id != pinnedId) continue;
      _duplicateReported.add(pinnedId);
      Analytics.capture('social_pinned_duplicate_viewed', {
        'content_id': pinnedId,
        // 它在算法序列里的位次（从 1 起，与产品口径一致）。
        'serp_position': i + 1,
        // 🔴 这条尤其需要 rank_mode：降级到时间倒序时"序列位次"根本不是算法排的,
        // 混在一起看会得出错误结论（Story 16.5）。
        ..._rankContext,
      });
      break;
    }
  }

  @override
  void dispose() {
    _controller.removeListener(_onScroll);
    _controller.dispose();
    super.dispose();
  }

  void _onScroll() {
    // 访客态闸门：禁用滚动自动翻页，翻页由底部引导卡「Lanjut lihat dulu」手动触发。
    if (!widget.autoLoadMore) return;
    // 失败态停止自动预加载——避免静默重试循环，须用户点击底部「重试」。
    if (!widget.hasMore || widget.loadingMore || widget.loadMoreFailed) return;
    final pos = _controller.position;
    if (pos.pixels >= pos.maxScrollExtent - _preloadThreshold) {
      widget.onLoadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    // 已在首页再次点击 Home Tab → 回到顶部（bug 20260709-278）。
    ref.listen<int>(homeScrollTopProvider, (_, _) => _scrollToTop());
    return LayoutBuilder(
      builder: (context, constraints) {
        // 🔴 高度护栏（V1.1.6 Story 3.3）的「可视区高度」口径 = **滚动视口的实际高度**。
        // 在这里量最准：顶部标签行与底部导航栏已经被外层扣掉，卡片内不需要再减一次。
        // 视口无界时（理论上不会发生）算出的上限是无穷大，护栏自动不介入 —— 安全退化。
        final maxImageHeight = FeedCardMetrics.maxImageHeight(constraints.maxHeight);
        // 顶置卡：与普通卡**同一个组件**，只多挂一个右上角标。
        // 🛡 为空则整块不渲染 —— 不留占位。
        final pin = widget.pinned;
        final pinnedItem = pin?.item;
        final promo = pin?.promo;
        final cards = <Widget>[
          // 推广卡片（Story 4.3）：与内容类顶置**共用同一个角标**，视觉不作区分、不加广告标识。
          if (promo != null)
            Container(
              key: const ValueKey('feedPromoCard'),
              padding: const EdgeInsets.only(bottom: 12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.line2, width: 1)),
              ),
              child: PromoPinnedCard(
                promo: promo,
                maxImageHeight: maxImageHeight,
                // 🔴 认不出的跳转目标 → 不可点。运营填错一个字符不该让首页出问题。
                onTap: promo.jumpTarget == PromoJumpTarget.unknown
                    ? null
                    : () {
                        Analytics.capture('social_pinned_slot_tapped', {
                          'pin_config_id': pin!.pinConfigId,
                          'pin_type': pin.analyticsType,
                          'jump_target': promo.jumpTarget.analyticsValue,
                          ..._rankContext,
                        });
                        widget.onTapPromo?.call(promo);
                      },
              ),
            ),
          if (pinnedItem != null)
            Container(
              padding: const EdgeInsets.only(bottom: 12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.line2, width: 1)),
              ),
              child: MasonryCard(
                key: const ValueKey('feedPinnedCard'),
                item: pinnedItem,
                deletedUserLabel: widget.deletedUserLabel,
                feedTab: widget.feedTab,
                rankMode: widget.rankMode,
                maxImageHeight: maxImageHeight,
                pinnedBadge: const PinnedBadge(),
                onTap: () {
                  Analytics.capture('social_pinned_slot_tapped', {
                    'pin_config_id': pin!.pinConfigId,
                    'pin_type': pin.analyticsType,
                    'content_id': pinnedItem.id,
                    // 本 story 只有"顶置一篇已发布内容"，跳的就是详情页。
                    // 推广卡片的外链 / 深链属 Story 4.3。
                    'jump_target': 'post_detail',
                    ..._rankContext,
                  });
                  widget.onTapPinned?.call(pinnedItem);
                },
                onComment: widget.onCommentItem == null
                    ? null
                    : () => widget.onCommentItem!(pinnedItem),
                onAuthorTap:
                    widget.onAuthorTap == null ? null : () => widget.onAuthorTap!(pinnedItem),
                // 🔴 举报入口（长按 + 「···」）**必须一并挂上**：AC 要求"其余部分与普通条目完全一致，
                // 常规互动入口位置不变"。实机上才发现漏了 —— 只挂点击与评论会让顶置卡少一个入口，
                // 用户对顶置内容反而没法举报。
                onLongPress: widget.onLongPressItem == null
                    ? null
                    : () => widget.onLongPressItem!(pinnedItem),
                onMore: widget.onMoreItem == null ? null : () => widget.onMoreItem!(pinnedItem),
              ),
            ),
          for (var i = 0; i < widget.items.length; i++)
            // 通栏版式（V1.1.6 Story 3.2 · FR-93）：条目之间 1px 分隔线 + 上下 12px；
            // ⚠️ **最后一条不画线**，否则列表底部会多出一道悬空的横线。
            Container(
              padding: const EdgeInsets.only(bottom: 12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: i == widget.items.length - 1
                  ? null
                  : const BoxDecoration(
                      border: Border(bottom: BorderSide(color: AppColors.line2, width: 1)),
                    ),
              child: MasonryCard(
                item: widget.items[i],
                deletedUserLabel: widget.deletedUserLabel,
                feedTab: widget.feedTab,
                rankMode: widget.rankMode,
                onTap: widget.onTapItem == null ? null : () => widget.onTapItem!(widget.items[i]),
                onLongPress: widget.onLongPressItem == null
                    ? null
                    : () => widget.onLongPressItem!(widget.items[i]),
                onAuthorTap: widget.onAuthorTap == null
                    ? null
                    : () => widget.onAuthorTap!(widget.items[i]),
                onComment: widget.onCommentItem == null
                    ? null
                    : () => widget.onCommentItem!(widget.items[i]),
                onMore: widget.onMoreItem == null
                    ? null
                    : () => widget.onMoreItem!(widget.items[i]),
                maxImageHeight: maxImageHeight,
              ),
            ),
        ];

        return RefreshIndicator(
          color: AppColors.accentGrowth,
          onRefresh: widget.onRefresh,
          child: SingleChildScrollView(
            controller: _controller,
            physics: const AlwaysScrollableScrollPhysics(),
            child: Column(
              children: [
                if (widget.header != null) widget.header!,
                Padding(
                  // 🔴 通栏：**去掉左右屏边距**，让卡片里的图片能贴到屏幕边缘。
                  // ⚠️ 文字区的 16px 由卡片内部各块自己补（作者行/操作行/正文/时间），
                  // 不是整张卡贴边 —— 那样文字会顶到屏幕边上。
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.screenEdge),
                  child: Column(
                    children: [
                      // 单列通栏卡片（FR-93）。
                      ...cards,
                      if (widget.footer != null) widget.footer!,
                      if (widget.loadingMore)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: AppSpacing.lg),
                          child: CircularProgressIndicator(color: AppColors.accentGrowth),
                        ),
                      // AC5：增量加载失败 → 底部「加载失败，点击重试」（已加载内容保留在上方）。
                      if (widget.loadMoreFailed && !widget.loadingMore)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                          child: TextButton.icon(
                            key: const ValueKey('feedLoadMoreRetry'),
                            onPressed: widget.onLoadMore,
                            icon: const Icon(
                              Icons.refresh,
                              size: 18,
                              color: AppColors.accentGrowth,
                            ),
                            label: Text(
                              widget.loadMoreErrorLabel ??
                                  'Gagal memuat lagi, ketuk untuk coba lagi',
                              style: const TextStyle(color: AppColors.accentGrowth),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
