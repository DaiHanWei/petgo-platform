import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shipping_estimate_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repurchase_repository.dart';
import 'package:tailtopia/features/shop/domain/free_shipping_progress.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_repurchase.dart';
import 'package:tailtopia/features/shop/presentation/cart_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'fake_shop_products.dart';
import 'pdp_fakes.dart';

/// 购物车 v3（v1.4.2 shop Story 8.3 · UI C1–C8；取代 `cart_page_v2_test.dart`，用例迁入本文件）。
///
/// 本类守的是**会造成资损或误导**的几件事：
/// - 底栏金额 / 已省 / 免运差额**原样**来自服务端 `estimate`（P-1），App 不加不减；
/// - 失效行不进合计、勾选框**恒未勾且不可点**（PRD 11-05，取代 2026-09-18 的「如实反映 + 可点」）；
/// - 取消勾选不得走 `remove`；库存不明时不得加；单店模型不长出店铺分组；
/// - R-3 删除可撤销。
void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<_FakeCartController> pump(
    WidgetTester tester,
    CartView cart, {
    bool loggedIn = true,
    List<ShippingAddress> addresses = const [],
    ShopDeliveryArea? area,
    Recommendations? reco,
    List<ShopProductSummary> top = const [],
    CartEstimate? nextEstimate,
    Future<List<ShippingAddress>> Function()? addressLoader,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) async {
    final ctrl = _FakeCartController(cart)..nextEstimate = nextEstimate;
    Widget stub(String label) => Builder(
          builder: (c) => Scaffold(
            body: TextButton(
                key: const ValueKey('stubBack'),
                onPressed: () => c.pop(),
                child: Text(label)),
          ),
        );
    final router = GoRouter(initialLocation: '/shop/cart', routes: [
      GoRoute(path: '/shop/cart', builder: (c, s) => const CartPageV3()),
      GoRoute(path: '/shop/checkout', builder: (c, s) => stub('STUB_CHECKOUT')),
      GoRoute(path: '/me/addresses', builder: (c, s) => stub('STUB_ADDRESS_BOOK')),
      GoRoute(path: '/me/addresses/new', builder: (c, s) => stub('STUB_ADDRESS_NEW')),
      GoRoute(path: '/shop', builder: (c, s) => stub('STUB_SHOP')),
      GoRoute(path: '/login', builder: (c, s) => stub('STUB_LOGIN')),
    ]);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(loggedIn
            ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
            : const AuthState(status: AuthStatus.guest))),
        cartProvider.overrideWith(() => ctrl),
        addressListProvider.overrideWith((ref) {
          ctrl.addressLoads++;
          return addressLoader != null ? addressLoader() : Future.value(addresses);
        }),
        shopDeliveryAreaProvider.overrideWith(() => FakeDeliveryAreaController(area, [])),
        recommendationsProvider.overrideWith((ref) async =>
            reco ?? const Recommendations(degraded: true, missing: 'PROFILE', items: [])),
        ...tokoHomeOverrides(top: top),
      ],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        routerConfig: router,
        builder: (c, child) => MediaQuery(
          data: MediaQuery.of(c).copyWith(size: size, textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return ctrl;
  }

  CartLine line(
    String token, {
    String name = 'Royal Canin Adult Dog',
    String spec = '3 kg',
    int price = 185000,
    int qty = 1,
    int? stock = 10,
    CartInvalidReason? invalid,
    bool selected = true,
  }) =>
      CartLine(
        skuToken: token,
        productName: name,
        specName: spec,
        price: price,
        qty: qty,
        availableStock: stock,
        invalidReason: invalid,
        selected: selected,
      );

  /// ⚠️ 刻意**不**让 selectedSubtotal 等于 subtotal —— 两者相等只是「全选」特例。
  CartView cartOf({
    List<CartLine> valid = const [],
    List<CartLine> invalid = const [],
    CartEstimate? estimate,
  }) {
    final picked = valid.where((l) => l.selected);
    return CartView(
      lines: valid,
      invalidLines: invalid,
      subtotal: valid.fold(0, (a, l) => a + l.lineTotal),
      itemCount: valid.fold(0, (a, l) => a + l.qty),
      selectedSubtotal: picked.fold(0, (a, l) => a + l.lineTotal),
      selectedCount: picked.fold(0, (a, l) => a + l.qty),
      estimate: estimate,
    );
  }

  /// UI C1 假数据：商品 349.000 + 最低运费 10.000 − 满减 20.000 − 免运券 10.000 = 329.000。
  const c1Estimate = CartEstimate(
    lowestFee: 10000,
    payable: 329000,
    saved: 30000,
    freeShippingProgress: FreeShippingProgress(threshold: 500000, remaining: 171000),
  );
  // C1：1 + 2 件 = 3 件（349.000）。
  List<CartLine> c1Lines() => [
        line('a', name: 'Royal Canin Mini', price: 189000, qty: 1),
        line('b', name: 'Whiskas Pouch', price: 80000, qty: 2),
      ];

  String? textOf(WidgetTester tester, String key) =>
      tester.widget<Text>(find.byKey(ValueKey(key))).data;
  String? totalOf(WidgetTester tester) => textOf(tester, 'cartPayableV3');

  group('AC1 页面结构', () {
    testWidgets('进入上报 toko_cart_page_viewed', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate));
      expect(events.where((e) => e.$1 == 'toko_cart_page_viewed'), hasLength(1));
    });

    testWidgets('🔴 单店模型：多行只有一个总计，有效行在同一张卡片内', (tester) async {
      await pump(tester, cartOf(valid: [line('a', qty: 2), line('b', price: 78000)]));
      expect(find.byKey(const ValueKey('cartPayableV3')), findsOneWidget);
      expect(totalOf(tester), 'Rp 448.000');
      expect(find.byKey(const ValueKey('cartValidCard')), findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('cartValidCard')),
              matching: find.byType(ShopStepper)),
          findsNWidgets(2));
    });

    testWidgets('标题计数含失效商品', (tester) async {
      await pump(tester, cartOf(
        valid: [line('a'), line('b')],
        invalid: [line('x', invalid: CartInvalidReason.outOfStock)],
      ));
      expect(find.text('Keranjang (3)'), findsOneWidget,
          reason: '不算失效项会让用户以为自己漏加了');
    });
  });

  group('AC2 C1 有默认地址：送达行、免运进度、预估应付', () {
    testWidgets('🔴 底栏原样显示 estimate：329.000 / Hemat 30.000 / 差 171.000 / Checkout (3)',
        (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          addresses: [fakeAddress(kecamatan: 'Kebayoran Baru')]);

      expect(totalOf(tester), 'Rp 329.000',
          reason: 'App 不做「商品 + 运费 − 券」的任何计算 —— selectedSubtotal 是 349.000');
      expect(textOf(tester, 'cartTotalLabelV3'), 'Estimasi total');
      expect(textOf(tester, 'cartSavedV3'), 'Hemat Rp 30.000');
      expect(textOf(tester, 'cartFreeShipProgress'),
          'Belanja Rp 171.000 lagi untuk potongan ongkir');
      expect(find.text('Checkout (3)'), findsOneWidget);
      final amount = tester.widget<Text>(find.byKey(const ValueKey('cartPayableV3')));
      expect(amount.style?.color, ShopColors.purple);
    });

    testWidgets('送达行：默认地址的 kecamatan', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          addresses: [
            fakeAddress(token: 'a0', kecamatan: 'Tebet', isDefault: false),
            fakeAddress(kecamatan: 'Kebayoran Baru'),
          ],
          area: const ShopDeliveryArea(
              kecamatanId: 9, kecamatan: 'Cilandak', kota: 'Jaksel', provinsi: 'DKI'));
      expect(textOf(tester, 'cartShipToText'), 'Dikirim ke Kec. Kebayoran Baru',
          reason: '有默认地址时服务端按默认地址估算，送达行必须同源');
    });

    testWidgets('送达行：无默认地址时取本机记住的地区', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          area: const ShopDeliveryArea(
              kecamatanId: 9, kecamatan: 'Cilandak', kota: 'Jaksel', provinsi: 'DKI'));
      expect(textOf(tester, 'cartShipToText'), 'Dikirim ke Kec. Cilandak');
    });

    testWidgets('点送达行（有地址）→ 地址簿；返回后刷新地址并重估购物车', (tester) async {
      final ctrl = await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          addresses: [fakeAddress()]);
      final loadsBefore = ctrl.addressLoads;
      await tester.tap(find.byKey(const ValueKey('cartShipToRow')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_ADDRESS_BOOK'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('stubBack')));
      await tester.pumpAndSettle();
      expect(ctrl.refreshes, 1, reason: '换了默认地址要按新地址重估');
      expect(ctrl.addressLoads, greaterThan(loadsBefore));
    });

    testWidgets('复审修复：地址加载中 → 文案「—」（不先闪本机地区），点击进地址簿而非新增', (tester) async {
      final never = Completer<List<ShippingAddress>>();
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          addressLoader: () => never.future,
          area: const ShopDeliveryArea(
              kecamatanId: 9, kecamatan: 'Cilandak', kota: 'Jaksel', provinsi: 'DKI'));
      expect(textOf(tester, 'cartShipToText'), '—');
      await tester.tap(find.byKey(const ValueKey('cartShipToRow')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_ADDRESS_BOOK'), findsOneWidget,
          reason: '还不知道有没有地址 —— 进新增会造出第二个地址');
    });

    testWidgets('复审修复：地址加载出错 → 点击进地址簿而非新增', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate),
          addressLoader: () => Future.error(Exception('net')));
      expect(textOf(tester, 'cartShipToText'), isNot('—'));
      await tester.tap(find.byKey(const ValueKey('cartShipToRow')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_ADDRESS_BOOK'), findsOneWidget);
    });

    testWidgets('点送达行（无地址）→ 新增地址', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate));
      await tester.tap(find.byKey(const ValueKey('cartShipToRow')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_ADDRESS_NEW'), findsOneWidget);
    });

    testWidgets('免运进度：remaining == 0 →「Dapat potongan ongkir」', (tester) async {
      await pump(
          tester,
          cartOf(
              valid: c1Lines(),
              estimate: const CartEstimate(
                  lowestFee: 10000,
                  payable: 339000,
                  saved: 10000,
                  freeShippingProgress: FreeShippingProgress(threshold: 300000, remaining: 0))),
          addresses: [fakeAddress()]);
      expect(textOf(tester, 'cartFreeShipProgress'), 'Dapat potongan ongkir');
    });

    testWidgets('免运进度：门槛 0 / 未配置 → 不画', (tester) async {
      await pump(
          tester,
          cartOf(
              valid: c1Lines(),
              estimate: const CartEstimate(
                  lowestFee: 10000,
                  payable: 359000,
                  saved: 0,
                  freeShippingProgress: FreeShippingProgress(threshold: 0, remaining: 0))),
          addresses: [fakeAddress()]);
      expect(find.byKey(const ValueKey('cartFreeShipProgress')), findsNothing);
      expect(find.byKey(const ValueKey('cartSavedV3')), findsNothing, reason: 'saved == 0 不画');
    });
  });

  group('AC3 C2 无地址或询价失败', () {
    testWidgets('lowestFee == null →「Pilih alamat…」+「Belum termasuk ongkir」，Hemat 与免运照常',
        (tester) async {
      await pump(
          tester,
          cartOf(
              valid: c1Lines(),
              estimate: const CartEstimate(
                  payable: 319000,
                  saved: 20000,
                  reason: CartEstimate.reasonNoDestination,
                  freeShippingProgress: FreeShippingProgress(threshold: 500000, remaining: 171000))));
      expect(textOf(tester, 'cartShipToText'), 'Pilih alamat untuk hitung ongkir');
      expect(textOf(tester, 'cartTotalLabelV3'), 'Belum termasuk ongkir');
      expect(totalOf(tester), 'Rp 319.000', reason: '金额原样取 payable（不含运费）');
      expect(textOf(tester, 'cartSavedV3'), 'Hemat Rp 20.000');
      expect(find.byKey(const ValueKey('cartFreeShipProgress')), findsOneWidget);
    });

    testWidgets('有默认地址但询价失败 → 送达行同样提示选地址', (tester) async {
      await pump(
          tester,
          cartOf(
              valid: c1Lines(),
              estimate: const CartEstimate(
                  payable: 329000, saved: 20000, reason: CartEstimate.reasonQuoteFailed)),
          addresses: [fakeAddress()]);
      expect(textOf(tester, 'cartShipToText'), 'Pilih alamat untuk hitung ongkir');
      expect(textOf(tester, 'cartTotalLabelV3'), 'Belum termasuk ongkir');
    });

    testWidgets('旧后端（estimate == null）→ 回落 selectedSubtotal，标「Belum termasuk ongkir」',
        (tester) async {
      await pump(tester, cartOf(valid: c1Lines()));
      expect(totalOf(tester), 'Rp 349.000');
      expect(textOf(tester, 'cartTotalLabelV3'), 'Belum termasuk ongkir');
      expect(find.byKey(const ValueKey('cartSavedV3')), findsNothing);
      expect(find.byKey(const ValueKey('cartFreeShipProgress')), findsNothing);
    });
  });

  group('AC4 C3 失效商品', () {
    testWidgets('失效行渲染在独立分组里，且合计不含它', (tester) async {
      await pump(tester, cartOf(
        valid: [line('a', qty: 1)],
        invalid: [line('x', name: 'Vitamin', price: 95000, invalid: CartInvalidReason.outOfStock)],
      ));
      expect(find.text('Vitamin'), findsOneWidget, reason: '悄悄删掉失效行会让用户以为自己记错了');
      expect(find.textContaining('Tidak Tersedia'), findsOneWidget);
      expect(totalOf(tester), 'Rp 185.000', reason: '把卖不了的东西放进合计 = 用户付了钱才发现');
    });

    testWidgets('🔴 失效行的主出口是「找相似」（go /shop）而不是只有删除', (tester) async {
      await pump(tester, cartOf(invalid: [line('x', invalid: CartInvalidReason.delisted)]));
      expect(find.byKey(const ValueKey('cartRemoveInvalid_x')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('cartFindSimilar_x')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_SHOP'), findsOneWidget);
    });

    testWidgets('「Hapus」走 remove', (tester) async {
      final ctrl = await pump(tester,
          cartOf(valid: [line('a')], invalid: [line('x', invalid: CartInvalidReason.delisted)]));
      await tester.tap(find.byKey(const ValueKey('cartRemoveInvalid_x')));
      await tester.pumpAndSettle();
      expect(ctrl.removed, ['x']);
    });

    testWidgets('复审修复：「Hapus」防重入 —— 请求进行中双击只发一次 DELETE', (tester) async {
      final ctrl = await pump(tester,
          cartOf(valid: [line('a')], invalid: [line('x', invalid: CartInvalidReason.delisted)]));
      final gate = Completer<void>();
      ctrl.removeGate = gate;
      await tester.tap(find.byKey(const ValueKey('cartRemoveInvalid_x')));
      await tester.pump();
      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('cartRemoveInvalid_x')));
      expect(btn.loading, isTrue);
      expect(btn.onTap, isNull);
      await tester.tap(find.byKey(const ValueKey('cartRemoveInvalid_x')), warnIfMissed: false);
      await tester.pump();
      expect(ctrl.removed, ['x']);
      gate.complete();
      await tester.pumpAndSettle();
      expect(ctrl.removed, ['x']);
    });

    testWidgets('失效原因是图片底部的小标签（Stok habis / Sudah ditarik / Tidak tersedia）',
        (tester) async {
      await pump(tester, cartOf(invalid: [
        line('x', invalid: CartInvalidReason.outOfStock),
        line('y', invalid: CartInvalidReason.delisted),
        line('z', invalid: CartInvalidReason.unavailable),
      ]));
      Finder tag(String t) => find.byKey(ValueKey('cartInvalidReasonTag_$t'));
      expect(find.descendant(of: tag('x'), matching: find.text('Stok habis')), findsOneWidget);
      expect(find.descendant(of: tag('y'), matching: find.text('Sudah ditarik')), findsOneWidget);
      expect(find.descendant(of: tag('z'), matching: find.text('Tidak tersedia')), findsOneWidget);
    });

    testWidgets('失效行价格转灰，不用玫红', (tester) async {
      await pump(tester,
          cartOf(invalid: [line('x', price: 95000, invalid: CartInvalidReason.outOfStock)]));
      final price = tester.widget<Text>(find.text('Rp 95.000'));
      expect(price.style?.color, ShopColors.text4);
      expect(price.style?.color, isNot(ShopColors.accent));
    });

    testWidgets('认不出的失效原因照样算失效（不当成有效行）', (tester) async {
      await pump(tester, cartOf(
        valid: [line('a')],
        invalid: [line('x', price: 95000, invalid: CartInvalidReason.unavailable)],
      ));
      expect(find.textContaining('Tidak Tersedia'), findsOneWidget);
      expect(totalOf(tester), 'Rp 185.000');
    });

    /// 🔴 PRD 11-05 改线上：取代 v2 的「③ 如实反映 + 可点」「③b 取消勾选失效行」
    /// 「失效行取消勾选后还能再勾回来」三条。后端 3.1 起失效行对外 selected 恒 false、勾选 422。
    testWidgets('🔴 失效行勾选框占位、恒未勾、点击不调 setSelected、不进合计', (tester) async {
      final ctrl = await pump(
          tester,
          cartOf(
            valid: [line('a', price: 100000)],
            // 即便服务端（旧数据）给了 true，界面也恒未勾：失效行不参与结算。
            invalid: [line('x', price: 95000, invalid: CartInvalidReason.outOfStock, selected: true)],
          ));
      // 全选 + 有效行 + 失效行 = 三个（失效行要占位，左边缘不参差）。
      expect(find.byType(ShopCheckbox), findsNWidgets(3));
      final box = tester.widget<ShopCheckbox>(
          find.byKey(const ValueKey('cartInvalidLineCheckbox_x')));
      expect(box.value, isFalse);
      expect(box.onChanged, isNull);
      expect(box.enabled, isFalse);

      await tester.tap(find.byKey(const ValueKey('cartInvalidLineCheckbox_x')),
          warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(ctrl.selectedCalls, isEmpty, reason: '勾选失效行服务端 422 —— 不发请求');
      expect(ctrl.removed, isEmpty);
      expect(totalOf(tester), 'Rp 100.000');
    });

    testWidgets('「Hapus semua」二次确认后才清，并上报 toko_cart_clear_invalid_tapped',
        (tester) async {
      final ctrl = await pump(tester,
          cartOf(valid: [line('a')], invalid: [line('x', invalid: CartInvalidReason.delisted)]));
      await tester.tap(find.byKey(const ValueKey('cartClearInvalidV3')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('cartClearInvalidConfirmV3')), findsOneWidget);
      expect(ctrl.clearInvalidCalls, 0, reason: '批量删除不可撤销，先确认');

      await tester.tap(find.byKey(const ValueKey('cartClearInvalidConfirmYesV3')));
      await tester.pumpAndSettle();
      expect(ctrl.clearInvalidCalls, 1);
      expect(events.where((e) => e.$1 == 'toko_cart_clear_invalid_tapped'), hasLength(1));
    });

    testWidgets('只有失效行时不算空、也不渲染结算条', (tester) async {
      await pump(tester, cartOf(invalid: [line('x', invalid: CartInvalidReason.outOfStock)]));
      expect(find.textContaining('Tidak Tersedia'), findsOneWidget);
      expect(find.byKey(const ValueKey('cartCheckoutV3')), findsNothing,
          reason: '一车都是买不了的东西，结算按钮点下去必然失败');
    });
  });

  group('库存：不明时不得加', () {
    testWidgets('availableStock 为 null → + 不可点', (tester) async {
      await pump(tester, cartOf(valid: [line('a', qty: 2, stock: null)]));
      final stepper = tester.widget<ShopStepper>(find.byType(ShopStepper));
      expect(stepper.max, 2, reason: '库存不明时上限=当前数量 → 加不了');
    });

    testWidgets('触顶时出现剩余库存提示', (tester) async {
      await pump(tester, cartOf(valid: [line('a', qty: 3, stock: 3)]));
      expect(find.byKey(const ValueKey('cartStockTag')), findsOneWidget);
      expect(find.text('Sisa stok 3'), findsOneWidget);
    });

    testWidgets('未触顶时不显示库存提示', (tester) async {
      await pump(tester, cartOf(valid: [line('a', qty: 1, stock: 10)]));
      expect(find.byKey(const ValueKey('cartStockTag')), findsNothing);
    });
  });

  group('行选择', () {
    testWidgets('🔴 点掉一行：setSelected 调一次、remove 零次，底栏按服务端返回刷新', (tester) async {
      final ctrl = await pump(
          tester, cartOf(valid: [line('a', price: 100000, qty: 2), line('b', price: 50000)]));
      expect(totalOf(tester), 'Rp 250.000');
      expect(find.text('Checkout (3)'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('cartLineCheckbox_b')));
      await tester.pumpAndSettle();

      expect(totalOf(tester), 'Rp 200.000');
      expect(find.text('Checkout (2)'), findsOneWidget);
      expect(ctrl.selectedCalls, [(sku: 'b', selected: false)]);
      expect(ctrl.removed, isEmpty,
          reason: '用 DELETE 模拟「不买这件」= 用破坏性操作实现查询语义，取消结算就丢数据');
    });

    testWidgets('🔴 车里有失效行时，全选框仍呈现「已全选」', (tester) async {
      await pump(tester, cartOf(
        valid: [line('a'), line('b')],
        invalid: [line('x', invalid: CartInvalidReason.delisted)],
      ));
      final selectAll =
          tester.widget<ShopCheckbox>(find.byKey(const ValueKey('cartSelectAllV3')));
      expect(selectAll.value, isTrue);
    });

    testWidgets('全选框点击走 setAllSelected，不是逐行 setSelected', (tester) async {
      final ctrl = await pump(tester, cartOf(valid: [line('a'), line('b')]));
      await tester.tap(find.byKey(const ValueKey('cartSelectAllV3')));
      await tester.pumpAndSettle();
      expect(ctrl.selectAllCalls, [false], reason: '当前已全选，点一下是全不选');
      expect(ctrl.selectedCalls, isEmpty);
    });
  });

  group('AC5 C4 未勾选', () {
    testWidgets('🔴 一件没勾：「Pilih produk dulu」、Rp 0 变灰、Checkout 禁用不带件数、无进度无 Hemat，点击不发请求',
        (tester) async {
      final ctrl = await pump(
          tester,
          cartOf(
            valid: [line('a', price: 100000, selected: false), line('b', price: 50000, selected: false)],
            estimate: const CartEstimate(
                payable: 0, saved: 0, reason: CartEstimate.reasonNoSelection,
                freeShippingProgress: FreeShippingProgress(threshold: 500000, remaining: 500000)),
          ),
          addresses: [fakeAddress()]);

      expect(textOf(tester, 'cartTotalLabelV3'), 'Pilih produk dulu');
      expect(totalOf(tester), 'Rp 0');
      final amount = tester.widget<Text>(find.byKey(const ValueKey('cartPayableV3')));
      expect(amount.style?.color, ShopColors.text4);
      final btn = tester.widget<ShopButton>(find.byKey(const ValueKey('cartCheckoutV3')));
      expect(btn.onTap, isNull);
      expect(btn.variant, ShopButtonVariant.disabled, reason: '禁用态必须看得出来');
      expect(btn.label, 'Checkout');
      expect(find.byKey(const ValueKey('cartFreeShipProgress')), findsNothing);
      expect(find.byKey(const ValueKey('cartSavedV3')), findsNothing);
      // 一件没选不是「没地址」：送达行仍显示默认地址。
      expect(textOf(tester, 'cartShipToText'), 'Dikirim ke Kec. Kebayoran Baru');

      await tester.tap(find.byKey(const ValueKey('cartCheckoutV3')), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('STUB_CHECKOUT'), findsNothing);
      expect(ctrl.selectedCalls, isEmpty);
      expect(ctrl.removed, isEmpty);
    });
  });

  group('AC7 去结算', () {
    testWidgets('Checkout → push /shop/checkout（CART 模式，无参数）', (tester) async {
      await pump(tester, cartOf(valid: c1Lines(), estimate: c1Estimate));
      await tester.tap(find.byKey(const ValueKey('cartCheckoutV3')));
      await tester.pumpAndSettle();
      expect(find.text('STUB_CHECKOUT'), findsOneWidget);
      // 结算页从 /shop/checkout 无参数进入上报 entry=cart，由 checkout_page_v3_test
      //「无参数 → CART；上报 toko_checkout_page_viewed{entry: cart}」覆盖（7.1）。
    });
  });

  /// 🔴 R-3（2026-09-02 产品拍板）：删除必须可撤销（不弹确认）。
  group('🔴 AC5 C5：删除后可撤销', () {
    Future<_FakeCartController> pumpOneLine(WidgetTester tester, {bool addFails = false}) async {
      final ctrl = await pump(tester, cartOf(valid: [line('sku-a', qty: 1)]));
      ctrl.addFailsOnStock = addFails;
      return ctrl;
    }

    testWidgets('🔴 删除后弹出撤销条（带商品名）', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();

      expect(ctrl.removed, ['sku-a']);
      expect(find.byKey(const ValueKey('appToastAction')), findsOneWidget);
      expect(find.text('Urungkan'), findsOneWidget);
      expect(find.textContaining('dihapus'), findsOneWidget);
      await tester.pump(const Duration(seconds: 6));
    });

    testWidgets('🔴 点撤销 → 按删除时的数量加回来', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();

      expect(ctrl.added, hasLength(1));
      expect(ctrl.added.single.sku, 'sku-a');
      expect(ctrl.added.single.qty, 1);
      expect(find.byKey(const ValueKey('appToastAction')), findsNothing);
    });

    testWidgets('🔴 ocr M1：删除后立刻离开购物车，再点撤销 → 仍按原数量加回', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      GoRouter.of(tester.element(find.byType(CartPageV3))).go('/shop');
      await tester.pumpAndSettle();
      expect(find.byType(CartPageV3), findsNothing, reason: '购物车页已销毁');
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(ctrl.added, hasLength(1));
      expect(ctrl.added.single.sku, 'sku-a');
    });

    testWidgets('撤销带自述的归因值 CART_UNDO，而不是留 null', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();
      expect(ctrl.added.single.entrySource, 'CART_UNDO');
    });

    testWidgets('🔴 撤销条可点 —— 带动作这条不能穿透', (tester) async {
      final ctrl = await pumpOneLine(tester);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      expect(
          find.ancestor(
            of: find.byKey(const ValueKey('appToastAction')),
            matching: find.byType(IgnorePointer),
          ),
          findsNothing);
      await tester.tap(find.byKey(const ValueKey('appToastAction')));
      await tester.pumpAndSettle();
      expect(ctrl.added, hasLength(1));
    });

    testWidgets('🔴 撤销失败（货被买走）必须出声，不能静默', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await pumpOneLine(tester, addFails: true);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Urungkan'));
      await tester.pumpAndSettle();
      expect(find.text(l10n.cartStockError), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('qty=1 时行内出现删除图标，点击调用 remove()', (tester) async {
      final ctrl = await pumpOneLine(tester);
      expect(find.byIcon(Icons.delete_outline), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      await tester.pumpAndSettle();
      expect(ctrl.removed, ['sku-a']);
      await tester.pump(const Duration(seconds: 6));
    });

    testWidgets('🔴 UI C5：删除后标题 / 底栏 / 免运差额按服务端新 estimate 同步', (tester) async {
      final ctrl = await pump(
          tester,
          cartOf(valid: [
            line('a', name: 'Royal Canin Mini', price: 285000, qty: 1),
            line('b', name: 'Whiskas Pouch', price: 64000, qty: 1),
          ], estimate: c1Estimate),
          addresses: [fakeAddress()],
          // 285.000 + 运费 10.000 − 免运券 10.000 = 285.000；满减门槛 300.000 不再满足，差 215.000。
          nextEstimate: const CartEstimate(
              lowestFee: 10000,
              payable: 285000,
              saved: 10000,
              freeShippingProgress: FreeShippingProgress(threshold: 500000, remaining: 215000)));
      expect(find.text('Keranjang (2)'), findsOneWidget);
      expect(find.text('Checkout (2)'), findsOneWidget);

      await tester.tap(find.descendant(
          of: find.byKey(const ValueKey('b')), matching: find.byKey(const ValueKey('stepperDec'))));
      await tester.pumpAndSettle();

      expect(ctrl.removed, ['b']);
      expect(find.text('Keranjang (1)'), findsOneWidget);
      expect(find.text('Checkout (1)'), findsOneWidget);
      expect(find.byKey(const ValueKey('cartPayableV3')), findsOneWidget);
      expect(totalOf(tester), 'Rp 285.000');
      expect(textOf(tester, 'cartSavedV3'), 'Hemat Rp 10.000');
      expect(textOf(tester, 'cartFreeShipProgress'),
          'Belanja Rp 215.000 lagi untuk potongan ongkir');
      await tester.pump(const Duration(seconds: 6));
    });
  });

  group('AC6 C6 空购物车 / C7 加载失败 / C8 未登录', () {
    testWidgets('C6 空车：原空态文案 +「Mulai belanja」go /shop，无结算条', (tester) async {
      await pump(tester, cartOf());
      expect(find.text('Keranjang masih kosong'), findsOneWidget);
      expect(find.byKey(const ValueKey('cartCheckoutV3')), findsNothing);
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.tap(find.text(l10n.cartEmptyCta));
      await tester.pumpAndSettle();
      expect(find.text('STUB_SHOP'), findsOneWidget);
    });

    testWidgets('C6 无档案 →「Mungkin kamu suka」+ top 6', (tester) async {
      await pump(tester, cartOf(),
          top: [
            for (var i = 0; i < 6; i++)
              ShopProductSummary(
                  token: 'p$i', name: 'Produk $i', brand: 'B', category: ShopCategory.makanan,
                  minPrice: 10000),
          ]);
      expect(find.byKey(const ValueKey('shopRecoGridTop')), findsOneWidget);
      expect(find.text('Mungkin kamu suka'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopProductCardAdd_p0')), findsOneWidget,
          reason: '卡片同首页，可「＋」直接加购');
    });

    testWidgets('C6 有档案 → 为宠物推荐（栏头同首页）', (tester) async {
      await pump(tester, cartOf(),
          reco: const Recommendations(
            degraded: false,
            profileSummary: ProfileSummary(petName: 'Mochi', species: 'DOG'),
            items: [
              RecommendationItem(productToken: 'r1', name: 'Reco 1', minPrice: 50000, reason: 'x'),
            ],
          ));
      expect(find.byKey(const ValueKey('shopRecoGridProfile')), findsOneWidget);
      expect(find.byKey(const ValueKey('shopRecoHeader')), findsOneWidget);
    });

    testWidgets('C6 推荐加购成功后从空态切到列表态', (tester) async {
      final ctrl = await pump(tester, cartOf(), top: [
        const ShopProductSummary(
            token: 'p0', name: 'Produk 0', brand: 'B', category: ShopCategory.makanan,
            minPrice: 10000),
      ]);
      expect(find.byKey(const ValueKey('cartEmptyV3')), findsOneWidget);
      // 卡片「＋」→ 规格弹层 → cartProvider.add（8.1 已测）；这里模拟其结果：车里多了一行。
      ctrl.push(cartOf(valid: [line('p0-sku', name: 'Produk 0', price: 10000)]));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('cartEmptyV3')), findsNothing);
      expect(find.byKey(const ValueKey('cartValidCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('cartCheckoutV3')), findsOneWidget);
    });

    testWidgets('C7 加载失败：cartLoadFailed + 重试', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final ctrl = await pump(tester, cartOf());
      ctrl.fail();
      await tester.pumpAndSettle();
      expect(find.text(l10n.cartLoadFailed), findsOneWidget);
      expect(find.text(l10n.commonRetry), findsOneWidget);
    });

    testWidgets('C8 游客：软性引导、「Masuk」进登录、不请求地址', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      final ctrl = await pump(tester, CartView.empty, loggedIn: false);
      expect(find.text(l10n.cartGuestTitle), findsOneWidget);
      expect(ctrl.addressLoads, 0, reason: '游客不发任何 /me 请求');
      await tester.tap(find.text(l10n.cartGuestCta));
      await tester.pumpAndSettle();
      expect(find.text('STUB_LOGIN'), findsOneWidget);
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 标准字号', (tester) async {
      await pump(
          tester,
          cartOf(
            valid: [line('a', qty: 2), line('b', name: 'Whiskas Adult Cat')],
            invalid: [line('x', invalid: CartInvalidReason.delisted)],
            estimate: c1Estimate,
          ),
          addresses: [fakeAddress()]);
      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号（NFR-13 上限）', (tester) async {
      await pump(
          tester,
          cartOf(
            valid: [line('a', qty: 3, stock: 3, name: 'Royal Canin Adult Dog Premium Nutrition')],
            invalid: [line('x', invalid: CartInvalidReason.delisted)],
            estimate: const CartEstimate(
                lowestFee: 10000,
                payable: 12345000,
                saved: 1234000,
                freeShippingProgress: FreeShippingProgress(threshold: 50000000, remaining: 37655000)),
          ),
          addresses: [fakeAddress(kecamatan: 'Kebayoran Baru Selatan Panjang Sekali')],
          textScale: 1.3);
      expect(tester.takeException(), isNull);
    });
  });
}

