import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/checkout_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart' show ReturnPolicy;
import 'package:tailtopia/features/shop/presentation/checkout_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.7：结算页与两段金额（FR-97 / FR-99 / FR-100A / FR-104 / S-6 / UX-DR13 / UX-DR14）。
///
/// 🔴 本页最贵的错误是**金额说不清**：只显示一个总数、把免运抵扣藏成 0、
/// PawCoin 被上限截断却不解释。这几条各有一条用例看着，且都做过变异验证（见 story）。
void main() {
  late _FakeCheckoutRepo repo;
  late _FakeCartRepo cart;

  setUp(() {
    repo = _FakeCheckoutRepo();
    cart = _FakeCartRepo();
  });

  /// 下单成功后页面会 `go('/shop')`（订单详情页属 Story 3.8），所以宿主必须有真路由。
  GoRouter router() => GoRouter(
        initialLocation: '/shop/checkout',
        routes: [
          GoRoute(path: '/shop/checkout', builder: (c, s) => const CheckoutPage()),
          GoRoute(path: '/shop', builder: (c, s) => const Scaffold(body: Text('TOKO PAGE'))),
          GoRoute(
              path: '/me/addresses/new',
              builder: (c, s) => const Scaffold(body: Text('ADDRESS FORM'))),
        ],
      );

  Widget host(List<ShippingAddress> addresses, GoRouter r) => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          addressListProvider.overrideWith((ref) async => addresses),
          checkoutRepositoryProvider.overrideWithValue(repo),
          cartRepositoryProvider.overrideWithValue(cart),
        ],
        child: MaterialApp.router(
          routerConfig: r,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      );

  Future<GoRouter> open(WidgetTester t, {List<ShippingAddress>? addresses}) async {
    t.view.physicalSize = const Size(1200, 4000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    final r = router();
    await t.pumpWidget(host(addresses ?? [_addr('a1', isDefault: true)], r));
    await t.pumpAndSettle();
    return r;
  }

  group('🔴 FR-100A 规则 2：两段金额同时展示', () {
    testWidgets('混合支付：PawCoin 段与 QRIS 段都在，且底栏写清构成', (t) async {
      repo.previewData = _preview(coin: 60000, cash: 310000, total: 370000);
      await open(t);

      // 只显示一个总数会让用户对扣款构成产生误解 —— 而 PawCoin 段是不能提现的
      expect(find.text('− Rp 60.000'), findsOneWidget);
      expect(find.text('Rp 310.000'), findsWidgets);
      expect(find.byKey(const ValueKey('checkoutBottomMixed')), findsOneWidget);
    });

    testWidgets('🔴 付款前就说清 PawCoin 段不可提现（不等退款时才告知）', (t) async {
      repo.previewData = _preview(coin: 60000, cash: 310000, total: 370000);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutPawcoinNote')), findsOneWidget);
    });

    testWidgets('纯 QRIS：没有 PawCoin 段，也不显示不可提现提示', (t) async {
      repo.previewData = _preview(coin: 0, cash: 305000, total: 305000);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutCoinSegment')), findsNothing);
      expect(find.byKey(const ValueKey('checkoutPawcoinNote')), findsNothing);
      expect(find.byKey(const ValueKey('checkoutCashSegment')), findsOneWidget);
    });

    testWidgets('纯 Coin：现金段为 0 时不渲染 QRIS 行', (t) async {
      repo.previewData = _preview(coin: 120000, cash: 0, total: 120000);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutCoinSegment')), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutCashSegment')), findsNothing);
    });
  });

  group('🔴 C-16 / UX-DR14：单笔上限截断必须明示', () {
    testWidgets('被截断 → 多出「本单最多可用 …」一行', (t) async {
      repo.previewData = _preview(
        coin: 1000000,
        cash: 500000,
        total: 1500000,
        coinCapped: true,
        maxCoinPerOrder: 1000000,
      );
      await open(t);

      // 不明示则用户会以为系统出错了
      expect(find.byKey(const ValueKey('checkoutCoinCapped')), findsOneWidget);
      expect(find.textContaining('Rp 1.000.000'), findsWidgets);
    });

    testWidgets('未被截断 → 不出现该行（无谓的提示只会让用户困惑）', (t) async {
      repo.previewData = _preview(coin: 60000, cash: 310000, total: 370000);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutCoinCapped')), findsNothing);
    });
  });

  group('🔴 金额明细四行', () {
    testWidgets('免运抵扣是一条负数行，不是把运费改成 0', (t) async {
      repo.previewData = _preview(
        coin: 0,
        cash: 285000,
        total: 285000,
        shippingFee: 20000,
        shippingDiscount: -20000,
      );
      await open(t);

      // 直接把运费显示成 0 会让用户不知道自己省了钱，免运门槛也就失去了拉高客单价的作用
      expect(find.byKey(const ValueKey('checkoutFreeShipping')), findsOneWidget);
      expect(find.text('Rp 20.000'), findsOneWidget);
      expect(find.text('Rp -20.000'), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutPayable')), findsOneWidget);
    });

    testWidgets('🔴 不含优惠券 / 促销码 / 会员价（FR-97：不为暂缓功能提前埋成本）', (t) async {
      repo.previewData = _preview(coin: 0, cash: 305000, total: 305000);
      await open(t);

      for (final word in ['Kupon', 'Voucher', 'Promo', 'Member']) {
        expect(find.textContaining(word), findsNothing, reason: '$word 属会员/优惠体系，本版本零实现');
      }
    });
  });

  group('🔴 FR-104 第 2 处明示 / S-6 取最严', () {
    testWidgets('措辞与商品详情页逐字一致（同一批 ARB key）', (t) async {
      repo.previewData = _preview(
        coin: 0,
        cash: 285000,
        total: 285000,
        strictest: ReturnPolicy.noReturnAfterOpen,
      );
      await open(t);

      // 三处明示必须是同一句话，否则用户会以为规则变了
      expect(find.text('Tidak dapat dikembalikan setelah dibuka.'), findsOneWidget);
    });

    testWidgets('多 SKU 展开可看逐行标识', (t) async {
      repo.previewData = _preview(
        coin: 0,
        cash: 285000,
        total: 285000,
        strictest: ReturnPolicy.noReturn,
        lines: [
          _line('s1', name: 'A', policy: ReturnPolicy.returnable),
          _line('s2', name: 'B', policy: ReturnPolicy.noReturn),
        ],
      );
      await open(t);

      expect(find.text('Tidak bisa diretur.'), findsOneWidget);
      await t.tap(find.byKey(const ValueKey('checkoutPerLineToggle')));
      await t.pumpAndSettle();
      expect(find.textContaining('A · Bisa diretur.'), findsOneWidget);
      expect(find.textContaining('B · Tidak bisa diretur.'), findsOneWidget);
    });
  });

  group('🔴 FR-99 地址两态：提交必须禁用', () {
    testWidgets('无地址 → 引导态，且没有可提交的按钮', (t) async {
      await open(t, addresses: const []);

      expect(find.byKey(const ValueKey('checkoutAddAddress')), findsOneWidget);
      final btn = t.widget<FilledButton>(find.byKey(const ValueKey('checkoutSubmit')));
      expect(btn.onPressed, isNull, reason: '没有收货地址就下不了单');
      expect(repo.calls, isEmpty, reason: '没有地址就不该试算');
    });

    testWidgets('超服务范围 → 警示 + 提交禁用，但地址与商品照常渲染', (t) async {
      repo.previewData = _preview(coin: null, cash: null, total: null, serviceable: false);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutOutOfRange')), findsOneWidget);
      final btn = t.widget<FilledButton>(find.byKey(const ValueKey('checkoutSubmit')));
      expect(btn.onPressed, isNull);
      // 用户得看见自己选的是哪个地址，否则不知道该改哪里
      expect(find.byKey(const ValueKey('checkoutAddress')), findsOneWidget);
    });
  });

  group('配送方式（C-14 / UX-DR13）', () {
    testWidgets('只有一档，且不给多档选择控件', (t) async {
      repo.previewData = _preview(coin: 0, cash: 305000, total: 305000);
      await open(t);

      expect(find.byKey(const ValueKey('checkoutShippingMethod')), findsOneWidget);
      expect(find.text('Reguler · 2–4 hari'), findsOneWidget);
      // 原型画的多档选择器已作废：留一个恒选中的单选框只会让用户去找别的选项
      expect(find.byType(Radio<String>), findsNothing);
      expect(find.text('Sameday'), findsNothing);
    });
  });

  group('🔴 FR-95 第二次库存校验：不整单打回', () {
    testWidgets('下单撞库存 → 逐行列出「是哪件、还剩几件」，并可移除后继续', (t) async {
      repo.previewData = _preview(coin: 0, cash: 285000, total: 285000);
      await open(t);

      repo.failure = const CheckoutFailure(
        kind: CheckoutFailureKind.unavailableLines,
        unavailableLines: [
          UnavailableLine(
              skuToken: 's1',
              productName: 'Pro Plan',
              reason: 'INSUFFICIENT_STOCK',
              available: 2,
              requested: 5),
        ],
      );
      await t.tap(find.byKey(const ValueKey('checkoutSubmit')));
      await t.pumpAndSettle();

      expect(find.byKey(const ValueKey('checkoutUnavailableDialog')), findsOneWidget);
      expect(find.textContaining('Pro Plan'), findsOneWidget);
      expect(find.textContaining('Sisa 2'), findsOneWidget);

      await t.tap(find.byKey(const ValueKey('checkoutRemoveUnavailable')));
      await t.pumpAndSettle();

      // 只移除被挡住的那一行，其余留在车里
      expect(cart.calls, contains('remove:s1'));
    });
  });

  group('提交成功', () {
    testWidgets('下单后刷新购物车（角标必须跟上）', (t) async {
      repo.previewData = _preview(coin: 0, cash: 285000, total: 285000);
      await open(t);

      await t.tap(find.byKey(const ValueKey('checkoutSubmit')));
      await t.pumpAndSettle();

      expect(repo.calls, contains('placeOrder:a1'));
      expect(cart.calls, contains('view'), reason: '下单后车里已少了几行，角标要重拉');

      await t.pump(const Duration(seconds: 3));
      await t.pumpAndSettle();
    });

    testWidgets('🔴 归因暂不编造：entrySource / triggerType 一律不发（错归因比缺归因更糟）', (t) async {
      repo.previewData = _preview(coin: 0, cash: 285000, total: 285000);
      await open(t);

      await t.tap(find.byKey(const ValueKey('checkoutSubmit')));
      await t.pumpAndSettle();

      // 购物车行上没有「当初从哪个入口加进来」的记录（V108 无该列），
      // 前端此刻只能填「从购物车结算」——那不是归因。闭合归 Story 9.2。
      expect(repo.lastEntrySource, isNull);
      expect(repo.lastTriggerType, isNull);

      await t.pump(const Duration(seconds: 3));
      await t.pumpAndSettle();
    });
  });

  group('domain', () {
    test('未知 returnPolicy 降级到最保守档（结算页是承诺现场）', () {
      final l = CheckoutLine.fromJson(const {
        'skuToken': 's',
        'specName': '1 kg',
        'price': 1000,
        'qty': 1,
        'returnPolicy': 'SOMETHING_NEW',
      });
      expect(l.returnPolicy, ReturnPolicy.noReturn);
    });

    test('超范围时金额位保持 null —— 算不出就不显示，绝不填 0', () {
      final p = CheckoutPreview.fromJson(const {
        'address': {'token': 't'},
        'serviceable': false,
        'lines': [],
        'unavailableLines': [],
        'goodsSubtotal': 285000,
      });
      expect(p.serviceable, isFalse);
      expect(p.shippingFee, isNull);
      expect(p.payableTotal, isNull);
      expect(p.canSubmit, isFalse);
    });

    test('CheckoutFailure：409 带明细 → unavailableLines；422 → notPlaceable', () {
      final conflict = CheckoutFailure.from(DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 409,
          data: const {
            'status': 409,
            'unavailableLines': [
              {'skuToken': 's1', 'reason': 'DELISTED', 'available': 0, 'requested': 1}
            ],
          },
        ),
      ));
      expect(conflict.kind, CheckoutFailureKind.unavailableLines);
      expect(conflict.unavailableLines.single.isDelisted, isTrue);

      final unprocessable = CheckoutFailure.from(DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 422,
          data: const {'status': 422, 'detail': '暂不配送至 X'},
        ),
      ));
      expect(unprocessable.kind, CheckoutFailureKind.notPlaceable);
    });
  });
}

