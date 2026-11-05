import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/order/data/order_repository.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_list_page_v2.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_summary.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 订单列表「Belanja」二级分组（v1.4.2 shop Story 9.1 · F1 / F3 / F18 / AC2）。
class _FakeOrders extends OrderRepository {
  _FakeOrders() : super(dio: Dio());

  int calls = 0;

  @override
  Future<OrderPage> fetchOrders({OrderType? type, String? cursor, int limit = 20}) async {
    calls++;
    return const OrderPage(items: [], hasMore: false, pawcoinBalance: 0);
  }
}

class _FakeShopOrders extends ShopOrderRepository {
  _FakeShopOrders() : super(dio: Dio());

  final List<(ShopOrderGroup, String?)> listCalls = [];

  /// group → 首页项。
  Map<ShopOrderGroup, List<ShopOrderSummary>> pages = {};

  /// 首页 hasMore（只对 all 组）。
  bool firstHasMore = false;

  /// 依次消费的错误（首页 / 加载更多）。
  final List<Object?> errors = [];

  @override
  Future<ShopOrderListPage> list({ShopOrderGroup group = ShopOrderGroup.all, String? cursor}) async {
    listCalls.add((group, cursor));
    if (errors.isNotEmpty) {
      final e = errors.removeAt(0);
      if (e != null) throw e;
    }
    if (cursor != null) {
      return ShopOrderListPage(items: [_item('more1', 'COMPLETED')], hasMore: false);
    }
    return ShopOrderListPage(
        items: pages[group] ?? const [], nextCursor: firstHasMore ? 'c2' : null, hasMore: firstHasMore);
  }
}

ShopOrderSummary _item(String token, String status, {String? displayStatus, bool canPay = false}) =>
    ShopOrderSummary.fromJson({
      'orderToken': token,
      'displayNo': 'TOKO-$token',
      'status': status,
      'displayStatus': displayStatus ?? status,
      'totalAmount': 329000,
      'itemTitle': 'Royal Canin',
      'itemCount': 1,
      'canPay': canPay,
      if (canPay)
        'paymentDeadline': DateTime.now().toUtc().add(const Duration(minutes: 30)).toIso8601String(),
    });

