import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/follow_list_repository.dart';
import '../domain/follow_list_item.dart';
import '../domain/follow_state.dart';

/// 一个 Tab 的分页状态（照 `OrderListState`）。
class FollowListState {
  const FollowListState({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final List<FollowListItem> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;

  FollowListState copyWith({
    List<FollowListItem>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      FollowListState(
        items: items ?? this.items,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// family key：主页用户 token + Tab（两个 Tab 各自独立分页状态，切换不丢已加载数据）。
typedef FollowListKey = ({String token, FollowListTab tab});

/// 关注 / 粉丝列表 controller（V1.3.4 follow · Story 2.2 · AC3.4）。
///
/// 每页快照**发请求前**记全局关注版本号（Story 2.1 AC3.3）：下拉刷新 = 这个位置刷新了，
/// 本页之前写的覆盖对新快照自动失效（「刷新数据后覆盖清除」）。
class FollowListController extends AsyncNotifier<FollowListState> {
  FollowListController(this.key);

  final FollowListKey key;

  @override
  Future<FollowListState> build() async {
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final page = await ref.read(followListRepositoryProvider).fetch(key.token, key.tab);
    return FollowListState(
      items: [for (final i in page.items) i.withFollowEpoch(epoch)],
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
    );
  }

  /// 滚到底 / 底部「Coba lagi」：拉下一页追加；失败保留已加载内容、置 [FollowListState.loadMoreFailed]。
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.loadingMore || current.nextCursor == null) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    try {
      final page = await ref
          .read(followListRepositoryProvider)
          .fetch(key.token, key.tab, cursor: current.nextCursor);
      state = AsyncData(FollowListState(
        items: [...current.items, for (final i in page.items) i.withFollowEpoch(epoch)],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
      ));
    } catch (_) {
      state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 下拉刷新当前 Tab（重置游标）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

/// ⚠️ `retry: null` 关掉 Riverpod 3 的自动重试（同 `blockedUsersProvider`）：失败停在失败态，
/// 由用户点「Coba lagi」重试，页面不在错误态与加载中之间横跳。
final followListControllerProvider =
    AsyncNotifierProvider.autoDispose.family<FollowListController, FollowListState, FollowListKey>(
  FollowListController.new,
  retry: (_, _) => null,
);
