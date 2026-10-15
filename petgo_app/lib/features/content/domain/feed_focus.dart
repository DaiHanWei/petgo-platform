import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 「去 Feed 看刚发的帖」的待定位信号（bug 20261012-586）。
///
/// 发布成功页点「去 Feed 看」→ [request] 记下新帖 id → Discover Feed 重拉；
/// Feed 首屏到手且含该帖 → 滚到它（紧贴顶置卡下方）并短暂高亮 → [clear]。
/// 首屏里没有（老后端 / 排序没排上）→ `FeedController` 用 `ownPostLookupProvider`
/// （`data/own_post_lookup.dart`）取回这一条、本地插到自然流第一位，再走同一套定位。
///
/// ⚠️ 一次性信号：定位完成或放弃都必须 [clear]，否则下次下拉刷新会再滚一次。
class PendingFeedFocusNotifier extends Notifier<int?> {
  @override
  int? build() => null;

  void request(int postId) => state = postId;

  void clear() => state = null;
}

final NotifierProvider<PendingFeedFocusNotifier, int?> pendingFeedFocusProvider =
    NotifierProvider<PendingFeedFocusNotifier, int?>(PendingFeedFocusNotifier.new);
