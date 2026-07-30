import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/pawcoin_repository.dart';
import '../domain/pawcoin_transaction.dart';

/// PawCoin 余额页状态（Story 1.4）。余额 + 已加载流水 + 游标 + 加载更多态。
class PawCoinState {
  const PawCoinState({
    required this.balance,
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  final int balance;
  final List<PawCoinTxnItem> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;

  PawCoinState copyWith({
    int? balance,
    List<PawCoinTxnItem>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
  }) =>
      PawCoinState(
        balance: balance ?? this.balance,
        items: items ?? this.items,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// 余额 + 流水加载（照 `content/presentation/feed_controller.dart` 的 AsyncNotifier 范式）。
/// 初始 build 失败 → `.when` 错误态 + 重试（不静默画空态，bug 20260625-088）；
/// loadMore 失败 → 保留已加载 + 底部重试，不整屏报错、不清空、不回顶。
class PawCoinController extends AsyncNotifier<PawCoinState> {
  @override
  Future<PawCoinState> build() async {
    final page = await ref.read(pawCoinRepositoryProvider).fetch();
    return PawCoinState(
      balance: page.balance,
      items: page.items,
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
    );
  }

  Future<void> loadMore() async {
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore || cur.nextCursor == null) return;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    try {
      final page = await ref.read(pawCoinRepositoryProvider).fetch(cursor: cur.nextCursor);
      state = AsyncData(cur.copyWith(
        items: [...cur.items, ...page.items],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        loadingMore: false,
      ));
    } catch (_) {
      // 保留已加载内容，底部「点击重试」；不整屏报错、不回顶。
      state = AsyncData(cur.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<PawCoinController, PawCoinState> pawCoinProvider =
    AsyncNotifierProvider<PawCoinController, PawCoinState>(PawCoinController.new);
