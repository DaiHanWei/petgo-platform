import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/collect_providers.dart';
import '../data/collect_repository.dart';
import '../domain/collect_target.dart';
import '../domain/collected_item.dart';

/// 「我的 → Disimpan」列表状态（V1.4.0 ④ Story 2.4）。已加载行 + 游标 + 加载更多态。
class CollectionListState<T> {
  const CollectionListState({
    required this.items,
    this.nextCursor,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final List<T> items;
  final String? nextCursor;
  final bool loadingMore;
  final bool loadMoreFailed;

  bool get hasMore => nextCursor != null;

  CollectionListState<T> copyWith({
    List<T>? items,
    String? Function()? nextCursor,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      CollectionListState<T>(
        items: items ?? this.items,
        nextCursor: nextCursor != null ? nextCursor() : this.nextCursor,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// 列表加载（照 `PawCoinController`）：首屏失败 → 错误态 + 重试；加载更多失败 → 保留已加载 + 底部重试，不回顶。
///
/// `build` 订阅 [collectionsRevisionProvider]：详情页收藏 / 取消成功后 bump → 自动重拉首页（AD-16）。
abstract class _CollectionListController<T> extends AsyncNotifier<CollectionListState<T>> {
  CollectTarget get target;

  Future<CollectionPageData<T>> fetch(CollectRepository repo, {String? cursor});

  /// 每次 build（信号重拉 / 重试 / 换账号）自增：在途的 loadMore 回来时若已换代就丢弃结果，
  /// 否则会把旧页拼回刚拉的新首页（重复行 / 回退刷新）。
  int _generation = 0;

  @override
  Future<CollectionListState<T>> build() async {
    _generation++;
    ref.watch(collectionsRevisionProvider(target));
    final page = await fetch(ref.read(collectRepositoryProvider));
    return CollectionListState<T>(items: page.items, nextCursor: page.nextCursor);
  }

  Future<void> loadMore() async {
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore) return;
    final gen = _generation;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    try {
      final page = await fetch(ref.read(collectRepositoryProvider), cursor: cur.nextCursor);
      // 在最新状态上追加（期间可能本地移除过某行），而不是在发请求前的快照上。
      final now = state.value;
      if (gen != _generation || now == null) return;
      state = AsyncData(now.copyWith(
        items: [...now.items, ...page.items],
        nextCursor: () => page.nextCursor,
        loadingMore: false,
        loadMoreFailed: false,
      ));
    } catch (_) {
      final now = state.value;
      if (gen != _generation || now == null) return;
      state = AsyncData(now.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 移除成功后只删本地这一行：不整页重拉、不回顶、不丢已加载的后续页。
  void removeWhere(bool Function(T item) test) {
    final cur = state.value;
    if (cur == null) return;
    state = AsyncData(cur.copyWith(items: cur.items.where((e) => !test(e)).toList()));
  }

  void retry() => ref.invalidateSelf();
}

class CollectedPostsController extends _CollectionListController<CollectedPost> {
  @override
  CollectTarget get target => CollectTarget.post;

  @override
  Future<CollectionPageData<CollectedPost>> fetch(CollectRepository repo, {String? cursor}) =>
      repo.fetchCollectedPosts(cursor: cursor);

  void removeLocal(int postId) => removeWhere((e) => e.postId == postId);
}

class CollectedPlacesController extends _CollectionListController<CollectedPlace> {
  @override
  CollectTarget get target => CollectTarget.place;

  @override
  Future<CollectionPageData<CollectedPlace>> fetch(CollectRepository repo, {String? cursor}) =>
      repo.fetchCollectedPlaces(cursor: cursor);

  void removeLocal(String placeToken) => removeWhere((e) => e.placeToken == placeToken);
}

/// autoDispose：只在 Disimpan 且对应子分类可见时被 watch，切走即释放、切回重拉首页。
/// 🔴 已登记 `resetUserScopedCaches`：「我的」页常驻，Disimpan 可见时不回收 —— 不登记 = 换账号看到上个账号的收藏。
/// ⚠️ `retry: (_, _) => null`（同 bug 20261006-583）：Riverpod 3 默认自动重试会让失败态停在加载中。
final collectedPostsProvider =
    AsyncNotifierProvider.autoDispose<CollectedPostsController, CollectionListState<CollectedPost>>(
  CollectedPostsController.new,
  retry: (_, _) => null,
);

final collectedPlacesProvider =
    AsyncNotifierProvider.autoDispose<CollectedPlacesController, CollectionListState<CollectedPlace>>(
  CollectedPlacesController.new,
  retry: (_, _) => null,
);
