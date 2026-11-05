package com.tailtopia.shop.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 3.3 AC7.1（S-7）—— displayStatusAt 唯一实现。 */
class ShopOrderDisplayStatusTest {

    private static final Instant T = Instant.parse("2026-11-05T10:00:00Z");

    private static ShopOrder order() {
        return ShopOrder.place("tok", "TOKO-1", T, 9L, 200_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628123", "DKI", "Jaksel", "Kebayoran", "Jl. 1", "12160"));
    }

    @Test
    @DisplayName("宽限期（订单窗已过、仍 PENDING_PAYMENT）→ CANCELLED")
    void gracePeriodShowsCancelled() {
        ShopOrder o = order();
        assertThat(o.displayStatusAt(T.plus(Duration.ofMinutes(61)))).isEqualTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getStatus()).as("真实状态不变").isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("订单窗内 → PENDING_PAYMENT（恰在截止时刻仍可付）")
    void insideWindow() {
        ShopOrder o = order();
        assertThat(o.displayStatusAt(T.plus(Duration.ofMinutes(30)))).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(o.displayStatusAt(T.plus(Duration.ofMinutes(60)))).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("其他状态原样（宽限期内到账 → 待发货）")
    void otherStatusesUnchanged() {
        ShopOrder o = order();
        o.markPaid(T.plus(Duration.ofMinutes(62)));
        assertThat(o.displayStatusAt(T.plus(Duration.ofMinutes(63)))).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }
}
