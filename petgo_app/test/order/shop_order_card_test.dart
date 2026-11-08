import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/order/data/order_repository.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/order_list_page_v2.dart';
import 'package:tailtopia/features/order/presentation/widgets/shop_order_card.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_order_summary.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_countdown.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 电商订单卡（v1.4.2 shop Story 9.1 · F1 / F2）。
ShopOrderSummary _s(Map<String, Object?> j) => ShopOrderSummary.fromJson({
      'orderToken': 't1',
      'displayNo': 'TOKO-1',
      'status': 'PENDING_SHIPMENT',
      'totalAmount': 329000,
      'itemTitle': 'Royal Canin',
      'itemCount': 3,
      ...j,
    });

String _deadline() => DateTime.now().toUtc().add(const Duration(minutes: 30)).toIso8601String();

class _FakeOrders extends OrderRepository {
  _FakeOrders() : super(dio: Dio());

  @override
  Future<OrderPage> fetchOrders({OrderType? type, String? cursor, int limit = 20}) async =>
      const OrderPage(items: [], hasMore: false, pawcoinBalance: 0);
}

class _FakeShop extends ShopOrderRepository {
  _FakeShop(this.items) : super(dio: Dio());

  final List<ShopOrderSummary> items;
  int listCalls = 0;
  int detailCalls = 0;
  int payCalls = 0;
  int confirmCalls = 0;

  @override
  Future<ShopOrderListPage> list({ShopOrderGroup group = ShopOrderGroup.all, String? cursor}) async {
    listCalls++;
    return ShopOrderListPage(items: items);
  }

  @override
  Future<ShopOrderDetail> detail(String orderToken) async {
    detailCalls++;
    return ShopOrderDetail.fromJson({
      'orderToken': orderToken,
      'displayNo': 'TOKO-1',
      'status': 'PENDING_PAYMENT',
      'displayStatus': 'PENDING_PAYMENT',
      'canPay': true,
      'totalAmount': 329000,
      'coinAmount': 12000,
      'cashAmount': 317000,
      'payChannel': 'MIXED',
      'expiresAt': _deadline(),
      'paymentDeadline': _deadline(),
    });
  }

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    payCalls++;
    return const ShopPayResult(
        orderStatus: 'PENDING_PAYMENT', paymentIntentToken: 'pi', payload: 'QR-DATA');
  }

  @override
  Future<ShopOrderDetail> confirmReceipt(String orderToken) async {
    confirmCalls++;
    return detail(orderToken);
  }
}

