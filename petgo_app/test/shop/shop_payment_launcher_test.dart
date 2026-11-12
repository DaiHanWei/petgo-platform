import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/presentation/shop_payment_launcher.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 7.2 · AC8：支付启动器（单一入口）八种结局各一例。
///
/// 启动器只返回结果、不跳路由；埋点名 / 属性 / 触发条件与搬迁前一致（详见 shop_payment_analytics_test）。
void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);
  List<String> names() => events.map((e) => e.$1).toList();

  late ShopOrderDetail current;

  ShopOrderDetail order({
    ShopOrderStatus status = ShopOrderStatus.pendingPayment,
    String? paymentFailureCategory,
  }) =>
      ShopOrderDetail(
        orderToken: 'ord1',
        displayNo: 'TOKO-1',
        status: status,
        paymentFailureCategory: paymentFailureCategory,
        goodsSubtotal: 189000,
        shippingFee: 15000,
        shippingDiscount: 0,
        totalAmount: 204000,
        payChannel: 'MIXED',
        coinAmount: 50000,
        cashAmount: 154000,
        expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 30)),
        lines: const [],
        receiverName: 'Budi',
        receiverPhone: '08123456789',
        addressText: 'Jl. Test',
        attributionSource: 'TOKO_ALL_FEATURED',
      );

  ShopPaymentOutcome? outcome;

  Future<void> launch(WidgetTester tester, _Repo repo, {bool isRetry = false}) async {
    outcome = null;
    current = order();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        shopOrderRepositoryProvider.overrideWithValue(repo),
        shopOrderDetailProvider.overrideWith((ref, token) async => current),
      ],
      child: MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: Scaffold(
          body: Consumer(
            builder: (ctx, ref, _) => ElevatedButton(
              onPressed: () async =>
                  outcome = await launchShopPayment(ctx, ref, current, isRetry: isRetry),
              child: const Text('pay'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('pay'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  Future<void> serverMovesTo(WidgetTester tester, ShopOrderDetail next) async {
    current = next;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();
  }

  Future<void> settle(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  }

  testWidgets('settledByCoin：纯币当场结清，不出码，发 succeeded(PAWCOIN)', (tester) async {
    await launch(tester, _Repo()..payload = null);
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.settledByCoin);
    expect(find.byKey(const ValueKey('qrPayImage')), findsNothing);
    expect(names(), containsAllInOrder(['toko_order_pay_tapped', 'toko_order_payment_succeeded']));
    expect(names(), isNot(contains('toko_payment_qr_shown')));
    expect(events.firstWhere((e) => e.$1 == 'toko_order_pay_tapped').$2, isNull,
        reason: '订单行没有 token（旧后端）→ 与原先一样不带属性（analytics-pipeline 3.4）');
  });

  group('analytics-pipeline 3.4：toko_order_pay_tapped.items[]', () {
    ShopOrderDetail withLines(List<ShopOrderLine> lines) => ShopOrderDetail(
          orderToken: 'ord1',
          status: ShopOrderStatus.pendingPayment,
          goodsSubtotal: 1,
          shippingFee: 0,
          shippingDiscount: 0,
          totalAmount: 1,
          expiresAt: DateTime.now().toUtc(),
          lines: lines,
          receiverName: 'Budi',
          receiverPhone: '08123456789',
          addressText: 'Jl. Test',
        );
    ShopOrderLine line({String? productToken, String? skuToken}) => ShopOrderLine(
        productName: 'Royal Canin', specName: '2 kg', unitPrice: 1, qty: 2, lineTotal: 2,
        productToken: productToken, skuToken: skuToken);

    test('每行带 product_token 与 sku_id（与 toko_order_submitted.items[] 同键名），无名称 / 价格 / 订单号', () {
      final items = shopPayTappedItems(withLines([
        line(productToken: 'p1', skuToken: 's1'),
        line(productToken: 'p2', skuToken: 's2'),
      ]));
      expect(items, [
        {'product_token': 'p1', 'sku_id': 's1'},
        {'product_token': 'p2', 'sku_id': 's2'},
      ]);
    });

    test('取不到 token 的行跳过；全部取不到 → null（不带 items 键）', () {
      expect(shopPayTappedItems(withLines([line(productToken: 'p1'), line()])), [
        {'product_token': 'p1'},
      ]);
      expect(shopPayTappedItems(withLines([line(), line()])), isNull);
      expect(shopPayTappedItems(withLines(const [])), isNull);
    });

    test('ShopOrderLine.fromJson 读 productToken / skuToken（空串 → null，旧后端缺键 → null）', () {
      final l = ShopOrderLine.fromJson({
        'productName': 'A', 'specName': 'B', 'unitPrice': 1, 'qty': 1, 'lineTotal': 1,
        'productToken': 'p1', 'skuToken': '',
      });
      expect(l.productToken, 'p1');
      expect(l.skuToken, isNull);
      final old = ShopOrderLine.fromJson({'productName': 'A', 'specName': 'B'});
      expect(old.productToken, isNull);
      expect(old.skuToken, isNull);
    });
  });

  testWidgets('paid：轮询到账 → succeeded(pay_channel, attribution_source)，不弹 shopOrderPaid', (tester) async {
    await launch(tester, _Repo());
    await serverMovesTo(tester, order(status: ShopOrderStatus.pendingShipment));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.paid);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.shopOrderPaid), findsNothing);
    final succeeded = events.firstWhere((e) => e.$1 == 'toko_order_payment_succeeded').$2;
    expect(succeeded, {'pay_channel': 'MIXED', 'attribution_source': 'TOKO_ALL_FEATURED'});
  });

  testWidgets('dismissed：× 关面板 → sheet_dismissed', (tester) async {
    await launch(tester, _Repo());
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.dismissed);
    expect(names(), contains('toko_payment_sheet_dismissed'));
  });

  testWidgets('declined：网关拒付 → declined_shown', (tester) async {
    await launch(tester, _Repo(), isRetry: true);
    await serverMovesTo(tester, order(paymentFailureCategory: 'GATEWAY_DECLINED'));
    expect(outcome, ShopPaymentOutcome.declined);
    expect(names(), containsAllInOrder(['toko_order_pay_tapped', 'toko_payment_retry_tapped']));
    expect(names(), contains('toko_payment_declined_shown'));
    await settle(tester);
  });

  testWidgets('expired：超时 → expired_shown', (tester) async {
    await launch(tester, _Repo());
    await serverMovesTo(
        tester, order(status: ShopOrderStatus.cancelled, paymentFailureCategory: 'EXPIRED'));
    expect(outcome, ShopPaymentOutcome.expired);
    expect(names(), contains('toko_payment_expired_shown'));
    await settle(tester);
  });

  testWidgets('userCancelled：静默', (tester) async {
    await launch(tester, _Repo());
    await serverMovesTo(
        tester, order(status: ShopOrderStatus.cancelled, paymentFailureCategory: 'USER_CANCELLED'));
    expect(outcome, ShopPaymentOutcome.userCancelled);
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    expect(names(), isNot(contains('toko_order_payment_failed_shown')));
  });

  testWidgets('abortedUnknown：老后端无类别中止 → 不发 sheet_dismissed', (tester) async {
    await launch(tester, _Repo());
    await serverMovesTo(tester, order(status: ShopOrderStatus.cancelled));
    expect(outcome, ShopPaymentOutcome.abortedUnknown);
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    expect(names(), isNot(contains('toko_payment_declined_shown')));
    expect(names(), isNot(contains('toko_payment_expired_shown')));
  });

  testWidgets('failed：/pay 非取码类错误 → failed_shown + shopOrderPayFailed toast，不开面板', (tester) async {
    await launch(tester, _Repo()..error = StateError('boom'));
    expect(outcome, ShopPaymentOutcome.failed);
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.shopOrderPayFailed), findsOneWidget);
    expect(names(), contains('toko_order_payment_failed_shown'));
    await settle(tester);
  });

  testWidgets('failed：E7（5xx）打开失败态面板，关掉 → failed，不发 qr_shown / sheet_dismissed', (tester) async {
    final req = RequestOptions(path: '/pay');
    await launch(
        tester,
        _Repo()
          ..error = DioException(
              requestOptions: req,
              type: DioExceptionType.badResponse,
              response: Response<Object?>(requestOptions: req, statusCode: 503)));
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    expect(names(), contains('toko_order_payment_failed_shown'));
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.failed);
    expect(names(), isNot(contains('toko_payment_qr_shown')));
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
  });

  testWidgets('UI E7：取码 502（网关拒付）后轮询到 GATEWAY_DECLINED → 面板不自动关，仍停在 Coba lagi', (tester) async {
    await launch(tester, _Repo()..error = _http(502));
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    await serverMovesTo(tester, order(paymentFailureCategory: 'GATEWAY_DECLINED'));
    await tester.pump(const Duration(seconds: 6));
    expect(outcome, isNull, reason: '面板仍开着，launchShopPayment 尚未返回');
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.failed);
  });

  testWidgets('E7 → Coba lagi 出码成功 → 补发 qr_shown；之后关面板记 sheet_dismissed', (tester) async {
    final repo = _Repo()..error = DioException(requestOptions: RequestOptions(path: '/pay'));
    await launch(tester, repo);
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget, reason: '无响应的网络错误也算取码失败');
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayImage')), findsOneWidget);
    expect(names(), contains('toko_payment_qr_shown'));
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.dismissed);
    expect(names(), contains('toko_payment_sheet_dismissed'));
  });

  testWidgets('abortedUnknownCategory：后端新增 App 不认识的类别 → failed_shown + shopOrderPayFailed',
      (tester) async {
    await launch(tester, _Repo());
    await serverMovesTo(tester, order(paymentFailureCategory: 'SOMETHING_NEW'));
    expect(outcome, ShopPaymentOutcome.abortedUnknownCategory);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.shopOrderPayFailed), findsOneWidget);
    expect(names(), contains('toko_order_payment_failed_shown'));
    expect(names(), isNot(contains('toko_payment_sheet_dismissed')));
    await settle(tester);
  });

  testWidgets('409 shop-order-not-payable → E7 且无「Coba lagi」', (tester) async {
    await launch(tester, _Repo()..error = _http(409, type: 'shop-order-not-payable'));
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsNothing);
    expect(names(), contains('toko_order_payment_failed_shown'));
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.failed);
  });

  testWidgets('connectionError（无 response）→ E7 带「Coba lagi」', (tester) async {
    await launch(
        tester,
        _Repo()
          ..error = DioException(
              requestOptions: RequestOptions(path: '/pay'), type: DioExceptionType.connectionError));
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
  });

  testWidgets('普通 409 conflict → 原 shopOrderPayFailed toast，不开面板', (tester) async {
    await launch(tester, _Repo()..error = _http(409, type: 'conflict'));
    expect(outcome, ShopPaymentOutcome.failed);
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsNothing);
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.shopOrderPayFailed), findsOneWidget);
    await settle(tester);
  });

  testWidgets('E7 重试 /pay 返回 409 not-payable → 切到无重试的 E7', (tester) async {
    final repo = _Repo()
      ..error = _http(503)
      ..retryError = _http(409, type: 'shop-order-not-payable');
    await launch(tester, repo);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
    expect(outcome, ShopPaymentOutcome.failed);
  });

  testWidgets('E7 重试遇 5xx → 保持可重试', (tester) async {
    final repo = _Repo()
      ..error = _http(503)
      ..retryError = _http(502);
    await launch(tester, repo);
    await tester.tap(find.byKey(const ValueKey('qrPayRetryCode')));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const ValueKey('qrPayCodeFailed')), findsOneWidget);
    expect(find.byKey(const ValueKey('qrPayRetryCode')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('qrPayCloseX')));
    await tester.pumpAndSettle();
  });

  testWidgets('「Saya sudah bayar」调 paymentCheck(token)；paid → paid', (tester) async {
    final repo = _Repo()..checkPaid = true;
    await launch(tester, repo);
    await tester.tap(find.byKey(const ValueKey('qrPayPaidCheck')));
    await tester.pumpAndSettle();
    expect(repo.checkTokens, ['ord1']);
    expect(outcome, ShopPaymentOutcome.paid);
  });
}

class _Repo extends ShopOrderRepository {
  _Repo() : super(dio: Dio());

  /// 首次 `/pay` 抛的错（之后正常）。
  Object? error;

  /// 第二次 `/pay`（E7 重试）抛的错。
  Object? retryError;
  int calls = 0;
  String? payload = 'QR-DATA';
  bool checkPaid = false;
  final List<String> checkTokens = [];

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    calls++;
    if (calls == 2 && retryError != null) throw retryError!;
    final e = error;
    if (e != null) {
      error = null;
      throw e;
    }
    return ShopPayResult(orderStatus: 'PENDING_PAYMENT', paymentIntentToken: 'pi-1', payload: payload);
  }

  @override
  Future<PaymentCheckResult> paymentCheck(String orderToken) async {
    checkTokens.add(orderToken);
    return PaymentCheckResult(paid: checkPaid);
  }
}

DioException _http(int status, {String? type}) {
  final req = RequestOptions(path: '/pay');
  return DioException(
    requestOptions: req,
    type: DioExceptionType.badResponse,
    response: Response<Object?>(
      requestOptions: req,
      statusCode: status,
      data: type == null ? null : {'type': 'https://petgo/errors/$type', 'status': status},
    ),
  );
}
