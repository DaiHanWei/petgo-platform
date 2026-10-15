import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../social/domain/follow_state.dart';
import '../data/feed_repository.dart';
import '../domain/feed_item.dart';
import 'feed_controller.dart';

/// 关注流控制器（V1.3.4 following 流）。与 [FeedController] 同签名同语义，
/// 只是取数改调 [FeedRepository.getFollowingFeed]（无分类 Tab、无宠物状态联动）。
class FollowingFeedController extends AsyncNotifier<FeedState> {
  /// build 代际：build 重跑（刷新 / 换账号 invalidate）后，在途 loadMore 的迟到结果据此丢弃，
  /// 否则会把旧列表 + 旧游标写回、覆盖新首屏（OCR 审查 #2，同 PostLikersController）。
  int _gen = 0;

  @override
  Future<FeedState> build() async {
    _gen++;
    // 同 FeedController：发请求前读全局关注版本号写进快照（Story 2.1 AC3.3）。
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final page = await ref.read(feedRepositoryProvider).getFollowingFeed();
    return FeedState(
      items: [for (final i in page.items) i.copyWith(followEpoch: epoch)],
      category: FeedCategory.all,
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      pagesLoaded: 1,
      rankMode: page.rankMode,
    );
  }

  /// 翻页：用 nextCursor 拉下一批并追加；失败保留已加载内容并置 loadMoreFailed。
  Future<void> loadMore({int pages = 1}) async {
    for (var n = 0; n < pages; n++) {
      final current = state.value;
      if (current == null || !current.hasMore || current.loadingMore || current.nextCursor == null) {
        return;
      }
      state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
      final epoch = ref.read(followStateProvider.notifier).currentEpoch;
      final gen = _gen;
      try {
        final page = await ref.read(feedRepositoryProvider).getFollowingFeed(cursor: current.nextCursor);
        if (!ref.mounted || gen != _gen) return;
        state = AsyncData(current.copyWith(
          items: [...current.items, for (final i in page.items) i.copyWith(followEpoch: epoch)],
          nextCursor: page.nextCursor,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
          pagesLoaded: current.pagesLoaded + 1,
          rankMode: RankMode.merge(current.rankMode, page.rankMode),
        ));
      } catch (_) {
        if (!ref.mounted || gen != _gen) return;
        state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
        return;
      }
    }
  }

  /// 举报成功后本地乐观移除该卡片。
  void removeItem(int postId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.id != postId).toList();
    if (filtered.length == current.items.length) return;
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 拉黑成功后本地乐观移除该作者的全部卡片。
  void removeByAuthor(int authorId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.authorId != authorId).toList();
    if (filtered.length == current.items.length) return;
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 按 postId 回写互动计数；不在列表 / 值没变 → 无操作。
  void syncCounts(int postId, {int? commentCount, int? likeCount, bool? liked}) {
    final current = state.value;
    if (current == null) return;
    var changed = false;
    final items = current.items.map((i) {
      if (i.id != postId) return i;
      final next = i.copyWith(commentCount: commentCount, likeCount: likeCount, liked: liked);
      if (next.commentCount != i.commentCount || next.likeCount != i.likeCount || next.liked != i.liked) {
        changed = true;
      }
      return next;
    }).toList();
    if (!changed) return;
    state = AsyncData(current.copyWith(items: items));
  }

  /// 下拉刷新：重建首屏（重置游标）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<FollowingFeedController, FeedState> followingFeedProvider =
    AsyncNotifierProvider<FollowingFeedController, FeedState>(FollowingFeedController.new);
