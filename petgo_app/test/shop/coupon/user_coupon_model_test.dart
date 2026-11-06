import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/coupon/domain/user_coupon.dart';
import 'package:tailtopia/features/shop/domain/checkout_preview.dart';

import 'my_coupons_fakes.dart';

/// L0：我的券模型（v1.4.2 shop Story 9.3 · T1）—— 缺键容错、未知枚举降级、C-3 / C-9 字段。
void main() {
  test('完整 CouponView：字段一一读取（含 C-3 占用、C-9 已使用三字段）', () {
    final c = UserCoupon.fromJson(couponJson(
      kind: 'FREE_SHIPPING',
      amount: 15000,
      scopeType: 'CATEGORY',
      scopeCategories: ['MAKANAN'],
      status: 'USED',
      usedAt: '2026-11-01T03:00:00Z',
      usedOrderNo: 'TOKO-20261101-ABC123',
      usedOrderToken: 'ordTok',
    ));
    expect(c.kind, CouponKind.freeShipping);
    expect(c.amount, 15000);
    expect(c.scopeType, CouponScopeType.category);
    expect(c.scopeCategories, ['MAKANAN']);
    expect(c.status, UserCouponStatus.used);
    expect(c.usedAt, DateTime.utc(2026, 11, 1, 3));
    expect(c.usedOrderNo, 'TOKO-20261101-ABC123');
    expect(c.usedOrderToken, 'ordTok');
    expect(c.isLocked, isFalse);
  });

  test('LOCKED / lockedByPendingOrder 任一即 isLocked', () {
    expect(UserCoupon.fromJson(couponJson(status: 'LOCKED')).isLocked, isTrue);
    expect(UserCoupon.fromJson(couponJson(locked: true)).isLocked, isTrue);
  });

  test('缺键 / 类型错：不抛，降级为空值', () {
    final c = UserCoupon.fromJson(<String, dynamic>{
      'userCouponToken': 'u',
      'amount': 'x',
      'scopeCategories': 'oops',
      'validUntil': 123,
      'kind': 'WHAT',
      'scopeType': 'NEW_SCOPE',
      'status': 'VOIDED',
    });
    expect(c.couponToken, '');
    expect(c.amount, 0);
    expect(c.minSpend, 0);
    expect(c.scopeCategories, isEmpty);
    expect(c.validUntil, isNull);
    expect(c.kind, CouponKind.discount);
    expect(c.scopeType, CouponScopeType.all);
    expect(c.status, UserCouponStatus.unknown);
    expect(c.usedOrderToken, isNull);
  });

  test('页：无 userCouponToken 的条目丢弃；nextCursor 空串视为末页；缺 availableCount → null', () {
    final p = MyCouponPage.fromJson({
      'items': [couponJson(token: 'a'), {'userCouponToken': ''}, 'junk'],
      'nextCursor': '',
    });
    expect(p.items.map((e) => e.userCouponToken), ['a']);
    expect(p.hasMore, isFalse);
    expect(p.availableCount, isNull);
    expect(MyCouponPage.fromJson({'nextCursor': 'c1', 'availableCount': 3}).hasMore, isTrue);
  });

  test('兑换码规范化：大写 + 只留 [A-Z0-9] + 限长 32；不足 4 位不可提交（复审 #3）', () {
    expect(normalizeCouponCode('  kucing2026 '), 'KUCING2026');
    expect(normalizeCouponCode('kuc-ing 20_26!'), 'KUCING2026');
    expect(normalizeCouponCode('A' * 40), 'A' * 32);
    expect(normalizeCouponCode(' ${'b' * 33} '), 'B' * 32);
    expect(isSubmittableCouponCode('ab-c'), isFalse);
    expect(isSubmittableCouponCode('ab1c'), isTrue);
  });
}
