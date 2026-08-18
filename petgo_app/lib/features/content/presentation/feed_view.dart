import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../shared/widgets/masonry_card.dart';
import '../domain/feed_image_layout.dart';
import '../domain/feed_item.dart';
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
    this.footer,
    this.autoLoadMore = true,
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

  /// 可选全幅尾部（随 Feed 同滚）。feed-guest 用作底部登录引导横幅（访客翻页闸门）。
  final Widget? footer;

  /// 滚动到底是否自动翻页。默认 true；访客态置 false——翻页只由底部引导卡「Lanjut lihat dulu」手动触发。
  final bool autoLoadMore;

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

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onScroll);
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
        final cards = <Widget>[
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
