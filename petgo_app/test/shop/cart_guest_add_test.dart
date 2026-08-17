import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_soft_sheet.dart';

/// Story 3.6 AC2：游客加购走**软性登录引导**，登录成功后**自动完成本次加购并停留原页**。
///
/// 🔴 这条链路是转化漏斗上最贵的一次点击 —— 用户刚表达购买意图。把他踢到登录页、
/// 或者让他登录完回来发现车里什么都没有，都是在这一步把人丢掉。所以本文件走
/// **真实的 LoginGuideController + 真实的 LoginSoftSheet**，只把 HTTP 边界换成假的。
void main() {
  late _FakeCartRepo repo;

  setUp(() => repo = _FakeCartRepo());

  final detail = ShopProductDetail(
    token: 'tok',
    name: 'Royal Canin Medium Adult',
    brand: 'Royal Canin',
    returnPolicy: ReturnPolicy.noReturnAfterOpen,
    skus: const [
      ShopSku(
        token: 'sku-1',
        specName: '3 kg',
        price: 285000,
        returnPolicy: ReturnPolicy.noReturnAfterOpen,
        stockStatus: StockStatus.inStock,
        remaining: 9,
      ),
    ],
  );

  GoRouter router() => GoRouter(
        initialLocation: '/shop/products/tok',
        routes: [
          GoRoute(
            path: '/shop/products/:token',
            builder: (c, s) => ProductDetailPage(token: s.pathParameters['token']!),
          ),
          GoRoute(path: '/shop/cart', builder: (c, s) => const Scaffold(body: Text('CART PAGE'))),
          GoRoute(path: '/login', builder: (c, s) => const Scaffold(body: Text('LOGIN PAGE'))),
          GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('HOME PAGE'))),
          GoRoute(
              path: '/onboarding', builder: (c, s) => const Scaffold(body: Text('ONBOARDING'))),
        ],
      );

  Future<GoRouter> open(WidgetTester t, {required bool loggedIn}) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    final r = router();
    await t.pumpWidget(ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(
              loggedIn
                  ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
                  : const AuthState.guest(),
            )),
        shopProductDetailProvider.overrideWith((ref, token) async => detail),
        cartRepositoryProvider.overrideWithValue(repo),
        // 真 controller + 假登录执行器：登录成功即写回登录态（与线上 provider 同样的动作）。
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
      ],
      child: MaterialApp.router(
        routerConfig: r,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await t.pumpAndSettle();
    return r;
  }

  String path(GoRouter r) => r.routerDelegate.currentConfiguration.uri.path;

  Finder addButton() => find.byType(FilledButton);

  testWidgets('🔴 游客点加购：弹软浮层，不跳登录页、不跳走、也不偷偷加购', (t) async {
    final r = await open(t, loggedIn: false);

    await t.tap(addButton());
    await t.pumpAndSettle();

    expect(find.byType(LoginSoftSheet), findsOneWidget, reason: '必须是软浮层，不是硬门槛');
    expect(find.text('LOGIN PAGE'), findsNothing, reason: '把用户踢到登录页就是丢掉这次意图');
    expect(path(r), '/shop/products/tok', reason: '停留原页');
    expect(repo.calls, isEmpty, reason: '还没登录就不该有购物车写操作');
  });

  testWidgets('🔴 登录成功 → 自动完成本次加购，且路由未变（不丢意图、不跳走）', (t) async {
    final r = await open(t, loggedIn: false);

    await t.tap(addButton());
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('softSheetGoogleCta')));
    await t.pumpAndSettle();

    // pendingAction 的 onResume 就是「把刚才那件商品加进车」
    expect(repo.calls, contains('add:sku-1:1'));
    expect(path(r), '/shop/products/tok', reason: '登录后必须停在原页，不跳首页也不跳购物车');
    expect(find.byType(LoginSoftSheet), findsNothing, reason: '浮层应已关闭');

    await t.pump(const Duration(seconds: 3)); // 等 toast 自动消失，避免 pending timer
    await t.pumpAndSettle();
  });

  testWidgets('🔴 软浮层的 session 去重不得吞掉第二次加购（主动动作 ≠ 被动劝登录）', (t) async {
    final r = await open(t, loggedIn: false);

    // 第一次：弹出后用户关掉（没登录）
    await t.tap(addButton());
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('softSheetClose')));
    await t.pumpAndSettle();
    expect(find.byType(LoginSoftSheet), findsNothing);

    // 第二次：仍须弹。若 allowRepeat 被去掉，这里就是「按钮点了没反应」——
    // 而它恰恰是漏斗上最贵的一次点击。
    await t.tap(addButton());
    await t.pumpAndSettle();
    expect(find.byType(LoginSoftSheet), findsOneWidget);
    expect(path(r), '/shop/products/tok');
  });

  testWidgets('已登录：直接加购，提示成功，路由不变', (t) async {
    final r = await open(t, loggedIn: true);

    await t.tap(addButton());
    await t.pumpAndSettle();

    expect(find.byType(LoginSoftSheet), findsNothing, reason: '已登录不该再被拦一道');
    expect(repo.calls, contains('add:sku-1:1'));
    expect(find.text('Ditambahkan ke keranjang'), findsOneWidget);
    expect(path(r), '/shop/products/tok');

    await t.pump(const Duration(seconds: 3));
    await t.pumpAndSettle();
  });

  testWidgets('加购撞库存（409）→ 展示本地化文案，不回显后端 detail 原文', (t) async {
    await open(t, loggedIn: true);

    repo.error = DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: 409,
        data: const {'status': 409, 'detail': '库存不足，该规格最多可购买 3 件'},
      ),
    );
    await t.tap(addButton());
    await t.pumpAndSettle();

    expect(find.text('Stok tidak cukup. Kurangi jumlahnya.'), findsOneWidget);
    expect(find.textContaining('库存不足'), findsNothing);

    await t.pump(const Duration(seconds: 3));
    await t.pumpAndSettle();
  });
}

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
  Future<CartView> view() async {
    calls.add('view');
    return next;
  }

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1, String? entrySource, String? triggerType}) async =>
      _write('add:$skuToken:$qty${entrySource == null ? '' : ':$entrySource'}');

  @override
  Future<CartView> setQty(String skuToken, int qty) async => _write('setQty:$skuToken:$qty');

  @override
  Future<CartView> remove(String skuToken) async => _write('remove:$skuToken');

  @override
  Future<CartView> clearInvalid() async => _write('clearInvalid');

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