/// 固定返回给定购物车的 controller。
///
/// 🔴 覆写整个 controller 而不是底层 repository：本类测的是渲染。
/// ⚠️ 假实现可以简陋，但**不能比真实现更宽容**：remove / setSelected 真的改状态、重算合计
/// （2026-09-03 R-3 教训：只记一笔不动状态，「撤销点了没反应」能一路绿着上线）。
class _FakeCartController extends CartController {
  _FakeCartController(this._cart);

  CartView _cart;

  /// 变更后服务端返回的 estimate（null = 旧后端不下发）。
  CartEstimate? nextEstimate;

  final removed = <String>[];
  final added = <({String sku, int qty, String? entrySource})>[];
  final selectedCalls = <({String sku, bool selected})>[];
  final selectAllCalls = <bool>[];
  int clearInvalidCalls = 0;
  int refreshes = 0;
  int addressLoads = 0;
  bool addFailsOnStock = false;

  /// 非 null 时 remove 记下调用后挂起到 complete（模拟请求进行中）。
  Completer<void>? removeGate;

  @override
  Future<CartView> build() async => _cart;

  /// 模拟别处（推荐卡「＋」）改了车。
  void push(CartView v) {
    _cart = v;
    state = AsyncData(v);
  }

  void fail() => state = AsyncError(Exception('boom'), StackTrace.empty);

