import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';
import 'package:tailtopia/features/shop/presentation/cart_icon_button.dart';
import 'package:tailtopia/features/shop/presentation/cart_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.6：购物车页（FR-96 / FR-95）。
///
/// 🔴 测试直接驱动**真实的 [CartController]**，只把最外层的 [CartRepository] 换掉 ——
/// 这样「减到 1 再减就是删除」「+ 到上限置灰」这类规则是被真的执行了一遍，
/// 而不是断言我在测试里自己 mock 出来的返回值。
void main() {
  late _FakeCartRepo repo;

  setUp(() => repo = _FakeCartRepo());

  Widget host({required bool loggedIn}) => ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                loggedIn
                    ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
                    : const AuthState.guest(),
              )),
          cartRepositoryProvider.overrideWithValue(repo),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('id'),
          home: CartPage(),
        ),
      );

  /// 购物车行较高，默认 800x600 画布会让底部失效分区根本没 build 出来
  /// （HANDOFF 七.3 的坑）。放大画布让整页一次渲染。
  Future<void> open(WidgetTester t, {bool loggedIn = true}) async {
    t.view.physicalSize = const Size(1200, 3000);
    t.view.devicePixelRatio = 1.0;
    addTearDown(() {
      t.view.resetPhysicalSize();
      t.view.resetDevicePixelRatio();
    });
    await t.pumpWidget(host(loggedIn: loggedIn));
    await t.pumpAndSettle();
  }

  group('🔴 FR-96 平铺列表 + 失效分区', () {
    testWidgets('有效行与失效行都渲染，失效行恒在列表底部', (t) async {
      repo.next = _cart(
        valid: [_line('sku-a', name: 'Royal Canin', price: 285000)],
        invalid: [
          _line('sku-x', name: 'Pro Plan', price: 620000, reason: CartInvalidReason.outOfStock),
        ],
      );
      await open(t);

      expect(find.text('Royal Canin'), findsOneWidget);
      // 🔴 下架商品不静默消失 —— 用户加过的东西凭空不见会引发客诉
      expect(find.text('Pro Plan'), findsOneWidget);

      final validY = t.getTopLeft(find.byKey(const ValueKey('cartLine_sku-a'))).dy;
      final invalidY = t.getTopLeft(find.byKey(const ValueKey('cartInvalidLine_sku-x'))).dy;
      expect(invalidY, greaterThan(validY), reason: '失效分区必须置于列表底部');
    });

    testWidgets('🔴 失效行不参与合计 —— 底部金额只等于有效行小计', (t) async {
      repo.next = _cart(
        valid: [_line('sku-a', price: 285000), _line('sku-b', price: 85000)],
        invalid: [_line('sku-x', price: 620000, reason: CartInvalidReason.delisted)],
        subtotal: 370000,
        itemCount: 2,
      );
      await open(t);

      expect(find.text('Rp 370.000'), findsOneWidget);
      // 990.000 = 把失效的 620.000 也算进去。这一条红了就说明有人在客户端自己重算了合计。
      expect(find.text('Rp 990.000'), findsNothing);
      expect(find.text('Total (2 barang)'), findsOneWidget);
    });

    testWidgets('🔴 失效行不可勾选、不给任何数量控件', (t) async {
      repo.next = _cart(
        valid: [_line('sku-a')],
        invalid: [_line('sku-x', reason: CartInvalidReason.outOfStock)],
      );
      await open(t);

      expect(find.byKey(const ValueKey('cartInc_sku-x')), findsNothing);
      expect(find.byKey(const ValueKey('cartDec_sku-x')), findsNothing);
      // 单店模型没有勾选框，更不该给失效行来一个
      expect(find.byType(Checkbox), findsNothing);
    });

    testWidgets('🔴 下架与售罄措辞不同 —— 一个是永久的、一个是暂时的', (t) async {
      repo.next = _cart(
        valid: const [],
        invalid: [
          _line('sku-x', reason: CartInvalidReason.delisted),
          _line('sku-y', reason: CartInvalidReason.outOfStock),
        ],
      );
      await open(t);

      expect(find.text('Sudah ditarik'), findsOneWidget);
      expect(find.text('Stok habis'), findsOneWidget);
    });

    testWidgets('未知失效原因降级到通用措辞，且仍留在失效区（绝不当成有效行）', (t) async {
      // 后端加了新原因而 App 未升级：认不出就当失效，是「宁可挡一次购买」的那一侧。
      repo.next = CartView.fromJson({
        'lines': const [],
        'invalidLines': [
          {
            'skuToken': 'sku-z',
            'productName': 'Produk Z',
            'specName': '1 kg',
            'price': 50000,
            'qty': 1,
            'invalidReason': 'SOME_FUTURE_REASON',
          }
        ],
        'subtotal': 0,
        'itemCount': 0,
      });
      await open(t);

      expect(find.text('Tidak tersedia'), findsOneWidget);
      expect(find.byKey(const ValueKey('cartInvalidLine_sku-z')), findsOneWidget);
      expect(find.byKey(const ValueKey('cartLine_sku-z')), findsNothing);
    });

    testWidgets('「Hapus semua」清空全部失效行', (t) async {
      repo.next = _cart(
        valid: [_line('sku-a')],
        invalid: [_line('sku-x', reason: CartInvalidReason.delisted)],
      );
      await open(t);

      repo.next = _cart(valid: [_line('sku-a')], invalid: const []);
      await t.tap(find.byKey(const ValueKey('cartClearInvalid')));
      await t.pumpAndSettle();

      expect(repo.calls, contains('clearInvalid'));
      expect(find.byKey(const ValueKey('cartInvalidLine_sku-x')), findsNothing);
    });
  });

  group('🔴 FR-95 数量受可售库存约束', () {
    testWidgets('未到上限：+ 可点，调 setQty(qty+1)', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 2, stock: 5)]);
      await open(t);

      repo.next = _cart(valid: [_line('sku-a', qty: 3, stock: 5)]);
      await t.tap(find.byKey(const ValueKey('cartInc_sku-a')));
      await t.pumpAndSettle();

      expect(repo.calls, contains('setQty:sku-a:3'));
      expect(find.text('3'), findsOneWidget);
    });

    testWidgets('🔴 到达可售库存上限：+ 置灰，并展示真实剩余数', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 5, stock: 5)]);
      await open(t);

      final plus = t.widget<IconButton>(find.byKey(const ValueKey('cartInc_sku-a')));
      expect(plus.onPressed, isNull, reason: '到顶还能点就会让用户白撞一次 409');
      // 数字取真实剩余数（FR-95），不编
      expect(find.text('Sisa stok 5'), findsOneWidget);
    });

    testWidgets('🔴 库存不明（availableStock 缺失）时 + 同样置灰 —— 宁可挡一次购买', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 1, stock: null)]);
      await open(t);

      final plus = t.widget<IconButton>(find.byKey(const ValueKey('cartInc_sku-a')));
      expect(plus.onPressed, isNull, reason: '库存未知时放行 = 放过一次超卖');
    });

    testWidgets('qty=1 时减号变删除图标，点击即删除该行', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 1, stock: 5)]);
      await open(t);

      expect(find.byIcon(Icons.delete_outline), findsOneWidget);
      expect(find.byIcon(Icons.remove), findsNothing);

      repo.next = _cart(valid: const [], invalid: const []);
      await t.tap(find.byKey(const ValueKey('cartDec_sku-a')));
      await t.pumpAndSettle();

      expect(repo.calls, contains('setQty:sku-a:0'));
    });

    testWidgets('qty>1 时减号是减号，调 setQty(qty-1)', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 3, stock: 5)]);
      await open(t);

      expect(find.byIcon(Icons.remove), findsOneWidget);
      repo.next = _cart(valid: [_line('sku-a', qty: 2, stock: 5)]);
      await t.tap(find.byKey(const ValueKey('cartDec_sku-a')));
      await t.pumpAndSettle();

      expect(repo.calls, contains('setQty:sku-a:2'));
    });

    testWidgets('库存不足（后端 409）→ 展示本地化文案，不回显后端 detail 原文', (t) async {
      repo.next = _cart(valid: [_line('sku-a', qty: 2, stock: 5)]);
      await open(t);

      repo.error = DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 409,
          data: const {'status': 409, 'detail': '库存不足，该规格最多可购买 3 件'},
        ),
      );
      await t.tap(find.byKey(const ValueKey('cartInc_sku-a')));
      await t.pumpAndSettle();

      expect(find.text('Stok tidak cukup. Kurangi jumlahnya.'), findsOneWidget);
      // 后端那句是中文运营语，直接展示既不合语种也泄实现
      expect(find.textContaining('库存不足'), findsNothing);

      // toast 挂在 root Overlay 上带 2.6s 自动消失定时器 —— 不等它结束，
      // 测试结束时会因「Timer is still pending」失败（与被测逻辑无关）。
      await t.pump(const Duration(seconds: 3));
      await t.pumpAndSettle();
    });
  });

  group('空态与游客态', () {
    testWidgets('空车展示空态，且不渲染底部结算栏', (t) async {
      repo.next = CartView.empty;
      await open(t);

      expect(find.text('Keranjang masih kosong'), findsOneWidget);
      expect(find.byKey(const ValueKey('cartCheckout')), findsNothing);
    });

    testWidgets('🔒 游客态：页面不发请求，展示登录引导入口', (t) async {
      await open(t, loggedIn: false);

      expect(repo.calls, isEmpty, reason: '游客不得触发任何购物车请求');
      expect(find.text('Masuk untuk melihat keranjang'), findsOneWidget);
      expect(find.byKey(const ValueKey('emptyStateAction')), findsOneWidget);
    });

    test('🔒 游客态：数据层自己就短路，即便有人 watch 也不打 /me/cart', () async {
      // ⚠️ 上面那条 widget 用例守不住这一点 —— 游客态页面根本没 watch cartProvider，
      // 所以哪怕数据层删掉短路它照样绿（变异验证 M5 实测如此）。这条把断言直接压在
      // CartController.build 上：游客打 /me/cart 会 401 → 拦截器强弹窗，
      // 那正是 FR-93A 不要的登录墙换了个触发点。
      final container = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(const AuthState.guest())),
        cartRepositoryProvider.overrideWithValue(repo),
      ]);
      addTearDown(container.dispose);

      final cart = await container.read(cartProvider.future);

      expect(repo.calls, isEmpty);
      expect(cart.itemCount, 0);
      expect(cart.isEmpty, isTrue);
    });
  });

  group('🔴 角标是件数不是种类数', () {
    testWidgets('3 种商品共 7 件 → 角标显示 7', (t) async {
      repo.next = _cart(
        valid: [
          _line('sku-a', qty: 3),
          _line('sku-b', qty: 2),
          _line('sku-c', qty: 2),
        ],
        itemCount: 7,
      );

      await t.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(
                const AuthState(status: AuthStatus.authenticated, role: 'USER'),
              )),
          cartRepositoryProvider.overrideWithValue(repo),
        ],
        child: const MaterialApp(
          localizationsDelegates: [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: Locale('id'),
          home: Scaffold(appBar: null, body: CartIconButton()),
        ),
      ));
      await t.pumpAndSettle();

      // 显示 3（种类数）会让用户以为自己漏加了
      expect(find.text('7'), findsOneWidget);
      expect(find.text('3'), findsNothing);
    });
  });

  group('domain', () {
    test('未知 invalidReason → unavailable（不是 null）', () {
      expect(CartInvalidReason.fromApi('DELISTED'), CartInvalidReason.delisted);
      expect(CartInvalidReason.fromApi('OUT_OF_STOCK'), CartInvalidReason.outOfStock);
      expect(CartInvalidReason.fromApi('WHATEVER'), CartInvalidReason.unavailable);
      // null / 空串才是「有效行」
      expect(CartInvalidReason.fromApi(null), isNull);
      expect(CartInvalidReason.fromApi(''), isNull);
    });

    test('canIncrease 边界：qty<stock 可加、qty==stock 不可、stock 未知不可、失效行不可', () {
      expect(_line('a', qty: 1, stock: 2).canIncrease, isTrue);
      expect(_line('a', qty: 2, stock: 2).canIncrease, isFalse);
      expect(_line('a', qty: 1, stock: null).canIncrease, isFalse);
      expect(
        _line('a', qty: 1, stock: 9, reason: CartInvalidReason.outOfStock).canIncrease,
        isFalse,
      );
    });

    test('fromJson 缺字段不崩', () {
      final v = CartView.fromJson(const {});
      expect(v.lines, isEmpty);
      expect(v.invalidLines, isEmpty);
      expect(v.subtotal, 0);
      expect(v.itemCount, 0);
      expect(v.isEmpty, isTrue);
    });
  });
}

// ---------- helpers ----------

CartLine _line(
  String token, {
  String name = 'Produk',
  String spec = '3 kg',
  int price = 285000,
  int qty = 1,
  int? stock = 10,
  CartInvalidReason? reason,
}) =>
    CartLine(
      skuToken: token,
      productToken: 'p-$token',
      productName: name,
      specName: spec,
      price: price,
      qty: qty,
      availableStock: stock,
      invalidReason: reason,
    );

CartView _cart({
  List<CartLine> valid = const [],
  List<CartLine> invalid = const [],
  int? subtotal,
  int? itemCount,
}) =>
    CartView(
      lines: valid,
      invalidLines: invalid,
      subtotal: subtotal ?? valid.fold(0, (s, l) => s + l.lineTotal),
      itemCount: itemCount ?? valid.fold(0, (s, l) => s + l.qty),
    );

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);

  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

/// 只替换最外层 HTTP 边界：controller / provider / 页面全是真的。
class _FakeCartRepo implements CartRepository {
  CartView next = CartView.empty;

  /// 下一次写操作要抛的异常（抛完清空）。
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
  Future<CartView> add(String skuToken, {int qty = 1}) async => _write('add:$skuToken:$qty');

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
