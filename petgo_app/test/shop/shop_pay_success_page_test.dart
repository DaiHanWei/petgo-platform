import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/data/shop_return_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_return.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/shop_pay_success_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 7.1 · E6 支付成功页。
void main() {
  late GoRouter router;

  Future<void> pump(WidgetTester tester, Future<ShopOrderDetail> Function() load) async {
    router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('HOME'))),
        GoRoute(path: '/shop', builder: (c, s) => const Scaffold(body: Text('TOKO'))),
        GoRoute(
            path: '/shop/orders/:token',
            builder: (c, s) => Scaffold(body: Text('DETAIL ${s.pathParameters['token']}'))),
        GoRoute(
            path: '/shop/orders/:token/paid',
            builder: (c, s) => ShopPaySuccessPage(orderToken: s.pathParameters['token']!)),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [shopOrderDetailProvider.overrideWith((ref, token) => load())],
      child: MaterialApp.router(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        routerConfig: router,
      ),
    ));
    await tester.pumpAndSettle();
    router.push('/shop/orders/ord1/paid');
    await tester.pumpAndSettle();
  }

  ShopOrderDetail order({int? coin, int? cash}) => ShopOrderDetail(
        orderToken: 'ord1',
        displayNo: 'TOKO-1',
        status: ShopOrderStatus.pendingShipment,
        goodsSubtotal: 349000,
        shippingFee: 10000,
        shippingDiscount: 0,
        totalAmount: 329000,
        coinAmount: coin,
        cashAmount: cash,
        lines: const [],
        packages: const [],
        receiverName: 'Budi',
        receiverPhone: '08123456789',
        addressText: 'Jl. Test',
      );

  testWidgets('混合支付：应付 + 「PawCoin Rp a + QRIS Rp b」', (tester) async {
    await pump(tester, () async => order(coin: 12000, cash: 317000));
    expect(find.text('Pembayaran berhasil'), findsOneWidget);
    expect(find.text('Rp 329.000'), findsOneWidget);
    expect(find.text('PawCoin Rp 12.000 + QRIS Rp 317.000'), findsOneWidget);
    expect(find.text('Pesanan kamu sedang disiapkan untuk dikirim.'), findsOneWidget);
  });

  testWidgets('纯币：不画拆分行', (tester) async {
    await pump(tester, () async => order(coin: 329000, cash: 0));
    expect(find.text('Rp 329.000'), findsOneWidget);
    expect(find.byKey(const ValueKey('paySuccessSplit')), findsNothing);
  });

  testWidgets('纯 QRIS（两段为 null）：不画拆分行', (tester) async {
    await pump(tester, () async => order());
    expect(find.byKey(const ValueKey('paySuccessSplit')), findsNothing);
  });

  testWidgets('🔴 加载失败：只显示标题与两个按钮，不编数字', (tester) async {
    await pump(tester, () async => throw StateError('boom'));
    expect(find.text('Pembayaran berhasil'), findsOneWidget);
    expect(find.textContaining('Rp'), findsNothing);
    expect(find.byKey(const ValueKey('paySuccessViewOrder')), findsOneWidget);
    expect(find.byKey(const ValueKey('paySuccessContinue')), findsOneWidget);
  });

  testWidgets('Lihat pesanan → 订单详情（替换本页）', (tester) async {
    await pump(tester, () async => order());
    await tester.tap(find.byKey(const ValueKey('paySuccessViewOrder')));
    await tester.pumpAndSettle();
    expect(find.text('DETAIL ord1'), findsOneWidget);
    expect(find.byType(ShopPaySuccessPage), findsNothing);
  });

  testWidgets('Lanjut belanja → /shop 用 go（Tab 根不 push）', (tester) async {
    await pump(tester, () async => order());
    await tester.tap(find.byKey(const ValueKey('paySuccessContinue')));
    await tester.pumpAndSettle();
    expect(find.text('TOKO'), findsOneWidget);
    expect(router.canPop(), isFalse, reason: 'go 会清掉栈；push 的话还能 pop 回来');
  });

  testWidgets('🔴 系统返回键等同「Lihat pesanan」', (tester) async {
    await pump(tester, () async => order());
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('DETAIL ord1'), findsOneWidget);
  });

  testWidgets('v1.4.2 Story 7.2 · 从 QRIS 路径进入（详情页轮询到账）→ E6 显示「PawCoin + QRIS」拆分',
      (tester) async {
    var current = order(coin: 12000, cash: 317000).copyStatus(ShopOrderStatus.pendingPayment);
    final r = GoRouter(
      initialLocation: '/shop/orders/ord1',
      routes: [
        GoRoute(
            path: '/shop/orders/:token',
            builder: (c, s) => ShopOrderDetailPageV2(orderToken: s.pathParameters['token']!)),
        GoRoute(
            path: '/shop/orders/:token/paid',
            builder: (c, s) => ShopPaySuccessPage(orderToken: s.pathParameters['token']!)),
      ],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        shopOrderRepositoryProvider.overrideWithValue(_QrisRepo()),
        shopOrderDetailProvider.overrideWith((ref, token) async => current),
        returnEligibilityProvider.overrideWith((ref, token) async =>
            const ReturnEligibility(orderToken: 'ord1', eligible: false, lines: [])),
      ],
      child: MaterialApp.router(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        routerConfig: r,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('shopOrderPayV2')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);

    current = order(coin: 12000, cash: 317000); // 服务端推进到待发货
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(find.byType(ShopPaySuccessPage), findsOneWidget);
    expect(find.text('PawCoin Rp 12.000 + QRIS Rp 317.000'), findsOneWidget);
  });
}

extension on ShopOrderDetail {
  ShopOrderDetail copyStatus(ShopOrderStatus s) => ShopOrderDetail(
        orderToken: orderToken,
        displayNo: displayNo,
        status: s,
        goodsSubtotal: goodsSubtotal,
        shippingFee: shippingFee,
        shippingDiscount: shippingDiscount,
        totalAmount: totalAmount,
        payChannel: 'MIXED',
        coinAmount: coinAmount,
        cashAmount: cashAmount,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
        lines: lines,
        packages: packages,
        receiverName: receiverName,
        receiverPhone: receiverPhone,
        addressText: addressText,
      );
}

class _QrisRepo extends ShopOrderRepository {
  _QrisRepo() : super(dio: Dio());

  @override
  Future<ShopPayResult> pay(String orderToken) async => const ShopPayResult(
      orderStatus: 'PENDING_PAYMENT', paymentIntentToken: 'pi-1', payload: 'QR-DATA');
}
