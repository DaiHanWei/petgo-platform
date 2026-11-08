package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.shop.order.dto.RefundDestinationRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：v1.4.2 shop Story 4.1 AC5.3 / AC5.4（m1）—— 成长值扣回事件三处各一次、键按退款单 + 段、迟到款不发。 */
class RefundGrowthEventTest {

    private final RefundTestRig rig = new RefundTestRig();

    private List<RefundSucceededEvent> events() {
        ArgumentCaptor<Object> c = ArgumentCaptor.forClass(Object.class);
        verify(rig.events, atLeast(0)).publishEvent(c.capture());
        return c.getAllValues().stream().filter(RefundSucceededEvent.class::isInstance)
                .map(RefundSucceededEvent.class::cast).toList();
    }

    private void choose(ShopOrderRefund r, String destination, String channel) {
        rig.service.chooseDestination(RefundTestRig.USER, RefundTestRig.ORDER_TOKEN, r.getPublicToken(),
                new RefundDestinationRequest(destination, channel, "1234567890", "Budi"));
    }

    @Test
    @DisplayName("币段退回：SHOPREF:{t}:COIN，金额 = 币段，分母 = paidTotal")
    void coinSegment() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        assertThat(events()).singleElement().satisfies(e -> {
            assertThat(e.refundKey()).isEqualTo("SHOPREF:" + r.getPublicToken() + ":COIN");
            assertThat(e.refundAmount()).isEqualTo(100_000L);
            assertThat(e.originalAmount()).isEqualTo(329_575L);
            assertThat(e.purpose()).isEqualTo(PaymentPurpose.SHOP_ORDER);
            assertThat(e.bizRefType()).isEqualTo("SHOP_ORDER");
            assertThat(e.bizRefId()).isEqualTo(String.valueOf(RefundTestRig.ORDER_ID));
            assertThat(e.userId()).isEqualTo(RefundTestRig.USER);
        });
    }

    @Test
    @DisplayName("现金选 PawCoin：SHOPREF:{t}:CASH_PAWCOIN，金额 = 现金段（补偿不计入）")
    void cashToPawcoin() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        choose(r, "TO_PAWCOIN", null);
        List<RefundSucceededEvent> es = events();
        assertThat(es).extracting(RefundSucceededEvent::refundKey).containsExactly(
                "SHOPREF:" + r.getPublicToken() + ":COIN", "SHOPREF:" + r.getPublicToken() + ":CASH_PAWCOIN");
        assertThat(es.get(1).refundAmount()).isEqualTo(229_575L);
    }

    @Test
    @DisplayName("TO_BANK：选择时不发，标记已打款才发 SHOPREF:{t}:CASH_BANK；重复标记不重复发")
    void cashToBank() {
        rig.service.cancelWhole(RefundTestRig.ORDER_TOKEN, CancelReason.OUT_OF_STOCK, null, 7L);
        ShopOrderRefund r = rig.only();
        choose(r, "TO_BANK", "OVO");
        assertThat(events()).hasSize(1);

        rig.service.markPaidOut(r.getPublicToken(), 8L);
        rig.service.markPaidOut(r.getPublicToken(), 8L);
        List<RefundSucceededEvent> es = events();
        assertThat(es).extracting(RefundSucceededEvent::refundKey).containsExactly(
                "SHOPREF:" + r.getPublicToken() + ":COIN", "SHOPREF:" + r.getPublicToken() + ":CASH_BANK");
        assertThat(es.get(1).refundAmount()).isEqualTo(229_575L);
    }

    @Test
    @DisplayName("部分取消多张退款单：键各不相同（不被 GrowthRefundListener 去重吞掉）")
    void distinctKeysPerRefund() {
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        rig.service.cancelLine(RefundTestRig.ORDER_TOKEN, 1L, 1, CancelReason.OUT_OF_STOCK, null, 7L);
        assertThat(events()).extracting(RefundSucceededEvent::refundKey).doesNotHaveDuplicates().hasSize(2);
    }

    @Test
    @DisplayName("🔴 LATE_PAYMENT 退款单：建单、选 PawCoin、标记已打款三处都不发")
    void latePaymentNeverPublishes() {
        ShopOrderRefund a = rig.service.createLatePaymentRefund(rig.order, 50_000L, 7L);
        choose(a, "TO_PAWCOIN", null);
        ShopOrderRefund b = rig.service.createLatePaymentRefund(rig.order, 60_000L, 7L);
        choose(b, "TO_BANK", "BCA");
        rig.service.markPaidOut(b.getPublicToken(), 8L);
        verify(rig.events, never()).publishEvent(org.mockito.ArgumentMatchers.any(RefundSucceededEvent.class));
        assertThat(a.getCompensationAmount()).isZero();
    }
}
