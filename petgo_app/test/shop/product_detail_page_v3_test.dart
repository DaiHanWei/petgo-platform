import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/coupon/domain/shop_coupon.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart' show CouponKind;
import 'package:tailtopia/features/shop/domain/free_shipping_progress.dart';
import 'package:tailtopia/features/shop/domain/shipping_estimate.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_review.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_pressable.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_soft_sheet.dart';

import 'fake_shop_products.dart';
import 'pdp_fakes.dart';

/// 商品详情 v3（v1.4.2 shop Story 8.2 · B1–B15）。取代 `product_detail_page_v2_test.dart`（用例迁入本文件）。
///
/// 继承自 v2 的硬规则（默认规格、库存不虚构、开封不退、售罄态、评分不显示 0）原样保留断言；
/// 「多规格默认选中」改为断言规格行与弹层默认项，「Lihat Alternatif」改为回 Toko 首页（品类下线）。
void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  ShopSku sku(
    String token, {
    String spec = '3 kg',
    int price = 185000,
    StockStatus stock = StockStatus.inStock,
    int? remaining,
    ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen,
    bool sellable = true,
  }) =>
      ShopSku(
        token: token,
        specName: spec,
        price: price,
        returnPolicy: policy,
        stockStatus: stock,
        remaining: remaining,
        sellable: sellable,
      );

  ShopProductDetail detail({
    required List<ShopSku> skus,
    ShopCategory? category = ShopCategory.makanan,
    ReturnPolicy policy = ReturnPolicy.noReturnAfterOpen,
    List<String> gallery = const [],
    String? mainImage,
    ProductReviews? reviewSummary = ProductReviews.empty,
    bool reviewListEnabled = false,
  }) =>
      ShopProductDetail(
        token: 'tok1',
        mainImageUrl: mainImage,
        name: 'Royal Canin Adult Dog',
        brand: 'Royal Canin',
        category: category,
        returnPolicy: policy,
        skus: skus,
        galleryUrls: gallery,
        detailHtml: '<p>Makanan kering.</p>',
        reviewSummary: reviewSummary,
        reviewListEnabled: reviewListEnabled,
      );

  late _FakeCartRepo cart;
  setUp(() => cart = _FakeCartRepo());

  Future<GoRouter> pump(
    WidgetTester t,
    ShopProductDetail? d, {
    bool loggedIn = false,
    Object? detailError,
    FakeCouponRepository? coupons,
    FakeShippingEstimateRepository? estimates,
    ShopDeliveryArea? storedArea,
    List<ShopDeliveryArea>? selectedAreas,
    Completer<List<ShippingAddress>>? addressGate,
    List<Override> extra = const [],
    ProductReviews reviews = ProductReviews.empty,
    bool tall = true,
    double textScale = 1,
    String initial = '/shop/products/tok1',
  }) async {
    if (tall) {
      t.view.physicalSize = const Size(1200, 3000);
      t.view.devicePixelRatio = 1.0;
    } else {
      t.view.physicalSize = const Size(411, 891);
      t.view.devicePixelRatio = 1.0;
    }
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    final r = GoRouter(
      initialLocation: initial,
      routes: [
        GoRoute(
          path: '/shop/products/:token',
          builder: (c, s) => ProductDetailPageV3(
              token: s.pathParameters['token']!, entrySource: s.uri.queryParameters['from']),
        ),
        GoRoute(
            path: '/shop/products/:token/reviews',
            builder: (c, s) => const Scaffold(body: Text('REVIEWS PAGE'))),
        GoRoute(path: '/shop', builder: (c, s) => const Scaffold(body: Text('TOKO HOME'))),
        GoRoute(path: '/shop/cart', builder: (c, s) => const Scaffold(body: Text('CART PAGE'))),
        GoRoute(
            path: '/shop/checkout',
            builder: (c, s) => Scaffold(body: Text('CHECKOUT ${s.uri.query}'))),
        GoRoute(path: '/login', builder: (c, s) => const Scaffold(body: Text('LOGIN PAGE'))),
      ],
    );
    await t.pumpWidget(ProviderScope(
      overrides: [
        ...pdpOverrides(
          detail: d,
          detailError: detailError,
          coupons: coupons,
          estimates: estimates,
          storedArea: storedArea,
          selectedAreas: selectedAreas,
          addresses: loggedIn ? [fakeAddress()] : const [],
          addressGate: addressGate,
          regions: fakeRegionTree,
          reviews: reviews,
        ),
        authControllerProvider.overrideWith(() => _TestAuthController(loggedIn
            ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
            : const AuthState.guest())),
        cartRepositoryProvider.overrideWithValue(cart),
        loginGuideControllerProvider.overrideWith((ref) => LoginGuideController(() async {
              const resp = LoginResponse(
                accessToken: 'a',
                refreshToken: 'r',
                role: 'USER',
                isNewUser: false,
                onboardingCompleted: true,
              );
              ref.read(authControllerProvider.notifier).applyLogin(resp);
              return resp;
            })),
        ...extra,
      ],
      child: MaterialApp.router(
        routerConfig: r,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        builder: (c, child) => MediaQuery(
          data: MediaQuery.of(c).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
    ));
    await t.pumpAndSettle();
    return r;
  }

  String location(GoRouter r) => r.routerDelegate.currentConfiguration.uri.toString();

  Future<void> settleToast(WidgetTester t) async {
    await t.pump(const Duration(seconds: 3));
    await t.pumpAndSettle();
  }

  String valueOf(WidgetTester t, String key) =>
      t.widget<Text>(find.byKey(ValueKey('${key}Value'))).data!;

  ShopCoupon coupon(String token,
          {CouponKind kind = CouponKind.discount, int amount = 10000, bool claimed = false}) =>
      ShopCoupon(
          couponToken: token,
          kind: kind,
          amount: amount,
          minSpend: 50000,
          nameId: 'Diskon $token',
          claimed: claimed);

  // ======================================================================= 迁移自 v2

  group('🔴 R-1：主图点击放大（迁移）', () {
    testWidgets('有图 → 图区可点，全屏查看器用 contain', (t) async {
      await pump(t, detail(skus: [sku('s1')], mainImage: 'https://cdn.test/main.jpg'));
      await t.tap(find.byKey(const ValueKey('pdpGalleryZoom')));
      await t.pumpAndSettle();
      expect(find.byType(InteractiveViewer), findsOneWidget);
      final img = t.widget<Image>(
          find.descendant(of: find.byType(InteractiveViewer), matching: find.byType(Image)));
      expect(img.fit, BoxFit.contain);
    });

    testWidgets('🔴 查看器拿原图 URL', (t) async {
      await pump(t, detail(skus: [sku('s1')], mainImage: 'https://cdn.test/main.jpg'));
      await t.tap(find.byKey(const ValueKey('pdpGalleryZoom')));
      await t.pumpAndSettle();
      final img = t.widget<Image>(
          find.descendant(of: find.byType(InteractiveViewer), matching: find.byType(Image)));
      expect((img.image as NetworkImage).url, 'https://cdn.test/main.jpg');
    });

    testWidgets('无图 → 不给点击入口', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.byKey(const ValueKey('pdpGalleryZoom')), findsNothing);
    });

    testWidgets('🔴 多图 → 整组进查看器，从被点那张打开', (t) async {
      await pump(
          t,
          detail(
            skus: [sku('s1')],
            mainImage: 'https://cdn.test/a.jpg',
            gallery: const ['https://cdn.test/b.jpg', 'https://cdn.test/c.jpg'],
          ));
      await t.tap(find.byKey(const ValueKey('pdpGalleryZoom')).hitTestable());
      await t.pumpAndSettle();
      final indicator = t.widget<Text>(find.byKey(const ValueKey('imageViewerPageIndicator')));
      expect(indicator.data, '1/3');
    });

    testWidgets('AC1.4：图区高 300 且不裁切（contain）', (t) async {
      await pump(t, detail(skus: [sku('s1')], mainImage: 'https://cdn.test/main.jpg'));
      final img = t.widget<ShopImage>(
          find.descendant(of: find.byKey(const ValueKey('pdpGallery')), matching: find.byType(ShopImage)));
      expect(img.fit, BoxFit.contain, reason: '线上 cover 会裁图');
      expect(img.size, 300);
    });
  });

  group('默认规格（有货 > 低价 > 后台顺序）· 规格行与弹层默认项（按设计改写）', () {
    Future<bool> chipSelected(WidgetTester t, String token) async =>
        t.widget<ShopChip>(find.byKey(ValueKey('skuChip_$token'))).selected;

    Future<void> openSheet(WidgetTester t) async {
      await t.tap(find.byKey(const ValueKey('pdpVariantRow')));
      await t.pumpAndSettle();
    }

    testWidgets('都有货 → 选最低价；规格行显示它，弹层默认选中它，胶囊坞可点', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: '7.5 kg', price: 400000),
            sku('s2', spec: '1.5 kg', price: 110000),
            sku('s3', spec: '3 kg', price: 185000),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), '1.5 kg');
      expect(t.widget<ShopPressable>(find.byKey(const ValueKey('pdpAddToCart'))).onTap, isNotNull);
      expect(t.widget<ShopPressable>(find.byKey(const ValueKey('pdpBuyNow'))).onTap, isNotNull);
      await openSheet(t);
      expect(await chipSelected(t, 's2'), isTrue);
      expect(await chipSelected(t, 's1'), isFalse);
    });

    testWidgets('最低价售罄 → 跳过，选有货里的次低价', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: 'A', price: 50000, stock: StockStatus.outOfStock),
            sku('s2', spec: 'B', price: 80000),
            sku('s3', spec: 'C', price: 60000),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), 'C');
      await openSheet(t);
      expect(await chipSelected(t, 's3'), isTrue);
      expect(await chipSelected(t, 's1'), isFalse);
    });

    testWidgets('库存紧张也算有货，不比剩余数量', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: 'A', price: 90000, remaining: 500),
            sku('s2', spec: 'B', price: 70000, stock: StockStatus.lowStock, remaining: 2),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), 'B');
    });

    testWidgets('同价 → 取后台顺序靠前的', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: 'A', price: 90000),
            sku('s2', spec: 'B', price: 60000),
            sku('s3', spec: 'C', price: 60000),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), 'B');
    });

    testWidgets('全部售罄 → 不默认选中，没有可点的购买入口', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: 'A', stock: StockStatus.outOfStock),
            sku('s2', spec: 'B', stock: StockStatus.outOfStock),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), '');
      expect(find.byKey(const ValueKey('pdpAddToCart')), findsNothing);
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsNothing);
    });

    testWidgets('弹层里手动切规格 → 回写本页（规格行跟着变）', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', spec: 'A', price: 60000),
            sku('s2', spec: 'B', price: 90000),
          ]));
      expect(valueOf(t, 'pdpVariantRow'), 'A');
      await openSheet(t);
      await t.tap(find.byKey(const ValueKey('skuChip_s2')));
      await t.pumpAndSettle();
      Navigator.of(t.element(find.byKey(const ValueKey('skuPickerSheet')))).pop();
      await t.pumpAndSettle();
      expect(valueOf(t, 'pdpVariantRow'), 'B');
    });
  });

  group('🔴 FR-95：库存数不虚构（迁移）', () {
    testWidgets('remaining ≤ 20 显真实数字', (t) async {
      await pump(t, detail(skus: [sku('s1', remaining: 14)]));
      expect(find.textContaining('Sisa 14'), findsOneWidget);
    });

    testWidgets('remaining > 20 只说「有货」', (t) async {
      await pump(t, detail(skus: [sku('s1', remaining: 340)]));
      expect(find.textContaining('340'), findsNothing);
      expect(find.textContaining('Stok tersedia'), findsOneWidget);
    });

    testWidgets('remaining 为 null 时不编数字', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.textContaining('Sisa'), findsNothing);
    });
  });

  group('🔴 FR-104：开封不退在本页明示（迁移）', () {
    testWidgets('NO_RETURN_AFTER_OPEN → 警示块出现', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.byType(ShopWarnBlock), findsOneWidget);
    });

    testWidgets('选中的 SKU 规则优先于商品级规则', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', price: 60000, policy: ReturnPolicy.returnable),
            sku('s2', spec: '7.5 kg', price: 90000, policy: ReturnPolicy.noReturn),
          ], policy: ReturnPolicy.returnable));
      await t.tap(find.byKey(const ValueKey('pdpVariantRow')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('skuChip_s2')));
      await t.pumpAndSettle();
      Navigator.of(t.element(find.byKey(const ValueKey('skuPickerSheet')))).pop();
      await t.pumpAndSettle();
      expect(find.text('Tidak bisa diretur.'), findsOneWidget);
    });
  });

  group('售罄态（迁移 + AC5.2 按设计改写）', () {
    final soldOut = [sku('s1', stock: StockStatus.outOfStock)];

    testWidgets('🔴 主按钮置灰但不消失', (t) async {
      await pump(t, detail(skus: soldOut));
      expect(find.byKey(const ValueKey('pdpSoldOutDisabled')), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsNothing);
    });

    testWidgets('🔴 价格转灰 + harga terakhir', (t) async {
      await pump(t, detail(skus: soldOut));
      expect(find.text('harga terakhir'), findsOneWidget);
      final price = t.widget<Text>(find.text('Rp 185.000'));
      expect(price.style?.color, ShopColors.text4);
    });

    testWidgets('🔴 不写「segera」', (t) async {
      await pump(t, detail(skus: soldOut));
      expect(find.textContaining('segera'), findsNothing);
    });

    testWidgets('点 Lihat Alternatif → go(/shop)（品类入口下线）', (t) async {
      final r = await pump(t, detail(skus: soldOut));
      await t.tap(find.byKey(const ValueKey('pdpSeeAlternatives')));
      await t.pumpAndSettle();
      expect(location(r), '/shop');
      expect(find.text('TOKO HOME'), findsOneWidget);
    });

    testWidgets('品类未知也渲染且同样落首页', (t) async {
      final r = await pump(t, detail(skus: soldOut, category: null));
      expect(find.byKey(const ValueKey('pdpSeeAlternatives')), findsOneWidget);
      await t.tap(find.byKey(const ValueKey('pdpSeeAlternatives')));
      await t.pumpAndSettle();
      expect(location(r), '/shop');
    });

    testWidgets('多规格未全售罄 → 不算售罄', (t) async {
      await pump(
          t,
          detail(skus: [
            sku('s1', stock: StockStatus.outOfStock),
            sku('s2', spec: '7.5 kg'),
          ]));
      expect(find.byKey(const ValueKey('pdpSoldOutDisabled')), findsNothing);
    });
  });

  group('评分与已售数（迁移）', () {
    testWidgets('🔴 无评分整段不显示，不显示 0 分', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.textContaining('★'), findsNothing);
      expect(find.textContaining('0.0'), findsNothing);
    });

    testWidgets('有评分显示 ★ 与评价数（取详情 reviewSummary）', (t) async {
      await pump(
          t,
          detail(
              skus: [sku('s1')],
              reviewSummary: const ProductReviews(total: 128, items: [], averageRating: 4.9)));
      expect(find.textContaining('★ 4.9'), findsOneWidget);
      expect(find.textContaining('128 ulasan'), findsOneWidget);
    });

    testWidgets('旧后端无 reviewSummary → 回落 /reviews', (t) async {
      await pump(t, detail(skus: [sku('s1')], reviewSummary: null),
          reviews: const ProductReviews(total: 7, items: [], averageRating: 4.2));
      expect(find.textContaining('★ 4.2'), findsOneWidget);
      expect(find.textContaining('7 ulasan'), findsOneWidget);
    });

    testWidgets('🔴 不拿评价数冒充「已售数」', (t) async {
      await pump(
          t,
          detail(
              skus: [sku('s1')],
              reviewSummary: const ProductReviews(total: 128, items: [], averageRating: 4.9)));
      expect(find.textContaining('terjual'), findsNothing);
    });
  });

  group('布局不得溢出（迁移）', () {
    testWidgets('411dp · 标准字号', (t) async {
      await pump(t, detail(skus: [sku('s1'), sku('s2', spec: '7.5 kg')]), tall: false);
      expect(t.takeException(), isNull);
    });

    testWidgets('1.3 倍字号（NFR-13 上限）', (t) async {
      await pump(t, detail(skus: [sku('s1', remaining: 14)]), tall: false, textScale: 1.3, loggedIn: true);
      expect(t.takeException(), isNull);
    });
  });

  // ======================================================================= 新增

  group('AC1 页面结构', () {
    testWidgets('上部顺序：标题 → 价格 → 券行 → 送达 → 运费 → 免运', (t) async {
      await pump(
        t,
        detail(skus: [sku('s1')]),
        loggedIn: true,
        coupons: FakeCouponRepository(productItems: [coupon('c1')]),
        estimates: FakeShippingEstimateRepository(
            respond: (_) => const ShippingEstimate(
                serviceable: true,
                lowestFee: 9000,
                freeShippingProgress: FreeShippingProgress(threshold: 200000, remaining: 15000))),
      );
      double y(String k) => t.getTopLeft(find.byKey(ValueKey(k))).dy;
      final order = ['pdpTitle', 'pdpPrice', 'pdpCouponRow', 'pdpShipTo', 'pdpShippingFee', 'pdpFreeShipProgress'];
      for (var i = 1; i < order.length; i++) {
        expect(y(order[i - 1]) < y(order[i]), isTrue, reason: '${order[i - 1]} 应在 ${order[i]} 之上');
      }
    });

    testWidgets('胶囊坞两个 key 存在，立即购买按钮嵌价格', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.byKey(const ValueKey('pdpAddToCart')), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsOneWidget);
      expect(find.descendant(
          of: find.byKey(const ValueKey('pdpBuyNow')), matching: find.text('Beli Sekarang')), findsOneWidget);
      expect(t.widget<Text>(find.byKey(const ValueKey('pdpBuyNowPrice'))).data, 'Rp 185.000');
    });

    testWidgets('游客没有 PawCoin 行；已登录有', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.byKey(const ValueKey('pdpPawcoinRow')), findsNothing);
    });

    testWidgets('已登录有 PawCoin 行', (t) async {
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true);
      expect(find.byKey(const ValueKey('pdpPawcoinRow')), findsOneWidget);
    });
  });

  group('AC2 / AC3 送达地区与运费', () {
    testWidgets('B1：已登录有默认地址 → Kec. 区, 市；「Mulai Rp X」；免运还差', (t) async {
      final est = FakeShippingEstimateRepository(
          respond: (_) => const ShippingEstimate(
              serviceable: true,
              lowestFee: 9000,
              freeShippingProgress: FreeShippingProgress(threshold: 200000, remaining: 15000)));
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, estimates: est);
      expect(valueOf(t, 'pdpShipTo'), 'Kec. Kebayoran Baru, Jakarta Selatan');
      expect(valueOf(t, 'pdpShippingFee'), 'Mulai Rp 9.000');
      expect(find.text('Belanja Rp 15.000 lagi untuk potongan ongkir'), findsOneWidget);
      expect(est.calls.single, (product: 'tok1', sku: 's1', address: 'addr-1', region: null));
    });

    testWidgets('B1：门槛 0 / 无进度 → 免运整条不出现', (t) async {
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true);
      expect(find.byKey(const ValueKey('pdpFreeShipProgress')), findsNothing);
    });

    testWidgets('B2：无地址无地区 → 「Pilih wilayah pengiriman」+ 先选地区提示，不询价', (t) async {
      final est = FakeShippingEstimateRepository();
      await pump(t, detail(skus: [sku('s1')]), estimates: est);
      expect(valueOf(t, 'pdpShipTo'), 'Pilih wilayah pengiriman');
      expect(valueOf(t, 'pdpShippingChooseArea'), 'Pilih wilayah untuk lihat ongkir');
      expect(est.calls, isEmpty);
    });

    testWidgets('B3：不可配送 → 橙色提示，免运进度不显示，底栏仍可加购', (t) async {
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          estimates: FakeShippingEstimateRepository(
              respond: (_) => const ShippingEstimate(
                  serviceable: false,
                  freeShippingProgress: FreeShippingProgress(threshold: 200000, remaining: 15000))));
      expect(find.text('Wilayah ini belum bisa dikirim kurir'), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpFreeShipProgress')), findsNothing);
      expect(t.widget<ShopPressable>(find.byKey(const ValueKey('pdpAddToCart'))).onTap, isNotNull);
    });

    testWidgets('B4：达门槛 → 「Dapat potongan ongkir」', (t) async {
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          estimates: FakeShippingEstimateRepository(
              respond: (_) => const ShippingEstimate(
                  serviceable: true,
                  lowestFee: 9000,
                  freeShippingProgress: FreeShippingProgress(threshold: 100000, remaining: 0))));
      expect(find.byKey(const ValueKey('pdpFreeShipReached')), findsOneWidget);
      expect(find.text('Dapat potongan ongkir'), findsOneWidget);
    });

    testWidgets('B15：询价失败 → 「Ongkir belum bisa dimuat」+ Coba lagi 重新请求；免运进度照常', (t) async {
      final est = FakeShippingEstimateRepository(
          respond: (_) => const ShippingEstimate(
              quoteFailed: true,
              freeShippingProgress: FreeShippingProgress(threshold: 200000, remaining: 15000)));
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, estimates: est);
      expect(find.text('Ongkir belum bisa dimuat'), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpFreeShipRemaining')), findsOneWidget);
      expect(est.calls, hasLength(1));
      await t.tap(find.byKey(const ValueKey('pdpShippingRetry')));
      await t.pumpAndSettle();
      expect(est.calls, hasLength(2));
    });

    testWidgets('复审 #5：重试在途 → 「Coba lagi」不可点，不重复请求', (t) async {
      final est = FakeShippingEstimateRepository(
          respond: (_) => const ShippingEstimate(quoteFailed: true));
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, estimates: est);
      est.gate = Completer<void>();
      await t.tap(find.byKey(const ValueKey('pdpShippingRetry')));
      await t.pump();
      expect(t.widget<ShopPressable>(find.byKey(const ValueKey('pdpShippingRetry'))).onTap, isNull);
      await t.tap(find.byKey(const ValueKey('pdpShippingRetry')), warnIfMissed: false);
      await t.pump();
      expect(est.calls, hasLength(2));
      est.gate!.complete();
      await t.pumpAndSettle();
      expect(t.widget<ShopPressable>(find.byKey(const ValueKey('pdpShippingRetry'))).onTap, isNotNull);
    });

    testWidgets('复审 #6：已登录、地址列表加载中 → 运费行「—」，不先按本地地区询价', (t) async {
      final est = FakeShippingEstimateRepository();
      final gate = Completer<List<ShippingAddress>>();
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          estimates: est,
          addressGate: gate,
          storedArea: const ShopDeliveryArea(
              kecamatanId: 11, kecamatan: 'Kebayoran', kota: 'Jakarta Selatan', provinsi: 'DKI Jakarta'));
      expect(valueOf(t, 'pdpShippingLoading'), '—');
      expect(est.calls, isEmpty);
      gate.complete([fakeAddress()]);
      await t.pumpAndSettle();
      expect(est.calls.single.address, 'addr-1', reason: '地址回来后只按默认地址询价一次');
    });

    testWidgets('B15：网络整体失败 → 同样给重试', (t) async {
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          estimates: FakeShippingEstimateRepository(respond: (_) => throw DioException(
              requestOptions: RequestOptions(path: '/x'))));
      expect(find.byKey(const ValueKey('pdpShippingRetry')), findsOneWidget);
    });

    testWidgets('优先级：本地记住的地区（游客）→ 按 regionKecamatanId 询价', (t) async {
      final est = FakeShippingEstimateRepository();
      await pump(t, detail(skus: [sku('s1')]),
          estimates: est,
          storedArea: const ShopDeliveryArea(
              kecamatanId: 11, kecamatan: 'Kebayoran', kota: 'Jakarta Selatan', provinsi: 'DKI Jakarta'));
      expect(valueOf(t, 'pdpShipTo'), 'Kec. Kebayoran, Jakarta Selatan');
      expect(est.calls.single, (product: 'tok1', sku: 's1', address: null, region: 11));
    });

    testWidgets('优先级：默认地址 > 本地记住的地区', (t) async {
      final est = FakeShippingEstimateRepository();
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          estimates: est,
          storedArea: const ShopDeliveryArea(
              kecamatanId: 11, kecamatan: 'Kebayoran', kota: 'Jakarta Selatan', provinsi: 'DKI Jakarta'));
      expect(valueOf(t, 'pdpShipTo'), 'Kec. Kebayoran Baru, Jakarta Selatan');
      expect(est.calls.single.address, 'addr-1');
    });

    testWidgets('优先级：本页 B6 选的 > 默认地址；选中写本地 + 埋点 serviceable', (t) async {
      final selected = <ShopDeliveryArea>[];
      final est = FakeShippingEstimateRepository(
          respond: (q) => ShippingEstimate(serviceable: q.region != 12, lowestFee: q.region == 12 ? null : 9000));
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true, estimates: est, selectedAreas: selected);
      await t.tap(find.byKey(const ValueKey('pdpShipTo')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan')));
      await t.pumpAndSettle();

      expect(find.byKey(const ValueKey('deliveryAreaSheet')), findsNothing, reason: '选中区即关抽屉');
      expect(valueOf(t, 'pdpShipTo'), 'Kec. Pesanggrahan, Jakarta Selatan');
      expect(selected.single.kecamatanId, 12, reason: '只记本地');
      expect(est.calls.last, (product: 'tok1', sku: 's1', address: null, region: 12));
      final ev = events.where((e) => e.$1 == 'toko_delivery_area_selected').single;
      expect(ev.$2, {'serviceable': false});
      expect(find.text('Wilayah ini belum bisa dikirim kurir'), findsOneWidget);
    });

    testWidgets('B5 不可售 SKU 不询价（后端 422），底栏整宽「Belum bisa dijual」', (t) async {
      final est = FakeShippingEstimateRepository();
      await pump(t, detail(skus: [sku('s1', sellable: false)]), loggedIn: true, estimates: est);
      expect(est.calls, isEmpty);
      final b = t.widget<ShopButton>(find.byKey(const ValueKey('pdpNotSellable')));
      expect(b.label, 'Belum bisa dijual');
      expect(b.variant, ShopButtonVariant.disabled);
      expect(find.byKey(const ValueKey('pdpAddToCart')), findsNothing);
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsNothing);
    });

    testWidgets('B5：切到可售规格恢复底栏', (t) async {
      await pump(t, detail(skus: [
        sku('s1', spec: 'A', price: 50000, sellable: false),
        sku('s2', spec: 'B', price: 60000),
      ]));
      expect(find.byKey(const ValueKey('pdpNotSellable')), findsOneWidget);
      await t.tap(find.byKey(const ValueKey('pdpVariantRow')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('skuChip_s2')));
      await t.pumpAndSettle();
      Navigator.of(t.element(find.byKey(const ValueKey('skuPickerSheet')))).pop();
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('pdpNotSellable')), findsNothing);
      expect(find.byKey(const ValueKey('pdpBuyNow')), findsOneWidget);
    });
  });

  group('AC4 可领的券（B1 / B7 / B8）', () {
    testWidgets('券行空 → 不画', (t) async {
      await pump(t, detail(skus: [sku('s1')]));
      expect(find.byKey(const ValueKey('pdpCouponRow')), findsNothing);
    });

    testWidgets('券行至多两枚摘要：满减面额 / 免运「Gratis ongkir」', (t) async {
      await pump(t, detail(skus: [sku('s1')]),
          coupons: FakeCouponRepository(productItems: [
            coupon('c1', amount: 20000),
            coupon('c2', kind: CouponKind.freeShipping),
            coupon('c3'),
          ]));
      expect(find.byKey(const ValueKey('pdpCouponTag_c1')), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpCouponTag_c2')), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpCouponTag_c3')), findsNothing);
      expect(find.descendant(of: find.byKey(const ValueKey('pdpCouponTag_c1')), matching: find.text('Rp 20.000')),
          findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('pdpCouponTag_c2')), matching: find.text('Gratis ongkir')),
          findsOneWidget);
    });

    testWidgets('B7：列表 + 已领「Sudah diklaim」灰不可点', (t) async {
      await pump(t, detail(skus: [sku('s1')]),
          loggedIn: true,
          coupons: FakeCouponRepository(productItems: [coupon('c1'), coupon('c2', claimed: true)]));
      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      expect(find.text('Kupon untuk produk ini'), findsOneWidget);
      final done = t.widget<ShopButton>(find.byKey(const ValueKey('productCouponClaim_c2')));
      expect(done.label, 'Sudah diklaim');
      expect(done.onTap, isNull);
      expect(t.widget<ShopButton>(find.byKey(const ValueKey('productCouponClaim_c1'))).label, 'Klaim');
    });

    testWidgets('B8：领成功 → 变「Sudah diklaim」、toast 在顶部、抽屉不关、埋点 source=detail', (t) async {
      final repo = FakeCouponRepository(productItems: [coupon('c1')]);
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('productCouponClaim_c1')));
      await t.pumpAndSettle();

      expect(repo.claims.single, ('c1', CouponClaimSource.detail));
      expect(find.byKey(const ValueKey('productCouponsSheet')), findsOneWidget, reason: '抽屉不关');
      expect(t.widget<ShopButton>(find.byKey(const ValueKey('productCouponClaim_c1'))).label, 'Sudah diklaim');
      final toast = find.text('Kupon diklaim. Cek di Kupon Saya');
      expect(toast, findsOneWidget);
      expect(t.getTopLeft(toast).dy < 1500, isTrue, reason: '抽屉开着 → toast 放顶部');
      final ev = events.where((e) => e.$1 == 'toko_coupon_claimed').single;
      expect(ev.$2, {'coupon_id': 'c1', 'source': 'detail'});
      await settleToast(t);
    });

    testWidgets('领券失败：already-claimed → 「Kamu sudah klaim kupon ini」', (t) async {
      final repo = FakeCouponRepository(
          productItems: [coupon('c1')], claimError: problemError('coupon-already-claimed'));
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('productCouponClaim_c1')));
      await t.pumpAndSettle();
      expect(find.text('Kamu sudah klaim kupon ini'), findsOneWidget);
      await settleToast(t);
    });

    testWidgets('领券失败：sold-out → 「Kupon sudah habis」', (t) async {
      final repo = FakeCouponRepository(
          productItems: [coupon('c1')], claimError: problemError('coupon-sold-out'));
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('productCouponClaim_c1')));
      await t.pumpAndSettle();
      expect(find.text('Kupon sudah habis'), findsOneWidget);
      await settleToast(t);
    });

    testWidgets('🔴 复审 #2：领券在途关掉抽屉 → 不抛异常；for-you 与本商品券行照样刷新', (t) async {
      final repo = FakeCouponRepository(productItems: [coupon('c1')])..claimGate = Completer<void>();
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true, coupons: repo);
      final container = ProviderScope.containerOf(t.element(find.byType(ProductDetailPageV3)));
      final sub = container.listen(couponsForYouProvider, (_, _) {});
      addTearDown(sub.close);
      await t.pumpAndSettle();
      final forYouBefore = repo.forYouCalls;
      final productBefore = repo.productCalls;

      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('productCouponClaim_c1')));
      await t.pump();
      await t.tap(find.byKey(const ValueKey('productCouponsClose')));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('productCouponsSheet')), findsNothing);

      repo.claimGate!.complete();
      await t.pumpAndSettle();
      expect(t.takeException(), isNull, reason: '已卸载的抽屉 ref 不得再被使用');
      expect(repo.forYouCalls, greaterThan(forYouBefore), reason: '首页「给你的券」要刷新');
      expect(repo.productCalls, greaterThan(productBefore), reason: '详情页券行要刷新');
      await settleToast(t);
    });

    testWidgets('游客领券 → 软登录浮层；登录后自动领这一张', (t) async {
      final repo = FakeCouponRepository(productItems: [coupon('c1')]);
      await pump(t, detail(skus: [sku('s1')]), coupons: repo);
      await t.tap(find.byKey(const ValueKey('pdpCouponRowTap')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('productCouponClaim_c1')));
      await t.pumpAndSettle();
      expect(find.byType(LoginSoftSheet), findsOneWidget);
      expect(repo.claims, isEmpty);
      await t.tap(find.byKey(const ValueKey('softSheetGoogleCta')));
      await t.pumpAndSettle();
      expect(repo.claims.single, ('c1', CouponClaimSource.detail));
      await settleToast(t);
    });
  });

  group('AC6 规格弹层、加购、立即购买（B9 / B10 / B11）', () {
    testWidgets('B9 / B11：加购 → 弹层确认 → toast「Ditambahkan ke keranjang」、留在详情、角标 +N', (t) async {
      cart.next = _cartWith(2);
      final r = await pump(t, detail(skus: [sku('s1', remaining: 9)]), loggedIn: true);
      await t.tap(find.byKey(const ValueKey('pdpAddToCart')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('skuPickerConfirm')));
      await t.pumpAndSettle();
      expect(cart.calls, contains('add:s1:1'));
      expect(find.text('Ditambahkan ke keranjang'), findsOneWidget);
      expect(location(r), '/shop/products/tok1');
      expect(t.widget<ShopImageButton>(find.byKey(const ValueKey('cartBadgeV2'))).badgeCount, 2);
      final ev = events.where((e) => e.$1 == 'toko_add_to_cart_tapped').single;
      expect(ev.$2, {'product_token': 'tok1', 'sku_token': 's1'});
      await settleToast(t);
    });

    testWidgets('B10：立即购买 → 结算路由带 source=buy_now&sku&qty，且未调 cart.add', (t) async {
      final r = await pump(t, detail(skus: [sku('s1')]), loggedIn: true);
      await t.tap(find.byKey(const ValueKey('pdpBuyNow')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('skuPickerConfirm')));
      await t.pumpAndSettle();
      expect(find.text('CHECKOUT source=buy_now&sku=s1&qty=1'), findsOneWidget,
          reason: 'push 进结算页（go_router 的 currentConfiguration 不反映 push 的子路由，按页面断言）');
      expect(r.canPop(), isTrue);
      expect(cart.calls.where((c) => c.startsWith('add')), isEmpty, reason: '立即购买不碰购物车');
      expect(events.map((e) => e.$1), contains('toko_buy_now_tapped'));
    });

    testWidgets('加购撞 422 sku-not-sellable → 兜底 cartGenericError', (t) async {
      cart.error = problemError('sku-not-sellable', status: 422);
      await pump(t, detail(skus: [sku('s1')]), loggedIn: true);
      await t.tap(find.byKey(const ValueKey('pdpAddToCart')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('skuPickerConfirm')));
      await t.pumpAndSettle();
      expect(find.text(_l10nId.cartGenericError), findsOneWidget);
      await settleToast(t);
    });
  });

  group('AC7 评价', () {
    final items = [
      for (var i = 1; i <= 4; i++)
        ShopReviewItem(id: i, rating: 5, imageUrls: const [], content: 'Bagus $i', nickname: 'Dewi S.'),
    ];

    testWidgets('开关关 → 评价区不出现，元信息仍有 ★ 与条数', (t) async {
      await pump(
          t,
          detail(
              skus: [sku('s1')],
              reviewSummary: ProductReviews(total: 4, averageRating: 4.5, items: const [])));
      expect(find.byKey(const ValueKey('pdpReviews')), findsNothing);
      expect(find.textContaining('★ 4.5'), findsOneWidget);
    });

    testWidgets('开关开且有评价 → 「Ulasan (n)」+ ★ + 最多 3 条 + Lihat semua 跳全部评价', (t) async {
      final r = await pump(
          t,
          detail(
              skus: [sku('s1')],
              reviewListEnabled: true,
              reviewSummary: ProductReviews(total: 4, averageRating: 4.5, items: items)));
      expect(find.text('Ulasan (4)'), findsOneWidget);
      expect(find.text('Bagus 3'), findsOneWidget);
      expect(find.text('Bagus 4'), findsNothing, reason: '页内最多 3 条');
      expect(find.text('Dewi S.'), findsNWidgets(3));
      await t.tap(find.byKey(const ValueKey('pdpReviewsSeeAll')));
      await t.pumpAndSettle();
      expect(find.text('REVIEWS PAGE'), findsOneWidget);
      expect(r.canPop(), isTrue);
    });

    testWidgets('B14：开关开且 0 条 → 「Ulasan」+「Belum ada ulasan」，元信息无 ★ 与条数', (t) async {
      await pump(t, detail(skus: [sku('s1')], reviewListEnabled: true));
      expect(find.text('Belum ada ulasan'), findsOneWidget);
      expect(find.textContaining('★'), findsNothing);
      expect(find.byKey(const ValueKey('pdpMeta')).evaluate().isEmpty ||
          !t.widget<Text>(find.byKey(const ValueKey('pdpMeta'))).data!.contains('ulasan'), isTrue);
    });
  });

  group('AC8 已下架（B13）', () {
    testWidgets('410 → 下架页，「Ke Toko」go(/shop)，没有通用重试', (t) async {
      final r = await pump(t, null, detailError: const ShopProductDelisted());
      expect(find.text('Produk ini sudah tidak dijual'), findsOneWidget);
      expect(find.text('Lihat produk lain di Toko.'), findsOneWidget);
      expect(find.byIcon(Icons.remove_shopping_cart), findsOneWidget);
      expect(find.byKey(const ValueKey('shopRetryButton')), findsNothing);
      await t.tap(find.byKey(const ValueKey('pdpDelistedCta')));
      await t.pumpAndSettle();
      expect(location(r), '/shop');
    });

    testWidgets('404 / 网络失败 → 仍为通用失败态 + 重试', (t) async {
      await pump(t, null,
          detailError: DioException(
              requestOptions: RequestOptions(path: '/x'),
              response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 404)));
      expect(find.text('Gagal memuat produk ini.'), findsOneWidget);
      expect(find.byKey(const ValueKey('shopRetryButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('pdpDelisted')), findsNothing);
    });

    test('仓储：410 / type 末段 product-delisted → isProductDelistedError', () {
      expect(isProductDelistedError(problemError('product-delisted', status: 410)), isTrue);
      expect(isProductDelistedError(problemError('not-found', status: 404)), isFalse);
    });
  });
}

final AppLocalizations _l10nId = lookupAppLocalizations(const Locale('id'));

CartView _cartWith(int count) => CartView.fromJson({
      'itemCount': count,
      'subtotal': 185000 * count,
      'lines': [
        {'skuToken': 's1', 'productToken': 'tok1', 'specName': '3 kg', 'price': 185000, 'qty': count},
      ],
    });

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _FakeCartRepo implements CartRepository {
  CartView next = CartView.empty;
  Object? error;
  final List<String> calls = [];

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CartView> view({int? regionKecamatanId}) async {
    calls.add('view');
    return CartView.empty;
  }

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false, int? regionKecamatanId}) async =>
      _write('add:$skuToken:$qty');

  @override
  Future<CartView> setQty(String skuToken, int qty, {int? regionKecamatanId}) async => _write('setQty');

  @override
  Future<CartView> remove(String skuToken, {int? regionKecamatanId}) async => _write('remove');

  @override
  Future<CartView> setSelected(String skuToken, bool selected, {int? regionKecamatanId}) async => _write('setSelected');

  @override
  Future<CartView> setAllSelected(bool selected, {int? regionKecamatanId}) async => _write('setAllSelected');

  @override
  Future<CartView> clearInvalid({int? regionKecamatanId}) async => _write('clearInvalid');

  Future<CartView> _write(String call) async {
    calls.add(call);
    final e = error;
    if (e != null) {
      error = null;
      throw e;
    }
    return next;
  }
}
