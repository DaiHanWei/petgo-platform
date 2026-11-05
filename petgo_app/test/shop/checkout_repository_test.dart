import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/data/checkout_repository.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';

/// v1.4.2 shop Story 7.1 AC2：结算数据层（试算 POST / 新结算模式下单 / P-5 失败分派）。
void main() {
  group('请求体', () {
    test('preview：CART + AUTO → POST /me/checkout/preview，不带券 token', () async {
      final a = _Adapter((o) => _json(200, _minimalPreview));
      final repo = CheckoutRepository(dio: _dio(a));
      await repo.preview(const CheckoutRequest(
          source: CheckoutSource.cart, discountCouponToken: 'uc1', useCoin: false));
      expect(a.last!.method, 'POST');
      expect(a.last!.path, endsWith('/me/checkout/preview'));
      final body = a.last!.data as Map<String, dynamic>;
      expect(body['source'], 'CART');
      expect(body['couponMode'], 'AUTO');
      expect(body.containsKey('discountCouponToken'), isFalse, reason: 'AUTO 不带券');
      expect(body.containsKey('addressToken'), isFalse, reason: '无地址不带键（服务端用默认地址）');
      expect(body.containsKey('buyNow'), isFalse);
      expect(body['useCoin'], false);
      expect(body['insurance'], false);
    });

    test('preview：BUY_NOW + MANUAL → 带 buyNow 与两张券', () async {
      final a = _Adapter((o) => _json(200, _minimalPreview));
      final repo = CheckoutRepository(dio: _dio(a));
      await repo.preview(const CheckoutRequest(
        source: CheckoutSource.buyNow,
        buyNowSkuToken: 'sku9',
        buyNowQty: 2,
        addressToken: 'addr1',
        courierKey: 'jne:reg',
        insurance: true,
        manualCoupons: true,
        discountCouponToken: 'uc1',
        freeShippingCouponToken: 'uc2',
      ));
      final body = a.last!.data as Map<String, dynamic>;
      expect(body['source'], 'BUY_NOW');
      expect(body['buyNow'], {'skuToken': 'sku9', 'qty': 2});
      expect(body['addressToken'], 'addr1');
      expect(body['courierKey'], 'jne:reg');
      expect(body['insurance'], true);
      expect(body['couponMode'], 'MANUAL');
      expect(body['discountCouponToken'], 'uc1');
      expect(body['freeShippingCouponToken'], 'uc2');
      expect(body['useCoin'], true);
    });

    test('placeOrder：新结算模式请求体 + Idempotency-Key 头，解析 paid', () async {
      final a = _Adapter((o) => _json(201, {
            'orderToken': 'ord1',
            'status': 'PENDING_SHIPMENT',
            'totalAmount': 329000,
            'payChannel': 'PAWCOIN',
            'paid': true,
          }));
      final repo = CheckoutRepository(dio: _dio(a));
      final ref = await repo.placeOrder(
        const PlaceOrderRequest(
          source: CheckoutSource.cart,
          addressToken: 'addr1',
          quoteToken: 'q1',
          courierKey: 'jne:reg',
          insurance: false,
          useCoin: true,
          discountCouponToken: 'uc1',
        ),
        idempotencyKey: 'k1',
      );
      expect(a.last!.headers['Idempotency-Key'], 'k1');
      final body = a.last!.data as Map<String, dynamic>;
      expect(body, {
        'source': 'CART',
        'addressToken': 'addr1',
        'quoteToken': 'q1',
        'courierKey': 'jne:reg',
        'insurance': false,
        'discountCouponToken': 'uc1',
        'useCoin': true,
        'agreeReturnPolicy': true,
      });
      expect(ref.orderToken, 'ord1');
      expect(ref.settled, isTrue);
    });
  });

  group('CheckoutFailure.from：按 typeSlug 分支（P-5）', () {
    Future<CheckoutFailure> failWith(int status, Map<String, dynamic> body) async {
      final repo = CheckoutRepository(dio: _dio(_Adapter((o) => _json(status, body))));
      try {
        await repo.placeOrder(
            const PlaceOrderRequest(
                source: CheckoutSource.cart,
                addressToken: 'a',
                quoteToken: 'q',
                courierKey: 'k',
                insurance: false,
                useCoin: true),
            idempotencyKey: 'x');
      } on CheckoutFailure catch (e) {
        return e;
      }
      throw StateError('应抛 CheckoutFailure');
    }

    String t(String slug) => 'https://api.tailtopia.app/problems/$slug';

    test('unavailable-lines 带逐行明细', () async {
      final e = await failWith(409, {
        'type': t('unavailable-lines'),
        'status': 409,
        'unavailableLines': [
          {'skuToken': 's1', 'productName': 'A', 'reason': 'INSUFFICIENT_STOCK', 'available': 1, 'requested': 3},
        ],
      });
      expect(e.kind, CheckoutFailureKind.unavailableLines);
      expect(e.unavailableLines.single.available, 1);
      expect(e.unavailableLines.single.requested, 3);
    });

    for (final (slug, status, kind) in [
      ('not-placeable', 422, CheckoutFailureKind.notPlaceable),
      ('coupon-invalid', 409, CheckoutFailureKind.couponInvalid),
      ('quote-expired', 409, CheckoutFailureKind.quoteExpired),
      ('sku-not-sellable', 422, CheckoutFailureKind.skuNotSellable),
      ('conflict', 409, CheckoutFailureKind.generic),
      ('validation', 422, CheckoutFailureKind.generic),
    ]) {
      test('$slug → $kind', () async {
        final e = await failWith(status, {'type': t(slug), 'status': status});
        expect(e.kind, kind);
      });
    }

    test('typeSlug 缺失：409 带行明细 → unavailableLines；422 → notPlaceable；其他 → generic', () async {
      expect((await failWith(409, {
        'status': 409,
        'unavailableLines': [
          {'skuToken': 's1', 'reason': 'DELISTED', 'available': 0, 'requested': 1},
        ],
      }))
          .kind, CheckoutFailureKind.unavailableLines);
      expect((await failWith(422, {'status': 422})).kind, CheckoutFailureKind.notPlaceable);
      expect((await failWith(500, {'status': 500})).kind, CheckoutFailureKind.generic);
    });
  });

  group('🔴 outcomeUnknown：结果不明确的失败要钉住幂等键（复审修复 2）', () {
    Future<CheckoutFailure> run(ResponseBody Function(RequestOptions) h) async {
      final repo = CheckoutRepository(dio: _dio(_Adapter(h)));
      try {
        await repo.placeOrder(
            const PlaceOrderRequest(
                source: CheckoutSource.cart,
                addressToken: 'a',
                quoteToken: 'q',
                courierKey: 'k',
                insurance: false,
                useCoin: true),
            idempotencyKey: 'x');
      } on CheckoutFailure catch (e) {
        return e;
      }
      throw StateError('应抛 CheckoutFailure');
    }

    test('超时（无响应）→ generic + outcomeUnknown', () async {
      final e = await run((o) => throw DioException(
          requestOptions: o, type: DioExceptionType.receiveTimeout));
      expect(e.kind, CheckoutFailureKind.generic);
      expect(e.outcomeUnknown, isTrue);
    });

    test('5xx → outcomeUnknown', () async {
      expect((await run((o) => _json(503, {'status': 503}))).outcomeUnknown, isTrue);
    });

    test('409 conflict「订单正在提交中」→ outcomeUnknown', () async {
      final e = await run((o) => _json(409,
          {'type': 'https://api.tailtopia.app/problems/conflict', 'status': 409}));
      expect(e.outcomeUnknown, isTrue);
    });

    test('明确业务错误（422 validation / not-placeable / coupon-invalid）→ 不是 outcomeUnknown', () async {
      for (final slug in ['validation', 'not-placeable', 'coupon-invalid']) {
        final e = await run((o) => _json(slug == 'coupon-invalid' ? 409 : 422,
            {'type': 'https://api.tailtopia.app/problems/$slug', 'status': 422}));
        expect(e.outcomeUnknown, isFalse, reason: slug);
      }
    });
  });

  group('CheckoutPreview.fromJson（后端 CheckoutQuoteView）', () {
    test('失效行拆进 unavailableLines；无报价时金额位不可展示', () {
      final p = CheckoutPreview.fromJson({
        'source': 'CART',
        'lines': [
          {'skuToken': 's1', 'specName': '1kg', 'price': 1000, 'qty': 1, 'returnPolicy': 'RETURNABLE'},
          {
            'skuToken': 's2',
            'specName': '2kg',
            'price': 2000,
            'qty': 3,
            'returnPolicy': 'RETURNABLE',
            'unavailableReason': 'INSUFFICIENT_STOCK',
            'availableStock': 1,
          },
        ],
        'pricing': {'goodsAmount': 1000, 'discountCouponAmount': 0, 'insuranceFee': 0, 'payable': 1000,
          'coinAmount': 0, 'cashAmount': 1000, 'savedAmount': 0, 'shippingIncluded': false,
          'shippingDiscountSource': 'NONE'},
        'placeable': false,
        'notPlaceableReason': 'UNAVAILABLE_LINES',
      });
      expect(p.lines.single.skuToken, 's1');
      expect(p.unavailableLines.single.toUnavailable().available, 1);
      expect(p.unavailableLines.single.toUnavailable().requested, 3);
      expect(p.blockReason, CheckoutBlockReason.unavailableLines);
      expect(p.amountsKnown, isFalse);
      expect(p.pricing.shippingFee, isNull);
      expect(p.canSubmit, isFalse);
    });

    test('未知 notPlaceableReason → unknown（不可提交）；未知券原因 → unknown', () {
      final p = CheckoutPreview.fromJson({
        'notPlaceableReason': 'SOMETHING_NEW',
        'coupons': {
          'discount': {
            'available': [],
            'unavailable': [
              {'userCouponToken': 'u1', 'kind': 'DISCOUNT', 'deduction': 0, 'unavailableReason': 'WHAT'},
            ],
          },
        },
      });
      expect(p.blockReason, CheckoutBlockReason.unknown);
      expect(p.discountCoupons.unavailable.single.reason, CouponUnavailableReason.unknown);
    });
  });
}

const _minimalPreview = <String, dynamic>{'source': 'CART', 'lines': [], 'placeable': false};

Dio _dio(_Adapter a) => Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = a;

ResponseBody _json(int status, Map<String, dynamic> body) => ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );

class _Adapter implements HttpClientAdapter {
  _Adapter(this.handler);

  final ResponseBody Function(RequestOptions) handler;
  RequestOptions? last;

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) async {
    last = options;
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}