void main() {
  Future<void> pumpCard(WidgetTester tester, ShopOrderCard card) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: Scaffold(body: card),
    ));
    await tester.pump();
  }

  group('状态文案映射（PRD §3.0）', () {
    final table = <Map<String, Object?>, String>{
      {'status': 'PENDING_PAYMENT', 'displayStatus': 'PENDING_PAYMENT'}: 'Menunggu pembayaran',
      {'status': 'PENDING_SHIPMENT'}: 'Menunggu dikirim',
      {'status': 'SHIPPED', 'logisticsStatusKey': 'awaiting_pickup'}: 'Menunggu dijemput',
      {'status': 'SHIPPED', 'logisticsStatusKey': 'picked'}: 'Sudah dijemput',
      {'status': 'SHIPPED', 'logisticsStatusKey': 'in_transit'}: 'Dalam perjalanan',
      {'status': 'SHIPPED', 'logisticsStatusKey': 'out_for_delivery'}: 'Sedang diantar',
      {'status': 'DELIVERED', 'logisticsStatusKey': 'delivered'}: 'Diterima',
      {'status': 'SHIPPED'}: 'Dikirim',
      {'status': 'DELIVERED'}: 'Paket sudah diterima',
      {'status': 'SHIPPED', 'deliveryIssue': true, 'logisticsStatusKey': 'in_transit'}: 'Pengiriman bermasalah',
      {'status': 'COMPLETED'}: 'Selesai',
      {'status': 'CANCELLED'}: 'Dibatalkan',
      {'status': 'PENDING_PAYMENT', 'displayStatus': 'CANCELLED'}: 'Dibatalkan',
      {'status': 'REFUNDING'}: 'Pengembalian diproses',
      {'status': 'REFUNDED'}: 'Dana dikembalikan',
    };
    table.forEach((json, label) {
      test('$json → $label', () async {
        final l10n = await AppLocalizations.delegate.load(const Locale('id'));
        expect(shopOrderCardStatusLabel(l10n, _s(json)), label);
      });
    });
  });

  testWidgets('F1 待付款 + canPay：倒计时 + 「Bayar sekarang」→ onPay；金额紫色', (tester) async {
    var paid = 0;
    await pumpCard(
        tester,
        ShopOrderCard(
          order: _s({
            'status': 'PENDING_PAYMENT',
            'displayStatus': 'PENDING_PAYMENT',
            'canPay': true,
            'paymentDeadline': _deadline(),
          }),
          onPay: () => paid++,
        ));
    expect(find.byKey(const ValueKey('shopOrderCardCountdown_t1')), findsOneWidget);
    expect(find.byType(ShopCountdown), findsOneWidget);
    expect(find.text('Bayar dalam '), findsOneWidget);
    expect(find.text('dan 2 barang lain'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('shopOrderCardPay_t1')));
    expect(paid, 1);
  });

  testWidgets('待付款但 canPay=false → 无去支付', (tester) async {
    await pumpCard(
        tester,
        ShopOrderCard(
            order: _s({'status': 'PENDING_PAYMENT', 'displayStatus': 'PENDING_PAYMENT', 'canPay': false})));
    expect(find.byKey(const ValueKey('shopOrderCardPay_t1')), findsNothing);
  });

  testWidgets('F2 待收货：只有「Konfirmasi terima」；配送异常加「Hubungi CS」', (tester) async {
    var confirmed = 0;
    var cs = 0;
    await pumpCard(
        tester,
        ShopOrderCard(
            order: _s({'status': 'SHIPPED'}), onConfirmReceipt: () => confirmed++, onContactCs: () => cs++));
    expect(find.byKey(const ValueKey('shopOrderCardConfirm_t1')), findsOneWidget);
    expect(find.byKey(const ValueKey('shopOrderCardContactCs_t1')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('shopOrderCardConfirm_t1')));
    expect(confirmed, 1);

    await pumpCard(
        tester,
        ShopOrderCard(
            order: _s({'status': 'DELIVERED', 'deliveryIssue': true}),
            onConfirmReceipt: () => confirmed++,
            onContactCs: () => cs++));
    await tester.tap(find.byKey(const ValueKey('shopOrderCardContactCs_t1')));
    expect(cs, 1);
    expect(find.byKey(const ValueKey('shopOrderCardConfirm_t1')), findsOneWidget);
  });

  testWidgets('其余状态无按钮', (tester) async {
    for (final st in ['PENDING_SHIPMENT', 'COMPLETED', 'CANCELLED']) {
      await pumpCard(tester, ShopOrderCard(order: _s({'status': st})));
      expect(find.byKey(const ValueKey('shopOrderCardPay_t1')), findsNothing, reason: st);
      expect(find.byKey(const ValueKey('shopOrderCardConfirm_t1')), findsNothing, reason: st);
    }
  });

  group('列表上下文动作', () {
    late List<String> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, props) => events.add(e);
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<_FakeShop> pumpList(WidgetTester tester, List<ShopOrderSummary> items) async {
      final shop = _FakeShop(items);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => const OrderListPageV2()),
        GoRoute(path: '/shop/orders/:token', builder: (c, s) => Text('ORDER ${s.pathParameters['token']}')),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          orderRepositoryProvider.overrideWithValue(_FakeOrders()),
          shopOrderRepositoryProvider.overrideWithValue(shop),
          shopOrderCountsProvider.overrideWith((ref) async => null),
          couponSummaryProvider.overrideWith((ref) async => null),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('orderFilterV2_ecommerce')));
      await tester.pumpAndSettle();
      return shop;
    }

    testWidgets('F1「Bayar sekarang」→ 先取详情再**直接拉起支付面板**（不跳详情）；关面板后刷新列表',
        (tester) async {
      final shop = await pumpList(tester, [
        _s({
          'status': 'PENDING_PAYMENT',
          'displayStatus': 'PENDING_PAYMENT',
          'canPay': true,
          'paymentDeadline': _deadline(),
        })
      ]);
      final before = shop.listCalls;
      await tester.tap(find.byKey(const ValueKey('shopOrderCardPay_t1')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(shop.detailCalls, 1);
      expect(shop.payCalls, 1);
      expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
      expect(find.text('ORDER t1'), findsNothing, reason: '不再先跳详情');
      expect(events, contains('toko_order_pay_tapped'));

      await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
      await tester.pumpAndSettle();
      expect(shop.listCalls, greaterThan(before), reason: '面板关闭后刷新列表');
    });

    testWidgets('确认收货：无二次确认弹窗，直接调接口 + toast', (tester) async {
      final shop = await pumpList(tester, [_s({'status': 'SHIPPED'})]);
      await tester.tap(find.byKey(const ValueKey('shopOrderCardConfirm_t1')));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      expect(shop.confirmCalls, 1);
      expect(find.text('Terima kasih, pesanan selesai'), findsOneWidget);
      expect(events,
          containsAllInOrder(['toko_order_receipt_confirm_tapped', 'toko_order_receipt_confirm_succeeded']));
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('点卡片 → /shop/orders/{token}', (tester) async {
      await pumpList(tester, [_s({'status': 'COMPLETED'})]);
      await tester.tap(find.byKey(const ValueKey('shopOrderCard_t1')));
      await tester.pumpAndSettle();
      expect(find.text('ORDER t1'), findsOneWidget);
    });
  });
}
