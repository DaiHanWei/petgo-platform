import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/address/presentation/address_form_body.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/checkout_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/presentation/checkout_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'address/fill_address_form.dart';

/// v1.4.2 shop Story 7.1：一页结算（D1–D18）。
///
/// 取代 `checkout_page_v2_test.dart`（随 v2 删除），其断言按 Dev Notes「测试」映射迁入并改写为新规则：
/// - 「无地址预览」→ D2；「FR-100A 两段金额」→ 支付方式卡同时显示币额与 QRIS 额、**明细里不再出现 PawCoin 行**；
/// - 「截断说明」→ D13（数字来自 coinCap）；「防套现提示」→ 用币时出现、D15 不出现；
/// - 「FR-99 超范围」→ D3（「—」、提交禁用、城市块、换地址出口改为打开 D4）；
/// - 「FR-104」原样；「金额一个都不自己算」→ 改用 pricing 字段断言；
/// - 「D-4 底栏合计不含 PawCoin 段」→ **按新规则改写**：底栏显示 payable（含币），次行「Sisa bayar lewat QRIS」；
/// - 「布局不得溢出」三条原样。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<String> names() => events.map((e) => e.$1).toList();
  Map<String, Object>? propsOf(String name) =>
      events.lastWhere((e) => e.$1 == name, orElse: () => (name, null)).$2;

  late _FakeCheckoutRepo repo;
  late _FakeCart cart;
  late _FakeAddressRepo addressRepo;
  late GoRouter router;

  Future<void> pumpPage(
    WidgetTester tester, {
    String location = '/shop/checkout',
    bool direct = false,
    List<ShippingAddress>? addresses,
    RegionTree? regions = kTestRegions,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) async {
    router = GoRouter(
      initialLocation: direct ? location : '/',
      routes: [
        GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('HOME'))),
        GoRoute(path: '/shop', builder: (c, s) => const Scaffold(body: Text('TOKO'))),
        GoRoute(
          path: '/shop/checkout',
          builder: (c, s) {
            final q = s.uri.queryParameters;
            if (q['source'] == 'buy_now') {
              return CheckoutPageV3(
                source: CheckoutSource.buyNow,
                buyNowSkuToken: q['sku'],
                buyNowQty: int.tryParse(q['qty'] ?? '') ?? 0,
                buyNowEntrySource: q['from'],
              );
            }
            return const CheckoutPageV3();
          },
        ),
        GoRoute(
            path: '/shop/orders/:token',
            builder: (c, s) => Scaffold(body: Text('DETAIL ${s.uri}'))),
        GoRoute(
            path: '/shop/orders/:token/paid',
            builder: (c, s) => Scaffold(body: Text('PAID ${s.pathParameters['token']}'))),
      ],
    );
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        checkoutRepositoryProvider.overrideWithValue(repo),
        cartProvider.overrideWith(() => cart),
        addressRepositoryProvider.overrideWithValue(addressRepo),
        addressListProvider.overrideWith((ref) async => addresses ?? [_addr('addr1', isDefault: true)]),
        regionTreeProvider.overrideWith((ref) async {
          if (regions == null) throw StateError('no regions');
          return regions;
        }),
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
        routerConfig: router,
        builder: (c, child) => MediaQuery(
          data: MediaQuery.of(c).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    if (!direct) {
      router.push(location);
      await tester.pumpAndSettle();
    }
  }

  setUp(() {
    repo = _FakeCheckoutRepo((req, n) async =>
        CheckoutPreview.fromJson(pv(selected: req.courierKey ?? 'jne:reg')));
    cart = _FakeCart();
    addressRepo = _FakeAddressRepo();
  });

  bool submitEnabled(WidgetTester tester) {
    final b = tester.widget<ShopButton>(find.byKey(const ValueKey('checkoutSubmitV3')));
    return b.onTap != null && b.variant != ShopButtonVariant.disabled;
  }

  String textOf(WidgetTester tester, String key) =>
      tester.widget<Text>(find.byKey(ValueKey(key))).data!;

  Future<void> flushToast(WidgetTester tester) async {
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
  }

  Finder inDetails(Finder f) =>
      find.descendant(of: find.byKey(const ValueKey('checkoutDetailsCard')), matching: f);

  // ================================================================ AC1 路由与入口

  group('AC1 · 路由、入口与埋点', () {
    testWidgets('无参数 → CART；上报 toko_checkout_page_viewed{entry: cart}', (tester) async {
      await pumpPage(tester);
      expect(repo.requests.first.source, CheckoutSource.cart);
      expect(propsOf('toko_checkout_page_viewed'), {'entry': 'cart'});
    });

    testWidgets('buy_now + sku + qty → BUY_NOW 试算带 buyNow；entry=buy_now', (tester) async {
      await pumpPage(tester, location: '/shop/checkout?source=buy_now&sku=sku9&qty=2');
      final r = repo.requests.first;
      expect(r.source, CheckoutSource.buyNow);
      expect(r.toJson()['buyNow'], {'skuToken': 'sku9', 'qty': 2});
      expect(propsOf('toko_checkout_page_viewed'), {'entry': 'buy_now'});
    });

    testWidgets('v1.4.2 8.2 复审：buy_now 带 from → 试算 buyNow.entrySource 透传；无 from 不发该键', (tester) async {
      await pumpPage(tester, location: '/shop/checkout?source=buy_now&sku=sku9&qty=2&from=TOKO_CATEGORY');
      expect(repo.requests.first.toJson()['buyNow'],
          {'skuToken': 'sku9', 'qty': 2, 'entrySource': 'TOKO_CATEGORY'});
    });

    testWidgets('🔴 buy_now 参数不合法 → 失败态，不退化成 CART（不发试算）', (tester) async {
      await pumpPage(tester, location: '/shop/checkout?source=buy_now&sku=sku9&qty=0');
      expect(repo.requests, isEmpty, reason: '退化成 CART 会把车里别的商品结掉');
      expect(find.text('Gagal memuat halaman checkout.'), findsOneWidget);
      // 复审修复 4：重试没用 → 按钮是「Kembali」（复用 shopOrderCancelConfirmNo），点了 pop
      expect(find.text('Coba lagi'), findsNothing);
      await tester.tap(find.text('Kembali'));
      await tester.pumpAndSettle();
      expect(find.text('HOME'), findsOneWidget);
    });

    testWidgets('buy_now 参数不合法且不能 pop（深链直达）→ go Toko 首页', (tester) async {
      await pumpPage(tester,
          location: '/shop/checkout?source=buy_now&sku=&qty=1', direct: true);
      await tester.tap(find.text('Kembali'));
      await tester.pumpAndSettle();
      expect(find.text('TOKO'), findsOneWidget);
    });

    testWidgets('首屏试算失败 → ShopRetryState，点重试重新试算', (tester) async {
      var n = 0;
      repo = _FakeCheckoutRepo((req, i) async {
        n++;
        if (n == 1) throw DioException(requestOptions: RequestOptions());
        return CheckoutPreview.fromJson(pv());
      });
      await pumpPage(tester);
      expect(find.text('Gagal memuat halaman checkout.'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('shopRetryButton')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('checkoutDeliveryCard')), findsOneWidget);
    });
  });

  // ================================================================ D1

  group('D1 · 默认版式', () {
    testWidgets('结构顺序：配送 → 商品 → 优惠 → 支付方式 → 明细 → 同意 → 底栏', (tester) async {
      await pumpPage(tester, size: const Size(411, 2400));
      double y(String k) => tester.getTopLeft(find.byKey(ValueKey(k))).dy;
      expect(y('checkoutDeliveryCard'), lessThan(y('checkoutItemsCard')));
      expect(y('checkoutItemsCard'), lessThan(y('checkoutCouponCard')));
      expect(y('checkoutCouponCard'), lessThan(y('checkoutPaymentCard')));
      expect(y('checkoutPaymentCard'), lessThan(y('checkoutDetailsCard')));
      expect(y('checkoutDetailsCard'), lessThan(y('checkoutAgreeNoReturn')));
      expect(y('checkoutAgreeNoReturn'), lessThan(y('checkoutSubmitV3')));
    });

    testWidgets('🔴 金额一个都不自己算：全部取 pricing；底栏 = payable（含币）+ 已省 + QRIS 次行',
        (tester) async {
      // 故意给一组**互相对不上**的数：页面若自己加减就会露馅
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(
          goods: 349000, disc: 20000, ship: 10000, shipDisc: 10000, payable: 111111,
          coin: 12000, cash: 99999, saved: 30000)));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(textOf(tester, 'checkoutPayableV3'), 'Rp 111.111');
      expect(textOf(tester, 'checkoutBottomSaved'), 'Hemat Rp 30.000');
      expect(textOf(tester, 'checkoutBottomSubline'), 'Sisa bayar lewat QRIS: Rp 99.999');
      expect(textOf(tester, 'checkoutCashAmount'), 'Rp 99.999');
      expect(textOf(tester, 'checkoutCoinAmount'), '12.000');
      expect(textOf(tester, 'checkoutGoodsAmount'), 'Rp 349.000');
      expect(textOf(tester, 'checkoutSavedOnOrder'), 'Kamu hemat Rp 30.000 di pesanan ini');
      expect(textOf(tester, 'checkoutDiscountCouponValue'), '−Rp 20.000');
      expect(textOf(tester, 'checkoutFreeShippingCouponValue'), '−Rp 10.000');
    });

    testWidgets('🔴 明细按 FR-130 顺序到运费减免为止，**不出现 PawCoin 行**', (tester) async {
      await pumpPage(tester, size: const Size(411, 2400));
      expect(inDetails(find.text('Subtotal produk')), findsOneWidget);
      expect(inDetails(find.text('Diskon belanja')), findsOneWidget);
      expect(inDetails(find.text('Ongkos kirim')), findsOneWidget);
      expect(inDetails(find.text('Kupon gratis ongkir')), findsOneWidget);
      expect(inDetails(find.textContaining('PawCoin')), findsNothing,
          reason: 'FR-131.3：PawCoin 是支付方式，不是减免');
      double y(String k) => tester.getTopLeft(find.byKey(ValueKey(k))).dy;
      expect(y('checkoutDetailSubtotal'), lessThan(y('checkoutDetailCoupon')));
      expect(y('checkoutDetailCoupon'), lessThan(y('checkoutDetailShipping')));
      expect(y('checkoutDetailShipping'), lessThan(y('checkoutDetailShipDiscount')));
    });

    testWidgets('用币时防套现提示出现（FR-100A 两段金额同时展示）', (tester) async {
      await pumpPage(tester, size: const Size(411, 2400));
      expect(find.byKey(const ValueKey('checkoutCoinAmount')), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutCashAmount')), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutPawcoinNote')), findsOneWidget);
      expect(textOf(tester, 'checkoutQrisTitle'), 'Sisanya lewat QRIS');
    });

    testWidgets('快递行：所选即最便宜 → 次行 Termurah + 右侧运费', (tester) async {
      await pumpPage(tester);
      expect(textOf(tester, 'checkoutCourierSub'), 'Termurah');
      expect(textOf(tester, 'checkoutCourierPrice'), 'Rp 10.000');
    });

    testWidgets('保价默认关；打开后请求 insurance=true', (tester) async {
      await pumpPage(tester);
      expect(repo.requests.first.insurance, isFalse);
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.insurance, isTrue);
    });

    testWidgets('FR-104：取消同意则不可提交，再勾回可提交', (tester) async {
      await pumpPage(tester, size: const Size(411, 2400));
      expect(submitEnabled(tester), isTrue);
      await tester.tap(find.byKey(const ValueKey('checkoutAgreeNoReturn')));
      await tester.pumpAndSettle();
      expect(submitEnabled(tester), isFalse);
      await tester.tap(find.byKey(const ValueKey('checkoutAgreeNoReturn')));
      await tester.pumpAndSettle();
      expect(submitEnabled(tester), isTrue);
    });

    testWidgets('FR-104：开封不退文案取最严规则（与详情页同一 key）', (tester) async {
      await pumpPage(tester, size: const Size(411, 2400));
      expect(find.textContaining('kemasan makanan yang sudah dibuka'), findsOneWidget);
    });

    testWidgets('AC8.5 免运进度：threshold>0 且 remaining>0 → 再买一行；threshold=0 不画', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(
          pv(progress: {'threshold': 300000, 'remaining': 15000})));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(textOf(tester, 'checkoutFreeShipRemaining'),
          'Belanja Rp 15.000 lagi untuk potongan ongkir');
    });

    testWidgets('免运进度 threshold=0 → 不画', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async =>
          CheckoutPreview.fromJson(pv(progress: {'threshold': 0, 'remaining': 0})));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(find.byKey(const ValueKey('checkoutFreeShipRemaining')), findsNothing);
    });
  });

  // ================================================================ D2 / D3 / D4 / D5

  group('地址', () {
    testWidgets('D2 无地址：请求不带 addressToken、金额位「—」、提交禁用、点地址区直接打开 D5', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async =>
          CheckoutPreview.fromJson(pv(address: false, quoted: false, block: 'NO_ADDRESS')));
      await pumpPage(tester, addresses: const []);
      expect(repo.requests.first.addressToken, isNull);
      expect(textOf(tester, 'checkoutPayableV3'), '—');
      expect(textOf(tester, 'checkoutCashAmount'), '—');
      expect(find.text('Rp 0'), findsNothing, reason: 'FR-99：绝不显示 Rp 0');
      expect(find.byKey(const ValueKey('checkoutCourierNoAddress')), findsOneWidget);
      expect(submitEnabled(tester), isFalse);

      await tester.tap(find.byKey(const ValueKey('checkoutAddAddressV3')));
      await tester.pumpAndSettle();
      expect(find.byType(AddressFormBody), findsOneWidget, reason: '直接进 D5，不经选择抽屉');
      expect(find.text('Simpan & pakai'), findsOneWidget);
      expect(find.byType(CheckoutPageV3), findsOneWidget, reason: '不再 push 整页地址选择器');
    });

    testWidgets('D5 页内新增：保存 → 存入地址簿 → 本单选中新地址 → 重新试算', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => req.addressToken == null
          ? CheckoutPreview.fromJson(pv(address: false, quoted: false, block: 'NO_ADDRESS'))
          : CheckoutPreview.fromJson(pv(addrToken: req.addressToken!)));
      await pumpPage(tester, addresses: const []);
      await tester.tap(find.byKey(const ValueKey('checkoutAddAddressV3')));
      await tester.pumpAndSettle();

      await fillAndSaveAddressForm(tester);

      expect(addressRepo.created, 1);
      expect(find.byType(AddressFormBody), findsNothing, reason: '保存后抽屉关闭');
      expect(repo.requests.last.addressToken, 'addr-new');
      expect(submitEnabled(tester), isTrue);
    });

    testWidgets('D3 不支持配送：警示 + 「—」+ 提交禁用 + 城市块 + 埋点一次；「Pilih alamat lain」打开 D4',
        (tester) async {
      repo = _FakeCheckoutRepo(
          (req, n) async => CheckoutPreview.fromJson(pv(quoted: false, block: 'NOT_SERVICEABLE')));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(textOf(tester, 'checkoutOutOfRangeTitle'), 'Wilayah ini belum bisa dikirim kurir');
      expect(find.text('Silakan pilih alamat lain untuk melanjutkan.'), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutNoCourier')), findsOneWidget);
      expect(textOf(tester, 'checkoutPayableV3'), '—');
      expect(inDetails(find.text('—')), findsOneWidget, reason: '运费位「—」而非 Rp 0');
      expect(submitEnabled(tester), isFalse);
      expect(find.byKey(const ValueKey('checkoutServiceArea')), findsOneWidget);
      expect(find.text('Wilayah yang sudah kami layani'), findsOneWidget);
      expect(find.text('Bandung'), findsOneWidget);
      expect(names().where((e) => e == 'toko_checkout_out_of_range_shown').length, 1);

      await tester.tap(find.byKey(const ValueKey('checkoutPickAnotherAddress')));
      await tester.pumpAndSettle();
      expect(find.text('Pilih alamat'), findsOneWidget, reason: '修线上缺陷：打开 D4 而非跳地址管理页');
      expect(find.byType(CheckoutPageV3), findsOneWidget);
    });

    testWidgets('D3 区域取不到 → 城市块整块不画', (tester) async {
      repo = _FakeCheckoutRepo(
          (req, n) async => CheckoutPreview.fromJson(pv(quoted: false, block: 'NOT_SERVICEABLE')));
      await pumpPage(tester, regions: null, size: const Size(411, 2400));
      expect(find.byKey(const ValueKey('checkoutServiceArea')), findsNothing);
    });

    testWidgets('D4：列全部地址、默认标 Utama、不可配送标「Belum dilayani」仍可选；选中即带新 token 重算、不设默认',
        (tester) async {
      await pumpPage(tester, addresses: [
        _addr('addr1', isDefault: true),
        _addr('addr2', serviceable: false),
      ]);
      await tester.tap(find.byKey(const ValueKey('checkoutAddressRowV3')));
      await tester.pumpAndSettle();
      expect(find.text('Utama'), findsOneWidget);
      expect(find.text('Belum dilayani'), findsOneWidget);
      expect(find.text('+ Tambah alamat baru'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('checkoutAddressOption_addr2')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.addressToken, 'addr2');
      expect(addressRepo.setDefaultCalls, 0, reason: '页内换地址只对本单，不写回默认');
    });

    testWidgets('换地址时快递保留：请求带当前 courierKey', (tester) async {
      await pumpPage(tester, addresses: [_addr('addr1', isDefault: true), _addr('addr2')]);
      await tester.tap(find.byKey(const ValueKey('checkoutCourierRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutCourierOption_sicepat:best')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutAddressRowV3')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutAddressOption_addr2')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.addressToken, 'addr2');
      expect(repo.requests.last.courierKey, 'sicepat:best');
    });
  });

  // ================================================================ D6 – D9

  group('快递与保价', () {
    testWidgets('D6 询价中（换地址）：「Menghitung ongkir…」、应付「—」、提交禁用', (tester) async {
      final gate = Completer<CheckoutPreview>();
      repo = _FakeCheckoutRepo((req, n) async =>
          n == 1 ? CheckoutPreview.fromJson(pv()) : gate.future);
      await pumpPage(tester, addresses: [_addr('addr1', isDefault: true), _addr('addr2')]);
      await tester.tap(find.byKey(const ValueKey('checkoutAddressRowV3')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutAddressOption_addr2')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('checkoutQuoting')), findsOneWidget);
      expect(textOf(tester, 'checkoutPayableV3'), '—');
      expect(submitEnabled(tester), isFalse);
      gate.complete(CheckoutPreview.fromJson(pv(addrToken: 'addr2')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('checkoutQuoting')), findsNothing);
      expect(submitEnabled(tester), isTrue);
    });

    testWidgets('轻量重算（保价开关）：保留上一次金额、只禁用提交', (tester) async {
      final gate = Completer<CheckoutPreview>();
      repo = _FakeCheckoutRepo((req, n) async =>
          n == 1 ? CheckoutPreview.fromJson(pv()) : gate.future);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pump();
      expect(textOf(tester, 'checkoutPayableV3'), 'Rp 329.000', reason: '不闪骨架');
      expect(submitEnabled(tester), isFalse);
      gate.complete(CheckoutPreview.fromJson(pv(insurance: true, insFee: 1745)));
      await tester.pumpAndSettle();
      expect(submitEnabled(tester), isTrue);
    });

    testWidgets('🔴 过期响应被丢弃：先发后到的旧响应不覆盖新结果', (tester) async {
      final slow = Completer<CheckoutPreview>();
      final fast = Completer<CheckoutPreview>();
      repo = _FakeCheckoutRepo((req, n) async => switch (n) {
            1 => CheckoutPreview.fromJson(pv()),
            2 => slow.future,
            _ => fast.future,
          });
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pump();
      fast.complete(CheckoutPreview.fromJson(pv(payable: 222222)));
      await tester.pumpAndSettle();
      slow.complete(CheckoutPreview.fromJson(pv(payable: 999999)));
      await tester.pumpAndSettle();
      expect(textOf(tester, 'checkoutPayableV3'), 'Rp 222.222');
    });

    testWidgets('D7：按响应顺序、首项 Termurah、Tanpa asuransi、底部封顶说明；选中后带 courierKey 重算',
        (tester) async {
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutCourierRow')));
      await tester.pumpAndSettle();
      expect(find.text('Pilih kurir'), findsOneWidget);
      final jne = tester.getTopLeft(find.byKey(const ValueKey('checkoutCourierOption_jne:reg'))).dy;
      final sc = tester.getTopLeft(find.byKey(const ValueKey('checkoutCourierOption_sicepat:best'))).dy;
      expect(jne, lessThan(sc));
      expect(find.byKey(const ValueKey('checkoutCourierCheapestTag')), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutCourierNoInsurance_sicepat:best')), findsOneWidget);
      expect(find.text('Estimasi 2 - 3 hari'), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutCourierCapNote')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('checkoutCourierOption_sicepat:best')));
      await tester.pumpAndSettle();
      expect(find.text('Pilih kurir'), findsNothing);
      expect(repo.requests.last.courierKey, 'sicepat:best');
    });

    testWidgets('D8 询价失败：红字 + Coba lagi 重新试算；「—」、不可提交', (tester) async {
      repo = _FakeCheckoutRepo(
          (req, n) async => CheckoutPreview.fromJson(pv(quoted: false, block: 'QUOTE_FAILED')));
      await pumpPage(tester);
      expect(textOf(tester, 'checkoutQuoteFailed'), 'Ongkir belum bisa dimuat.');
      expect(textOf(tester, 'checkoutPayableV3'), '—');
      expect(submitEnabled(tester), isFalse);
      final before = repo.requests.length;
      await tester.tap(find.byKey(const ValueKey('checkoutQuoteRetry')));
      await tester.pumpAndSettle();
      expect(repo.requests.length, before + 1);
    });

    testWidgets('D9 不支持保价：不画保价行、次行 Tanpa asuransi、下一次请求 insurance=false', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(
          selected: 'sicepat:best', insuranceAvailable: false, ship: 24000, payable: 343000)));
      await pumpPage(tester);
      expect(find.byKey(const ValueKey('checkoutInsuranceRow')), findsNothing);
      expect(textOf(tester, 'checkoutCourierSub'), 'Tanpa asuransi');
      expect(textOf(tester, 'checkoutPayableV3'), 'Rp 343.000');
      await tester.tap(find.byKey(const ValueKey('checkoutCoinSwitch')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.insurance, isFalse);
    });
  });

  // ================================================================ D10 – D12

  group('券', () {
    testWidgets('首次 AUTO；D10 换券 → MANUAL + 两张券（另一张沿用上次响应）', (tester) async {
      await pumpPage(tester);
      expect(repo.requests.first.manualCoupons, isFalse);
      await tester.tap(find.byKey(const ValueKey('checkoutDiscountCouponRow')));
      await tester.pumpAndSettle();
      expect(find.text('Kupon diskon belanja'), findsOneWidget);
      expect(find.text('Bisa dipakai (1)'), findsOneWidget);
      expect(find.text('Belum bisa dipakai (2)'), findsOneWidget);
      // 原因映射：满减的 OUT_OF_SCOPE =「Produk tidak termasuk」
      expect(find.text('Belum capai minimum belanja'), findsOneWidget);
      expect(find.text('Produk tidak termasuk'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('couponDontUse')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('couponApply')));
      await tester.pumpAndSettle();
      final r = repo.requests.last;
      expect(r.manualCoupons, isTrue);
      expect(r.discountCouponToken, isNull, reason: '「Tidak pakai kupon」传 null');
      expect(r.freeShippingCouponToken, 'ucF1', reason: '未改的那张沿用上次响应值');
    });

    testWidgets('D11：免运券 OUT_OF_SCOPE 文案不同、RULE_BETTER 文案；选券 → MANUAL', (tester) async {
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutFreeShippingCouponRow')));
      await tester.pumpAndSettle();
      expect(find.text('Kupon gratis ongkir'), findsWidgets);
      expect(find.text('Ada produk di luar cakupan kupon'), findsOneWidget);
      expect(find.text('Potongan otomatis lebih hemat'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('couponOption_ucF1')));
      await tester.tap(find.byKey(const ValueKey('couponApply')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.manualCoupons, isTrue);
      expect(repo.requests.last.freeShippingCouponToken, 'ucF1');
      expect(repo.requests.last.discountCouponToken, 'ucD1');
    });

    testWidgets('🔴 手选券未被采用（selectedToken=null）→ 写「Tidak dipakai」，不让用户以为已用券', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async =>
          CheckoutPreview.fromJson(pv(fsSelected: null, shipDisc: 0, shipSrc: 'NONE')));
      await pumpPage(tester);
      expect(textOf(tester, 'checkoutFreeShippingCouponValue'), 'Tidak dipakai');
    });

    testWidgets('该组无可用券 →「Belum ada yang bisa dipakai」', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(
          discount: {'available': [], 'unavailable': []}, disc: 0)));
      await pumpPage(tester);
      expect(textOf(tester, 'checkoutDiscountCouponValue'), 'Belum ada yang bisa dipakai');
    });

    testWidgets('D12 满额免运：优惠卡两行文案 + 明细「Potongan ongkir (belanja ≥ Rp X)」', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(
          fsSelected: null,
          shipSrc: 'FREE_SHIPPING_RULE',
          progress: {'threshold': 300000, 'remaining': 0})));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(find.text('Potongan otomatis'), findsOneWidget);
      expect(textOf(tester, 'checkoutAutoShipDiscountHint'),
          'Belanja ≥ Rp 300.000, lebih hemat dari kupon');
      expect(inDetails(find.text('Potongan ongkir (belanja ≥ Rp 300.000)')), findsOneWidget);
    });

    testWidgets('S-10：商品 302.000、门槛 300.000、券减 5.000 → 默认用券，应付取服务端 317.000', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(
          goods: 302000, disc: 5000, ship: 20000, shipDisc: 0, shipSrc: 'NONE',
          payable: 317000, coin: 0, cash: 317000, saved: 5000, balance: 0, fsSelected: null)));
      await pumpPage(tester);
      expect(repo.requests.first.manualCoupons, isFalse, reason: '默认最优由服务端选');
      expect(textOf(tester, 'checkoutDiscountCouponValue'), '−Rp 5.000');
      expect(textOf(tester, 'checkoutPayableV3'), 'Rp 317.000');
    });
  });

  // ================================================================ D13 – D15

  group('PawCoin', () {
    testWidgets('D13 超上限：提醒数字来自 coinCap（不写死 1.000.000）', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(
          pv(balance: 800000, cap: 500000, coin: 500000, cash: 10000, capReached: true)));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(textOf(tester, 'checkoutCoinCapped'),
          'Maksimal 500.000 PawCoin per pesanan. Pemakaian di atas batas ini bisa dianggap pencairan dana.');
    });

    testWidgets('D14 足额：不画 QRIS 行、写全币说明、底栏「Dibayar penuh dengan PawCoin」', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async =>
          CheckoutPreview.fromJson(pv(balance: 500000, coin: 329000, cash: 0)));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(find.byKey(const ValueKey('checkoutQrisRow')), findsNothing);
      expect(find.byKey(const ValueKey('checkoutPaidFullCoinNote')), findsOneWidget);
      expect(find.byKey(const ValueKey('checkoutPawcoinNote')), findsOneWidget);
      expect(textOf(tester, 'checkoutBottomSubline'), 'Dibayar penuh dengan PawCoin');
    });

    testWidgets('D15 关掉：本页内保持 useCoin=false；QRIS 全额标题；无防套现提示、无底栏次行', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async => req.useCoin
          ? CheckoutPreview.fromJson(pv())
          : CheckoutPreview.fromJson(pv(useCoin: false, coin: 0, cash: 329000)));
      await pumpPage(tester, size: const Size(411, 2400));
      expect(repo.requests.first.useCoin, isTrue, reason: '有余额默认开');
      await tester.tap(find.byKey(const ValueKey('checkoutCoinSwitch')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.useCoin, isFalse);
      expect(textOf(tester, 'checkoutQrisTitle'), 'Bayar penuh lewat QRIS');
      expect(textOf(tester, 'checkoutCashAmount'), 'Rp 329.000');
      expect(find.byKey(const ValueKey('checkoutPawcoinNote')), findsNothing);
      expect(find.byKey(const ValueKey('checkoutBottomSubline')), findsNothing);
      // 后续任何重算都带 useCoin=false
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.useCoin, isFalse);
    });

    testWidgets('余额为 0：开关不可点、QRIS 写全额', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async =>
          CheckoutPreview.fromJson(pv(balance: 0, useCoin: false, coin: 0, cash: 329000)));
      await pumpPage(tester, size: const Size(411, 2400));
      final before = repo.requests.length;
      await tester.tap(find.byKey(const ValueKey('checkoutCoinSwitch')));
      await tester.pumpAndSettle();
      expect(repo.requests.length, before);
      expect(textOf(tester, 'checkoutQrisTitle'), 'Bayar penuh lewat QRIS');
    });
  });

  // ================================================================ D18

  testWidgets('D18 商品展开：原地展开每件一行，再点收起，不跳页', (tester) async {
    repo = _FakeCheckoutRepo((req, n) async => CheckoutPreview.fromJson(pv(lines: [
          _line('s1', qty: 2),
          _line('s2'),
        ])));
    await pumpPage(tester);
    expect(find.text('Produk (3)'), findsOneWidget);
    expect(find.byKey(const ValueKey('checkoutItemLine_s1')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('checkoutItemsToggle')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('checkoutItemLine_s1')), findsOneWidget);
    expect(find.byKey(const ValueKey('checkoutItemLine_s2')), findsOneWidget);
    expect(find.byIcon(Icons.expand_less_rounded), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('checkoutItemsToggle')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('checkoutItemLine_s1')), findsNothing);
    expect(find.byType(CheckoutPageV3), findsOneWidget);
  });

  testWidgets('试算阶段失效行（UNAVAILABLE_LINES）：CART 给「Hapus & lanjut」移除后重算', (tester) async {
    var removedDone = false;
    repo = _FakeCheckoutRepo((req, n) async => removedDone
        ? CheckoutPreview.fromJson(pv())
        : CheckoutPreview.fromJson(pv(block: 'UNAVAILABLE_LINES', lines: [
            _line('s1'),
            _line('s2', unavailable: 'INSUFFICIENT_STOCK', stock: 1, qty: 3),
          ])));
    cart.onRemove = () => removedDone = true;
    await pumpPage(tester);
    expect(find.byKey(const ValueKey('checkoutUnavailableCard')), findsOneWidget);
    expect(find.textContaining('Sisa 1, diminta 3'), findsOneWidget);
    expect(submitEnabled(tester), isFalse);
    await tester.tap(find.byKey(const ValueKey('checkoutRemoveUnavailableInline')));
    await tester.pumpAndSettle();
    expect(cart.removed, ['s2']);
    expect(find.byKey(const ValueKey('checkoutUnavailableCard')), findsNothing);
    expect(submitEnabled(tester), isTrue);
  });

  // ================================================================ 提交

  group('提交与失败（D16 / D17 / P-5）', () {
    testWidgets('请求体：quoteToken / courierKey / 保价 / 两张实际采用的券 / useCoin', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      final r = repo.placed.single;
      expect(r.toJson(), {
        'source': 'CART',
        'addressToken': 'addr1',
        'quoteToken': 'q1',
        'courierKey': 'jne:reg',
        'insurance': false,
        'discountCouponToken': 'ucD1',
        'freeShippingCouponToken': 'ucF1',
        'useCoin': true,
        'agreeReturnPolicy': true,
      });
      expect(names(), contains('toko_checkout_submit_tapped'));
    });

    testWidgets('需扫码 → pushReplacement /shop/orders/x?autopay=1；CART 刷新购物车', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(find.text('DETAIL /shop/orders/ord1?autopay=1'), findsOneWidget);
      expect(find.byType(CheckoutPageV3), findsNothing, reason: 'pushReplacement');
      expect(cart.refreshes, 1);
      expect(names(), isNot(contains('toko_order_payment_succeeded')));
    });

    testWidgets('🔴 纯币 → /shop/orders/x/paid、不调 /pay、上报 payment_succeeded{PAWCOIN}', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_SHIPMENT', totalAmount: 329000, paid: true);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(find.text('PAID ord1'), findsOneWidget);
      expect(find.byType(CheckoutPageV3), findsNothing);
      expect(propsOf('toko_order_payment_succeeded'), {'pay_channel': 'PAWCOIN'},
          reason: '服务端没给 payChannel → 回落 PAWCOIN');
    });

    testWidgets('已结清分支 pay_channel 取服务端 order.payChannel（复审修复 3）', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_SHIPMENT', totalAmount: 0, payChannel: 'QRIS',
          paid: true);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(propsOf('toko_order_payment_succeeded'), {'pay_channel': 'QRIS'});
    });

    testWidgets('埋点 toko_order_submitted：原属性 + 新五键（值与类型）', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      final p = propsOf('toko_order_submitted')!;
      expect(p['item_count'], 1);
      expect((p['items'] as List).single, {
        'sku_id': 's1',
        'qty': 1,
        'entry_source': 'unknown',
        'trigger_type': 'none',
      });
      expect(p['coupon_ids'], ['cD1', 'cF1'], reason: '券**模板** token，不是用户券 token');
      expect(p['coin_used'], true);
      expect(p['courier'], 'jne_reg');
      expect(p['shipping_fee'], 10000);
      expect(p['insurance'], false);
      expect(p.keys.any((k) => k.contains('order') || k.contains('address')), isFalse);
    });

    testWidgets('D16 CART：弹窗逐行 → Hapus & lanjut → 逐行移除 → 停在本页重算、选择保留', (tester) async {
      repo.onPlace = (r) async => throw const CheckoutFailure(
            kind: CheckoutFailureKind.unavailableLines,
            unavailableLines: [
              UnavailableLine(
                  skuToken: 's1', productName: 'Royal Canin', reason: 'INSUFFICIENT_STOCK',
                  available: 1, requested: 2),
            ],
          );
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutCourierRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutCourierOption_sicepat:best')));
      await tester.pumpAndSettle();
      final before = repo.requests.length;
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('checkoutUnavailableDialogV3')), findsOneWidget);
      expect(find.text('Royal Canin · Sisa 1, diminta 2'), findsOneWidget);
      expect(find.text('Batal'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('checkoutRemoveUnavailableV3')));
      await tester.pumpAndSettle();
      expect(cart.removed, ['s1']);
      expect(repo.requests.length, before + 1);
      expect(repo.requests.last.courierKey, 'sicepat:best', reason: '选择保留');
      expect(find.byType(CheckoutPageV3), findsOneWidget);
    });

    testWidgets('D16 BUY_NOW：只有 Batal，关闭后 pop 回上一页；全程不碰购物车', (tester) async {
      repo.onPlace = (r) async => throw const CheckoutFailure(
            kind: CheckoutFailureKind.unavailableLines,
            unavailableLines: [
              UnavailableLine(skuToken: 'sku9', reason: 'DELISTED', available: 0, requested: 1),
            ],
          );
      await pumpPage(tester, location: '/shop/checkout?source=buy_now&sku=sku9&qty=1');
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('checkoutRemoveUnavailableV3')), findsNothing);
      await tester.tap(find.text('Batal'));
      await tester.pumpAndSettle();
      expect(find.text('HOME'), findsOneWidget);
      expect(find.byType(CheckoutPageV3), findsNothing);
      expect(cart.mutations, 0);
      expect(cart.refreshes, 0);
    });

    testWidgets('BUY_NOW 提交成功也不刷新购物车', (tester) async {
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      await pumpPage(tester, location: '/shop/checkout?source=buy_now&sku=sku9&qty=1');
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(repo.placed.single.toJson()['buyNow'], {'skuToken': 'sku9', 'qty': 1});
      expect(cart.refreshes, 0);
      expect(cart.mutations, 0);
    });

    testWidgets('D17 coupon-invalid：toast + couponMode=AUTO 重算 + 停在本页、不自动再提交', (tester) async {
      repo.onPlace = (r) async =>
          throw const CheckoutFailure(kind: CheckoutFailureKind.couponInvalid);
      await pumpPage(tester);
      // 先手动换一次券 → MANUAL
      await tester.tap(find.byKey(const ValueKey('checkoutDiscountCouponRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('couponApply')));
      await tester.pumpAndSettle();
      expect(repo.requests.last.manualCoupons, isTrue);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      await tester.pump();
      expect(find.text('Kupon sudah tidak berlaku. Kami pilihkan kupon lain.'), findsOneWidget);
      await flushToast(tester);
      expect(repo.requests.last.manualCoupons, isFalse);
      expect(repo.placed.length, 1, reason: '不自动再提交');
      expect(find.byType(CheckoutPageV3), findsOneWidget);
    });

    testWidgets('quote-expired：不弹提示，静默重算', (tester) async {
      repo.onPlace = (r) async =>
          throw const CheckoutFailure(kind: CheckoutFailureKind.quoteExpired);
      await pumpPage(tester);
      final before = repo.requests.length;
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(repo.requests.length, before + 1);
      expect(find.byType(SnackBar), findsNothing);
      expect(find.text('Gagal membuat pesanan. Coba lagi.'), findsNothing);
      expect(find.text('Pesanan tidak bisa dibuat sekarang. Muat ulang halaman.'), findsNothing);
    });

    for (final (kind, text) in [
      (CheckoutFailureKind.notPlaceable, 'Pesanan tidak bisa dibuat sekarang. Muat ulang halaman.'),
      (CheckoutFailureKind.skuNotSellable, 'Pesanan tidak bisa dibuat sekarang. Muat ulang halaman.'),
      (CheckoutFailureKind.generic, 'Gagal membuat pesanan. Coba lagi.'),
    ]) {
      testWidgets('$kind → toast「$text」，按钮放开', (tester) async {
        repo.onPlace = (r) async => throw CheckoutFailure(kind: kind);
        await pumpPage(tester);
        await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
        await tester.pump();
        await tester.pump();
        expect(find.text(text), findsOneWidget);
        await flushToast(tester);
        expect(submitEnabled(tester), isTrue);
      });
    }

    testWidgets('🔴 同一份试算内重试复用同一幂等键（网络抖动不重复建单）', (tester) async {
      var n = 0;
      repo.onPlace = (r) async {
        n++;
        throw const CheckoutFailure(kind: CheckoutFailureKind.generic);
      };
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      await flushToast(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      await flushToast(tester);
      expect(n, 2);
      expect(repo.keys[0], repo.keys[1]);
    });
  });

  group('复审修复 · 资金一致性', () {
    testWidgets('🔴 轻量重算失败：选择回滚到上次成功试算（币开关回到「开」），提交体与显示一致', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async {
        if (n == 2) throw DioException(requestOptions: RequestOptions());
        return CheckoutPreview.fromJson(pv(selected: req.courierKey ?? 'jne:reg'));
      });
      repo.onPlace = (r) async => const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      await pumpPage(tester, size: const Size(411, 2400));
      await tester.tap(find.byKey(const ValueKey('checkoutCoinSwitch')));
      await tester.pump();
      expect(repo.requests.last.useCoin, isFalse);
      await flushToast(tester);
      final sw = tester.widget<ShopSwitch>(find.byKey(const ValueKey('checkoutCoinSwitch')));
      expect(sw.value, isTrue, reason: '失败后开关必须回到旧值，否则显示「关」实际扣币');
      expect(find.byKey(const ValueKey('checkoutCoinAmount')), findsOneWidget);
      expect(submitEnabled(tester), isTrue);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(repo.placed.single.useCoin, isTrue, reason: '提交体 = 页面所示');
    });

    testWidgets('轻量重算失败：保价 / 快递 / 券同样回滚', (tester) async {
      repo = _FakeCheckoutRepo((req, n) async {
        if (n >= 2) throw DioException(requestOptions: RequestOptions());
        return CheckoutPreview.fromJson(pv(selected: req.courierKey ?? 'jne:reg'));
      });
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pump();
      await flushToast(tester);
      expect(tester.widget<ShopSwitch>(find.byKey(const ValueKey('checkoutInsuranceSwitch'))).value,
          isFalse);
      await tester.tap(find.byKey(const ValueKey('checkoutDiscountCouponRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('couponDontUse')));
      await tester.tap(find.byKey(const ValueKey('couponApply')));
      await tester.pump();
      await flushToast(tester);
      // 下一次请求（任意触发）应带回滚后的选择：AUTO、保价关
      await tester.tap(find.byKey(const ValueKey('checkoutCourierRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutCourierOption_sicepat:best')));
      await tester.pump();
      final r = repo.requests.last;
      expect(r.manualCoupons, isFalse);
      expect(r.insurance, isFalse);
      await flushToast(tester);
      expect(textOf(tester, 'checkoutCourierPrice'), 'Rp 10.000', reason: '快递回到上次成功的那家');
    });

    testWidgets('🔴 提交中禁用所有选择交互（币开关 / 保价 / 快递 / 券 / 地址）', (tester) async {
      final gate = Completer<ShopOrderRef>();
      repo.onPlace = (r) => gate.future;
      await pumpPage(tester, size: const Size(411, 2400));
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      final before = repo.requests.length;
      await tester.tap(find.byKey(const ValueKey('checkoutCoinSwitch')));
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pump();
      expect(repo.requests.length, before, reason: '提交中改选择 = 提交用的是旧值');
      await tester.tap(find.byKey(const ValueKey('checkoutCourierRow')));
      await tester.pump(const Duration(milliseconds: 500)); // 提交中转圈，不能 settle
      expect(find.text('Pilih kurir'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('checkoutDiscountCouponRow')));
      await tester.pump(const Duration(milliseconds: 500)); // 提交中转圈，不能 settle
      expect(find.text('Kupon diskon belanja'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('checkoutAddressRowV3')));
      await tester.pump(const Duration(milliseconds: 500)); // 提交中转圈，不能 settle
      expect(find.text('Pilih alamat'), findsNothing);
      gate.complete(const ShopOrderRef(
          orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000));
      await tester.pumpAndSettle();
    });

    testWidgets('🔴 结果不明确的失败后，即便有新试算也保留同一幂等键', (tester) async {
      var n = 0;
      repo.onPlace = (r) async {
        n++;
        if (n == 1) {
          throw const CheckoutFailure(kind: CheckoutFailureKind.generic, outcomeUnknown: true);
        }
        return const ShopOrderRef(
            orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      };
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      await flushToast(tester);
      // 用户改了一下选择 → 新试算成功到达
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(repo.keys.length, 2);
      expect(repo.keys[1], repo.keys[0], reason: '单可能已建：换键 = 重复下单 / 重复扣币');
    });

    testWidgets('明确业务错误后，新试算作废旧键', (tester) async {
      var n = 0;
      repo.onPlace = (r) async {
        n++;
        if (n == 1) throw const CheckoutFailure(kind: CheckoutFailureKind.notPlaceable);
        return const ShopOrderRef(
            orderToken: 'ord1', status: 'PENDING_PAYMENT', totalAmount: 329000);
      };
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pump();
      await flushToast(tester);
      await tester.tap(find.byKey(const ValueKey('checkoutInsuranceSwitch')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('checkoutSubmitV3')));
      await tester.pumpAndSettle();
      expect(repo.keys[1], isNot(repo.keys[0]));
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 正常', (tester) async {
      await pumpPage(tester);
      expect(tester.takeException(), isNull);
    });

    testWidgets('411dp · 超范围', (tester) async {
      repo = _FakeCheckoutRepo(
          (req, n) async => CheckoutPreview.fromJson(pv(quoted: false, block: 'NOT_SERVICEABLE')));
      await pumpPage(tester);
      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号', (tester) async {
      await pumpPage(tester, textScale: 1.3);
      expect(tester.takeException(), isNull);
    });
  });
}

// ================================================================ fixtures

ShippingAddress _addr(String token, {bool isDefault = false, bool? serviceable}) => ShippingAddress(
      token: token,
      receiverName: 'Budi',
      receiverPhone: '08123456789',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: 'Jakarta Selatan',
      kecamatan: 'Kebayoran',
      addressLine: 'Jl. Test No. 1',
      kodePos: '12160',
      label: 'Rumah',
      isDefault: isDefault,
      serviceable: serviceable,
    );

Map<String, dynamic> _line(String sku, {int qty = 1, String? unavailable, int? stock}) => {
      'skuToken': sku,
      'productToken': 'p-$sku',
      'productName': 'Produk $sku',
      'specName': '3 kg',
      'price': 349000,
      'qty': qty,
      'returnPolicy': 'NO_RETURN_AFTER_OPEN',
      'unavailableReason': ?unavailable,
      'availableStock': ?stock,
    };

const _unset = Object();

/// 后端 `CheckoutQuoteView` 形状的 JSON（默认 = UI D1 示例：349.000 − 20.000 + 10.000 − 10.000 = 329.000；
/// 币 12.000 + QRIS 317.000）。
Map<String, dynamic> pv({
  bool address = true,
  String addrToken = 'addr1',
  String? block,
  bool quoted = true,
  String? selected = 'jne:reg',
  bool insuranceAvailable = true,
  bool insurance = false,
  int goods = 349000,
  int disc = 20000,
  int ship = 10000,
  int shipDisc = 10000,
  String shipSrc = 'COUPON',
  int insFee = 0,
  int payable = 329000,
  int coin = 12000,
  int cash = 317000,
  int saved = 30000,
  Map<String, dynamic>? progress,
  Map<String, dynamic>? discount,
  Object? fsSelected = _unset,
  int balance = 12000,
  int cap = 1000000,
  bool useCoin = true,
  bool capReached = false,
  List<Map<String, dynamic>>? lines,
}) {
  final known = quoted && block != 'NOT_SERVICEABLE' && block != 'QUOTE_FAILED' && address;
  return {
    'source': 'CART',
    if (address)
      'address': {
        'token': addrToken,
        'receiverName': 'Budi',
        'receiverPhone': '08123456789',
        'provinsi': 'DKI Jakarta',
        'kotaKabupaten': 'Jakarta Selatan',
        'kecamatan': 'Kebayoran',
        'addressLine': 'Jl. Test No. 1',
        'kodePos': '12160',
        'isDefault': true,
      },
    if (known) 'quoteToken': 'q1',
    'couriers': known
        ? [
            {
              'courierKey': 'jne:reg',
              'courierCode': 'JNE',
              'serviceCode': 'REG',
              'courierName': 'JNE',
              'serviceName': 'Reguler',
              'etaText': '2 - 3',
              'price': 10000,
              'insuranceSupported': true,
              'cheapest': true,
            },
            {
              'courierKey': 'sicepat:best',
              'courierCode': 'sicepat',
              'serviceCode': 'best',
              'courierName': 'SiCepat',
              'serviceName': 'BEST',
              'etaText': '1 - 2',
              'price': 24000,
              'insuranceSupported': false,
              'cheapest': false,
            },
          ]
        : [],
    if (known && selected != null) 'selectedCourierKey': selected,
    'insuranceAvailable': known && insuranceAvailable,
    'insurance': known && insurance,
    'lines': lines ?? [_line('s1')],
    'pricing': {
      'goodsAmount': goods,
      'discountCouponAmount': disc,
      if (known) 'shippingFee': ship,
      if (known) 'shippingDiscount': shipDisc,
      'shippingDiscountSource': known ? shipSrc : 'NONE',
      'insuranceFee': insFee,
      'payable': payable,
      'coinAmount': coin,
      'cashAmount': cash,
      'savedAmount': saved,
      'shippingIncluded': known,
      'freeShippingProgress': ?progress,
    },
    'coupons': {
      'discount': discount ??
          {
            'selectedToken': 'ucD1',
            'available': [
              {
                'userCouponToken': 'ucD1',
                'couponToken': 'cD1',
                'kind': 'DISCOUNT',
                'nameId': 'Diskon 20rb',
                'nameEn': '20k off',
                'amount': 20000,
                'minSpend': 300000,
                'validUntil': '2026-11-30T16:59:59Z',
                'expiringSoon': true,
                'deduction': 20000,
              },
            ],
            'unavailable': [
              {
                'userCouponToken': 'ucD2',
                'couponToken': 'cD2',
                'kind': 'DISCOUNT',
                'amount': 50000,
                'minSpend': 500000,
                'expiringSoon': false,
                'deduction': 0,
                'unavailableReason': 'BELOW_MIN_SPEND',
              },
              {
                'userCouponToken': 'ucD3',
                'couponToken': 'cD3',
                'kind': 'DISCOUNT',
                'amount': 10000,
                'expiringSoon': false,
                'deduction': 0,
                'unavailableReason': 'OUT_OF_SCOPE',
              },
            ],
          },
      'freeShipping': {
        'selectedToken': ?(identical(fsSelected, _unset) ? 'ucF1' : fsSelected as String?),
        'available': [
          {
            'userCouponToken': 'ucF1',
            'couponToken': 'cF1',
            'kind': 'FREE_SHIPPING',
            'nameId': 'Gratis ongkir',
            'amount': 15000,
            'expiringSoon': false,
            'deduction': 10000,
          },
        ],
        'unavailable': [
          {
            'userCouponToken': 'ucF2',
            'couponToken': 'cF2',
            'kind': 'FREE_SHIPPING',
            'amount': 15000,
            'expiringSoon': false,
            'deduction': 0,
            'unavailableReason': 'OUT_OF_SCOPE',
          },
          {
            'userCouponToken': 'ucF3',
            'couponToken': 'cF3',
            'kind': 'FREE_SHIPPING',
            'amount': 15000,
            'expiringSoon': false,
            'deduction': 0,
            'unavailableReason': 'RULE_BETTER',
          },
        ],
      },
    },
    'coin': {
      'balance': balance,
      'cap': cap,
      'enabled': true,
      'useCoin': useCoin && balance > 0,
      'capReached': capReached,
    },
    'strictestReturnPolicy': 'NO_RETURN_AFTER_OPEN',
    'returnPolicyCopyKey': 'NO_RETURN_AFTER_OPEN',
    'placeable': block == null && known,
    'notPlaceableReason': ?block,
  };
}

class _FakeCheckoutRepo implements CheckoutRepository {
  _FakeCheckoutRepo(this.respond);

  final Future<CheckoutPreview> Function(CheckoutRequest req, int n) respond;
  final requests = <CheckoutRequest>[];
  final placed = <PlaceOrderRequest>[];
  final keys = <String>[];
  Future<ShopOrderRef> Function(PlaceOrderRequest r)? onPlace;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CheckoutPreview> preview(CheckoutRequest request) {
    requests.add(request);
    return respond(request, requests.length);
  }

  @override
  Future<ShopOrderRef> placeOrder(PlaceOrderRequest request, {required String idempotencyKey}) {
    placed.add(request);
    keys.add(idempotencyKey);
    return onPlace!(request);
  }
}

/// 🔴 BUY_NOW 断言「不调 cartProvider 任何变更方法」靠 [mutations] / [refreshes] 计数。
class _FakeCart extends CartController {
  final removed = <String>[];
  int mutations = 0;
  int refreshes = 0;
  VoidCallback? onRemove;

  @override
  Future<CartView> build() async => CartView.empty;

  @override
  Future<void> refresh() async => refreshes++;

  @override
  Future<void> remove(String skuToken) async {
    mutations++;
    removed.add(skuToken);
    onRemove?.call();
  }

  @override
  Future<void> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false}) async =>
      mutations++;

  @override
  Future<void> setQty(String skuToken, int qty) async => mutations++;

  @override
  Future<void> setSelected(String skuToken, bool selected) async => mutations++;

  @override
  Future<void> setAllSelected(bool selected) async => mutations++;

  @override
  Future<void> clearInvalid() async => mutations++;
}

class _FakeAddressRepo extends AddressRepository {
  _FakeAddressRepo() : super(dio: Dio());

  int created = 0;
  int setDefaultCalls = 0;

  @override
  Future<ShippingAddress> create(ShippingAddress a) async {
    created++;
    return _addr('addr-new', isDefault: true);
  }

  @override
  Future<ShippingAddress> setDefault(String token) async {
    setDefaultCalls++;
    return _addr(token, isDefault: true);
  }
}
