import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/feed_repository.dart';
import '../domain/feed_item.dart';
import '../domain/home_refresh_provider.dart';

/// 当前选中的分类 Tab（Story 3.2，AC3）。切换即重置游标重拉（feedProvider watch 此值）。
final NotifierProvider<FeedCategoryNotifier, FeedCategory> feedCategoryProvider =
    NotifierProvider<FeedCategoryNotifier, FeedCategory>(FeedCategoryNotifier.new);

class FeedCategoryNotifier extends Notifier<FeedCategory> {
  @override
  FeedCategory build() => FeedCategory.all;

  void select(FeedCategory category) => state = category;
}

/// Feed 列表态（不可变）。游标分页 + 无限滚动游标累积。
class FeedState {
  const FeedState({
    required this.items,
    required this.category,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.pagesLoaded = 1,
  });

  final List<FeedItem> items;
  final FeedCategory category;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;

  /// 已加载批次数（FR-0B：游客浏览至第 3 页触发软性登录浮层）。
  final int pagesLoaded;

  bool get isEmpty => items.isEmpty;

  FeedState copyWith({
    List<FeedItem>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    int? pagesLoaded,
  }) =>
      FeedState(
        items: items ?? this.items,
        category: category,
        nextCursor: nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        pagesLoaded: pagesLoaded ?? this.pagesLoaded,
      );
}

/// Feed 控制器（Story 3.2）。AsyncValue 三态（loading 骨架 / data / error）。
///
/// build 内 watch [feedCategoryProvider]（切 tab 重拉）与 [homeRefreshProvider]
/// （宠物状态变更即时刷新，FR-21/FR-17）。loadMore 用 nextCursor 追加下一批。
class FeedController extends AsyncNotifier<FeedState> {
  @override
  Future<FeedState> build() async {
    final category = ref.watch(feedCategoryProvider);
    ref.watch(homeRefreshProvider); // 宠物状态变更 → 重建 → 按新状态硬过滤
    final page = await ref.read(feedRepositoryProvider).getFeed(category: category);
    return FeedState(
      items: page.items,
      category: category,
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      pagesLoaded: 1,
    );
  }

  /// 距底预加载：用 nextCursor 拉下一批并追加（去重由游标稳定性保证）。
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.loadingMore || current.nextCursor == null) {
      return;
    }
    state = AsyncData(current.copyWith(loadingMore: true));
    try {
      final page = await ref
          .read(feedRepositoryProvider)
          .getFeed(category: current.category, cursor: current.nextCursor);
      state = AsyncData(current.copyWith(
        items: [...current.items, ...page.items],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        loadingMore: false,
        pagesLoaded: current.pagesLoaded + 1,
      ));
    } catch (_) {
      // 加载更多失败：复位 loadingMore，保留已有列表（不整屏报错）。
      state = AsyncData(current.copyWith(loadingMore: false));
    }
  }

  /// 下拉刷新：重建首屏（重置游标）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<FeedController, FeedState> feedProvider =
    AsyncNotifierProvider<FeedController, FeedState>(FeedController.new);
