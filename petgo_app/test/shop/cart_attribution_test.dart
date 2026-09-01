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
import 'package:tailtopia/features/shop/presentation/toko_page_v2.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';

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

    testWidgets('🔴 Toko 卡片：全部精选与品类页是两个不同的入口', (t) async {
      await t.pumpWidget(ProviderScope(
        overrides: [
          // banner 同样必须 override —— 真 provider 会发请求并留下未完成 Timer。
          shopBannerProvider.overrideWith((ref) async => null),
          shopProductsProvider.overrideWith((ref, category) async => const [
                ShopProductSummary(
                    token: 'p1', name: 'Produk', brand: 'B', minPrice: 285000),
              ]),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Builder(
            builder: (context) => const TokoPageV2(),
          ),
        ),
      ));
      await t.pumpAndSettle();

      // 未选品类 → 区域④；选了品类 → 品类入口。两者混为一谈就算不出「品类页值不值得做」。
      // widget 层拿不到 push 的 query，而这条三元分支是「两个入口不同」的唯一实现处。
      final source =
          File('lib/features/shop/presentation/toko_page_v2.dart').readAsStringSync();
      expect(source.contains("_selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY'"), isTrue,
          reason: '入口来源必须随当前筛选态变化');
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
      {int qty = 1, String? entrySource, String? triggerType}) async {
    lastEntrySource = entrySource;
    return CartView.empty;
  }

  @override
  Future<CartView> setQty(String skuToken, int qty) async => CartView.empty;

  @override
  Future<CartView> remove(String skuToken) async => CartView.empty;

  @override
  Future<CartView> clearInvalid() async => CartView.empty;
}
