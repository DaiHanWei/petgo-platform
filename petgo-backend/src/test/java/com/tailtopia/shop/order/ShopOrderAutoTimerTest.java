package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** L0：v1.4.2 shop Story 5.2 AC1.5 / AC2.3 —— 带异常不到期；清除后从 timer_resumed_at 重新计时（14 天 / 7 天边界）。 */
class ShopOrderAutoTimerTest {

    private static final Instant SHIPPED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);

    private static ShopOrder shipped() {
        ShopOrder o = ShopOrder.place("tok", "TOKO-20261001-ABC123", SHIPPED_AT.minus(DAY), 1L, 285_000L, 20_000L,
                0L, new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        o.markShipped(SHIPPED_AT);
        return o;
    }

    @Test
    @DisplayName("无异常、无 timer_resumed_at：与原逻辑一致（发货 14 天整不到期、超过才到期）")
    void deliverUnchangedWithoutException() {
        ShopOrder o = shipped();
        assertThat(o.getTimerResumedAt()).isNull();
        assertThat(o.isAutoDeliverDue(SHIPPED_AT.plus(ShopOrder.AUTO_DELIVER_AFTER))).isFalse();
        assertThat(o.isAutoDeliverDue(SHIPPED_AT.plus(ShopOrder.AUTO_DELIVER_AFTER).plusSeconds(1))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(LogisticsException.class)
    @DisplayName("带任一异常标记 → 自动送达不到期（发货 20 天前）")
    void anyExceptionBlocksDeliver(LogisticsException type) {
        ShopOrder o = shipped();
        o.flagLogisticsException(type, SHIPPED_AT.plus(DAY));
        assertThat(o.isAutoDeliverDue(SHIPPED_AT.plus(Duration.ofDays(20)))).isFalse();
    }

    @Test
    @DisplayName("AC2.3：发货 20 天前、清除于 1 天前 → 不到期；清除于 15 天前 → 到期；以 timer_resumed_at 为准的 14 天边界")
    void deliverRestartsFromResume() {
        Instant now = SHIPPED_AT.plus(Duration.ofDays(20));
        ShopOrder recent = shipped();
        recent.flagLogisticsException(LogisticsException.STALLED, SHIPPED_AT.plus(Duration.ofDays(3)));
        recent.clearLogisticsException(now.minus(DAY));
        assertThat(recent.isAutoDeliverDue(now)).isFalse();

        ShopOrder old = shipped();
        old.flagLogisticsException(LogisticsException.STALLED, SHIPPED_AT.plus(Duration.ofDays(3)));
        old.clearLogisticsException(now.minus(Duration.ofDays(15)));
        assertThat(old.isAutoDeliverDue(now)).isTrue();

        Instant resumed = old.getTimerResumedAt();
        assertThat(old.isAutoDeliverDue(resumed.plus(ShopOrder.AUTO_DELIVER_AFTER))).isFalse();
        assertThat(old.isAutoDeliverDue(resumed.plus(ShopOrder.AUTO_DELIVER_AFTER).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("timer_resumed_at 早于发货时刻（异常发生在发货前）→ 起点仍为发货时刻")
    void resumeBeforeShippedUsesShipped() {
        ShopOrder o = ShopOrder.place("tok", "TOKO-20261001-ABC123", SHIPPED_AT.minus(DAY), 1L, 285_000L, 20_000L,
                0L, new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        o.flagLogisticsException(LogisticsException.PICKUP_FAILED, SHIPPED_AT.minus(DAY));
        o.clearLogisticsException(SHIPPED_AT.minusSeconds(3600));
        o.markShipped(SHIPPED_AT);
        assertThat(o.isAutoDeliverDue(SHIPPED_AT.plus(ShopOrder.AUTO_DELIVER_AFTER))).isFalse();
        assertThat(o.isAutoDeliverDue(SHIPPED_AT.plus(ShopOrder.AUTO_DELIVER_AFTER).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("自动完成：无异常与原逻辑一致（7 天边界）；带异常不到期")
    void completeBasics() {
        ShopOrder o = shipped();
        Instant delivered = SHIPPED_AT.plus(Duration.ofDays(2));
        o.markDelivered(delivered, DeliverySource.ADMIN_MARK);
        assertThat(o.isAutoCompleteDue(delivered.plus(ShopOrder.AUTO_COMPLETE_AFTER))).isFalse();
        assertThat(o.isAutoCompleteDue(delivered.plus(ShopOrder.AUTO_COMPLETE_AFTER).plusSeconds(1))).isTrue();

        o.confirmLogisticsException(LogisticsException.DAMAGED, true, delivered.plus(DAY));
        assertThat(o.isAutoCompleteDue(delivered.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    @DisplayName("AC2.3：送达 10 天前、清除于 2 天前 → 不完成；清除满 7 天后才完成；签收时刻与退货窗口不受影响")
    void completeRestartsFromResume() {
        Instant now = SHIPPED_AT.plus(Duration.ofDays(20));
        Instant delivered = now.minus(Duration.ofDays(10));
        ShopOrder o = shipped();
        o.markDelivered(delivered, DeliverySource.ADMIN_MARK);
        Instant windowEnd = o.returnWindowEndsAt();
        o.confirmLogisticsException(LogisticsException.LOST, true, delivered.plus(DAY));
        o.clearLogisticsException(now.minus(Duration.ofDays(2)));
        assertThat(o.isAutoCompleteDue(now)).isFalse();
        assertThat(o.isAutoCompleteDue(o.getTimerResumedAt().plus(ShopOrder.AUTO_COMPLETE_AFTER).plusSeconds(1)))
                .isTrue();
        assertThat(o.getDeliveredAt()).isEqualTo(delivered);
        assertThat(o.returnWindowEndsAt()).isEqualTo(windowEnd);
    }
}
