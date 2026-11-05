package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.2 AC3）：支付方式两段状态推导（F4 / F7 / F12 / F20 表驱动）。 */
class PaymentMethodsViewTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private static ShopOrder order(ShopOrderStatus status, Long coin, Long cash, Instant expiresAt) {
        ShopOrder o = ShopOrder.place("tok", "TOKO-20261105-AAAAAA", NOW.minusSeconds(600), 7L, 200_000L, 20_000L,
                0L, new AddressSnapshot("Budi", "+628123", "DKI", "Jaksel", "Kebayoran", "Jl. 1", "12160"));
        if (coin != null || cash != null) {
            ReflectionTestUtils.setField(o, "payChannel", PayChannel.MIXED);
        }
        ReflectionTestUtils.setField(o, "coinAmount", coin);
        ReflectionTestUtils.setField(o, "cashAmount", cash);
        ReflectionTestUtils.setField(o, "status", status);
        ReflectionTestUtils.setField(o, "expiresAt", expiresAt);
        return o;
    }

    @Test
    @DisplayName("F4 待支付（窗内）：币 HELD、QRIS AWAITING")
    void pending() {
        PaymentMethodsView v = PaymentMethodsView.of(order(ShopOrderStatus.PENDING_PAYMENT, 20_000L, 200_000L,
                NOW.plusSeconds(600)), NOW);
        assertThat(v).isEqualTo(new PaymentMethodsView(20_000L, "HELD", 200_000L, "AWAITING"));
    }

    @Test
    @DisplayName("F7 已付款：两段 PAID")
    void paid() {
        ShopOrder o = order(ShopOrderStatus.SHIPPED, 20_000L, 200_000L, NOW.minusSeconds(3000));
        ReflectionTestUtils.setField(o, "paidAt", NOW.minusSeconds(3300));
        assertThat(PaymentMethodsView.of(o, NOW)).isEqualTo(new PaymentMethodsView(20_000L, "PAID", 200_000L, "PAID"));
    }

    @Test
    @DisplayName("F12 超时取消：币 RETURNED、QRIS UNPAID；宽限期（未关单）：币仍 HELD、QRIS UNPAID")
    void timeoutCancelled() {
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.CANCELLED, 20_000L, 200_000L,
                NOW.minusSeconds(600)), NOW))
                .isEqualTo(new PaymentMethodsView(20_000L, "RETURNED", 200_000L, "UNPAID"));
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.PENDING_PAYMENT, 20_000L, 200_000L,
                NOW.minusSeconds(60)), NOW))
                .isEqualTo(new PaymentMethodsView(20_000L, "HELD", 200_000L, "UNPAID"));
    }

    @Test
    @DisplayName("F20 取消后晚到：QRIS PAID（late_paid_at），币仍 RETURNED")
    void latePaid() {
        ShopOrder o = order(ShopOrderStatus.CANCELLED, 20_000L, 200_000L, NOW.minusSeconds(600));
        ReflectionTestUtils.setField(o, "latePaidAt", NOW.minusSeconds(60));
        ReflectionTestUtils.setField(o, "latePaidAmount", 200_000L);
        assertThat(PaymentMethodsView.of(o, NOW))
                .isEqualTo(new PaymentMethodsView(20_000L, "RETURNED", 200_000L, "PAID"));
    }

    @Test
    @DisplayName("纯币单：QRIS 项整项 null；纯 QRIS 单：币项整项 null（金额 0 视同无）")
    void singleChannel() {
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.PENDING_PAYMENT, 220_000L, 0L,
                NOW.plusSeconds(600)), NOW)).isEqualTo(new PaymentMethodsView(220_000L, "HELD", null, null));
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.PENDING_PAYMENT, null, 220_000L,
                NOW.plusSeconds(600)), NOW)).isEqualTo(new PaymentMethodsView(null, null, 220_000L, "AWAITING"));
    }

    @Test
    @DisplayName("存量订单：两段都为 null → 整个对象 null；paid_at 列上线前已付款（paid_at 空但已完成）→ 不说成未支付")
    void stockOrders() {
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.COMPLETED, null, null, null), NOW)).isNull();
        assertThat(PaymentMethodsView.of(order(ShopOrderStatus.COMPLETED, null, 220_000L, null), NOW))
                .isEqualTo(new PaymentMethodsView(null, null, 220_000L, "PAID"));
    }
}