// ---------- helpers ----------

ShippingAddress _addr(String token, {bool isDefault = false}) => ShippingAddress(
      token: token,
      receiverName: 'Budi',
      receiverPhone: '+62 812 3456 789',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: 'Jakarta Selatan',
      kecamatan: 'Kebayoran',
      addressLine: 'Jl. Test No. 1',
      kodePos: '12160',
      isDefault: isDefault,
    );

CheckoutLine _line(String token,
        {String name = 'Produk', ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen}) =>
    CheckoutLine(
      skuToken: token,
      productName: name,
      specName: '3 kg',
      price: 285000,
      qty: 1,
      returnPolicy: policy,
    );

CheckoutPreview _preview({
  required int? coin,
  required int? cash,
  required int? total,
  int goodsSubtotal = 285000,
  int? shippingFee = 20000,
  int? shippingDiscount = 0,
  bool serviceable = true,
  bool coinCapped = false,
  int maxCoinPerOrder = 1000000,
  int coinBalance = 60000,
  ReturnPolicy strictest = ReturnPolicy.noReturnAfterOpen,
  List<CheckoutLine>? lines,
}) =>
    CheckoutPreview(
      addressToken: 'a1',
      receiverName: 'Budi',
      receiverPhone: '+62 812 3456 789',
      addressText: 'Jl. Test No. 1, Kebayoran',
      serviceable: serviceable,
      lines: lines ?? [_line('s1')],
      unavailableLines: const [],
      goodsSubtotal: goodsSubtotal,
      shippingFee: serviceable ? shippingFee : null,
      shippingDiscount: serviceable ? shippingDiscount : null,
      payableTotal: total,
      coinAmount: coin,
      cashAmount: cash,
      coinBalance: coinBalance,
      maxCoinPerOrder: maxCoinPerOrder,
      coinCapped: coinCapped,
      strictestReturnPolicy: strictest,
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeCheckoutRepo implements CheckoutRepository {
  /// 下一次试算返回的内容（由每个用例摆好）。
  CheckoutPreview? previewData;
  CheckoutFailure? failure;
  final List<String> calls = [];
  String? lastEntrySource;
  String? lastTriggerType;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CheckoutPreview> preview(String addressToken) async {
    calls.add('preview:$addressToken');
    return previewData!;
  }

  @override
  Future<ShopOrderRef> placeOrder(String addressToken,
      {String? entrySource, String? triggerType}) async {
    calls.add('placeOrder:$addressToken');
    lastEntrySource = entrySource;
    lastTriggerType = triggerType;
    final f = failure;
    if (f != null) {
      failure = null;
      throw f;
    }
    return const ShopOrderRef(
        orderToken: 'ord-1', status: 'PENDING_PAYMENT', totalAmount: 305000);
  }
}

class _FakeCartRepo implements CartRepository {
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CartView> view() async {
    calls.add('view');
    return CartView.empty;
  }

  @override
  Future<CartView> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType}) async {
    calls.add('add:$skuToken${entrySource == null ? '' : ':$entrySource'}');
    return CartView.empty;
  }

  @override
  Future<CartView> setQty(String skuToken, int qty) async {
    calls.add('setQty:$skuToken:$qty');
    return CartView.empty;
  }

  @override
  Future<CartView> remove(String skuToken) async {
    calls.add('remove:$skuToken');
    return CartView.empty;
  }

  @override
  Future<CartView> clearInvalid() async {
    calls.add('clearInvalid');
    return CartView.empty;
  }
}
