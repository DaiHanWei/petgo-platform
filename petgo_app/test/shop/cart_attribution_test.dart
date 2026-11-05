import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/toko_page_v3.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'fake_shop_products.dart';

// v2 详情页加购按钮的印尼语文案（AppLocalizationsId.tokoAddToCartShort）。
// ⚠️ 两个测试都固定跑 Locale('id')，故直接用字面量；文案改了这里会红，正是想要的信号。
const String _kAddToCartId = '+ Keranjang';


/// Story 3.10：归因链闭合（AB-13B / A-16）。
///
/// 🔴 **这条链断在任何一环，AB-13B 都算不出「触发卡转化率 vs 普通曝光转化率」** ——
/// 而那个数字决定的是下一个版本做不做复购引擎。所以本类看的是**真实的请求参数**，
/// 不是源码里有没有出现某个单词：
/// 变异验证 H3（删掉 `'entrySource': ?entrySource` 这一行）曾让「源码含 entrySource」
/// 那条断言照样绿 —— 因为方法签名里还有这个词。**整文件 contains 是假绿的常见做法。**
void main() {
  group('🔴 请求真的带上了来源', () {
    late _CaptureAdapter adapter;
    late CartRepository repo;

    setUp(() {
      adapter = _CaptureAdapter();
      repo = CartRepository(dio: Dio()..httpClientAdapter = adapter);
    });

    test('带 entrySource 加购 → 查询参数里确实有它', () async {
      await repo.add('sku-1', entrySource: 'TOKO_ALL_FEATURED');

      expect(adapter.lastQuery?['skuToken'], 'sku-1');
      expect(adapter.lastQuery?['entrySource'], 'TOKO_ALL_FEATURED');
    });

    test('🔴 没有来源就不发这个参数 —— 写 null 是诚实的「未知」，编一个值会污染看板', () async {
      await repo.add('sku-1');

      expect(adapter.lastQuery?.containsKey('entrySource'), isFalse);
      expect(adapter.lastQuery?.containsKey('triggerType'), isFalse);
    });

    test('🔴 立即购买带 buyNow=true（code review #4：只结这一件）；普通加购不带', () async {
      await repo.add('sku-1', buyNow: true);
      expect(adapter.lastQuery?['buyNow'], true);

      await repo.add('sku-1');
      expect(adapter.lastQuery?.containsKey('buyNow'), isFalse, reason: '普通加购不带，旧后端行为不变');
    });

    test('triggerType 同样按需带（Epic 6 复购触发会用到）', () async {
      await repo.add('sku-1', entrySource: 'PROFILE_RECOMMEND', triggerType: 'REFILL');

      expect(adapter.lastQuery?['triggerType'], 'REFILL');
    });
  });

  group('🔴 Toko → 详情页 → 加购：来源一路传下去', () {
    testWidgets('详情页把路由 ?from= 带进加购请求', (t) async {
      final repo = _RecordingCartRepo();
      final router = GoRouter(
        initialLocation: '/shop/products/tok?from=TOKO_CATEGORY',
        routes: [
          GoRoute(
            path: '/shop/products/:token',
            builder: (c, s) => ProductDetailPageV2(
              token: s.pathParameters['token']!,
              entrySource: s.uri.queryParameters['from'],
            ),
          ),
          GoRoute(path: '/shop/cart', builder: (c, s) => const Scaffold(body: Text('CART'))),
        ],
      );

      await t.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          shopProductDetailProvider.overrideWith((ref, token) async => _detail),
          cartRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
        ),
      ));
      await t.pumpAndSettle();

      // ⚠️ 同上：按文案定位，避免点到旁边的「立即购买」。
      await t.tap(find.widgetWithText(ShopButton, _kAddToCartId));
      await t.pumpAndSettle();

      expect(repo.lastEntrySource, 'TOKO_CATEGORY');

      await t.pump(const Duration(seconds: 3));
      await t.pumpAndSettle();
    });

    testWidgets('🔴 Toko 卡片：品类入口下线后恒为全部精选入口（v1.4.2 shop 8.1 按设计改写）', (t) async {
      await t.pumpWidget(ProviderScope(
        overrides: [
          // banner / 券 / top 同样必须 override —— 真 provider 会发请求并留下未完成 Timer。
          ...tokoHomeOverrides(),
          fakeShopProducts(const [
            ShopProductSummary(
                token: 'p1', name: 'Produk', brand: 'B', minPrice: 285000),
          ]),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Builder(
            builder: (context) => const TokoPageV3(),
          ),
        ),
      ));
      await t.pumpAndSettle();
      expect(find.byKey(const ValueKey('shopProductCard_p1')), findsOneWidget);

      // 原断言「未选品类 → TOKO_ALL_FEATURED / 选了品类 → TOKO_CATEGORY」随品类筛选下线（D-17）改写：
      // 首页商品流只有「全部」，归因恒为 TOKO_ALL_FEATURED，源码里不再出现 TOKO_CATEGORY。
      final source =
          File('lib/features/shop/presentation/toko_page_v3.dart').readAsStringSync();
      expect(source.contains("entrySource: 'TOKO_ALL_FEATURED'"), isTrue,
          reason: '首页商品流的行级归因必须是全部精选入口');
      expect(source.contains('TOKO_CATEGORY'), isFalse,
          reason: '品类入口已下线（D-17），不应再有品类归因分支');
    });
  });
}

final _detail = ShopProductDetail(
  token: 'tok',
  name: 'Royal Canin Medium Adult',
  brand: 'Royal Canin',
  returnPolicy: ReturnPolicy.returnable,
  skus: const [
    ShopSku(
      token: 'sku-1',
      specName: '3 kg',
      price: 285000,
      returnPolicy: ReturnPolicy.returnable,
      stockStatus: StockStatus.inStock,
      remaining: 9,
    ),
  ],
);

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

/// 捕获真实 Dio 请求参数（真 CartRepository 在跑，只换掉最外层网络）。
class _CaptureAdapter implements HttpClientAdapter {
  Map<String, dynamic>? lastQuery;

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) async {
    lastQuery = options.queryParameters;
    return ResponseBody.fromString(
      '{"lines":[],"invalidLines":[],"subtotal":0,"itemCount":0}',
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

class _RecordingCartRepo implements CartRepository {
  String? lastEntrySource;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CartView> view() async => CartView.empty;

  @override
  Future<CartView> add(String skuToken,
      {int qty = 1, String? entrySource, String? triggerType, bool buyNow = false}) async {
    lastEntrySource = entrySource;
    return CartView.empty;
  }

  @override
  Future<CartView> setQty(String skuToken, int qty) async => CartView.empty;

  @override
  Future<CartView> remove(String skuToken) async => CartView.empty;

  @override
  Future<CartView> clearInvalid() async => CartView.empty;

  // Story 4-2：行选择。本类只关心归因，选择端点不参与断言。
  @override
  Future<CartView> setSelected(String skuToken, bool selected) async => CartView.empty;

  @override
  Future<CartView> setAllSelected(bool selected) async => CartView.empty;
}
