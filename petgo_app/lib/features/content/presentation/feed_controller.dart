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
    this.loadMoreFailed = false,
    this.pagesLoaded = 1,
  });

  final List<FeedItem> items;
  final FeedCategory category;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;

  /// 增量加载（loadMore）失败（AC5 · F13）：已加载内容保留，列表底部显「加载失败，点击重试」，
  /// 重试沿用当前 nextCursor 续拉，不回顶不重拉首屏。
  final bool loadMoreFailed;

  /// 已加载批次数（FR-0B：游客浏览至第 3 页触发软性登录浮层）。
  final int pagesLoaded;

  bool get isEmpty => items.isEmpty;

  FeedState copyWith({
    List<FeedItem>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
    int? pagesLoaded,
  }) =>
      FeedState(
        items: items ?? this.items,
        category: category,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
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

  /// 距底预加载 / 底部重试 / 访客引导卡「继续浏览」：用 nextCursor 拉下一批并追加（去重由游标稳定性保证）。
  /// [pages] 连续拉几页（访客引导卡一次拉 3 页，拉完卡片落到新底部）；任一页失败即停并置
  /// [FeedState.loadMoreFailed]——保留已加载内容，底部显「点击重试」，重试沿用同一 nextCursor。
  Future<void> loadMore({int pages = 1}) async {
    for (var n = 0; n < pages; n++) {
      final current = state.value;
      if (current == null || !current.hasMore || current.loadingMore || current.nextCursor == null) {
        return;
      }
      // 进入加载：清失败态（重试场景）+ 置 loadingMore；nextCursor 由 copyWith 保留。
      state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
      try {
        final page = await ref
            .read(feedRepositoryProvider)
            .getFeed(category: current.category, cursor: current.nextCursor);
        state = AsyncData(current.copyWith(
          items: [...current.items, ...page.items],
          nextCursor: page.nextCursor,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
          pagesLoaded: current.pagesLoaded + 1,
        ));
      } catch (_) {
        // AC5：加载更多失败 → 保留已加载内容（不整屏报错、不清空），底部失败提示 + 重试入口。
        state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
        return;
      }
    }
  }

  /// 举报成功后本地乐观移除该卡片（内容审核 cm-6 §6.1）：从当前列表剔除，无需等下次刷新即消失。
  /// 不维护本地举报名单（§6.2 前端不做本地过滤维护）；下次刷新以后端过滤（§5.4）为权威。
  void removeItem(int postId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.id != postId).toList();
    if (filtered.length == current.items.length) return; // 不在当前列表：无操作
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 下拉刷新：重建首屏（重置游标）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<FeedController, FeedState> feedProvider =
    AsyncNotifierProvider<FeedController, FeedState>(FeedController.new);
