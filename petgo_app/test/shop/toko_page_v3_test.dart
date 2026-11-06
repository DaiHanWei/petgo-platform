import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/coupon/domain/shop_coupon.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repurchase_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart' show CouponKind;
import 'package:tailtopia/features/shop/domain/shop_banner.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_repurchase.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v3.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_banner_carousel.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_reco_grid.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/l10n/app_localizations_en.dart';
import 'package:tailtopia/l10n/app_localizations_id.dart';
import 'fake_shop_products.dart';

/// Toko 首页 v3（v1.4.2 shop Story 8.1，A1–A9）。取代 `toko_page_v2_test.dart`（随 v2 删除）。
///
/// - **原样迁移**：布局不溢出、占位价、千分位、游客态、搜索入口、取数族键、触底预加载。
/// - **按设计改写**：v2 的「顶栏前景随 tone / banner 作顶栏背景 / 筛选行吸顶 / 四个品类 chip / 显式全部」组
///   → 「顶栏前景恒墨色、无品类 chip、取数族键 category == null」与「banner 在搜索框下方按比例不裁切」。
/// - **新增**：轮播、A2 / A3 / A4 / A5 / A8 / A9、entry 去重、Lagi promo、`?category=` 落首页。
class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

class _Paw extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

class _FakeGuide extends LoginGuideController {
  _FakeGuide() : super(() async => null);

  int shown = 0;
  RouteIntent? pending;

  @override
  Future<void> showSoftSheet(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'social_soft_login', bool allowRepeat = false}) async {
    shown++;
    pending = pendingAction;
  }
}

class _FailingList extends ShopProductListController {
  _FailingList(super.query);

  static int builds = 0;

  @override
  Future<ShopProductFeed> build() async {
    builds++;
    throw Exception('boom');
  }
}

ShopProductSummary _p(String token, {int? price, String name = 'Produk', bool promo = false}) =>
    ShopProductSummary(token: token, name: name, brand: 'BrandX', minPrice: price, promo: promo);

const _bannerUrl = 'https://example.test/b169.jpg';
const _banner169 = ShopBanner(
    imageUrl: _bannerUrl, imageW: 1600, imageH: 900, bannerId: 'bn1', target: ShopBannerTarget.none);

ShopCoupon _coupon(String token, {bool claimed = false, bool newbie = false}) => ShopCoupon(
    couponToken: token,
    kind: CouponKind.discount,
    amount: 20000,
    minSpend: 100000,
    claimed: claimed,
    newbie: newbie);

Recommendations _reco({
  String? species = 'DOG',
  String? size = 'SMALL',
  String? age = 'ADULT',
  bool weightMissing = false,
  int items = 4,
}) =>
    Recommendations(
      degraded: weightMissing,
      missing: weightMissing ? 'WEIGHT' : 'NONE',
      petName: 'Mochi',
      profileSummary: ProfileSummary(
          petName: 'Mochi', species: species, sizeClass: size, ageStage: age, weightMissing: weightMissing),
      items: [
        for (var i = 0; i < items; i++)
          RecommendationItem(
              productToken: 'r$i', name: 'Reco $i', minPrice: 99000, reason: 'Untuk anjing dewasa 10–25 kg'),
      ],
    );

class _Harness {
  final List<String> pushed = [];
  late GoRouter router;
}

