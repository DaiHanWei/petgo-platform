import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/shop/data/cart_repository.dart';
import 'package:tailtopia/features/shop/data/shipping_estimate_repository.dart';
import 'package:tailtopia/features/shop/domain/shop_cart.dart';

import 'pdp_fakes.dart';

/// v1.4.2 shop Story 8.3 AC7：`CartView.estimate` 解析 + `regionKecamatanId` 透传。
///
/// 后端 `MeCartController` 的 7 个出口都装配 `estimate`、都接受可选 `regionKecamatanId`
/// （无默认地址时按该地区估运费）—— App 在本机记过地区时每个请求都带上，null 时不发该键。
void main() {
  group('CartView.fromJson 读 estimate', () {
    test('各字段原样读出（UI C1 假数据：329.000 / Hemat 30.000 / 差 171.000）', () {
      final v = CartView.fromJson({
        'lines': [],
        'invalidLines': [],
        'subtotal': 349000,
        'itemCount': 3,
        'selectedSubtotal': 349000,
        'selectedCount': 3,
        'estimate': {
          'lowestFee': 10000,
          'goodsAmount': 349000,
          'discountCouponAmount': 20000,
          'shippingDiscount': 10000,
          'payable': 329000,
          'saved': 30000,
          'freeShippingProgress': {'threshold': 500000, 'remaining': 171000},
          'reason': null,
        },
      });
      final e = v.estimate!;
      expect(e.lowestFee, 10000);
      expect(e.payable, 329000);
      expect(e.saved, 30000);
      expect(e.goodsAmount, 349000);
      expect(e.discountCouponAmount, 20000);
      expect(e.shippingDiscount, 10000);
      expect(e.freeShippingProgress!.threshold, 500000);
      expect(e.freeShippingProgress!.remaining, 171000);
      expect(e.reason, isNull);
      expect(e.shippingKnown, isTrue);
    });

    test('缺键为 null：lowestFee / freeShippingProgress / reason', () {
      final e = CartEstimate.fromJson({'payable': 285000, 'reason': 'NO_DESTINATION'})!;
      expect(e.lowestFee, isNull);
      expect(e.shippingKnown, isFalse);
      expect(e.freeShippingProgress, isNull);
      expect(e.saved, 0);
      expect(e.reason, CartEstimate.reasonNoDestination);
    });

    test('旧后端不下发 estimate（或缺 payable）→ null，不编一个 0', () {
      final v = CartView.fromJson(
          {'lines': [], 'invalidLines': [], 'subtotal': 0, 'itemCount': 0});
      expect(v.estimate, isNull);
      expect(CartEstimate.fromJson({'saved': 1}), isNull);
      expect(CartEstimate.fromJson('x'), isNull);
    });
  });

  group('CartRepository 带 regionKecamatanId', () {
    late _CaptureAdapter adapter;
    late CartRepository repo;

    setUp(() {
      adapter = _CaptureAdapter();
      repo = CartRepository(dio: Dio()..httpClientAdapter = adapter);
    });

    test('view(regionKecamatanId: 42) 带 query；null 时不带', () async {
      await repo.view(regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);

      await repo.view();
      expect(adapter.lastQuery?.containsKey('regionKecamatanId'), isFalse);
    });

    test('变更类出口同样透传（与 GET 同口径估算）', () async {
      await repo.add('s', regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);
      await repo.setQty('s', 2, regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);
      expect(adapter.lastQuery?['qty'], 2);
      await repo.remove('s', regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);
      await repo.setSelected('s', false, regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);
      await repo.setAllSelected(true, regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);
      await repo.clearInvalid(regionKecamatanId: 42);
      expect(adapter.lastQuery?['regionKecamatanId'], 42);

      await repo.setQty('s', 2);
      expect(adapter.lastQuery?.containsKey('regionKecamatanId'), isFalse);
    });
  });

  group('CartController 读本机送达地区', () {
    ProviderContainer containerWith(_RecordingRepo repo, ShopDeliveryArea? area,
            {bool loggedIn = true}) =>
        ProviderContainer(overrides: [
          authControllerProvider.overrideWith(() => _Auth(loggedIn
              ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
              : const AuthState(status: AuthStatus.guest))),
          shopDeliveryAreaProvider.overrideWith(() => FakeDeliveryAreaController(area, [])),
          cartRepositoryProvider.overrideWithValue(repo),
        ]);

    const area = ShopDeliveryArea(
        kecamatanId: 42, kecamatan: 'Kebayoran Baru', kota: 'Jakarta Selatan', provinsi: 'DKI');

    test('有地区 → build 与变更都带 42', () async {
      final repo = _RecordingRepo();
      final c = containerWith(repo, area);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);
      expect(repo.regions, [('view', 42)]);

      await c.read(cartProvider.notifier).setQty('s', 2);
      await c.read(cartProvider.notifier).remove('s');
      expect(repo.regions.skip(1), [('setQty', 42), ('remove', 42)]);
    });

    test('没记过地区 → 不带', () async {
      final repo = _RecordingRepo();
      final c = containerWith(repo, null);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);
      expect(repo.regions, [('view', null)]);
    });

    test('🔒 游客不发任何 /me 请求', () async {
      final repo = _RecordingRepo();
      final c = containerWith(repo, area, loggedIn: false);
      addTearDown(c.dispose);
      expect(await c.read(cartProvider.future), same(CartView.empty));
      expect(repo.regions, isEmpty);
    });
  });

  /// 复审修复：build 与所有写操作共用单调序号 —— 只接受最新一次发出的请求的结果。
  group('🔴 CartController 乱序响应不回退', () {
    CartView v(int n) => CartView(
        lines: const [], invalidLines: const [], subtotal: n, itemCount: n,
        selectedSubtotal: n, selectedCount: n);

    ProviderContainer containerWith(_GatedRepo repo) {
      final c = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() =>
            _Auth(const AuthState(status: AuthStatus.authenticated, role: 'USER'))),
        shopDeliveryAreaProvider.overrideWith(() => FakeDeliveryAreaController(null, [])),
        cartRepositoryProvider.overrideWithValue(repo),
      ]);
      c.listen(cartProvider, (_, _) {});
      return c;
    }

    test('慢 GET + 快 POST：最终状态是 POST 的结果', () async {
      final gate = Completer<CartView>();
      final repo = _GatedRepo()..viewGate = gate;
      final c = containerWith(repo);
      addTearDown(c.dispose);
      c.read(cartProvider); // build 发出 GET，挂起
      await pumpEventQueue();
      expect(repo.views, 1);

      repo.addResult = v(2);
      await c.read(cartProvider.notifier).add('s'); // POST 先到
      expect(c.read(cartProvider).value?.itemCount, 2);

      gate.complete(v(1)); // 旧 GET 晚到
      await pumpEventQueue();
      expect(c.read(cartProvider).value?.itemCount, 2, reason: '晚到的旧 GET 不得盖掉加购结果');
    });

    test('A 行勾选（慢）后立刻全选（快）：勾选的旧响应不覆盖全选结果', () async {
      final repo = _GatedRepo();
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);

      repo.selectedGate = Completer<CartView>();
      final slow = c.read(cartProvider.notifier).setSelected('a', false);
      await pumpEventQueue();
      repo.allResult = v(9);
      await c.read(cartProvider.notifier).setAllSelected(true);
      expect(c.read(cartProvider).value?.itemCount, 9);

      repo.selectedGate!.complete(v(3));
      await slow;
      expect(c.read(cartProvider).value?.itemCount, 9, reason: '先发出的勾选晚到，丢弃');
    });

    test('最新一次失败、之前还有请求在路上 → 重拉一次 GET 对齐', () async {
      final repo = _GatedRepo();
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);
      expect(repo.views, 1);

      repo.selectedGate = Completer<CartView>();
      final slow = c.read(cartProvider.notifier).setSelected('a', false);
      await pumpEventQueue();
      repo.allError = true;
      await expectLater(
          c.read(cartProvider.notifier).setAllSelected(true), throwsA(isA<CartMutationError>()));
      await pumpEventQueue();
      expect(repo.views, 2, reason: '服务端此刻的车不可知，以 GET 为准');
      repo.selectedGate!.complete(v(3));
      await slow;
    });

    test('只有一个写请求失败 → 保持原状态、不重拉', () async {
      final repo = _GatedRepo()..viewResult = v(5);
      final c = containerWith(repo);
      addTearDown(c.dispose);
      await c.read(cartProvider.future);
      repo.allError = true;
      await expectLater(
          c.read(cartProvider.notifier).setAllSelected(true), throwsA(isA<CartMutationError>()));
      await pumpEventQueue();
      expect(repo.views, 1);
      expect(c.read(cartProvider).value?.itemCount, 5);
    });
  });
}

