import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/followed_pet_repository.dart';
import '../domain/followed_pet.dart';
import '../domain/pet_follow_state.dart';

/// 「某用户追更的宠物」分页状态（V1.4.2 social-topic · Story 1.5 · AC9，照 `FollowListState`）。
class FollowedPetListState {
  const FollowedPetListState({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
    this.petFollowingCount,
  });

  final List<FollowedPet> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;

  /// 首页信封的 `petFollowingCount`（拍板②：「Hewan N」胶囊以它校准主页值）；旧后端缺键 → null。
  /// 加载更多**不覆盖**（后续页不下发）。
  final int? petFollowingCount;

  FollowedPetListState copyWith({
    List<FollowedPet>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      FollowedPetListState(
        items: items ?? this.items,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
        petFollowingCount: petFollowingCount,
      );
}

/// 「我（或别人）追更的宠物」列表 controller（V1.4.2 social-topic · Story 1.5 · AC9），family 键 = 主页用户 token。
///
/// 每页快照**发请求前**记 `petFollowStateProvider` 的全局追更版本号：下拉刷新 = 新快照，
/// 本会话之前写的追更覆盖对新快照自动失效（刚取消的那行刷新后消失，同 1.3.4 B2「刚取关」规则）。
/// 🔴 失败停在错误态（`retry: null`），**不画成空态**。
class FollowedPetListController extends AsyncNotifier<FollowedPetListState> {
  FollowedPetListController(this.token);

  final String token;

  @override
  Future<FollowedPetListState> build() async {
    final epoch = ref.read(petFollowStateProvider.notifier).currentEpoch;
    final page = await ref.read(followedPetRepositoryProvider).fetch(token);
    return FollowedPetListState(
      items: [for (final p in page.items) p.withFollowEpoch(epoch)],
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      petFollowingCount: page.petFollowingCount,
    );
  }

  /// 滚到底 / 底部「Coba lagi」：拉下一页追加；失败保留已加载内容、置 [FollowedPetListState.loadMoreFailed]。
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.loadingMore || current.nextCursor == null) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final epoch = ref.read(petFollowStateProvider.notifier).currentEpoch;
    try {
      final page = await ref.read(followedPetRepositoryProvider).fetch(token, cursor: current.nextCursor);
      if (!ref.mounted) return;
      state = AsyncData(FollowedPetListState(
        items: [...current.items, for (final p in page.items) p.withFollowEpoch(epoch)],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        petFollowingCount: current.petFollowingCount,
      ));
    } catch (_) {
      if (!ref.mounted) return;
      state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 下拉刷新（重置游标）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

/// autoDispose：随列表页关闭回收；仍登记 `resetUserScopedCaches`（关系字段相对当前查看者，换账号必须重取）。
final followedPetListControllerProvider =
    AsyncNotifierProvider.autoDispose.family<FollowedPetListController, FollowedPetListState, String>(
  FollowedPetListController.new,
  retry: (_, _) => null,
);
