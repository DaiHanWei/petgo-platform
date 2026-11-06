package com.tailtopia.shop.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 2.1 AC5 —— 可用 / 过期现算，区间左闭右开。 */
class UserCouponTest {

    private static final Instant FROM = Instant.parse("2026-11-05T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-10T16:59:59Z");

    private static UserCoupon coupon(UserCouponStatus status) {
        UserCoupon c = new UserCoupon();
        ReflectionTestUtils.setField(c, "status", status);
        ReflectionTestUtils.setField(c, "validFrom", FROM);
        ReflectionTestUtils.setField(c, "validUntil", UNTIL);
        return c;
    }

    @Test
    void usableIsLeftClosedRightOpen() {
        UserCoupon c = coupon(UserCouponStatus.AVAILABLE);
        assertThat(c.isUsableAt(FROM.minusSeconds(1))).isFalse();
        assertThat(c.isUsableAt(FROM)).isTrue();
        assertThat(c.isUsableAt(UNTIL.minusMillis(1))).isTrue();
        assertThat(c.isUsableAt(UNTIL)).isFalse();
    }

    @Test
    void onlyAvailableIsUsable() {
        Instant mid = FROM.plusSeconds(3600);
        assertThat(coupon(UserCouponStatus.LOCKED).isUsableAt(mid)).isFalse();
        assertThat(coupon(UserCouponStatus.USED).isUsableAt(mid)).isFalse();
        assertThat(coupon(UserCouponStatus.VOIDED).isUsableAt(mid)).isFalse();
    }

    @Test
    void expiredIsComputedFromValidUntil() {
        UserCoupon c = coupon(UserCouponStatus.AVAILABLE);
        assertThat(c.isExpiredAt(UNTIL.minusMillis(1))).isFalse();
        assertThat(c.isExpiredAt(UNTIL)).isTrue();
        assertThat(c.isExpiredAt(UNTIL.plusSeconds(86400))).isTrue();
        // LOCKED / USED / VOIDED 不算过期（VOIDED 用户端不展示）
        assertThat(coupon(UserCouponStatus.LOCKED).isExpiredAt(UNTIL.plusSeconds(1))).isFalse();
        assertThat(coupon(UserCouponStatus.USED).isExpiredAt(UNTIL.plusSeconds(1))).isFalse();
        assertThat(coupon(UserCouponStatus.VOIDED).isExpiredAt(UNTIL.plusSeconds(1))).isFalse();
    }
}