void main() {
  late _FakeShopOrders shop;
  late _FakeOrders orders;

  Future<void> pump(WidgetTester tester,
      {ShopOrderCounts? counts, int? coupons, bool countsFail = false}) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const OrderListPageV2()),
      GoRoute(path: '/me/coupons', builder: (c, s) => const Scaffold(body: Text('MY COUPONS'))),
      GoRoute(path: '/shop', builder: (c, s) => const Scaffold(body: Text('TOKO'))),
      GoRoute(path: '/shop/orders/:token', builder: (c, s) => Text('ORDER ${s.pathParameters['token']}')),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        orderRepositoryProvider.overrideWithValue(orders),
        shopOrderRepositoryProvider.overrideWithValue(shop),
        shopOrderCountsProvider.overrideWith((ref) async => countsFail ? null : counts),
        couponSummaryProvider.overrideWith((ref) async => coupons),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> openBelanja(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('orderFilterV2_ecommerce')));
    await tester.pumpAndSettle();
  }

  setUp(() {
    shop = _FakeShopOrders();
    orders = _FakeOrders();
  });

  testWidgets('二级标签只在 Belanja 出现；Semua 混排走 /orders 不受影响', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('shopOrderGroupTabs')), findsNothing);
    expect(orders.calls, 1);
    expect(shop.listCalls, isEmpty);

    await openBelanja(tester);
    expect(find.byKey(const ValueKey('shopOrderGroupTabs')), findsOneWidget);
    for (final label in ['Semua', 'Belum bayar', 'Dikemas', 'Dikirim', 'Selesai', 'Dibatalkan']) {
      expect(
          find.descendant(of: find.byKey(const ValueKey('shopOrderGroupTabs')), matching: find.text(label)),
          findsOneWidget,
          reason: label);
    }
    expect(shop.listCalls.single, (ShopOrderGroup.all, null));

    await tester.tap(find.byKey(const ValueKey('orderFilterV2_vetConsult')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('shopOrderGroupTabs')), findsNothing);
  });

  testWidgets('切二级分组 → 带 group 重拉；切回一级再进 Belanja 重置为 all', (tester) async {
    await pump(tester);
    await openBelanja(tester);
    await tester.tap(find.byKey(const ValueKey('shopOrderGroup_to_receive')));
    await tester.pumpAndSettle();
    expect(shop.listCalls.last, (ShopOrderGroup.toReceive, null));

    await tester.tap(find.byKey(const ValueKey('orderFilterV2_all')));
    await tester.pumpAndSettle();
    await openBelanja(tester);
    expect(shop.listCalls.last, (ShopOrderGroup.all, null));
  });

  testWidgets('角标：取 counts；为 0 不显示', (tester) async {
    await pump(tester, counts: const ShopOrderCounts(pendingPayment: 2, toReceive: 0));
    await openBelanja(tester);
    expect(find.byKey(const ValueKey('shopOrderGroupBadge_pending_payment')), findsOneWidget);
    expect(find.descendant(
        of: find.byKey(const ValueKey('shopOrderGroupBadge_pending_payment')), matching: find.text('2')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('shopOrderGroupBadge_to_receive')), findsNothing);
  });

  testWidgets('counts 失败 → 无角标、不报错', (tester) async {
    await pump(tester, countsFail: true);
    await openBelanja(tester);
    expect(find.byKey(const ValueKey('shopOrderGroupBadge_pending_payment')), findsNothing);
    expect(find.byKey(const ValueKey('shopOrderGroupBadge_to_receive')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('我的券入口：有计数 →「Kupon Saya · n bisa dipakai」，点击进 /me/coupons', (tester) async {
    await pump(tester, coupons: 3);
    await openBelanja(tester);
    expect(find.text('Kupon Saya · 3 bisa dipakai'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('shopOrderCouponEntry')));
    await tester.pumpAndSettle();
    expect(find.text('MY COUPONS'), findsOneWidget);
  });

  testWidgets('我的券入口：0 / 失败 → 只显示「Kupon Saya」', (tester) async {
    await pump(tester, coupons: 0);
    await openBelanja(tester);
    expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderCouponEntryText'))).data, 'Kupon Saya');
  });

  testWidgets('F3 空态：Belanja 下有「Mulai belanja」→ /shop；其他一级无', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('orderEmptyState')), findsOneWidget);
    expect(find.byKey(const ValueKey('orderStartShopping')), findsNothing);

    await openBelanja(tester);
    expect(find.byKey(const ValueKey('orderEmptyState')), findsOneWidget);
    expect(find.text('Mulai belanja'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('orderStartShopping')));
    await tester.pumpAndSettle();
    expect(find.text('TOKO'), findsOneWidget);
  });

  testWidgets('F18 首屏失败 → 失败卡 + Coba lagi → 重试成功出卡', (tester) async {
    shop.errors.add(Exception('boom'));
    shop.pages = {ShopOrderGroup.all: [_item('a1', 'PENDING_SHIPMENT')]};
    await pump(tester);
    await openBelanja(tester);
    expect(find.byKey(const ValueKey('orderLoadFailedState')), findsOneWidget);
    expect(find.text('Gagal memuat pesanan'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('orderLoadRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('shopOrderCard_a1')), findsOneWidget);
  });

  testWidgets('加载更多失败 → 「Coba lagi」→ 重试追加', (tester) async {
    shop.firstHasMore = true;
    shop.pages = {ShopOrderGroup.all: [_item('a1', 'PENDING_SHIPMENT')]};
    await pump(tester);
    await openBelanja(tester);
    shop.errors.add(Exception('more failed'));
    await tester.tap(find.byKey(const ValueKey('orderLoadMoreV2')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('orderLoadMoreRetry')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('orderLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('shopOrderCard_more1')), findsOneWidget);
    expect(shop.listCalls.last, (ShopOrderGroup.all, 'c2'));
  });

  testWidgets('S-7 宽限期单在「Dibatalkan」组显示已取消、无去支付', (tester) async {
    shop.pages = {
      ShopOrderGroup.cancelled: [_item('g1', 'PENDING_PAYMENT', displayStatus: 'CANCELLED')]
    };
    await pump(tester);
    await openBelanja(tester);
    await tester.tap(find.byKey(const ValueKey('shopOrderGroup_cancelled')));
    await tester.pumpAndSettle();
    expect(tester.widget<Text>(find.byKey(const ValueKey('shopOrderCardStatus_g1'))).data, 'Dibatalkan');
    expect(find.byKey(const ValueKey('shopOrderCardPay_g1')), findsNothing);
  });

  testWidgets('列表卡倒计时归零 → 该单只刷新一次（服务端仍在待付款也不反复刷）', (tester) async {
    shop.pages = {
      ShopOrderGroup.all: [
        ShopOrderSummary.fromJson({
          'orderToken': 'x1',
          'status': 'PENDING_PAYMENT',
          'displayStatus': 'PENDING_PAYMENT',
          'totalAmount': 1,
          'canPay': true,
          'paymentDeadline':
              DateTime.now().toUtc().subtract(const Duration(seconds: 1)).toIso8601String(),
        })
      ]
    };
    await pump(tester);
    await openBelanja(tester);
    await tester.pumpAndSettle();
    final after = shop.listCalls.length;
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(seconds: 1));
    }
    await tester.pumpAndSettle();
    expect(shop.listCalls.length, after, reason: '首次归零的那次刷新之后不再重复');
    expect(shop.listCalls.where((c) => c.$1 == ShopOrderGroup.all).length, 2,
        reason: '进入 Belanja 1 次 + 归零刷新 1 次');
  });
}