class _GatedRepo implements CartRepository {
  int views = 0;
  Completer<CartView>? viewGate;
  Completer<CartView>? selectedGate;
  CartView viewResult = CartView.empty;
  CartView addResult = CartView.empty;
  CartView allResult = CartView.empty;
  bool allError = false;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<CartView> view({int? regionKecamatanId}) {
    views++;
    final g = viewGate;
    viewGate = null;
    return g != null ? g.future : Future.value(viewResult);
  }

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1,
          String? entrySource,
          String? triggerType,
          bool buyNow = false,
          int? regionKecamatanId}) async =>
      addResult;

  @override
  Future<CartView> setQty(String skuToken, int qty, {int? regionKecamatanId}) async =>
      CartView.empty;

  @override
  Future<CartView> remove(String skuToken, {int? regionKecamatanId}) async => CartView.empty;

  @override
  Future<CartView> setSelected(String skuToken, bool selected, {int? regionKecamatanId}) {
    final g = selectedGate;
    return g != null ? g.future : Future.value(CartView.empty);
  }

  @override
  Future<CartView> setAllSelected(bool selected, {int? regionKecamatanId}) async {
    if (allError) {
      throw DioException(requestOptions: RequestOptions(path: '/x'));
    }
    return allResult;
  }

  @override
  Future<CartView> clearInvalid({int? regionKecamatanId}) async => CartView.empty;
}

