import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/presentation/widgets/sku_picker_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 规格弹层（v1.4.2 shop Story 8.1 AC7，UI B9 / B10 / B5）。
class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

class _FakeCart extends CartController {
  final added = <({String sku, int qty, String? entrySource, bool buyNow})>[];
  bool failStock = false;

  @override
  Future<CartView> build() async => CartView.empty;

  @override
  Future<void> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false}) async {
    added.add((sku: skuToken, qty: qty, entrySource: entrySource, buyNow: buyNow));
    if (failStock) throw CartMutationError.stock;
  }
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

ShopSku _sku(String t,
        {int price = 100000,
        StockStatus stock = StockStatus.inStock,
        int? remaining,
        bool sellable = true}) =>
    ShopSku(
      token: t,
      specName: 'Spec $t',
      price: price,
      returnPolicy: ReturnPolicy.returnable,
      stockStatus: stock,
      remaining: remaining,
      sellable: sellable,
    );

ShopProductDetail _detail(List<ShopSku> skus) => ShopProductDetail(
      token: 'prod1',
      name: 'Royal Canin',
      brand: 'RC',
      returnPolicy: ReturnPolicy.returnable,
      skus: skus,
    );

void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<({_FakeCart cart, List<String> pushed})> pump(
    WidgetTester t,
    List<ShopSku> skus, {
    SkuPickerMode mode = SkuPickerMode.addToCart,
    bool loggedIn = true,
    LoginGuideController? guide,
    _FakeCart? cart,
  }) async {
    final c = cart ?? _FakeCart();
    final pushed = <String>[];
    final router = GoRouter(routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => Scaffold(
          body: Consumer(
            builder: (context, ref, _) => Center(
              child: TextButton(
                key: const ValueKey('open'),
                onPressed: () => showSkuPickerSheet(context, ref,
                    productToken: 'prod1', mode: mode, entrySource: 'TOKO_ALL_FEATURED'),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/shop/checkout',
        builder: (_, s) {
          pushed.add(s.uri.toString());
          return const Scaffold(body: Text('checkout'));
        },
      ),
    ]);
    await t.pumpWidget(ProviderScope(
      overrides: [
        shopProductDetailProvider.overrideWith((ref, token) async => _detail(skus)),
        cartProvider.overrideWith(() => c),
        if (loggedIn) authControllerProvider.overrideWith(_LoggedInAuth.new),
        if (guide != null) loginGuideControllerProvider.overrideWithValue(guide),
      ],
      child: MaterialApp.router(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        routerConfig: router,
      ),
    ));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('open')));
    await t.pumpAndSettle();
    return (cart: c, pushed: pushed);
  }

  testWidgets('只有一个 SKU 也弹（为了选数量）', (t) async {
    await pump(t, [_sku('a')]);
    expect(find.byKey(const ValueKey('skuPickerSheet')), findsOneWidget);
    expect(find.text('Pilih Varian'), findsOneWidget);
    expect(find.text('Jumlah'), findsOneWidget);
    expect(find.text('Dipilih: Spec a'), findsOneWidget);
  });

  testWidgets('默认规格：有货 > 低价 > 后台顺序（售罄不默认选中）', (t) async {
    await pump(t, [
      _sku('out', price: 10000, stock: StockStatus.outOfStock),
      _sku('b', price: 90000),
      _sku('c', price: 70000),
      _sku('d', price: 70000),
    ]);
    expect(find.text('Dipilih: Spec c'), findsOneWidget);
    expect(find.text('Rp 70.000'), findsOneWidget);
  });

  test('defaultShopSku 共享函数：全部售罄 → null', () {
    expect(defaultShopSku([_sku('x', stock: StockStatus.outOfStock)]), isNull);
    expect(defaultShopSku([_sku('a', price: 5), _sku('b', price: 5)])!.token, 'a');
  });

  testWidgets('步进器上限 = SKU remaining（LOW_STOCK）', (t) async {
    await pump(t, [_sku('a', stock: StockStatus.lowStock, remaining: 2)]);
    expect(find.text('Sisa 2'), findsOneWidget);
    await t.tap(find.byKey(const ValueKey('stepperInc')));
    await t.pump();
    await t.tap(find.byKey(const ValueKey('stepperInc')));
    await t.pump();
    final stepper = find.byKey(const ValueKey('skuPickerStepper'));
    expect(find.descendant(of: stepper, matching: find.text('2')), findsOneWidget,
        reason: '到上限后 + 不再生效');
  });

  testWidgets('售罄规格仍可点；选中后确认键置灰', (t) async {
    final r = await pump(t, [_sku('a'), _sku('out', stock: StockStatus.outOfStock)]);
    await t.tap(find.byKey(const ValueKey('skuChip_out')));
    await t.pump();
    expect(find.text('Dipilih: Spec out'), findsOneWidget);
    await t.tap(find.byKey(const ValueKey('skuPickerConfirm')));
    await t.pumpAndSettle();
    expect(r.cart.added, isEmpty);
    expect(find.byKey(const ValueKey('skuPickerSheet')), findsOneWidget);
  });

  testWidgets('sellable=false → 确认键「Belum bisa dijual」不可点', (t) async {
    final r = await pump(t, [_sku('a', sellable: false)]);
    expect(find.descendant(of: find.byKey(const ValueKey('skuPickerConfirm')), matching: find.text('Belum bisa dijual')),
        findsOneWidget);
    await t.tap(find.byKey(const ValueKey('skuPickerConfirm')));
    await t.pumpAndSettle();
    expect(r.cart.added, isEmpty);
  });

  testWidgets('addToCart 成功：带数量与归因 + toast + 埋点', (t) async {
    final r = await pump(t, [_sku('a')]);
    await t.tap(find.byKey(const ValueKey('stepperInc')));
    await t.pump();
    await t.tap(find.text('Tambah ke keranjang'));
    await t.pumpAndSettle();
    expect(r.cart.added.single, (sku: 'a', qty: 2, entrySource: 'TOKO_ALL_FEATURED', buyNow: false));
    expect(find.text('Ditambahkan ke keranjang'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'toko_add_to_cart_tapped').single.$2,
        {'product_token': 'prod1', 'sku_token': 'a', 'qty': 2, 'unit_price': 100000});
    await t.pump(const Duration(seconds: 3));
  });

  testWidgets('addToCart 库存失败 → cartStockError toast', (t) async {
    final cart = _FakeCart()..failStock = true;
    await pump(t, [_sku('a')], cart: cart);
    await t.tap(find.text('Tambah ke keranjang'));
    await t.pumpAndSettle();
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.cartStockError), findsOneWidget);
    await t.pump(const Duration(seconds: 3));
  });

  testWidgets('游客：软登录浮层，登录后自动完成本次加购', (t) async {
    final guide = _FakeGuide();
    final r = await pump(t, [_sku('a')], loggedIn: false, guide: guide);
    await t.tap(find.text('Tambah ke keranjang'));
    await t.pumpAndSettle();
    expect(guide.shown, 1);
    expect(r.cart.added, isEmpty);
    guide.pending!.onResume!();
    await t.pumpAndSettle();
    expect(r.cart.added.single.sku, 'a');
    await t.pump(const Duration(seconds: 3));
  });

  testWidgets('buyNow：直进 /shop/checkout?source=buy_now&sku=…&qty=…，不写购物车', (t) async {
    final r = await pump(t, [_sku('a')], mode: SkuPickerMode.buyNow);
    await t.tap(find.byKey(const ValueKey('stepperInc')));
    await t.pump();
    await t.tap(find.text('Beli Sekarang'));
    await t.pumpAndSettle();
    // v1.4.2 8.2 复审 #1（按设计改写）：立即购买同样带归因来源 `&from=`（与加购 entrySource 同值域）。
    expect(r.pushed, ['/shop/checkout?source=buy_now&sku=a&qty=2&from=TOKO_ALL_FEATURED']);
    expect(r.cart.added, isEmpty);
    expect(events.where((e) => e.$1 == 'toko_buy_now_tapped'), hasLength(1));
    // analytics-pipeline Story 3.3：立即购买分支属性保持原样（不带 qty / unit_price）。
    expect(events.where((e) => e.$1 == 'toko_buy_now_tapped').single.$2!.keys,
        unorderedEquals(['product_token', 'sku_token']));
  });
}
