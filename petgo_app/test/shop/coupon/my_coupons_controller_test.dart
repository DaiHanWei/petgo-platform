import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/coupon/domain/user_coupon.dart';
import 'package:tailtopia/features/shop/coupon/presentation/my_coupons_controller.dart';

import 'my_coupons_fakes.dart';

/// L0：我的券控制器（v1.4.2 shop Story 9.3 复审 #1）。
void main() {
  test('加载更多在途时插顶：loadingMore 立即清掉，作废页返回后不再卡转圈', () async {
    final repo = FakeMyCouponRepository(pages: {
      CouponTab.available: [
        MyCouponPage(items: [coupon(token: 'a')], nextCursor: 'c1', availableCount: 2),
        MyCouponPage(items: [coupon(token: 'b')], availableCount: 2),
      ],
    });
    repo.moreGate = Completer<void>();
    final c = ProviderContainer(overrides: [couponRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(c.dispose);
    final sub = c.listen(myCouponsProvider(CouponTab.available), (_, _) {});
    addTearDown(sub.close);
    await c.read(myCouponsProvider(CouponTab.available).future);
    final n = c.read(myCouponsProvider(CouponTab.available).notifier);

    final more = n.loadMore();
    expect(c.read(myCouponsProvider(CouponTab.available)).value!.loadingMore, isTrue);
    n.insertTop(coupon(token: 'new'));
    var s = c.read(myCouponsProvider(CouponTab.available)).value!;
    expect(s.loadingMore, isFalse);
    expect(s.items.first.userCouponToken, 'new');

    repo.moreGate!.complete();
    await more;
    s = c.read(myCouponsProvider(CouponTab.available)).value!;
    expect(s.loadingMore, isFalse);
    expect(s.items.map((e) => e.userCouponToken), ['new', 'a']); // 作废页丢弃，游标仍在可再拉
    expect(s.hasMore, isTrue);
  });
}