void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, props) => events.add((e, props));
    SharedPreferences.setMockInitialValues({});
    resetShopEntryThrottle();
    _FailingList.builds = 0;
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    shopEntryClock = DateTime.now;
  });

  Iterable<Map<String, Object>?> ev(String name) => events.where((e) => e.$1 == name).map((e) => e.$2);

  Future<_Harness> pump(
    WidgetTester t, {
    List<ShopProductSummary>? products,
    List<ShopBanner> banners = const [],
    FakeCouponRepository? coupons,
    bool loggedIn = false,
    Recommendations? reco,
    List<ShopProductSummary> top = const [],
    double textScale = 1,
    Locale locale = const Locale('id'),
    List<ShopProductsQuery>? seen,
    bool hasMore = false,
    List<ShopProductSummary> nextPageItems = const [],
    List<ShopProductsQuery>? loadMoreCalls,
    bool productsFail = false,
    LoginGuideController? guide,
    ValueNotifier<bool>? ticker,
    String initialLocation = '/shop',
    bool settle = true,
  }) async {
    final h = _Harness();
    Widget page() => ticker == null
        ? const TokoPageV3()
        : ValueListenableBuilder<bool>(
            valueListenable: ticker,
            builder: (_, on, child) => TickerMode(enabled: on, child: child!),
            child: const TokoPageV3(),
          );
    Widget sink(GoRouterState s) {
      h.pushed.add(s.uri.toString());
      return Scaffold(body: Text('route:${s.uri}'));
    }

    h.router = GoRouter(initialLocation: initialLocation, routes: [
      GoRoute(path: '/shop', builder: (c, s) => page()),
      GoRoute(path: '/shop/search', builder: (c, s) => sink(s)),
      GoRoute(path: '/shop/products/:token', builder: (c, s) => sink(s)),
      GoRoute(path: '/shop/checkout', builder: (c, s) => sink(s)),
      GoRoute(path: '/shop/cart', builder: (c, s) => sink(s)),
      GoRoute(path: '/me/coupons', builder: (c, s) => sink(s)),
      GoRoute(path: '/me/pawcoin', builder: (c, s) => sink(s)),
      GoRoute(path: '/login', builder: (c, s) => sink(s)),
      GoRoute(path: '/profile/edit', builder: (c, s) => sink(s)),
    ]);
    await t.pumpWidget(ProviderScope(
      // 失败态用例不要 Riverpod 3 的自动重试（否则一直停在重试中的 loading）。
      retry: (_, _) => null,
      overrides: [
        ...tokoHomeOverrides(banners: banners, coupons: coupons, top: top),
        if (productsFail)
          shopProductsProvider.overrideWith2((q) => _FailingList(q))
        else
          fakeShopProducts(products ?? [_p('a', price: 185000)],
              seen: seen, hasMore: hasMore, nextPageItems: nextPageItems, loadMoreCalls: loadMoreCalls),
        cartItemCountProvider.overrideWithValue(0),
        recommendationsProvider.overrideWith((ref) async =>
            reco ?? const Recommendations(degraded: true, missing: 'GUEST', items: [])),
        if (loggedIn) authControllerProvider.overrideWith(_LoggedInAuth.new),
        if (loggedIn) pawCoinProvider.overrideWith(_Paw.new),
        if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
      ],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: locale,
        routerConfig: h.router,
        builder: (c, child) => MediaQuery(
          data: MediaQuery.of(c).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
      ),
    ));
    if (settle) {
      await t.pumpAndSettle();
    } else {
      await t.pump();
      await t.pump();
    }
    return h;
  }

  void phone(WidgetTester t, Size logical) {
    t.view.physicalSize = logical * 3;
    t.view.devicePixelRatio = 3;
    addTearDown(t.view.reset);
  }

  // ------------------------------------------------------------------ 原样迁移
  group('🔴 布局不得溢出（迁移自 v2）', () {
    final many = [
      _p('a', price: 185000, name: 'Royal Canin Adult Dog Premium Nutrition', promo: true),
      _p('b', price: 78000, name: 'Whiskas Adult Cat'),
      _p('c', price: 215000, name: 'Pro Plan Puppy'),
      _p('d', name: 'Drontal Plus Obat Cacing'),
    ];

    testWidgets('411dp 宽 · 标准字号（含推荐 / 券 / banner）', (t) async {
      phone(t, const Size(411, 891));
      await pump(t,
          products: many,
          banners: const [_banner169],
          loggedIn: true,
          reco: _reco(weightMissing: true, items: 6),
          coupons: FakeCouponRepository(forYouItems: [_coupon('n', newbie: true), _coupon('c1')]));
      expect(t.takeException(), isNull);
    });

    testWidgets('1.3 倍字号仍不溢出（NFR-13 上限）', (t) async {
      phone(t, const Size(411, 891));
      await pump(t, products: many, textScale: 1.3, loggedIn: true, reco: _reco(items: 6),
          coupons: FakeCouponRepository(forYouItems: [_coupon('n', newbie: true), _coupon('c1')]));
      expect(t.takeException(), isNull);
    });

    testWidgets('极窄屏（320dp）不溢出', (t) async {
      phone(t, const Size(320, 640));
      await pump(t, products: many, loggedIn: true, reco: _reco(items: 6), textScale: 1.3,
          coupons: FakeCouponRepository(forYouItems: [_coupon('n', newbie: true), _coupon('c1')]));
      expect(t.takeException(), isNull);
    });
  });

  group('价格与空态（迁移自 v2）', () {
    testWidgets('🔴 minPrice 为 null 时显占位，不显示 Rp 0', (t) async {
      await pump(t, products: [_p('a')]);
      expect(find.text('Rp 0'), findsNothing);
      expect(find.text('Harga segera'), findsOneWidget);
    });

    testWidgets('价格按印尼盾格式化（千分位用点）', (t) async {
      await pump(t, products: [_p('a', price: 185000)]);
      expect(find.text('Rp 185.000'), findsOneWidget);
    });
  });

  group('游客态（FR-93A：浏览路径零登录墙，迁移自 v2）', () {
    testWidgets('🔒 不触发任何登录引导 / 弹窗；右上 Masuk 而不是余额', (t) async {
      await pump(t);
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(BottomSheet), findsNothing);
      expect(find.byKey(const ValueKey('tokoLoginCapsule')), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoPawcoinCapsule')), findsNothing);
      expect(find.text('Rp 0'), findsNothing);
    });

    testWidgets('A4 背景：游客无推荐块、无「Kupon buat kamu」', (t) async {
      await pump(t);
      expect(find.byType(ShopRecoGrid), findsOneWidget, reason: '组件在，但整块不渲染');
      expect(find.byKey(const ValueKey('shopRecoGridProfile')), findsNothing);
      expect(find.byKey(const ValueKey('couponsForYouRow')), findsNothing);
    });

    testWidgets('🔴 搜索入口存在且可用 → 点击进 /shop/search', (t) async {
      final h = await pump(t);
      expect(find.text('Cari makanan, obat, camilan…'), findsOneWidget);
      await t.tap(find.byKey(const ValueKey('tokoSearchEntryV2')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/shop/search']);
    });

    testWidgets('已登录：右上 PawCoin 余额胶囊', (t) async {
      await pump(t, loggedIn: true);
      expect(find.byKey(const ValueKey('tokoPawcoinCapsule')), findsOneWidget);
      expect(find.text('50rb'), findsOneWidget);
    });
  });

  // ------------------------------------------------------------------ 按设计改写
  group('🔴 按设计改写：品类筛选下线、顶栏恒墨色', () {
    testWidgets('标题「Toko」恒为墨色（v2「前景随 tone」组改写）', (t) async {
      await pump(t, banners: const [_banner169]);
      expect(t.widget<Text>(find.text('Toko')).style?.color, AppColors.ink);
    });

    testWidgets('无品类 chip、无输入框；取数族键 category == null 且不带关键词', (t) async {
      final seen = <ShopProductsQuery>[];
      await pump(t, seen: seen);
      expect(find.byType(ShopChip), findsNothing);
      for (final c in ['Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan']) {
        expect(find.text(c), findsNothing);
      }
      expect(find.byType(TextField), findsNothing);
      expect(seen, isNotEmpty);
      expect(seen.every((q) => q.category == null && q.keyword == null), isTrue);
    });

    testWidgets('🔴 `?category=OBAT_VITAMIN` 打开仍是首页且无报错（D-17）', (t) async {
      final seen = <ShopProductsQuery>[];
      await pump(t, initialLocation: '/shop?category=OBAT_VITAMIN', seen: seen);
      expect(t.takeException(), isNull);
      expect(find.byType(TokoPageV3), findsOneWidget);
      expect(seen.every((q) => q.category == null), isTrue);
      final router = File('lib/core/router/app_router.dart').readAsStringSync();
      expect(router, contains('AppTab.shop => const TokoPageV3()'),
          reason: '路由不得再把 ?category= 传给 Toko');
    });

    testWidgets('页面中不再有补货 / 建档引导（FR-136）', (t) async {
      await pump(t, loggedIn: true);
      expect(find.byKey(const ValueKey('tokoGuestProfileCta')), findsNothing);
      expect(ev('toko_repurchase_card_shown'), isEmpty);
    });
  });

  group('🔴 banner：在搜索框下方，按比例不裁切（bug 600 迁到轮播）', () {
    final image = find.byWidgetPredicate(
        (w) => w is Image && w.image is NetworkImage && (w.image as NetworkImage).url == _bannerUrl);

    testWidgets('图区比例 = 图比例，位于搜索框下方', (t) async {
      phone(t, const Size(411, 891));
      await pump(t, banners: const [_banner169]);
      final rect = t.getRect(image);
      expect(rect.width, closeTo(411 - 32, 0.01));
      expect(rect.height, closeTo((411 - 32) * 900 / 1600, 0.01),
          reason: '盒子比图高 → 留白 / 裁切（bug 600）');
      expect(rect.top, greaterThan(t.getBottomLeft(find.byKey(const ValueKey('tokoSearchEntryV2'))).dy));
    });

    testWidgets('A2：空数组 → 整块不渲染', (t) async {
      await pump(t);
      expect(find.byKey(const ValueKey('tokoBannerCarousel')), findsNothing);
    });
  });

  group('🔴 轮播（PageView，不引库）', () {
    const b2 = ShopBanner(imageUrl: 'https://example.test/2.jpg', imageW: 1600, imageH: 900, bannerId: 'bn2');
    const b3 = ShopBanner(imageUrl: 'https://example.test/3.jpg', imageW: 1600, imageH: 900, bannerId: 'bn3');

    int page(WidgetTester t) =>
        t.widget<PageView>(find.byKey(const ValueKey('tokoBannerPageView'))).controller!.page!.round();

    testWidgets('多张：每 4 秒自动翻页 + 页码点', (t) async {
      await pump(t, banners: const [_banner169, b2, b3]);
      expect(find.byKey(const ValueKey('tokoBannerDots')), findsOneWidget);
      expect(page(t), 0);
      await t.pump(const Duration(seconds: 4));
      await t.pumpAndSettle();
      expect(page(t), 1);
      await t.pump(const Duration(seconds: 4));
      await t.pumpAndSettle();
      expect(page(t), 2);
      await t.pump(const Duration(seconds: 4));
      await t.pumpAndSettle();
      expect(page(t), 0, reason: '末张后回到第一张');
    });

    testWidgets('手动滑动 → 从当前页重新计时', (t) async {
      await pump(t, banners: const [_banner169, b2, b3]);
      await t.pump(const Duration(seconds: 3));
      await t.drag(find.byKey(const ValueKey('tokoBannerPageView')), const Offset(-500, 0));
      await t.pumpAndSettle();
      expect(page(t), 1);
      await t.pump(const Duration(seconds: 2));
      await t.pumpAndSettle();
      expect(page(t), 1, reason: '原计时不应在手滑后 1 秒就翻页');
      await t.pump(const Duration(seconds: 2));
      await t.pumpAndSettle();
      expect(page(t), 2);
    });

    testWidgets('只有 1 张：不自动轮播、不画页码点', (t) async {
      await pump(t, banners: const [_banner169]);
      expect(find.byKey(const ValueKey('tokoBannerDots')), findsNothing);
      await t.pump(const Duration(seconds: 9));
      await t.pumpAndSettle();
      expect(page(t), 0);
    });

    testWidgets('TickerMode 关（页面不在前台）→ 暂停自动轮播', (t) async {
      final ticker = ValueNotifier(true);
      await pump(t, banners: const [_banner169, b2], ticker: ticker);
      ticker.value = false;
      await t.pump();
      await t.pump(const Duration(seconds: 9));
      await t.pump(const Duration(seconds: 1));
      ticker.value = true;
      await t.pump();
      expect(page(t), 0);
    });
  });

  group('banner 点击跳转（AC2.4 / A5 领券）', () {
    testWidgets('PRODUCT → /shop/products/{token}?from=BANNER', (t) async {
      final h = await pump(t, banners: const [
        ShopBanner(imageUrl: _bannerUrl, bannerId: 'bp', target: ShopBannerTarget.product, targetValue: 'pT1')
      ]);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/shop/products/pT1?from=BANNER']);
      expect(ev('toko_banner_tapped').single, {'banner_id': 'bp', 'target_type': 'product_detail', 'position': 1});
    });

    testWidgets('NONE 不可点', (t) async {
      final h = await pump(t, banners: const [_banner169]);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pumpAndSettle();
      expect(h.pushed, isEmpty);
      expect(ev('toko_banner_tapped'), isEmpty);
    });

    const couponBanner = ShopBanner(
        imageUrl: _bannerUrl, bannerId: 'bc', target: ShopBannerTarget.coupon, targetValue: 'cpBanner');

    testWidgets('A5 已登录领券成功：toast + toko_coupon_claimed(banner) + source=BANNER', (t) async {
      final repo = FakeCouponRepository();
      await pump(t, banners: const [couponBanner], loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      await t.pump();
      expect(repo.claims.single, ('cpBanner', CouponClaimSource.banner));
      expect(find.text('Kupon diklaim. Cek di Kupon Saya'), findsOneWidget);
      expect(ev('toko_coupon_claimed').single, {'coupon_id': 'cpBanner', 'source': 'banner'});
      expect(ev('toko_banner_tapped').single?['target_type'], 'coupon');
      await t.pump(const Duration(seconds: 3));
    });

    testWidgets('A5 已领过 → 「Kamu sudah klaim kupon ini」，不报 claimed', (t) async {
      final repo = FakeCouponRepository(claimError: problemError('coupon-already-claimed'));
      await pump(t, banners: const [couponBanner], loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      await t.pump();
      expect(find.text('Kamu sudah klaim kupon ini'), findsOneWidget);
      expect(ev('toko_coupon_claimed'), isEmpty);
      await t.pump(const Duration(seconds: 3));
    });

    testWidgets('A5 领完 → 「Kupon sudah habis」', (t) async {
      final repo = FakeCouponRepository(claimError: problemError('coupon-sold-out'));
      await pump(t, banners: const [couponBanner], loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      await t.pump();
      expect(find.text('Kupon sudah habis'), findsOneWidget);
      await t.pump(const Duration(seconds: 3));
    });

    testWidgets('A5 游客 → 软登录浮层，登录后自动领取一次', (t) async {
      final repo = FakeCouponRepository();
      final guide = _FakeGuide();
      await pump(t, banners: const [couponBanner], coupons: repo, guide: guide);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      expect(guide.shown, 1);
      expect(repo.claims, isEmpty, reason: '游客不直接打 /me 领券接口');
      guide.pending!.onResume!();
      await t.pump();
      await t.pump();
      expect(repo.claims.single.$1, 'cpBanner');
      await t.pump(const Duration(seconds: 3));
    });
  });

  // ------------------------------------------------------------------ A3 给你的券
  group('A3「Kupon buat kamu」', () {
    testWidgets('游客不渲染（也不请求）', (t) async {
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1')]);
      await pump(t, coupons: repo);
      expect(find.text('Kupon buat kamu'), findsNothing);
    });

    testWidgets('已登录但空 → 整块不出现', (t) async {
      await pump(t, loggedIn: true, coupons: FakeCouponRepository());
      expect(find.byKey(const ValueKey('couponsForYouRow')), findsNothing);
    });

    testWidgets('Klaim → Diklaim + toast + toko_coupon_claimed(home)；已领的显示 Diklaim', (t) async {
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1'), _coupon('c2', claimed: true)]);
      await pump(t, loggedIn: true, coupons: repo);
      expect(find.text('Kupon buat kamu'), findsOneWidget);
      expect(find.descendant(of: find.byKey(const ValueKey('couponClaim_c2')), matching: find.text('Diklaim')),
          findsOneWidget);
      await t.tap(find.byKey(const ValueKey('couponClaim_c1')));
      await t.pump();
      await t.pump();
      expect(repo.claims.single, ('c1', CouponClaimSource.home));
      expect(find.text('Kupon diklaim. Cek di Kupon Saya'), findsOneWidget);
      expect(ev('toko_coupon_claimed').single, {'coupon_id': 'c1', 'source': 'home'});
      await t.pumpAndSettle(const Duration(seconds: 3));
      expect(find.descendant(of: find.byKey(const ValueKey('couponClaim_c1')), matching: find.text('Diklaim')),
          findsOneWidget);
    });

    testWidgets('新人券置顶紫底「KHUSUS PENGGUNA BARU」+「Sudah masuk」，点券面进 /me/coupons', (t) async {
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1'), _coupon('nb', newbie: true)]);
      final h = await pump(t, loggedIn: true, coupons: repo);
      expect(find.text('KHUSUS PENGGUNA BARU'), findsOneWidget);
      expect(find.text('Sudah masuk'), findsOneWidget);
      final nb = t.getTopLeft(find.byKey(const ValueKey('couponForYou_nb'))).dx;
      final c1 = t.getTopLeft(find.byKey(const ValueKey('couponForYou_c1'))).dx;
      expect(nb, lessThan(c1), reason: '新人券置顶（最左）');
      expect(find.byType(Dialog), findsNothing, reason: '11-05：不弹到账弹窗');
      await t.tap(find.byKey(const ValueKey('couponForYou_nb')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/me/coupons']);
    });

    testWidgets('右上「Kupon Saya ›」进 /me/coupons', (t) async {
      final h = await pump(t, loggedIn: true, coupons: FakeCouponRepository(forYouItems: [_coupon('c1')]));
      await t.tap(find.byKey(const ValueKey('couponsForYouMyCoupons')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/me/coupons']);
    });
  });

  // ------------------------------------------------------------------ A4 / entry
  group('AC4 进入 Toko：已登录发新人券 / 游客弹窗', () {
    const campaign = NewbieCampaign(campaignToken: 'nbCamp', amount: 25000, minSpend: 100000);

    testWidgets('游客首次进入 → 弹 A4 + popup_shown，并记入 AppPrefs', (t) async {
      await pump(t, coupons: FakeCouponRepository(campaign: campaign));
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsOneWidget);
      expect(find.text('Daftar sekarang, dapat kupon Rp 25.000'), findsOneWidget);
      expect(find.text('Khusus pengguna baru · min. belanja Rp 100.000'), findsOneWidget);
      expect(ev('toko_newbie_coupon_popup_shown').single, {'coupon_id': 'nbCamp'});
      final prefs = await AppPrefs.create();
      expect(prefs.shownNewbieCampaigns, contains('nbCamp'));
    });

    testWidgets('关闭 → tapped(close)，同设备同活动不再弹', (t) async {
      final repo = FakeCouponRepository(campaign: campaign);
      final ticker = ValueNotifier(true);
      await pump(t, coupons: repo, ticker: ticker);
      await t.tap(find.byKey(const ValueKey('newbieCouponPopupClose')));
      await t.pumpAndSettle();
      expect(ev('toko_newbie_coupon_popup_tapped').single, {'coupon_id': 'nbCamp', 'action': 'close'});
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsNothing);
      // 离开再回来（切 Tab）
      ticker.value = false;
      await t.pump();
      ticker.value = true;
      await t.pumpAndSettle();
      expect(repo.campaignCalls, 2);
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsNothing);
      expect(ev('toko_newbie_coupon_popup_shown'), hasLength(1));
    });

    testWidgets('「Daftar & klaim」→ tapped(register) + 进 /login', (t) async {
      final h = await pump(t, coupons: FakeCouponRepository(campaign: campaign));
      await t.tap(find.byKey(const ValueKey('newbieCouponPopupCta')));
      await t.pumpAndSettle();
      expect(ev('toko_newbie_coupon_popup_tapped').single, {'coupon_id': 'nbCamp', 'action': 'register'});
      expect(h.pushed, ['/login']);
    });

    testWidgets('无活动（204）→ 不弹', (t) async {
      final repo = FakeCouponRepository();
      await pump(t, coupons: repo);
      expect(repo.campaignCalls, 1);
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsNothing);
      expect(ev('toko_newbie_coupon_popup_shown'), isEmpty);
    });

    testWidgets('已登录不请求游客活动、不弹窗；调 /me/shop/entry', (t) async {
      final repo = FakeCouponRepository(campaign: campaign);
      await pump(t, coupons: repo, loggedIn: true);
      expect(repo.campaignCalls, 0);
      expect(repo.entryCalls, 1);
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsNothing);
    });

    testWidgets('🔴 /me/shop/entry 60 秒内不重复；超过 60 秒切回 Tab 再调', (t) async {
      var now = DateTime(2026, 11, 5, 10);
      shopEntryClock = () => now;
      final repo = FakeCouponRepository();
      final ticker = ValueNotifier(true);
      await pump(t, coupons: repo, loggedIn: true, ticker: ticker);
      expect(repo.entryCalls, 1);
      ticker.value = false;
      await t.pump();
      now = now.add(const Duration(seconds: 30));
      ticker.value = true;
      await t.pumpAndSettle();
      expect(repo.entryCalls, 1, reason: '60 秒内切回不重复调');
      ticker.value = false;
      await t.pump();
      now = now.add(const Duration(seconds: 31));
      ticker.value = true;
      await t.pumpAndSettle();
      expect(repo.entryCalls, 2);
    });

    testWidgets('entry 返回已发新人券 → toko_coupon_claimed(newbie, coupon_id=新人券模板 token)', (t) async {
      final repo = FakeCouponRepository(entryIssued: true, forYouItems: [_coupon('nbT', newbie: true)]);
      await pump(t, coupons: repo, loggedIn: true);
      expect(ev('toko_coupon_claimed').single, {'coupon_id': 'nbT', 'source': 'newbie'});
    });

    testWidgets('entry 未发券 → 不报 claimed', (t) async {
      await pump(t, coupons: FakeCouponRepository(), loggedIn: true);
      expect(ev('toko_coupon_claimed'), isEmpty);
    });
  });

  // ------------------------------------------------------------------ A1 / A9 推荐
  group('A1 / A9 为宠物推荐', () {
    testWidgets('有档案：栏头「Untuk Mochi」+ 依据「Anjing kecil · dewasa」，三列最多 6 个', (t) async {
      await pump(t, loggedIn: true, reco: _reco(items: 8));
      expect(find.text('Untuk Mochi'), findsOneWidget);
      expect(find.text('Anjing kecil · dewasa'), findsOneWidget);
      expect(find.byWidgetPredicate((w) => w.key is ValueKey && '${(w.key as ValueKey).value}'.startsWith('shopRecoCard_')),
          findsNWidgets(6));
      expect(find.text('Untuk anjing dewasa 10–25 kg'), findsNothing, reason: '不显示单个商品推荐理由');
      expect(ev('toko_product_shown').where((p) => p?['zone'] == 'profile_reco'), hasLength(6));
    });

    testWidgets('点推荐卡 → toko_profile_reco_tapped + ?from=PROFILE_RECO', (t) async {
      final h = await pump(t, loggedIn: true, reco: _reco(items: 1));
      await t.tap(find.byKey(const ValueKey('shopProductCard_r0')));
      await t.pumpAndSettle();
      expect(ev('toko_profile_reco_tapped').single, {'zone': 'profile_reco', 'product_id': 'r0'});
      expect(h.pushed, ['/shop/products/r0?from=PROFILE_RECO']);
    });

    testWidgets('A9 未填体重：依据行加「berat belum diisi」+ 提示条进 /profile/edit', (t) async {
      final h = await pump(t, loggedIn: true, reco: _reco(size: null, weightMissing: true));
      expect(find.text('Anjing · dewasa · berat belum diisi'), findsOneWidget);
      await t.ensureVisible(find.byKey(const ValueKey('shopRecoAddWeight')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const ValueKey('shopRecoAddWeight')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/profile/edit']);
    });

    testWidgets('无档案（profileSummary == null）→ 首页整块不显示', (t) async {
      await pump(t,
          loggedIn: true,
          reco: const Recommendations(degraded: true, missing: 'PROFILE', items: []));
      expect(find.byKey(const ValueKey('shopRecoGridProfile')), findsNothing);
      expect(find.byKey(const ValueKey('shopRecoGridTop')), findsNothing, reason: '首页不出「Mungkin kamu suka」');
    });

    test('依据行：ID / EN 两种语序、size / age 缺省', () {
      final id = AppLocalizationsId();
      final en = AppLocalizationsEn();
      const full = ProfileSummary(petName: 'M', species: 'DOG', sizeClass: 'SMALL', ageStage: 'ADULT');
      expect(recoBasisLine(id, full), 'Anjing kecil · dewasa');
      expect(recoBasisLine(en, full), 'Small dog · adult');
      const noSize = ProfileSummary(petName: 'M', species: 'CAT', ageStage: 'SENIOR');
      expect(recoBasisLine(id, noSize), 'Kucing · senior');
      expect(recoBasisLine(en, noSize), 'Cat · senior');
      const noAge = ProfileSummary(petName: 'M', species: 'DOG', sizeClass: 'LARGE', weightMissing: false);
      expect(recoBasisLine(id, noAge), 'Anjing besar');
      const a9 = ProfileSummary(petName: 'M', species: 'DOG', ageStage: 'ADULT', weightMissing: true);
      expect(recoBasisLine(id, a9), 'Anjing · dewasa · berat belum diisi');
      expect(recoBasisLine(en, a9), 'Dog · adult · weight not added');
    });

    testWidgets('EN 栏头「For Mochi」', (t) async {
      await pump(t, loggedIn: true, reco: _reco(), locale: const Locale('en'));
      expect(find.text('For Mochi'), findsOneWidget);
      expect(find.text('Small dog · adult'), findsOneWidget);
    });
  });

  // ------------------------------------------------------------------ A1 全部商品
  group('A1 全部商品 / A8', () {
    testWidgets('段头「Semua produk」；无 totalCount 不显示件数', (t) async {
      await pump(t);
      expect(find.text('Semua produk'), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoProductCount')), findsNothing);
    });

    testWidgets('「Lagi promo」角标仅 promo=true', (t) async {
      await pump(t, products: [_p('a', price: 1000, promo: true), _p('b', price: 1000)]);
      expect(find.text('Lagi promo'), findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const ValueKey('shopProductCard_a')),
              matching: find.byKey(const ValueKey('shopProductPromoBadge'))),
          findsOneWidget);
    });

    testWidgets('卡片点击 ?from=TOKO_ALL_FEATURED；曝光 zone=all_featured', (t) async {
      final h = await pump(t, products: [_p('a', price: 1000)]);
      expect(ev('toko_product_shown').single, {'product_token': 'a', 'zone': 'all_featured'});
      await t.tap(find.byKey(const ValueKey('shopProductCard_a')));
      await t.pumpAndSettle();
      expect(h.pushed, ['/shop/products/a?from=TOKO_ALL_FEATURED']);
    });

    testWidgets('A8：首屏失败 → 失败卡 + 重试；banner 照常显示', (t) async {
      phone(t, const Size(411, 891));
      await pump(t, productsFail: true, banners: const [_banner169]);
      expect(find.byKey(const ValueKey('tokoProductsFailed')), findsOneWidget);
      expect(find.text('Gagal memuat produk.'), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off), findsOneWidget);
      expect(find.byKey(const ValueKey('tokoBannerCarousel')), findsOneWidget);
      final before = _FailingList.builds;
      await t.tap(find.text('Coba lagi'));
      await t.pumpAndSettle();
      expect(_FailingList.builds, greaterThan(before));
    });
  });

  group('🔴 触底预加载：竖向触发，横向不得触发（迁移自 v2）', () {
    List<ShopProductSummary> many(int n) => [for (var i = 0; i < n; i++) _p('t$i', price: 100000 + i, name: 'P $i')];

    testWidgets('🎯 竖向滚动到接近底部 → 取下一页', (t) async {
      final calls = <ShopProductsQuery>[];
      await pump(t,
          products: many(10), hasMore: true, nextPageItems: [_p('next', price: 9000, name: 'Halaman Dua')],
          loadMoreCalls: calls);
      expect(calls, isEmpty);
      await t.drag(find.byType(CustomScrollView), const Offset(0, -5000));
      await t.pumpAndSettle();
      expect(calls, isNotEmpty);
      await t.drag(find.byType(CustomScrollView), const Offset(0, -5000));
      await t.pumpAndSettle();
      expect(find.text('Halaman Dua'), findsOneWidget);
    });

    testWidgets('🎯 横滑 banner 轮播**不得**触发预加载（depth + 轴向）', (t) async {
      final calls = <ShopProductsQuery>[];
      await pump(t,
          products: many(10),
          banners: const [
            _banner169,
            ShopBanner(imageUrl: 'https://example.test/2.jpg', imageW: 1600, imageH: 900, bannerId: 'bn2'),
          ],
          hasMore: true,
          loadMoreCalls: calls);
      await t.drag(find.byKey(const ValueKey('tokoBannerPageView')), const Offset(-300, 0));
      await t.pumpAndSettle();
      expect(calls, isEmpty);
    });
  });

  // ------------------------------------------------------------------ 复审修复（8.1 code review）
  group('复审修复', () {
    ShopButton claimBtn(WidgetTester t, String token) =>
        t.widget<ShopButton>(find.byKey(ValueKey('couponClaim_$token')));

    testWidgets('#1「Diklaim」只取服务端值；换账号（列表重拉）后服务端可领 → 按钮恢复「Klaim」', (t) async {
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1')]);
      await pump(t, loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('couponClaim_c1')));
      await t.pumpAndSettle(const Duration(seconds: 3));
      expect(claimBtn(t, 'c1').label, 'Diklaim');

      // 模拟换账号：resetUserScopedCaches invalidate 列表，新账号服务端为未领。
      repo.forYouItems = [_coupon('c1')];
      ProviderScope.containerOf(t.element(find.byType(TokoPageV3))).invalidate(couponsForYouProvider);
      await t.pumpAndSettle();
      expect(claimBtn(t, 'c1').label, 'Klaim', reason: '本地「已领」集合会跨账号残留');
      expect(claimBtn(t, 'c1').onTap, isNotNull);
    });

    testWidgets('#1 perUserLimit > 1：领后服务端仍可领 → 按钮仍「Klaim」（不被本地永久锁）', (t) async {
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1')])..claimMarksClaimed = false;
      await pump(t, loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('couponClaim_c1')));
      await t.pumpAndSettle(const Duration(seconds: 3));
      expect(claimBtn(t, 'c1').label, 'Klaim');
      expect(claimBtn(t, 'c1').onTap, isNotNull);
    });

    testWidgets('#1 在途期间按钮转圈、重复点不再发请求', (t) async {
      final gate = Completer<void>();
      final repo = FakeCouponRepository(forYouItems: [_coupon('c1')])..claimGate = gate;
      await pump(t, loggedIn: true, coupons: repo);
      await t.tap(find.byKey(const ValueKey('couponClaim_c1')));
      await t.pump();
      expect(claimBtn(t, 'c1').loading, isTrue);
      await t.tap(find.byKey(const ValueKey('couponClaim_c1')), warnIfMissed: false);
      await t.pump();
      expect(repo.claims, hasLength(1));
      gate.complete();
      await t.pumpAndSettle(const Duration(seconds: 3));
      expect(claimBtn(t, 'c1').loading, isFalse);
    });

    testWidgets('#2 banner 领券在途：超过 800ms 再点同一张也不重复领', (t) async {
      final gate = Completer<void>();
      final repo = FakeCouponRepository()..claimGate = gate;
      await pump(t,
          loggedIn: true,
          coupons: repo,
          banners: const [
            ShopBanner(imageUrl: _bannerUrl, bannerId: 'bc', target: ShopBannerTarget.coupon, targetValue: 'cpB')
          ]);
      // 连点去重用真实时钟：注入可控时钟，确保第二次点击过了 800ms 窗口，测的是在途锁而非去重。
      var now = DateTime(2026, 11, 5, 10);
      shopBannerTapClock = () => now;
      addTearDown(() => shopBannerTapClock = DateTime.now);
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      now = now.add(const Duration(seconds: 1));
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pump();
      expect(repo.claims, hasLength(1));
      expect(ev('toko_banner_tapped'), hasLength(2), reason: '确认第二次点击确实越过了 800ms 去重');
      gate.complete();
      await t.pumpAndSettle(const Duration(seconds: 3));
      // 解锁后可再领（服务端判已领）。
      now = now.add(const Duration(seconds: 1));
      await t.tap(find.byKey(const ValueKey('tokoBannerImage_0')));
      await t.pumpAndSettle(const Duration(seconds: 3));
      expect(repo.claims, hasLength(2));
    });

    testWidgets('#3 拉活动期间页面切走 → 不记「弹过」也不弹；回来后照常弹一次', (t) async {
      final gate = Completer<void>();
      final repo = FakeCouponRepository(
          campaign: const NewbieCampaign(campaignToken: 'nbCamp', amount: 25000, minSpend: 0))
        ..campaignGate = gate;
      final ticker = ValueNotifier(true);
      await pump(t, coupons: repo, ticker: ticker);
      ticker.value = false;
      await t.pump();
      gate.complete();
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsNothing);
      expect((await AppPrefs.create()).shownNewbieCampaigns, isNot(contains('nbCamp')),
          reason: '记了却没弹 = 该活动在本设备永远不再弹');

      repo.campaignGate = null;
      ticker.value = true;
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('newbieCouponPopup')), findsOneWidget);
      expect((await AppPrefs.create()).shownNewbieCampaigns, contains('nbCamp'));
      expect(ev('toko_newbie_coupon_popup_shown'), hasLength(1));
    });

    const b2 = ShopBanner(imageUrl: 'https://example.test/2.jpg', imageW: 1600, imageH: 900, bannerId: 'bn2');
    int page(WidgetTester t) =>
        t.widget<PageView>(find.byKey(const ValueKey('tokoBannerPageView'))).controller!.page!.round();

    testWidgets('#4 App 进后台（paused）停止自动轮播，resumed 后重新计时', (t) async {
      await pump(t, banners: const [_banner169, b2]);
      t.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await t.pump();
      await t.pump(const Duration(seconds: 9));
      await t.pumpAndSettle();
      expect(page(t), 0);
      t.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await t.pump();
      await t.pump(const Duration(seconds: 3));
      await t.pumpAndSettle();
      expect(page(t), 0, reason: 'resumed 后从头计 4 秒');
      await t.pump(const Duration(seconds: 1));
      await t.pumpAndSettle();
      expect(page(t), 1);
    });

    testWidgets('#4 用户按住拖动期间不自动翻页，松手后从当前页重新计时', (t) async {
      await pump(t, banners: const [_banner169, b2]);
      final g = await t.startGesture(t.getCenter(find.byKey(const ValueKey('tokoBannerPageView'))));
      await g.moveBy(const Offset(-20, 0));
      await t.pump();
      await g.moveBy(const Offset(-20, 0));
      await t.pump(const Duration(seconds: 5));
      await t.pump(const Duration(milliseconds: 500));
      expect(page(t), 0, reason: '拖动中计时器仍在跑会把页面抢走');
      await g.up();
      await t.pumpAndSettle();
      expect(page(t), 0);
      await t.pump(const Duration(seconds: 4));
      await t.pumpAndSettle();
      expect(page(t), 1);
    });

    testWidgets('#5 toko_product_shown 页面级去重：滚走再滚回不重复；离开再回来重新计', (t) async {
      final ticker = ValueNotifier(true);
      await pump(t,
          products: [for (var i = 0; i < 40; i++) _p('t$i', price: 1000, name: 'P $i')], ticker: ticker);
      int count() => ev('toko_product_shown').where((p) => p?['product_token'] == 't0').length;
      expect(count(), 1);
      await t.drag(find.byType(CustomScrollView), const Offset(0, -4000));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopProductCard_t0')), findsNothing, reason: '确认卡片真的被回收了');
      await t.drag(find.byType(CustomScrollView), const Offset(0, 6000));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopProductCard_t0')), findsOneWidget);
      expect(count(), 1, reason: '惰性网格重建卡片不得重复报曝光');

      ticker.value = false;
      await t.pump();
      ticker.value = true;
      await t.pumpAndSettle();
      await t.drag(find.byType(CustomScrollView), const Offset(0, -4000));
      await t.pumpAndSettle();
      await t.drag(find.byType(CustomScrollView), const Offset(0, 6000));
      await t.pumpAndSettle();
      expect(count(), 2, reason: '离开页面再回来按 banner 曝光同一规则重新计');
    });
  });
}
