import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/pet_recommendation_repository.dart';

/// 全屏推荐集合页的累积翻页（V1.3.0 batch-b1 Story 4.3 · AC3）。
///
/// <h2>🔴 为什么不是 `FutureProvider` + invalidate</h2>
/// 推荐是**游标分页累积**的：用户翻到第三页时 `invalidate` 会重拉全部并把列表打回第一页，
/// 他会莫名其妙被弹回顶部。累积状态必须由控制器自己持有，「加载更多」只往后追加
/// （与 `place_comments_controller.dart` / `feed_controller.dart` 的既定范式逐字相同）。
///
/// <h2>🛡 增量失败**不动已加载内容**（F13 / NFR-10）</h2>
/// 第二页失败时列表照旧是那 10 只，底部给一个重试入口 —— 把整屏换成错误态
/// 正是这条口径首先要避免的事。所以 [loadMore] 的失败落在 [loadMoreFailed] 上，
/// **不写进 `state`**（写进去就会把 `AsyncData` 换成 `AsyncError`，整屏变错误态）。
///
/// <p>`isAutoDispose: true`：集合页是 push 进来的一次性页面，退出就该回收 ——
/// Riverpod 3 的 legacy `AsyncNotifierProvider` 默认是 **false**（不自动回收），
/// 不显式打开的话逛一次就永久挂着一份（并且下次进来看到的是上次的旧排名）。
class PetRecommendationListController extends AsyncNotifier<RecommendedPetPage> {
  /// 每页几只。集合页是 2 列网格，20 = 10 行，一次滚动看得完。
  static const int pageSize = 20;

  /// 上一次「加载更多」是否失败了（供底部重试入口显示）。
  ///
  /// ⚠️ 刻意不放进 `state`：放进去就是把整屏换成错误态（见类注释）。
  bool loadMoreFailed = false;

  /// 正在追加下一页（触底会连发好几次，去重靠它；也供底部 loading 指示）。
  bool isLoadingMore = false;

  @override
  Future<RecommendedPetPage> build() {
    return ref.read(petRecommendationRepositoryProvider).recommendations(limit: pageSize);
  }

  /// 一次 [loadMore] 最多连抓几页。
  ///
  /// 🔴 为什么要连抓：服务端会回**空 items + 有游标 + hasMore=true**
  /// （这一页的宠物全被拉黑 / 注销 / 没头像过滤掉了）。只抓一页就收手的话，
  /// 用户停在列表底部、**滚不动了也就不会再触发下一次** —— 表现是「列表莫名断掉，
  /// 而后面明明还有」。所以拿到空页就接着往下抓，直到抓到东西。
  ///
  /// ⚠️ 上限存在是因为「接着抓」不能变成无限抓：真被过滤空一大片时，
  /// 与其把用户的流量耗在一串空页上，不如让他自己再往下滑一次。
  static const int maxChainedFetches = 3;

  /// 连着几轮都没抓到任何新内容之后，**不再自动续抓**。
  ///
  /// 🔴 为什么需要它：屏幕没被填满时（一页只剩 1 只，或干脆 0 只）用户滚不动，
  /// 页面只能靠「渲染完自己再抓一次」把列表续上 —— 而那条路径如果没有上限，
  /// 服务端连续给空页就会变成一个**不停发请求的死循环**（用户什么都没做）。
  /// 上限用完之后列表就停在那儿：池子后面确实还有，但再往下挖的性价比已经没了。
  static const int maxAutoEmptyRounds = 2;

  int _emptyRounds = 0;

  /// 还能不能「自动」续抓（用户手势触发的那条路径不受此限）。
  bool get canAutoLoad => _emptyRounds < maxAutoEmptyRounds;

  /// 追加下一页（必要时连抓几页，见 [maxChainedFetches]）。
  ///
  /// 🔴 判「还有没有」只看 `hasMore`，不看 items 空不空。
  Future<void> loadMore() async {
    final current = state.value;
    if (current == null || !current.hasMore || current.nextCursor == null) {
      return;
    }
    if (isLoadingMore) {
      return; // 触底会连发好几次，去重靠这一条（否则同一页被追加两遍）
    }
    isLoadingMore = true;
    RecommendedPetPage acc = current;
    try {
      String? cursor = current.nextCursor;
      for (int i = 0; cursor != null && i < maxChainedFetches; i++) {
        final next = await ref
            .read(petRecommendationRepositoryProvider)
            .recommendations(limit: pageSize, cursor: cursor);
        acc = acc.append(next);
        cursor = next.hasMore ? next.nextCursor : null;
        if (next.items.isNotEmpty) {
          break; // 有新内容了就停手，把往下滑的主动权交回用户
        }
      }
      loadMoreFailed = false;
      // 这一轮到底有没有捞到新东西 —— 决定还要不要放行「自动续抓」。
      _emptyRounds = acc.items.length > current.items.length ? 0 : _emptyRounds + 1;
      state = AsyncData(acc);
    } catch (_) {
      // 🛡 已加载的内容一个不动（F13）—— 连抓途中失败时，前面抓到的也留着；
      //    调用方据 loadMoreFailed 摆底部重试入口。
      loadMoreFailed = true;
      state = AsyncData(acc);
    } finally {
      isLoadingMore = false;
    }
  }

  /// 第一页失败后的重试（整屏错误态上那个按钮）。
  Future<void> retryFirstPage() async {
    loadMoreFailed = false;
    _emptyRounds = 0;
    state = const AsyncLoading();
    state = await AsyncValue.guard(
        () => ref.read(petRecommendationRepositoryProvider).recommendations(limit: pageSize));
  }
}

final petRecommendationListProvider =
    AsyncNotifierProvider<PetRecommendationListController, RecommendedPetPage>(
  PetRecommendationListController.new,
  isAutoDispose: true,
  // ⚠️ 关掉自动重试：与 Diary 那两个推荐位同一个理由 —— 页面会在错误态与列表之间横跳，
  //    而 F13 要的是「保留已加载内容 + 给一个**用户主动**点的重试入口」。
  retry: (_, _) => null,
);