  @override
  Future<void> refresh() async => refreshes++;

  @override
  Future<void> remove(String skuToken) async {
    removed.add(skuToken);
    final g = removeGate;
    if (g != null) await g.future;
    _cart = _rebuild(
      _cart.lines.where((l) => l.skuToken != skuToken).toList(),
      invalid: _cart.invalidLines.where((l) => l.skuToken != skuToken).toList(),
    );
    state = AsyncData(_cart);
  }

  @override
  Future<void> setSelected(String skuToken, bool selected) async {
    selectedCalls.add((sku: skuToken, selected: selected));
    _cart = _rebuild([
      for (final l in _cart.lines) l.skuToken == skuToken ? _withSelected(l, selected) : l,
    ]);
    state = AsyncData(_cart);
  }

  @override
  Future<void> setAllSelected(bool selected) async {
    selectAllCalls.add(selected);
    _cart = _rebuild([for (final l in _cart.lines) _withSelected(l, selected)]);
    state = AsyncData(_cart);
  }

  @override
  Future<void> clearInvalid() async {
    clearInvalidCalls++;
    _cart = _rebuild(_cart.lines, invalid: const []);
    state = AsyncData(_cart);
  }

  @override
  Future<void> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false}) async {
    if (addFailsOnStock) throw CartMutationError.stock;
    added.add((sku: skuToken, qty: qty, entrySource: entrySource));
  }

  /// 🔴 失效行无论勾没勾都不进任何一个数。estimate 取 [nextEstimate]（服务端新算的）。
  CartView _rebuild(List<CartLine> lines, {List<CartLine>? invalid}) {
    final picked = lines.where((l) => l.selected);
    return CartView(
      lines: lines,
      invalidLines: invalid ?? _cart.invalidLines,
      subtotal: lines.fold(0, (n, l) => n + l.lineTotal),
      itemCount: lines.fold(0, (n, l) => n + l.qty),
      selectedSubtotal: picked.fold(0, (n, l) => n + l.lineTotal),
      selectedCount: picked.fold(0, (n, l) => n + l.qty),
      estimate: nextEstimate,
    );
  }

  static CartLine _withSelected(CartLine l, bool selected) => CartLine(
        skuToken: l.skuToken,
        productToken: l.productToken,
        productName: l.productName,
        specName: l.specName,
        price: l.price,
        qty: l.qty,
        mainImageUrl: l.mainImageUrl,
        availableStock: l.availableStock,
        invalidReason: l.invalidReason,
        selected: selected,
      );
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}
