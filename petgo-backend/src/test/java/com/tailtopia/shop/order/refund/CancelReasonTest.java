package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：取消原因整张属性表（v1.4.2 shop Story 3.2 AC2，D-14）。 */
class CancelReasonTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // reason,             platformFault, refundCoupon, refundShipping, compensate
            "USER_UNPAID_CANCEL,  false, true,  true,  false",
            "PAYMENT_TIMEOUT,     false, true,  true,  false",
            "OUT_OF_STOCK,        true,  true,  true,  true",
            "WAYBILL_FAILED,      true,  true,  true,  true",
            "PICKUP_FAILED,       true,  true,  true,  true",
            "COURIER_FAULT,       true,  true,  true,  true",
            "USER_FAULT_RETURNED, false, false, false, false",
            "LOST_DAMAGED,        true,  true,  false, true",
            "LATE_PAYMENT,        false, false, false, false"
    })
    @DisplayName("属性由类型推出，整张表钉住")
    void table(CancelReason reason, boolean platformFault, boolean refundCoupon,
            boolean refundShipping, boolean compensate) {
        assertThat(reason.platformFault()).isEqualTo(platformFault);
        assertThat(reason.refundCoupon()).isEqualTo(refundCoupon);
        assertThat(reason.refundShipping()).isEqualTo(refundShipping);
        assertThat(reason.compensate()).isEqualTo(compensate);
        assertThat(reason.couponReleaseMode())
                .isEqualTo(refundCoupon ? CouponReleaseMode.RETURN : CouponReleaseMode.KEEP);
    }

    @Test
    @DisplayName("恰好 9 个值，与迁移 ck_shop_orders_cancel_reason 同集合（顺序同 D-14）")
    void nineValues() {
        assertThat(Arrays.stream(CancelReason.values()).map(Enum::name)).containsExactly(
                "USER_UNPAID_CANCEL", "PAYMENT_TIMEOUT", "OUT_OF_STOCK", "WAYBILL_FAILED",
                "PICKUP_FAILED", "COURIER_FAULT", "USER_FAULT_RETURNED", "LOST_DAMAGED",
                "LATE_PAYMENT");
    }
}
