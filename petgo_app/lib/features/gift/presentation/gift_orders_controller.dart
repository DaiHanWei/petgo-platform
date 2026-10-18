import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/gift_repository.dart';
import '../domain/gift_order_history.dart';

/// 我的送礼记录分页状态（V1.4.0 gift Story 2.3 · AC4.2）。
class GiftOrdersState {
  const GiftOrdersState({
    required this.items,
    this.nextCursor,
    this.loadingMore = false,
    this.loadMoreFailed = false,
  });

  /// 接口顺序（服务端已按时间倒序），客户端不重排。
  final List<GiftOrderRecord> items;
  final String? nextCursor;
  final bool loadingMore;

  /// 下一页失败：已加载的行保留，底部行内重试（同钱包页）。
  final bool loadMoreFailed;

  bool get hasMore => nextCursor != null;

  GiftOrdersState copyWith({bool? loadingMore, bool? loadMoreFailed}) => GiftOrdersState(
        items: items,
        nextCursor: nextCursor,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
      );
}

/// 送礼记录 controller（照 [GiftSupportersController] / `PawCoinController` 的加载更多写法）。
/// 首页失败 = provider 错误态（整页 F4）；下一页失败 = [GiftOrdersState.loadMoreFailed]。
class GiftOrdersController extends AsyncNotifier<GiftOrdersState> {
  /// build 代际：build 重跑（下拉刷新）后，旧的 loadMore 结果据此丢弃。
  int _gen = 0;

  @override
  Future<GiftOrdersState> build() async {
    _gen++;
    final page = await ref.read(giftRepositoryProvider).getMyGiftOrders();
    return GiftOrdersState(items: page.items, nextCursor: page.nextCursor);
  }

  Future<void> loadMore() async {
    // 刷新进行中：等新首页落定再续页，避免旧游标结果盖掉新首页。
    if (state.isLoading) return;
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore) return;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    final gen = _gen;
    try {
      final page = await ref.read(giftRepositoryProvider).getMyGiftOrders(cursor: cur.nextCursor);
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(GiftOrdersState(
        items: [...cur.items, ...page.items],
        nextCursor: page.nextCursor,
      ));
    } catch (_) {
      if (!ref.mounted || gen != _gen) return;
      state = AsyncData(cur.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 下拉刷新 / F4 重试：重新拉首页。失败由 provider 的 error 态承载，这里吞掉。
  Future<void> refresh() async {
    if (!ref.mounted) return;
    ref.invalidateSelf();
    try {
      await future;
    } catch (_) {}
  }
}

/// 🔴 autoDispose：每次进入页面都重新拉取（QRIS 付款后再进来即变「Terkirim」，AC10b）；换账号不吃旧缓存。
/// `retry: null`：关掉 Riverpod 3 自动重试，失败直接停在 F4 由用户点「Coba lagi」。
final giftOrdersProvider = AsyncNotifierProvider.autoDispose<GiftOrdersController, GiftOrdersState>(
  GiftOrdersController.new,
  retry: (_, _) => null,
);
