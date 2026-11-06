import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/coupon/domain/user_coupon.dart';

import 'my_coupons_fakes.dart';

/// L0：兑换失败分类（v1.4.2 shop Story 9.3 · AC3.4 / AC3.5，P-5 按 typeSlug、不解析 detail）。
void main() {
  final table = <(int, String?, CouponRedeemError)>[
    (404, 'coupon-not-found', CouponRedeemError.invalid),
    (409, 'coupon-code-used', CouponRedeemError.codeUsed),
    (409, 'coupon-not-in-period', CouponRedeemError.notInPeriod),
    (409, 'coupon-already-claimed', CouponRedeemError.alreadyClaimed),
    (409, 'coupon-sold-out', CouponRedeemError.soldOut),
    (429, 'coupon-redeem-locked', CouponRedeemError.locked),
    (429, null, CouponRedeemError.locked), // 429 无 body 也算锁定
    (429, 'rate-limited', CouponRedeemError.locked),
    (401, 'unauthorized', CouponRedeemError.unauthorized),
    (409, 'coupon-invalid', CouponRedeemError.other), // 结算专用码，兑换接口不该出现 → toast
    (500, null, CouponRedeemError.other),
  ];
  for (final (status, slug, want) in table) {
    test('$status $slug → $want', () {
      expect(CouponRedeemError.from(problem(status, slug)), want);
    });
  }

  test('网络错误 / 非 Dio 异常 → other', () {
    expect(
        CouponRedeemError.from(DioException(
            requestOptions: RequestOptions(), type: DioExceptionType.connectionTimeout)),
        CouponRedeemError.other);
    expect(CouponRedeemError.from(StateError('x')), CouponRedeemError.other);
  });

  test('框下提示：五种失败 + 锁定为 inline；other / unauthorized 不是', () {
    expect(
        CouponRedeemError.values.where((e) => e.isInline).toSet(),
        {
          CouponRedeemError.invalid,
          CouponRedeemError.codeUsed,
          CouponRedeemError.notInPeriod,
          CouponRedeemError.alreadyClaimed,
          CouponRedeemError.soldOut,
          CouponRedeemError.locked,
        });
  });
}
