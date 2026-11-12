import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shipping_estimate_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';

import 'pdp_fakes.dart';

/// analytics-pipeline Story 3.3：购物车移出 / 改数量事件在 [CartController] 数据层上报。
///
/// - `remove` 成功 → 一条 `toko_cart_item_removed`（默认 `reason: manual`）；失败不报。
/// - `clearInvalid` 成功 → 按调用前的失效行各报一条 `reason: invalid_cleared`。
/// - `setQty` 成功且前后数量不同 → `toko_cart_quantity_changed`；失败 / 同值不报。
/// - 状态未加载 → 只带 `sku_token`（与 `reason` / `qty_after`），不抛异常。
/// - `refresh()`（下单后服务端删车行）不报任何移出事件（AC4）。
void main() {
  late List<(String, Map<String, Object>?)> events;
  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  List<Map<String, Object>?> named(String event) =>
      events.where((e) => e.$1 == event).map((e) => e.$2).toList();

  CartLine line(String sku, {String? product, int price = 50000, int qty = 2, CartInvalidReason? invalid}) =>
      CartLine(
        skuToken: sku,
        productToken: product,
        productName: 'Nama $sku',
        specName: 'Spec $sku',
        price: price,
        qty: qty,
        invalidReason: invalid,
      );

  CartView cart({List<CartLine> lines = const [], List<CartLine> invalid = const []}) => CartView(
        lines: lines,
        invalidLines: invalid,
        subtotal: 0,
        itemCount: 0,
        selectedSubtotal: 0,
        selectedCount: 0,
      );

  ProviderContainer containerWith(_FakeRepo repo) {
    final c = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(
          () => _Auth(const AuthState(status: AuthStatus.authenticated, role: 'USER'))),
      shopDeliveryAreaProvider.overrideWith(() => FakeDeliveryAreaController(null, [])),
      cartRepositoryProvider.overrideWithValue(repo),
    ]);
    c.listen(cartProvider, (_, _) {});
    return c;
  }

  group('toko_cart_item_removed', () {
    test('remove 成功 → 一条，带 token / qty / unit_price / reason: manual', () async {
      final repo = _FakeRepo()
        ..viewResult = cart(lines: [line('s1', product: 'p1', price: 75000, qty: 2)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).remove('s1');

      expect(named('toko_cart_item_removed'), [
        {'product_token': 'p1', 'sku_token': 's1', 'qty': 2, 'unit_price': 75000, 'reason': 'manual'},
      ]);
    });

    test('失效行删除同样用快照（invalidLines 里也找）；显式 invalidCleared → invalid_cleared', () async {
      final repo = _FakeRepo()
        ..viewResult = cart(invalid: [line('s9', product: 'p9', qty: 1, invalid: CartInvalidReason.delisted)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).remove('s9', reason: CartRemoveReason.invalidCleared);

      expect(named('toko_cart_item_removed'), [
        {'product_token': 'p9', 'sku_token': 's9', 'qty': 1, 'unit_price': 50000, 'reason': 'invalid_cleared'},
      ]);
    });

    test('remove 失败 → 抛 CartMutationError，无事件', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1')]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.fail = true;
      await expectLater(c.read(cartProvider.notifier).remove('s1'), throwsA(isA<CartMutationError>()));
      expect(named('toko_cart_item_removed'), isEmpty);
    });

    test('结果因序号过时被丢弃（服务端已删）→ 仍报一次', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1')]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.removeGate = Completer<CartView>();
      final slow = c.read(cartProvider.notifier).remove('s1');
      await pumpEventQueue();
      await c.read(cartProvider.notifier).setAllSelected(true); // 之后又发出请求 → remove 的结果过时
      repo.removeGate!.complete(cart());
      await slow;

      expect(named('toko_cart_item_removed'), hasLength(1));
    });

    test('clearInvalid 两条失效行 → 两条 reason: invalid_cleared', () async {
      final repo = _FakeRepo()
        ..viewResult = cart(
          lines: [line('ok', product: 'pk')],
          invalid: [
            line('x1', product: 'px1', price: 10000, qty: 1, invalid: CartInvalidReason.delisted),
            line('x2', product: 'px2', price: 20000, qty: 3, invalid: CartInvalidReason.outOfStock),
          ],
        );
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).clearInvalid();

      expect(named('toko_cart_item_removed'), [
        {'product_token': 'px1', 'sku_token': 'x1', 'qty': 1, 'unit_price': 10000, 'reason': 'invalid_cleared'},
        {'product_token': 'px2', 'sku_token': 'x2', 'qty': 3, 'unit_price': 20000, 'reason': 'invalid_cleared'},
      ]);
    });

    test('clearInvalid 失败 → 无事件', () async {
      final repo = _FakeRepo()
        ..viewResult = cart(invalid: [line('x1', product: 'px1', invalid: CartInvalidReason.delisted)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.fail = true;
      await expectLater(c.read(cartProvider.notifier).clearInvalid(), throwsA(isA<CartMutationError>()));
      expect(named('toko_cart_item_removed'), isEmpty);
    });

    test('状态未加载时 remove → 只带 sku_token 与 reason，不抛异常', () async {
      final repo = _FakeRepo()..viewGate = Completer<CartView>(); // build 的 GET 挂起，state 仍是 loading
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await pumpEventQueue();
      expect(c.read(cartProvider).hasValue, isFalse);

      await c.read(cartProvider.notifier).remove('s1');

      expect(named('toko_cart_item_removed'), [
        {'sku_token': 's1', 'reason': 'manual'},
      ]);
    });

    test('商品 token 缺失（旧后端）→ 不带 product_token，其余照带', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', qty: 4)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).remove('s1');

      expect(named('toko_cart_item_removed').single, {'sku_token': 's1', 'qty': 4, 'unit_price': 50000, 'reason': 'manual'});
    });

    test('AC4：refresh()（下单后服务端删车行）不报 toko_cart_item_removed', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1')]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.viewResult = cart(); // 下单成功后车已空
      await c.read(cartProvider.notifier).refresh();

      expect(c.read(cartProvider).value?.lines, isEmpty);
      expect(named('toko_cart_item_removed'), isEmpty);
    });
  });

  group('toko_cart_quantity_changed', () {
    test('setQty 2→3 → 一条，带 qty_before / qty_after / unit_price', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1', price: 60000, qty: 2)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).setQty('s1', 3);

      expect(named('toko_cart_quantity_changed'), [
        {'product_token': 'p1', 'sku_token': 's1', 'qty_before': 2, 'qty_after': 3, 'unit_price': 60000},
      ]);
      expect(named('toko_cart_item_removed'), isEmpty);
    });

    test('setQty 失败 → 无事件', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1', qty: 2)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.fail = true;
      await expectLater(c.read(cartProvider.notifier).setQty('s1', 3), throwsA(isA<CartMutationError>()));
      expect(named('toko_cart_quantity_changed'), isEmpty);
    });

    test('同值不报', () async {
      final repo = _FakeRepo()..viewResult = cart(lines: [line('s1', product: 'p1', qty: 2)]);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      await c.read(cartProvider.notifier).setQty('s1', 2);
      expect(named('toko_cart_quantity_changed'), isEmpty);
    });

    test('状态未加载 → 只带 sku_token 与 qty_after', () async {
      final repo = _FakeRepo()..viewGate = Completer<CartView>();
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await pumpEventQueue();

      await c.read(cartProvider.notifier).setQty('s1', 5);
      expect(named('toko_cart_quantity_changed'), [
        {'sku_token': 's1', 'qty_after': 5},
      ]);
    });
  });
}

