package com.tailtopia.shop.shipping.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 4.1 · AC4 · O-27）：门槛 &gt; 0 时最高减免必须 &gt; 0；门槛为 0 不校验封顶。 */
class FreeShippingCapValidationTest {

    @Test
    void thresholdAboveZeroRequiresPositiveCap() {
        assertThatThrownBy(() -> AdminShippingZoneService.validateFreeShipping(150_000, null))
                .isInstanceOf(AppException.class).hasMessageContaining("最高减免必填");
        assertThatThrownBy(() -> AdminShippingZoneService.validateFreeShipping(150_000, 0L))
                .isInstanceOf(AppException.class).hasMessageContaining("最高减免必填");
        assertThatCode(() -> AdminShippingZoneService.validateFreeShipping(150_000, 20_000L)).doesNotThrowAnyException();
    }

    @Test
    void zeroThresholdSkipsCapCheck() {
        assertThatCode(() -> AdminShippingZoneService.validateFreeShipping(0, null)).doesNotThrowAnyException();
        assertThatCode(() -> AdminShippingZoneService.validateFreeShipping(0, 0L)).doesNotThrowAnyException();
    }

    @Test
    void negativesRejected() {
        assertThatThrownBy(() -> AdminShippingZoneService.validateFreeShipping(-1, 10L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> AdminShippingZoneService.validateFreeShipping(0, -1L)).isInstanceOf(AppException.class);
    }
}
