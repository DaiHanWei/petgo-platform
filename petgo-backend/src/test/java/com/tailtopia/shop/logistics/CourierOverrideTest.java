package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 7.2 · AD-11 · O-5 · AC6）：重下单原快递不可用 → 改选最便宜一家；仍可用 / 无报价 → 不改。 */
class CourierOverrideTest {

    private static CourierRate rate(String c, String s, long price) {
        return new CourierRate(CourierRate.keyOf(c, s), c, s, c.toUpperCase(), s, "1-2 days", "1-2", "day", price,
                true);
    }

    @Test
    void keepsOriginalWhenStillQuoted() {
        assertThat(BiteshipShipmentService.pickOverride(List.of(rate("jne", "reg", 20_000), rate("sicepat", "reg", 15_000)),
                CourierRate.keyOf("jne", "reg"), false)).isNull();
    }

    @Test
    void picksCheapestWhenOriginalGone() {
        var o = BiteshipShipmentService.pickOverride(List.of(rate("sicepat", "reg", 15_000), rate("anteraja", "reg", 12_000)),
                CourierRate.keyOf("jne", "reg"), false);
        assertThat(o).isNotNull();
        assertThat(o.courierCode()).isEqualTo("anteraja");
        assertThat(o.price()).isEqualTo(12_000);
    }

    @Test
    void noRatesMeansNoOverride() {
        assertThat(BiteshipShipmentService.pickOverride(List.of(), CourierRate.keyOf("jne", "reg"), false)).isNull();
    }

    @Test
    void insuredOrderOnlyPicksInsurableCourier() {
        CourierRate cheapNoIns = new CourierRate(CourierRate.keyOf("x", "reg"), "x", "reg", "X", "reg", "1", "1", "day",
                5_000, false);
        var o = BiteshipShipmentService.pickOverride(List.of(cheapNoIns, rate("sicepat", "reg", 15_000)),
                CourierRate.keyOf("jne", "reg"), true);
        assertThat(o.courierCode()).isEqualTo("sicepat");
        assertThat(BiteshipShipmentService.pickOverride(List.of(cheapNoIns), CourierRate.keyOf("jne", "reg"), true))
                .isNull();
    }
}
