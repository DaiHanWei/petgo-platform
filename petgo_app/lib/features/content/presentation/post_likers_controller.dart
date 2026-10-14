import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../social/domain/follow_list_item.dart';
import '../../social/domain/follow_state.dart';
import '../data/post_likers_repository.dart';

/// 点赞人列表分页状态。[gone] = 帖子已不存在 / 对我不可见（404），UI 提示并关抽屉。
class LikersState {
  const LikersState({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
    this.gone = false,
  });

  const LikersState.gone() : this(items: const [], gone: true);

  final List<FollowListItem> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;
  final bool gone;

  LikersState copyWith({bool? loadingMore, bool? loadMoreFailed}) => LikersState(
        items: items,
        nextCursor: nextCursor,
        hasMore: hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
        gone: gone,
      );
}

bool _is404(Object e) => e is DioException && e.response?.statusCode == 404;

/// 点赞人列表 controller（V1.3.4）。每页**发请求前**记全局关注版本号（同关注列表），
/// 使 FollowButton 的覆盖逻辑一致。
class PostLikersController extends AsyncNotifier<LikersState> {
  PostLikersController(this.postId);

  final int postId;

  /// build 代际：build 重跑（retry / invalidate）后，旧的 loadMore 结果据此丢弃。
  int _gen = 0;

  @override
  Future<LikersState> build() async {
    _gen++;
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    try {
      final page = await ref.read(postLikersRepositoryProvider).getLikers(postId);
      return LikersState(
        items: [for (final i in page.items) i.withFollowEpoch(epoch)],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
      );
    } catch (e) {
      if (_is404(e)) return const LikersState.gone();
      rethrow;
    }
  }

  Future<void> loadMore() async {
    final current = state.value;
    if (current == null ||
        current.gone ||
        !current.hasMore ||
        current.loadingMore ||
        current.nextCursor == null) {
      return;
    }
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final gen = _gen;
    try {
      final page =
          await ref.read(postLikersRepositoryProvider).getLikers(postId, cursor: current.nextCursor);
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(LikersState(
        items: [...current.items, for (final i in page.items) i.withFollowEpoch(epoch)],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
      ));
    } catch (e) {
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(_is404(e)
          ? const LikersState.gone()
          : current.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 首页失败后的重试（重新 build）。
  Future<void> retry() async {
    if (!ref.mounted) return;
    ref.invalidateSelf();
    await future;
  }
}

/// `retry: null` 关掉 Riverpod 3 自动重试：失败停在错误态，由用户点重试。
final postLikersProvider =
    AsyncNotifierProvider.autoDispose.family<PostLikersController, LikersState, int>(
  PostLikersController.new,
  retry: (_, _) => null,
);