class _Auth extends AuthController {
  _Auth(this._s);
  final AuthState _s;
  @override
  AuthState build() => _s;
}

class _FakeRepo implements CartRepository {
  CartView viewResult = CartView.empty;
  Completer<CartView>? viewGate;
  Completer<CartView>? removeGate;
  bool fail = false;

  @override
  Dio get dio => throw UnimplementedError();

  Future<CartView> _r() async {
    if (fail) throw DioException(requestOptions: RequestOptions(path: '/x'));
    return viewResult;
  }

  @override
  Future<CartView> view({int? regionKecamatanId}) {
    final g = viewGate;
    return g != null ? g.future : Future.value(viewResult);
  }

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1,
          String? entrySource,
          String? triggerType,
          bool buyNow = false,
          int? regionKecamatanId}) =>
      _r();

  @override
  Future<CartView> setQty(String skuToken, int qty, {int? regionKecamatanId}) => _r();

  @override
  Future<CartView> remove(String skuToken, {int? regionKecamatanId}) {
    final g = removeGate;
    return g != null ? g.future : _r();
  }

  @override
  Future<CartView> setSelected(String skuToken, bool selected, {int? regionKecamatanId}) => _r();

  @override
  Future<CartView> setAllSelected(bool selected, {int? regionKecamatanId}) => _r();

  @override
  Future<CartView> clearInvalid({int? regionKecamatanId}) => _r();
}
