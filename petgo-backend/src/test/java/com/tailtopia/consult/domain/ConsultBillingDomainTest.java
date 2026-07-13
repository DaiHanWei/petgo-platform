package com.tailtopia.consult.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0 —— Story 3.1 计费实体/枚举纯单测。枚举 UPPER_SNAKE + 无 CANCELLED（A-5 表层保证）+ 工厂初态。
 */
class ConsultBillingDomainTest {

    @Test
    void requestStateHasOnlyTwoLiveStatesNoCancelled() {
        assertThat(ConsultRequestState.values())
                .extracting(Enum::name)
                .containsExactly("QUEUEING", "ACCEPTED_AWAIT_PAY");
    }

    @Test
    void orderStatusHasNoCancelled() {
        assertThat(ConsultOrderStatus.values()).extracting(Enum::name)
                .containsExactly("IN_PROGRESS", "COMPLETED", "REFUNDING", "REFUNDED")
                .doesNotContain("CANCELLED");
    }

    @Test
    void stageEventTypesUpperSnake() {
        assertThat(ConsultStageEvent.values()).extracting(Enum::name).containsExactly(
                "ACCEPTED", "PAID", "SESSION_STARTED", "SESSION_ENDED",
                "REFUND_REQUESTED", "REFUND_COMPLETED", "REFUND_REJECTED");
    }

    @Test
    void queueFactoryStartsQueueing() {
        ConsultRequest r = ConsultRequest.queue(7L, 3L, "tok", Instant.now());
        assertThat(r.getState()).isEqualTo(ConsultRequestState.QUEUEING);
        assertThat(r.getVetId()).isNull(); // 接单前 null
        assertThat(r.getRebroadcastCount()).isZero();
    }

    @Test
    void inProgressFactorySetsStatusAndSnapshots() {
        Instant now = Instant.now();
        ConsultOrder o = ConsultOrder.inProgress("tok", 7L, 9L, 3L, 50000L, PayChannel.QRIS, 42L,
                30000L, 60, 50000L, now);
        assertThat(o.getStatus()).isEqualTo(ConsultOrderStatus.IN_PROGRESS);
        assertThat(o.getVetPayout()).isEqualTo(30000L);
        assertThat(o.getVetShareRateSnapshot()).isEqualTo(60);
        assertThat(o.getUnitPriceSnapshot()).isEqualTo(50000L);
        assertThat(o.getPayChannel()).isEqualTo(PayChannel.QRIS);
        assertThat(o.getPaidAt()).isEqualTo(now);
        assertThat(o.isRefundRejected()).isFalse();
    }

    @Test
    void stageEventFactory() {
        Instant now = Instant.now();
        ConsultOrderStageEvent e = ConsultOrderStageEvent.of(5L, ConsultStageEvent.SESSION_STARTED, now, "n");
        assertThat(e.getConsultOrderId()).isEqualTo(5L);
        assertThat(e.getEventType()).isEqualTo(ConsultStageEvent.SESSION_STARTED);
        assertThat(e.getOccurredAt()).isEqualTo(now);
    }
}
