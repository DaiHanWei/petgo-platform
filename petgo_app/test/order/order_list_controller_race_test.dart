import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/order/data/order_repository.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_list_controller.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_summary.dart';

/// 订单列表控制器竞态（v1.4.2 shop Story 9.1 复审）：旧请求后到必须丢弃。
class _FakeOrders extends OrderRepository {
  _FakeOrders() : super(dio: Dio());

  @override
  Future<OrderPage> fetchOrders({OrderType? type, String? cursor, int limit = 20}) async =>
      const OrderPage(items: [], hasMore: false, pawcoinBalance: 0);
}

/// 每次 list 调用挂一个 Completer，由测试决定先后完成。
class _ControlledShop extends ShopOrderRepository {
  _ControlledShop() : super(dio: Dio());

  final List<(ShopOrderGroup, String?, Completer<ShopOrderListPage>)> pending = [];

  @override
  Future<ShopOrderListPage> list({ShopOrderGroup group = ShopOrderGroup.all, String? cursor}) {
    final c = Completer<ShopOrderListPage>();
    pending.add((group, cursor, c));
    return c.future;
  }

  Completer<ShopOrderListPage> take(ShopOrderGroup g, {String? cursor}) =>
      pending.firstWhere((p) => p.$1 == g && p.$2 == cursor).$3;
}

ShopOrderListPage _page(String token, {bool hasMore = false}) => ShopOrderListPage(
      items: [ShopOrderSummary.fromJson({'orderToken': token, 'status': 'COMPLETED', 'totalAmount': 1})],
      nextCursor: hasMore ? 'c2' : null,
      hasMore: hasMore,
    );

void main() {
  late _ControlledShop shop;
  late ProviderContainer c;

  setUp(() async {
    shop = _ControlledShop();
    c = ProviderContainer(overrides: [
      orderRepositoryProvider.overrideWithValue(_FakeOrders()),
      shopOrderRepositoryProvider.overrideWithValue(shop),
    ]);
    addTearDown(c.dispose);
    c.listen(orderListProvider, (_, _) {});
    await c.read(orderListProvider.future);
    // 进入 Belanja（all 首页立即返回）
    final f = c.read(orderListProvider.notifier).setFilter(OrderType.ecommerce);
    shop.take(ShopOrderGroup.all).complete(_page('all1', hasMore: true));
    await f;
  });

  test('快速切分组：先发的 to_receive 后到 → 丢弃，最终是 cancelled 的结果', () async {
    final n = c.read(orderListProvider.notifier);
    final a = n.setShopGroup(ShopOrderGroup.toReceive);
    final b = n.setShopGroup(ShopOrderGroup.cancelled);

    shop.take(ShopOrderGroup.cancelled).complete(_page('cancel1'));
    await b;
    shop.take(ShopOrderGroup.toReceive).complete(_page('recv1'));
    await a;

    final s = c.read(orderListProvider).value!;
    expect(s.shopGroup, ShopOrderGroup.cancelled);
    expect(s.shopItems.map((e) => e.orderToken), ['cancel1'], reason: '旧分组的结果不得覆盖新分组');
  });

  test('加载更多进行中切分组 → 旧列表的下一页丢弃，不追加到新分组', () async {
    final n = c.read(orderListProvider.notifier);
    final more = n.loadMore();
    final sw = n.setShopGroup(ShopOrderGroup.cancelled);
    shop.take(ShopOrderGroup.cancelled).complete(_page('cancel1'));
    await sw;

    shop.take(ShopOrderGroup.all, cursor: 'c2').complete(_page('allMore'));
    await more;

    final s = c.read(orderListProvider).value!;
    expect(s.shopGroup, ShopOrderGroup.cancelled);
    expect(s.shopItems.map((e) => e.orderToken), ['cancel1']);
    expect(s.loadingMore, isFalse);
  });

  test('倒计时归零按 token 只刷新一次', () {
    final n = c.read(orderListProvider.notifier);
    expect(n.markExpiryRefreshed('t1'), isTrue);
    expect(n.markExpiryRefreshed('t1'), isFalse);
    expect(n.markExpiryRefreshed('t2'), isTrue);
  });
}
