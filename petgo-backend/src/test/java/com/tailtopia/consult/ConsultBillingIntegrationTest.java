package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultStageEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultOrderStageEventRepository;
import com.tailtopia.consult.service.ConsultBillingService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（需 Docker）。上下文启动即验 Flyway V66 + validate（consult_orders/consult_order_stage_events 契约）。
 * 建单 IN_PROGRESS + 首条 PAID 节点；stage events append-only（追加多条不覆盖历史）。
 */
class ConsultBillingIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultBillingService billing;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private ConsultOrderStageEventRepository stageEvents;

    @Test
    void createOrderPersistsInProgressWithPaidStageEvent() {
        long userId = newUser().getId();
        ConsultOrder o = billing.createOrder(userId, 9L, 1L, 50_000L, PayChannel.QRIS, null,
                30_000L, 60, 50_000L, Instant.now());

        ConsultOrder saved = orders.findByOrderToken(o.getOrderToken()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ConsultOrderStatus.IN_PROGRESS);
        assertThat(saved.getVetPayout()).isEqualTo(30_000L);

        var events = stageEvents.findByConsultOrderIdOrderByOccurredAtAsc(o.getId());
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(ConsultStageEvent.PAID);
    }

    @Test
    void markSessionStartedLinksConsultSessionForHistoryPayout() {
        long userId = newUser().getId();
        long vetId = 9L;
        long sessionId = 987654L;
        ConsultOrder o = billing.createOrder(userId, vetId, 1L, 50_000L, PayChannel.QRIS, null,
                30_000L, 60, 50_000L, Instant.now());
        // V88：付费建 IM 会话后回填 consult_session_id（历史「到手金额」按会话取）。
        billing.markSessionStarted(o, Instant.now(), "im:conv-x", sessionId);

        ConsultOrder saved = orders.findByOrderToken(o.getOrderToken()).orElseThrow();
        assertThat(saved.getConsultSessionId()).isEqualTo(sessionId);

        // 批查命中：兽医历史按 (vetId, 会话 id 批) 取该单到手金额快照。
        var byVetSession = orders.findByVetIdAndConsultSessionIdIn(vetId, java.util.List.of(sessionId));
        assertThat(byVetSession).extracting(ConsultOrder::getVetPayout).containsExactly(30_000L);
        // 隔离性：他人会话 id 不误命中。
        assertThat(orders.findByVetIdAndConsultSessionIdIn(vetId, java.util.List.of(sessionId + 1))).isEmpty();
    }

    @Test
    void stageEventsAreAppendOnlyHistory() {
        long userId = newUser().getId();
        ConsultOrder o = billing.createOrder(userId, 9L, 1L, 50_000L, PayChannel.PAWCOIN, null,
                30_000L, 60, 50_000L, Instant.now());
        billing.appendStageEvent(o.getId(), ConsultStageEvent.SESSION_STARTED, Instant.now(), null);
        billing.appendStageEvent(o.getId(), ConsultStageEvent.SESSION_ENDED, Instant.now(), "done");

        var events = stageEvents.findByConsultOrderIdOrderByOccurredAtAsc(o.getId());
        assertThat(events).hasSize(3); // PAID + SESSION_STARTED + SESSION_ENDED，历史全保留不覆盖
        assertThat(events).extracting(e -> e.getEventType()).containsExactly(
                ConsultStageEvent.PAID, ConsultStageEvent.SESSION_STARTED, ConsultStageEvent.SESSION_ENDED);
    }
}
