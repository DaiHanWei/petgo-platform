/// 全部评价页（v1.4.2 shop Story 8.2 · UI B12，FR-133.6 / D-17）。路由 `/shop/products/:token/reviews`，游客可访问。
///
/// 顶栏「Ulasan ({n})」；头部「{avg} dari 5 · {n} ulasan」；列表每条昵称、星级、相对时间、「Varian: {规格}」、正文、缩略图。
/// 游标分页触底加载；首屏失败通用重试，追加失败底部重试。运营隐藏的评价服务端已过滤，App 不做处理。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/shop_tokens.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/shop_review_repository.dart';
import 'widgets/shop_review_tile.dart';
import 'widgets/shop_surface.dart';

class ReviewListPage extends ConsumerStatefulWidget {
  const ReviewListPage({super.key, required this.token});

  final String token;

  @override
  ConsumerState<ReviewListPage> createState() => _ReviewListPageState();
}

class _ReviewListPageState extends ConsumerState<ReviewListPage> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (pos.pixels >= pos.maxScrollExtent - 200) {
      ref.read(reviewListProvider(widget.token).notifier).loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(reviewListProvider(widget.token));
    final total = async.asData?.value.total;
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
          title: total == null ? l10n.tokoReviewsTitle : l10n.tokoReviewsHeader(total)),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.tokoLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(reviewListProvider(widget.token)),
        ),
        data: (s) => _list(l10n, s),
      ),
    );
  }

  Widget _list(AppLocalizations l10n, ReviewListState s) {
    // 首屏不满一屏时滚动回调不会触发 → 帧后补一次判定。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && s.hasMore && !s.loadingMore && !s.loadMoreFailed) _onScroll();
    });
    final avg = s.averageRating;
    return ListView(
      key: const ValueKey('reviewListView'),
      controller: _scroll,
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        ShopCard(
          margin: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (avg != null && s.total > 0)
                Row(
                  children: [
                    const Icon(Icons.star_rounded, size: 20, color: ShopColors.warnTitle),
                    const SizedBox(width: 4),
                    Expanded(
                      child: Text(l10n.tokoReviewsSummary(avg.toStringAsFixed(1), s.total),
                          key: const ValueKey('reviewListSummary'),
                          style: AppTypography.body
                              .copyWith(fontWeight: FontWeight.w600, color: ShopColors.text)),
                    ),
                  ],
                )
              else if (s.total > 0)
                Text(l10n.tokoReviewsCount(s.total),
                    key: const ValueKey('reviewListSummary'), style: AppTypography.body)
              else
                Text(l10n.tokoReviewsNone,
                    key: const ValueKey('reviewListEmpty'), style: AppTypography.body),
            ],
          ),
        ),
        if (s.items.isNotEmpty)
          ShopCard(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            child: Column(
              children: [
                for (var i = 0; i < s.items.length; i++) ...[
                  if (i > 0) const ShopDivider(),
                  ShopReviewTile(review: s.items[i], showSpec: true),
                ],
              ],
            ),
          ),
        if (s.loadingMore)
          const Padding(
            padding: EdgeInsets.all(16),
            child: Center(child: CircularProgressIndicator()),
          ),
        if (s.loadMoreFailed)
          Center(
            child: TextButton(
              key: const ValueKey('reviewListLoadMoreRetry'),
              onPressed: () => ref.read(reviewListProvider(widget.token).notifier).loadMore(),
              child: Text(l10n.commonRetry),
            ),
          ),
      ],
    );
  }
}
