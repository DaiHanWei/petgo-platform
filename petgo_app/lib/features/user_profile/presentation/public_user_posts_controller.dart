import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/public_user_posts_repository.dart';

/// 他人主页内容区控制器（V1.3.0 batch-b1 Story 2.2），按 userId 分族。
///
/// ## 🔴 为什么不是一个 `FutureProvider` + `invalidate`
/// 内容是**游标分页累积**的：用户翻到第三页时 `invalidate` 会把前三页全部重拉、
/// 并把网格打回第一屏 —— 他会莫名其妙被弹回顶部。累积状态必须由控制器自己持有，
/// 「加载更多」只往后追加（同 `place_comments_controller` / `feed_controller` 的既定范式）。
///
/// ## `isAutoDispose: true`
/// 主页是 push 进来的一次性页面，退出就该回收 —— Riverpod 3 的 legacy
/// `AsyncNotifierProvider` 默认是 **false**，不显式打开的话每看过一个人就永久挂一个族
/// （与 `publicProfileProvider` 同一条理由，那边还连带着换账号串数据的隐患）。
class PublicUserPostsController extends AsyncNotifier<PublicUserPostPage> {
  /// ⚠️ Riverpod 3 的 class family：族参数**从构造器进来**（provider 的工厂签名是
  /// `(Arg) -> Notifier`），`build()` 本身仍然不带参数。
  PublicUserPostsController(this.userId);

  final int userId;

  @override
  Future<PublicUserPostPage> build() {
    return ref.read(publicUserPostsRepositoryProvider).fetch(userId);
  }

  /// 追加下一页。
  ///
  /// 失败**不改动已加载内容**：保留现有网格，由调用方提示一声即可 ——
  /// 把整屏换成错误态是这条口径首先要避免的事（同场所评论区）。
  /// 在途的那次「加载更多」。🔴 连点两下会用同一个游标发两次请求、把同一页追加两遍
  /// （重复条目 + 重复 ValueKey），所以在途时直接复用它（batch-b1 复审）。
  Future<void>? _loadingMore;

  Future<void> loadMore() => _loadingMore ??= _loadMore().whenComplete(() => _loadingMore = null);

  Future<void> _loadMore() async {
    final current = state.value;
    final cursor = current?.nextCursor;
    if (current == null || !current.hasMore || cursor == null) return;
    final next = await ref.read(publicUserPostsRepositoryProvider).fetch(userId, cursor: cursor);
    // 等待期间页面走了（autoDispose）或列表被整体重拉过：这一页已经不属于当前列表，丢掉。
    if (!ref.mounted || !identical(state.value, current)) return;
    state = AsyncData(current.append(next));
  }
}

/// ⚠️ `retry: (_, _) => null` 是**关掉 Riverpod 3 的自动重试**，不是漏写
/// （同 `publicProfileProvider` / `blockedUsersProvider`）：放任它的话，内容区会在
/// 「错误态」与「加载中」之间自己反复横跳，用户点不到那个「重试」按钮。
final publicUserPostsProvider =
    AsyncNotifierProvider.family<PublicUserPostsController, PublicUserPostPage, int>(
  PublicUserPostsController.new,
  retry: (_, _) => null,
  isAutoDispose: true,
);
