import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/feed_repository.dart';
import '../domain/feed_item.dart';
import '../domain/home_refresh_provider.dart';
import '../domain/pinned_slot.dart';

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
    this.rankMode = RankMode.unknown,
  });

  final List<FeedItem> items;
  final FeedCategory category;

  /// 本次刷新（含已翻的各页）实际用的排序路径（V1.1.6 Story 16.5，服务端下发）。
  ///
  /// ⚠️ 翻页时按 [RankMode.merge] 合并：首屏推荐序、第二页恰好降级的情况确实存在，
  /// 那种会话记成 [RankMode.mixed] —— 挑一边冒充会污染 FR-95 的效果归因。
  final String rankMode;
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
    String? rankMode,
  }) =>
      FeedState(
        items: items ?? this.items,
        category: category,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
        pagesLoaded: pagesLoaded ?? this.pagesLoaded,
        rankMode: rankMode ?? this.rankMode,
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
      rankMode: page.rankMode,
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
          rankMode: RankMode.merge(current.rankMode, page.rankMode),
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

  /// 拉黑成功后本地乐观移除**该作者的全部卡片**（V1.1.4 Story 1.2 AC2）。
  ///
  /// 与 [removeItem] 的区别是粒度：举报针对一条内容，拉黑针对一个人——只移除当前那张卡的话，
  /// 同屏里他别的帖子还在，用户会觉得「拉黑没生效」。下次刷新以后端过滤为权威（Story 1.1 AC3）。
  void removeByAuthor(int authorId) {
    final current = state.value;
    if (current == null) return;
    final filtered = current.items.where((i) => i.authorId != authorId).toList();
    if (filtered.length == current.items.length) return; // 当前列表没有他的内容：无操作
    state = AsyncData(current.copyWith(items: filtered));
  }

  /// 按 postId 回写互动计数（bug 20260923-538）：详情页发评论 / 点赞后，Feed 快照不会自己变，
  /// 返回 Social 仍显示旧数（下拉刷新才对）。详情页拿到新值后调这里就地改那一张卡。
  /// 不在当前列表 / 值没变 → 无操作（不触发重建）。
  void syncCounts(int postId, {int? commentCount, int? likeCount, bool? liked}) {
    final current = state.value;
    if (current == null) return;
    var changed = false;
    final items = current.items.map((i) {
      if (i.id != postId) return i;
      final next = i.copyWith(
          commentCount: commentCount, likeCount: likeCount, liked: liked);
      if (next.commentCount != i.commentCount ||
          next.likeCount != i.likeCount ||
          next.liked != i.liked) {
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

final AsyncNotifierProvider<FeedController, FeedState> feedProvider =
    AsyncNotifierProvider<FeedController, FeedState>(FeedController.new);

/// 详情页 → Feed 计数回写入口（bug 20260923-538）。
///
/// 🔴 先判 [Ref.exists]：从通知深链直接进详情时 Feed 可能还没建，`ref.read(feedProvider.notifier)`
/// 会顺手把它建出来并发一次首屏请求 —— 为了回写一个数去拉整页 Feed 不值当，没建就不写。
/// ⚠️ 只覆盖 [feedProvider]（Social 全部 Tab 共用这一个实例，切 Tab 是同一实例重建）。
/// 顶置卡（pinnedSlotProvider）是独立快照，不在此回写。
void syncFeedCounts(WidgetRef ref, int postId,
    {int? commentCount, int? likeCount, bool? liked}) {
  if (!ref.exists(feedProvider)) return;
  ref.read(feedProvider.notifier).syncCounts(postId,
      commentCount: commentCount, likeCount: likeCount, liked: liked);
}


/// 顶置坑位（V1.1.6 Story 4.2 · FR-68）。
///
/// 🛡 **取数失败一律当作"没有顶置"** —— AC 明写"顶置取数失败不得连带整个首页失败"。
/// 一个运营位不该把主功能带崩，所以这里吞掉异常返回 null，而不是把错误抛给首页的状态机。
final FutureProvider<PinnedSlot?> pinnedSlotProvider =
    FutureProvider<PinnedSlot?>((ref) async {
  try {
    return await ref.read(feedRepositoryProvider).getPinnedSlot();
  } catch (_) {
    return null;
  }
});
