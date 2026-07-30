import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/order_repository.dart';
import '../domain/order_summary.dart';

/// 订单中心列表状态（Story 5.2）。已加载订单 + 游标 + 加载更多态 + PawCoin 余额 + 当前类型筛选。
class OrderListState {
  const OrderListState({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
    this.pawcoinBalance = 0,
    this.filter,
  });

  final List<OrderSummary> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;
  final int pawcoinBalance;

  /// 当前类型筛选（null=全部）。
  final OrderType? filter;

  OrderListState copyWith({
    List<OrderSummary>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
    int? pawcoinBalance,
    OrderType? filter,
  }) =>
      OrderListState(
        items: items ?? this.items,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
        pawcoinBalance: pawcoinBalance ?? this.pawcoinBalance,
        filter: filter ?? this.filter,
      );
}

/// 订单中心控制器（游标分页 + 类型筛选）。照 `pawcoin_controller` AsyncNotifier 范式。
class OrderListController extends AsyncNotifier<OrderListState> {
  OrderType? _filter;

  @override
  Future<OrderListState> build() async {
    final page = await ref.read(orderRepositoryProvider).fetchOrders(type: _filter);
    return OrderListState(
      items: page.items,
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      pawcoinBalance: page.pawcoinBalance,
      filter: _filter,
    );
  }

  /// 切换类型筛选（null=全部）→ 重置列表 + 游标重拉。
  Future<void> setFilter(OrderType? type) async {
    if (_filter == type) return;
    _filter = type;
    state = const AsyncLoading();
    state = await AsyncValue.guard(build);
  }

  /// 加载更多（游标追加，失败保留已加载 + 底部重试）。
  Future<void> loadMore() async {
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore || cur.nextCursor == null) return;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    try {
      final page = await ref
          .read(orderRepositoryProvider)
          .fetchOrders(type: cur.filter, cursor: cur.nextCursor);
      state = AsyncData(cur.copyWith(
        items: [...cur.items, ...page.items],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        loadingMore: false,
      ));
    } catch (_) {
      state = AsyncData(cur.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<OrderListController, OrderListState> orderListProvider =
    AsyncNotifierProvider<OrderListController, OrderListState>(OrderListController.new);
