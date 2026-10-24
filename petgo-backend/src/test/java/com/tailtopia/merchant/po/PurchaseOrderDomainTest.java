package com.tailtopia.merchant.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.merchant.po.domain.PoCancelReason;
import com.tailtopia.merchant.po.domain.PoDeliverySource;
import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：进货单状态机与时刻列（契约 5.2）。 */
class PurchaseOrderDomainTest {

    private static final Instant T0 = Instant.parse("2026-10-24T03:00:00Z");

    private static PurchaseOrder newOrder() {
        return PurchaseOrder.place("tok", "PO-261024-ABCDEF", 42L, PoShippingMethod.COD_SHIPPING, 90_000,
                new AddressSnapshot("n", "+6281", "p", "k", "c", "a", "12345"), "Toko", "+6281", T0);
    }

    @Test
    @DisplayName("付款截止 = 建单 + 60 分钟；到点（含正好等于）即视为过期")
    void deadlineIsInclusive() {
        PurchaseOrder po = newOrder();
        assertThat(po.getPaymentDeadline()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
        assertThat(po.isPaymentOverdueAt(T0.plus(Duration.ofMinutes(60)).minusMillis(1))).isFalse();
        assertThat(po.isPaymentOverdueAt(T0.plus(Duration.ofMinutes(60)))).isTrue();
    }

    @Test
    @DisplayName("超时取消：cancelled_at = payment_deadline，原因 PAYMENT_TIMEOUT，之后不能再付")
    void timeoutCancel() {
        PurchaseOrder po = newOrder();
        po.cancelForTimeout(T0.plus(Duration.ofHours(3)));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(po.getCancelReason()).isEqualTo(PoCancelReason.PAYMENT_TIMEOUT);
        assertThat(po.getCancelledAt()).isEqualTo(po.getPaymentDeadline());
        assertThatThrownBy(() -> po.markPaid(T0)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("履约：第一个包裹转 SHIPPED 并记时刻，后续包裹不改；送达、完成各写一次")
    void fulfillment() {
        PurchaseOrder po = newOrder();
        assertThatThrownBy(() -> po.recordShipment(T0, T0)).isInstanceOf(AppException.class);
        po.markPaid(T0.plusSeconds(10));
        assertThat(po.getPaidAt()).isEqualTo(T0.plusSeconds(10));

        po.recordShipment(T0.plusSeconds(20), T0.plusSeconds(20));
        po.recordShipment(T0.plusSeconds(30), T0.plusSeconds(30));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.SHIPPED);
        assertThat(po.getShippedAt()).isEqualTo(T0.plusSeconds(20));

        assertThat(po.isAutoDeliverDue(T0.plus(Duration.ofDays(6)))).isFalse();
        assertThat(po.isAutoDeliverDue(T0.plus(Duration.ofDays(8)))).isTrue();
        po.markDelivered(T0.plusSeconds(40), PoDeliverySource.ADMIN_MARK);
        assertThatThrownBy(() -> po.markDelivered(T0.plusSeconds(50), PoDeliverySource.ADMIN_MARK))
                .isInstanceOf(AppException.class);
        assertThat(po.getDeliveredAt()).isEqualTo(T0.plusSeconds(40));

        po.markCompleted(T0.plusSeconds(60));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.COMPLETED);
        assertThat(po.getCompletedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("只开放契约 5.2 的六条边，终态不再变")
    void transitions() {
        assertThat(PurchaseOrderStatus.PENDING_PAYMENT.canTransitionTo(PurchaseOrderStatus.SHIPPED)).isFalse();
        assertThat(PurchaseOrderStatus.PAID.canTransitionTo(PurchaseOrderStatus.CANCELLED)).isFalse();
        assertThat(PurchaseOrderStatus.SHIPPED.canTransitionTo(PurchaseOrderStatus.COMPLETED)).isFalse();
        for (PurchaseOrderStatus s : PurchaseOrderStatus.values()) {
            assertThat(PurchaseOrderStatus.COMPLETED.canTransitionTo(s)).isFalse();
            assertThat(PurchaseOrderStatus.CANCELLED.canTransitionTo(s)).isFalse();
        }
    }
}
