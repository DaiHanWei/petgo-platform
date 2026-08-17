import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/shop_order_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_order_detail.dart';
import 'package:tailtopia/features/shop/presentation/shop_order_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.8：支付与待支付订单详情（FR-100 / AD-8 / AD-9）。
///
/// 🔴 本页最容易写错的是**把过期判定放到客户端**。那样改一下手机时间就能看到另一套现实，
/// 而库存是真的被锁着的。倒计时只是显示，判定权在服务端。
void main() {
  late _FakeOrderRepo repo;

  setUp(() => repo = _FakeOrderRepo());

  Widget host() => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          shopOrderRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: const ShopOrderDetailPage(orderToken: 'ord-1'),
        ),
      );

  Future<void> open(WidgetTester t) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host());
    await t.pump();       // 让 FutureProvider 完成
    await t.pump();
  }

  group('🔴 AD-8 倒计时以服务端时刻为准', () {
    testWidgets('待支付：按服务端 expiresAt 渲染剩余时间', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().add(const Duration(minutes: 12, seconds: 30)),
      );
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderCountdown')), findsOneWidget);
      expect(find.textContaining('12:'), findsOneWidget);

      await t.pump(const Duration(seconds: 2));   // 停掉每秒 ticker
    });

    testWidgets('🔴 倒计时归零 → 重新拉详情，而不是客户端自己改成已取消', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().subtract(const Duration(seconds: 1)),
      );
      await open(t);

      // 归零后页面必须去问服务端（第二次 detail 调用），因为「是否真的超时」由它判定 ——
      // 它会就地取消订单并释放库存，客户端凭本地时钟改状态只会看到假象。
      await t.pump();
      await t.pump(const Duration(milliseconds: 100));
      expect(repo.calls.where((c) => c == 'detail').length, greaterThanOrEqualTo(2));

      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('已取消：不给倒计时、不给支付/取消按钮，并说明原因', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.cancelled, expiresAt: null);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderCountdown')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderPay')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderCancel')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderExpiredNotice')), findsOneWidget);
    });

    testWidgets('已支付（待发货）：动作栏消失', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.pendingShipment, expiresAt: null);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderPay')), findsNothing);
      expect(find.text('Menunggu dikirim'), findsOneWidget);
    });

    testWidgets('🔴 认不出的状态：中性文案且不给任何资金动作', (t) async {
      repo.detailData = _order(status: ShopOrderStatus.unknown, expiresAt: null);
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderPay')), findsNothing);
      expect(find.byKey(const ValueKey('shopOrderCancel')), findsNothing);
    });
  });

  group('🔴 FR-100A 规则 2 / FR-100', () {
    testWidgets('混合支付：两段金额都显示', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().add(const Duration(minutes: 30)),
        coinAmount: 60000,
        cashAmount: 290000,
        totalAmount: 350000,
      );
      await open(t);

      expect(find.byKey(const ValueKey('shopOrderCoinSegment')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopOrderCashSegment')), findsOneWidget);

      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('仅 QRIS，且说明可被各家 e-wallet 扫码（防用户以为不能用 GoPay）', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().add(const Duration(minutes: 30)),
      );
      await open(t);

      expect(find.textContaining('GoPay'), findsOneWidget);
      // 不支持的渠道不得出现（FR-100：无银行转账/VA）
      expect(find.textContaining('Transfer Bank'), findsNothing);
      expect(find.textContaining('Virtual Account'), findsNothing);

      await t.pump(const Duration(seconds: 2));
    });
  });

  group('取消是不可逆动作', () {
    testWidgets('🔴 点取消先弹确认；点「返回」则什么都不做', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().add(const Duration(minutes: 30)),
      );
      await open(t);

      await t.tap(find.byKey(const ValueKey('shopOrderCancel')));
      await t.pump();
      expect(find.byKey(const ValueKey('shopOrderCancelDialog')), findsOneWidget);

      await t.tap(find.text('Kembali'));
      await t.pump();
      expect(repo.calls, isNot(contains('cancel')),
          reason: '库存会被释放且订单不可恢复，误触一次就没了');

      await t.pump(const Duration(seconds: 2));
    });

    testWidgets('确认后才真的取消', (t) async {
      repo.detailData = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().add(const Duration(minutes: 30)),
      );
      await open(t);

      await t.tap(find.byKey(const ValueKey('shopOrderCancel')));
      await t.pump();
      await t.tap(find.byKey(const ValueKey('shopOrderCancelConfirmYes')));
      await t.pump();
      await t.pump();

      expect(repo.calls, contains('cancel'));

      await t.pump(const Duration(seconds: 4));
    });
  });

  group('domain', () {
    test('未知状态降级到 unknown，且不被当作待支付', () {
      expect(ShopOrderStatus.fromApi('PENDING_PAYMENT'), ShopOrderStatus.pendingPayment);
      expect(ShopOrderStatus.fromApi('SOMETHING_NEW'), ShopOrderStatus.unknown);
      expect(ShopOrderStatus.fromApi(null), ShopOrderStatus.unknown);
      expect(ShopOrderStatus.unknown.isPendingPayment, isFalse);
    });

    test('🔴 时间戳按 UTC 解析后转本地 —— 否则倒计时会整整差一个时区', () {
      final d = ShopOrderDetail.fromJson({
        'orderToken': 'o',
        'status': 'PENDING_PAYMENT',
        'expiresAt': '2026-08-18T10:00:00Z',
        'lines': const [],
      });
      expect(d.expiresAt!.isUtc, isFalse);
      expect(d.expiresAt!.toUtc(), DateTime.utc(2026, 8, 18, 10));
    });

    test('remaining 不返回负数（过期后恒为 0）', () {
      final d = _order(
        status: ShopOrderStatus.pendingPayment,
        expiresAt: DateTime.now().subtract(const Duration(minutes: 5)),
      );
      expect(d.remaining(DateTime.now()), Duration.zero);
    });

    test('纯 PawCoin 单的支付结果没有二维码 → settledImmediately', () {
      final r = ShopPayResult.fromJson(const {
        'orderStatus': 'PENDING_SHIPMENT',
        'paymentIntentToken': null,
        'payload': null,
      });
      expect(r.settledImmediately, isTrue);
    });
  });
}

