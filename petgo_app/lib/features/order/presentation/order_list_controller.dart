import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../shop/data/shop_order_repository.dart';
import '../../shop/domain/shop_order_summary.dart';
import '../data/order_repository.dart';
import '../domain/order_summary.dart';

/// 订单中心列表状态（Story 5.2）。已加载订单 + 游标 + 加载更多态 + PawCoin 余额 + 当前类型筛选。
///
/// v1.4.2 shop Story 9.1：一级筛选为「Belanja」（[OrderType.ecommerce]）时改走电商订单接口
/// `GET /me/shop-orders?group=`，数据落 [shopItems]、二级分组 [shopGroup]；[items] 此时为空。
/// 其余一级筛选（含「Semua」混排）仍走 `/orders`，行为不变。
class OrderListState {
  const OrderListState({
    required this.items,
    this.nextCursor,
    this.hasMore = false,
    this.loadingMore = false,
    this.loadMoreFailed = false,
    this.pawcoinBalance = 0,
    this.filter,
    this.shopGroup = ShopOrderGroup.all,
    this.shopItems = const [],
  });

  final List<OrderSummary> items;
  final String? nextCursor;
  final bool hasMore;
  final bool loadingMore;
  final bool loadMoreFailed;
  final int pawcoinBalance;

  /// 当前类型筛选（null=全部）。
  final OrderType? filter;

  /// 「Belanja」下的二级分组（只在 [isShop] 时有意义；切一级标签重置为 [ShopOrderGroup.all]）。
  final ShopOrderGroup shopGroup;

  /// 「Belanja」下的电商订单卡。
  final List<ShopOrderSummary> shopItems;

  bool get isShop => filter == OrderType.ecommerce;

  /// 当前列表是否为空（按所在分支判断）。
  bool get isEmpty => isShop ? shopItems.isEmpty : items.isEmpty;

  OrderListState copyWith({
    List<OrderSummary>? items,
    String? nextCursor,
    bool? hasMore,
    bool? loadingMore,
    bool? loadMoreFailed,
    int? pawcoinBalance,
    OrderType? filter,
    ShopOrderGroup? shopGroup,
    List<ShopOrderSummary>? shopItems,
  }) =>
      OrderListState(
        items: items ?? this.items,
        nextCursor: nextCursor ?? this.nextCursor,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreFailed: loadMoreFailed ?? this.loadMoreFailed,
        pawcoinBalance: pawcoinBalance ?? this.pawcoinBalance,
        filter: filter ?? this.filter,
        shopGroup: shopGroup ?? this.shopGroup,
        shopItems: shopItems ?? this.shopItems,
      );
}

/// 订单中心控制器（游标分页 + 类型筛选）。照 `pawcoin_controller` AsyncNotifier 范式。
///
/// 🔴 竞态（v1.4.2 shop Story 9.1 复审）：快速切一级 / 二级标签时，先发的请求可能后到。
/// 每次「换一批数据」（[build] / [setFilter] / [setShopGroup] / [refresh]）都自增 [_generation]，
/// 请求回来时代次已变就丢弃；[build] 在 await 前快照筛选与分组；[loadMore] 回写前校验代次与筛选 / 分组未变。
class OrderListController extends AsyncNotifier<OrderListState> {
  OrderType? _filter;
  ShopOrderGroup _shopGroup = ShopOrderGroup.all;

  /// 请求代次：每换一批数据 +1；旧代次的结果一律丢弃。
  int _generation = 0;

  /// 列表卡倒计时归零已触发过刷新的订单（按 token 记，同一单只刷一次；跨列表重建保留）。
  final Set<String> _expiryRefreshed = {};

  /// 当前一级筛选 / 二级分组（加载中 state 无值时，页面据此保持标签选中态不闪回「全部」）。
  OrderType? get currentFilter => _filter;
  ShopOrderGroup get currentShopGroup => _shopGroup;

