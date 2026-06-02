import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../shared/widgets/masonry_card.dart';
import '../domain/feed_item.dart';

/// Feed 瀑布流视图（Story 3.2，UX-DR4）。
///
/// 2 列不等高（按 index 奇偶分列，内容高度天然不等）、8px 列间距、16px 屏边距；
/// 距底自动 [onLoadMore]；[onRefresh] 下拉刷新；底部 [loadingMore] 转圈。
class FeedMasonryView extends StatefulWidget {
  const FeedMasonryView({
    super.key,
    required this.items,
    required this.hasMore,
    required this.loadingMore,
    required this.deletedUserLabel,
    required this.onLoadMore,
    required this.onRefresh,
    this.onTapItem,
    this.onLongPressItem,
    this.onAuthorTap,
  });

  final List<FeedItem> items;
  final bool hasMore;
  final bool loadingMore;
  final String deletedUserLabel;
  final Future<void> Function() onLoadMore;
  final Future<void> Function() onRefresh;
  final ValueChanged<FeedItem>? onTapItem;
  final ValueChanged<FeedItem>? onLongPressItem;
  final ValueChanged<FeedItem>? onAuthorTap;

  @override
  State<FeedMasonryView> createState() => _FeedMasonryViewState();
}

class _FeedMasonryViewState extends State<FeedMasonryView> {
  final ScrollController _controller = ScrollController();

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
    if (!widget.hasMore || widget.loadingMore) return;
    final pos = _controller.position;
    if (pos.pixels >= pos.maxScrollExtent - _preloadThreshold) {
      widget.onLoadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final left = <Widget>[];
    final right = <Widget>[];
    for (var i = 0; i < widget.items.length; i++) {
      final card = Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
        child: MasonryCard(
          item: widget.items[i],
          deletedUserLabel: widget.deletedUserLabel,
          onTap: widget.onTapItem == null ? null : () => widget.onTapItem!(widget.items[i]),
          onLongPress:
              widget.onLongPressItem == null ? null : () => widget.onLongPressItem!(widget.items[i]),
          onAuthorTap:
              widget.onAuthorTap == null ? null : () => widget.onAuthorTap!(widget.items[i]),
        ),
      );
      (i.isEven ? left : right).add(card);
    }

    return RefreshIndicator(
      color: AppColors.accentGrowth,
      onRefresh: widget.onRefresh,
      child: SingleChildScrollView(
        controller: _controller,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        child: Column(
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: Column(children: left)),
                const SizedBox(width: AppSpacing.sm),
                Expanded(child: Column(children: right)),
              ],
            ),
            if (widget.loadingMore)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: AppSpacing.lg),
                child: CircularProgressIndicator(color: AppColors.accentGrowth),
              ),
          ],
        ),
      ),
    );
  }
}
