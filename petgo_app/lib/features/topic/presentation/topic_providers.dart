import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../content/domain/feed_item.dart';
import '../../content/domain/pinned_slot.dart';
import '../../content/domain/post_like_state.dart';
import '../../social/domain/follow_state.dart';
import '../data/topic_repository.dart';
import '../domain/topic.dart';

/// 话题页 / 首页话题行的 providers（V1.4.2 social-topic · Story 4.3 · AC2 / AC3 / AC4）。
///
/// 🔴 全部 `retry: null`：Riverpod 3 默认失败自动重试，话题页的失败要停在 C11（用户点「Coba lagi」重来），
/// 首页话题行失败要停在「整行不渲染」——自动重试会让它们在有 / 无之间横跳。

/// 话题详情。404 → [TopicNotFoundException]（C10），其余失败 → C11。
final topicDetailProvider = FutureProvider.autoDispose.family<TopicDetail, String>(
  (ref, name) => ref.read(topicRepositoryProvider).getDetail(name),
  retry: (_, _) => null,
);

/// 话题坑位顶置。🛡 **失败一律当作没有顶置**（同首页 `pinnedSlotProvider` 口径：运营位不得连带页面失败）。
final topicPinnedProvider = FutureProvider.autoDispose.family<PinnedSlot?, String>(
  (ref, name) async {
    try {
      // 顶置卡同样在发请求前记关注版本号（V1.3.4 Story 2.1 AC3.3）。
      final epoch = ref.read(followStateProvider.notifier).currentEpoch;
      final likeEpoch = ref.read(postLikeStoreProvider.notifier).currentEpoch; // V1.5.0 FR-210
      return (await ref.read(topicRepositoryProvider).getPinnedSlot(name))
          ?.withFollowEpoch(epoch)
          .withLikeEpoch(likeEpoch);
    } catch (_) {
      return null;
    }
  },
  retry: (_, _) => null,
);

/// 空态推荐话题（≤10）。取数失败 → 空表（标题与列表整段不渲染，AC2.4）。
final topicRelatedProvider = FutureProvider.autoDispose.family<List<TopicSummary>, String>(
  (ref, name) async {
    try {
      return await ref.read(topicRepositoryProvider).getRelated(name);
    } catch (_) {
      return const <TopicSummary>[];
    }
  },
  retry: (_, _) => null,
);

/// Discover 首页话题行（≤5）。照 `petRecommendationsProvider`：autoDispose + 关自动重试；
/// 首页下拉刷新时 `ref.invalidate(homeTopicRowProvider)`（漏刷会让断网首启后整行永久消失）。
final homeTopicRowProvider = FutureProvider.autoDispose<List<TopicSummary>>(
  (ref) => ref.read(topicRepositoryProvider).getHomeRow(),
  retry: (_, _) => null,
);

/// 话题帖子列表的 family 键：（标准名或路由名, 排序）。两个页签**各自一个实例** = 各自缓存，切换不重拉。
typedef TopicPostsKey = (String name, TopicSort sort);

/// 话题帖子分页态（照 `FeedState`：items / hasMore / loadingMore / loadMoreFailed）。
class TopicPostsState {
  const TopicPostsState({
    required this.items,
    this.nextCursor,
    this.nextPage,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final List<FeedItem> items;

  /// Terbaru 的下一页游标（服务端下发）。
  final String? nextCursor;

  /// Populer 的下一页页码（App 自己递增；服务端不下发 nextCursor）。
  final int? nextPage;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;

  bool get isEmpty => items.isEmpty;

  TopicPostsState copyWith({
    List<FeedItem>? items,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      TopicPostsState(
        items: items ?? this.items,
        nextCursor: nextCursor,
        nextPage: nextPage,
        hasMore: hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// Populer 页码上限（后端 `TOPIC_POPULAR_MAX_PAGE`，超了 422）。到顶即视为没有更多。
const int kTopicPopularMaxPage = 50;

/// 话题帖子列表 controller（AC3.2）：Terbaru 游标翻页、Populer 页码翻页（`hasMore=false` 停）。
class TopicPostsController extends AsyncNotifier<TopicPostsState> {
  TopicPostsController(this.key);

  final TopicPostsKey key;

  String get _name => key.$1;
  TopicSort get _sort => key.$2;

  @override
  Future<TopicPostsState> build() async {
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final likeEpoch = ref.read(postLikeStoreProvider.notifier).currentEpoch; // V1.5.0 FR-210
    final page = await ref
        .read(topicRepositoryProvider)
        .getPosts(_name, sort: _sort, page: _sort == TopicSort.popular ? 1 : null);
    return _stateFrom(page, epoch, likeEpoch, const [], 1);
  }

  TopicPostsState _stateFrom(FeedPage page, int epoch, int likeEpoch, List<FeedItem> before, int pageNo) {
    final have = {for (final i in before) i.id};
    final items = [
      ...before,
      for (final i in page.items)
        if (!have.contains(i.id)) i.copyWith(followEpoch: epoch, likeEpoch: likeEpoch),
    ];
    final popular = _sort == TopicSort.popular;
    final hasMore = popular
        ? page.hasMore && pageNo < kTopicPopularMaxPage
        : page.hasMore && page.nextCursor != null;
    return TopicPostsState(
      items: items,
      nextCursor: popular ? null : page.nextCursor,
      nextPage: popular && hasMore ? pageNo + 1 : null,
      hasMore: hasMore,
    );
  }

  /// 距底预加载 / 底部重试：续拉下一页；失败保留已加载内容、置 [TopicPostsState.loadMoreFailed]。
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.loadingMore) return;
    final popular = _sort == TopicSort.popular;
    if (popular ? current.nextPage == null : current.nextCursor == null) return;
    state = AsyncData(current.copyWith(loadingMore: true, loadMoreFailed: false));
    final epoch = ref.read(followStateProvider.notifier).currentEpoch;
    final likeEpoch = ref.read(postLikeStoreProvider.notifier).currentEpoch;
    try {
      final page = await ref.read(topicRepositoryProvider).getPosts(
            _name,
            sort: _sort,
            cursor: popular ? null : current.nextCursor,
            page: popular ? current.nextPage : null,
          );
      if (!ref.mounted) return;
      state = AsyncData(_stateFrom(page, epoch, likeEpoch, current.items, popular ? current.nextPage! : 0));
    } catch (_) {
      if (!ref.mounted) return;
      state = AsyncData(current.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 举报成功 → 本页乐观移除该帖。
  void removeItem(int postId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.id != postId).toList();
    if (filtered.length == current.items.length) return;
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 拉黑 / 举报作者成功 → 本页乐观移除该作者全部卡片。
  void removeByAuthor(int authorId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.authorId != authorId).toList();
    if (filtered.length == current.items.length) return;
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 下拉刷新：重拉当前页签首屏。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

/// autoDispose：随话题页关闭回收；换账号时由 `resetUserScopedCaches` 整族失效（点赞 / 关注快照相对查看者）。
final topicPostsProvider =
    AsyncNotifierProvider.autoDispose.family<TopicPostsController, TopicPostsState, TopicPostsKey>(
  TopicPostsController.new,
  retry: (_, _) => null,
);