// ---------- helpers ----------

ShopOrderDetail _order({
  required ShopOrderStatus status,
  required DateTime? expiresAt,
  int goodsSubtotal = 285000,
  int shippingFee = 20000,
  int shippingDiscount = 0,
  int totalAmount = 305000,
  int? coinAmount,
  int? cashAmount,
}) =>
    ShopOrderDetail(
      orderToken: 'ord-1',
      status: status,
      goodsSubtotal: goodsSubtotal,
      shippingFee: shippingFee,
      shippingDiscount: shippingDiscount,
      totalAmount: totalAmount,
      coinAmount: coinAmount,
      cashAmount: cashAmount,
      expiresAt: expiresAt,
      createdAt: DateTime.now(),
      lines: const [
        ShopOrderLine(
            productName: 'Royal Canin',
            specName: '3 kg',
            unitPrice: 285000,
            qty: 1,
            lineTotal: 285000),
      ],
      receiverName: 'Budi',
      receiverPhone: '+62 812 3456 789',
      addressText: 'Jl. Test No. 1, Kebayoran',
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeOrderRepo implements ShopOrderRepository {
  ShopOrderDetail? detailData;
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<ShopOrderDetail> detail(String orderToken) async {
    calls.add('detail');
    return detailData!;
  }

  @override
  Future<ShopOrderDetail> confirmReceipt(String orderToken) async {
    calls.add('confirmReceipt');
    return detailData!;
  }

  @override
  Future<ShopPayResult> pay(String orderToken) async {
    calls.add('pay');
    return const ShopPayResult(orderStatus: 'PENDING_SHIPMENT');
  }

  @override
  Future<ShopOrderDetail> cancel(String orderToken) async {
    calls.add('cancel');
    detailData = _order(status: ShopOrderStatus.cancelled, expiresAt: null);
    return detailData!;
  }
}