  /// 列表卡倒计时归零：该单首次归零返回 true（调用方据此刷新列表），之后同一单恒 false。
  bool markExpiryRefreshed(String orderToken) => _expiryRefreshed.add(orderToken);

  @override
  Future<OrderListState> build() {
    _generation++;
    return _load(_filter, _shopGroup);
  }

  /// 按**快照**的筛选 / 分组取首页（不在 await 之后再读字段）。
  Future<OrderListState> _load(OrderType? filter, ShopOrderGroup group) async {
    if (filter == OrderType.ecommerce) {
      final page = await ref.read(shopOrderRepositoryProvider).list(group: group);
      return OrderListState(
        items: const [],
        shopItems: page.items,
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        filter: filter,
        shopGroup: group,
      );
    }
    final page = await ref.read(orderRepositoryProvider).fetchOrders(type: filter);
    return OrderListState(
      items: page.items,
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
      pawcoinBalance: page.pawcoinBalance,
      filter: filter,
    );
  }

  /// 以当前筛选 / 分组重拉；结果回来时代次已变（期间又切了标签）就丢弃。
  Future<void> _reload() async {
    final gen = ++_generation;
    final filter = _filter;
    final group = _shopGroup;
    state = const AsyncLoading();
    final result = await AsyncValue.guard(() => _load(filter, group));
    if (gen != _generation) return;
    state = result;
  }

  /// 切换类型筛选（null=全部）→ 重置列表 + 游标重拉；二级分组重置为全部。
  Future<void> setFilter(OrderType? type) async {
    if (_filter == type) return;
    _filter = type;
    _shopGroup = ShopOrderGroup.all;
    await _reload();
  }

  /// 切换「Belanja」二级分组（只在一级为 Belanja 时生效）→ 重置并重拉。
  Future<void> setShopGroup(ShopOrderGroup group) async {
    if (_filter != OrderType.ecommerce || _shopGroup == group) return;
    _shopGroup = group;
    await _reload();
  }

  /// 加载更多（游标追加，失败保留已加载 + 底部重试）。
  /// 回写前校验代次与筛选 / 分组未变：期间切了标签，这一页属于旧列表，丢弃。
  Future<void> loadMore() async {
    final cur = state.value;
    if (cur == null || !cur.hasMore || cur.loadingMore || cur.nextCursor == null) return;
    final gen = _generation;
    bool stale() => gen != _generation || _filter != cur.filter || _shopGroup != cur.shopGroup;
    state = AsyncData(cur.copyWith(loadingMore: true, loadMoreFailed: false));
    try {
      if (cur.isShop) {
        final page = await ref
            .read(shopOrderRepositoryProvider)
            .list(group: cur.shopGroup, cursor: cur.nextCursor);
        if (stale()) return;
        state = AsyncData(cur.copyWith(
          shopItems: [...cur.shopItems, ...page.items],
          nextCursor: page.nextCursor,
          hasMore: page.hasMore,
          loadingMore: false,
          loadMoreFailed: false,
        ));
        return;
      }
      final page = await ref
          .read(orderRepositoryProvider)
          .fetchOrders(type: cur.filter, cursor: cur.nextCursor);
      if (stale()) return;
      state = AsyncData(cur.copyWith(
        items: [...cur.items, ...page.items],
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        loadingMore: false,
        loadMoreFailed: false,
      ));
    } catch (_) {
      if (stale()) return;
      state = AsyncData(cur.copyWith(loadingMore: false, loadMoreFailed: true));
    }
  }

  /// 下拉刷新 / 重试：失效自身（[build] 自增代次，在途的旧请求随之作废）。
  Future<void> refresh() async {
    ref.invalidateSelf();
    await future;
  }
}

final AsyncNotifierProvider<OrderListController, OrderListState> orderListProvider =
    AsyncNotifierProvider<OrderListController, OrderListState>(OrderListController.new);