class _Auth extends AuthController {
  _Auth(this._s);
  final AuthState _s;
  @override
  AuthState build() => _s;
}

class _RecordingRepo implements CartRepository {
  final regions = <(String, int?)>[];

  @override
  Dio get dio => throw UnimplementedError();

  Future<CartView> _r(String op, int? region) async {
    regions.add((op, region));
    return CartView.empty;
  }

  @override
  Future<CartView> view({int? regionKecamatanId}) => _r('view', regionKecamatanId);

  @override
  Future<CartView> add(String skuToken,
          {int qty = 1,
          String? entrySource,
          String? triggerType,
          bool buyNow = false,
          int? regionKecamatanId}) =>
      _r('add', regionKecamatanId);

  @override
  Future<CartView> setQty(String skuToken, int qty, {int? regionKecamatanId}) =>
      _r('setQty', regionKecamatanId);

  @override
  Future<CartView> remove(String skuToken, {int? regionKecamatanId}) =>
      _r('remove', regionKecamatanId);

  @override
  Future<CartView> setSelected(String skuToken, bool selected, {int? regionKecamatanId}) =>
      _r('setSelected', regionKecamatanId);

  @override
  Future<CartView> setAllSelected(bool selected, {int? regionKecamatanId}) =>
      _r('setAllSelected', regionKecamatanId);

  @override
  Future<CartView> clearInvalid({int? regionKecamatanId}) =>
      _r('clearInvalid', regionKecamatanId);
}

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
