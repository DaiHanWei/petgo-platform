package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.consult.service.VetSettlementService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1（需 Docker）。Story 3.7 月结生成：聚合 WIB 月 COMPLETED 订单的 vet_payout → vet_settlements(PENDING)。
 *
 * <p>核心：仅 COMPLETED（REFUNDING 排除）；归月按 session_ended_at（WIB 月边界）；幂等（重跑不重复）；空月不生成。
 */
class VetSettlementIntegrationTest extends ApiIntegrationTest {

    private static final YearMonth JUN = YearMonth.of(2026, 6);
    // June WIB 内一时刻（2026-06-15 10:00 WIB = 03:00Z）；界外 5 月末（2026-05-31 16:00Z < June WIB 起点 05-31 17:00Z）。
    private static final Instant IN_JUNE = Instant.parse("2026-06-15T03:00:00Z");
    private static final Instant BEFORE_JUNE = Instant.parse("2026-05-31T16:00:00Z");

    @Autowired
    private VetSettlementService service;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private VetSettlementRepository settlements;

    @BeforeEach
    void clean() {
        settlements.deleteAll();
        orders.deleteAll();
    }

    private void seedCompleted(long vetId, long payout, Instant endedAt) {
        ConsultOrder o = ConsultOrder.inProgress("ord-" + SEQ.incrementAndGet(), 9000L + SEQ.incrementAndGet(),
                vetId, 1L, 50000L, PayChannel.PAWCOIN, null, payout, 60, 50000L, endedAt.minusSeconds(600));
        o.markCompleted(endedAt);
        orders.save(o);
    }

    private void seedRefunding(long vetId, Instant endedAt) {
        ConsultOrder o = ConsultOrder.inProgress("ord-" + SEQ.incrementAndGet(), 9000L + SEQ.incrementAndGet(),
                vetId, 1L, 50000L, PayChannel.PAWCOIN, null, 30000L, 60, 50000L, endedAt.minusSeconds(600));
        ReflectionTestUtils.setField(o, "status", ConsultOrderStatus.REFUNDING);
        ReflectionTestUtils.setField(o, "sessionEndedAt", endedAt);
        orders.save(o);
    }

    @Test
    void generatesPerVetSettlementFromCompletedOrders() {
        long vetA = 8001L;
        long vetB = 8002L;
        seedCompleted(vetA, 30000L, IN_JUNE);
        seedCompleted(vetA, 30000L, IN_JUNE);
        seedCompleted(vetA, 30000L, BEFORE_JUNE); // 界外（5 月）→ 不计
        seedCompleted(vetB, 30000L, IN_JUNE);
        seedRefunding(vetB, IN_JUNE);              // 退款中 → 不计

        int generated = service.generateSettlements(JUN);

        assertThat(generated).isEqualTo(2); // vetA + vetB
        VetSettlement a = settlements.findByVetIdOrderByPeriodDesc(vetA).get(0);
        assertThat(a.getPeriod()).isEqualTo("2026-06");
        assertThat(a.getOrderCount()).isEqualTo(2);        // 界外单不计
        assertThat(a.getPayoutAmount()).isEqualTo(60000L); // 2×30000
        assertThat(a.getGrossAmount()).isEqualTo(100000L); // 2×50000
        assertThat(a.getStatus()).isEqualTo("PENDING");
        VetSettlement b = settlements.findByVetIdOrderByPeriodDesc(vetB).get(0);
        assertThat(b.getOrderCount()).isEqualTo(1);        // 退款单不计
        assertThat(b.getPayoutAmount()).isEqualTo(30000L);
    }

    @Test
    void generationIsIdempotent() {
        long vetId = 8010L;
        seedCompleted(vetId, 30000L, IN_JUNE);

        assertThat(service.generateSettlements(JUN)).isEqualTo(1); // 首次生成
        assertThat(service.generateSettlements(JUN)).isEqualTo(0); // 重跑不重复（唯一 vet+period）
        assertThat(settlements.findByVetIdOrderByPeriodDesc(vetId)).hasSize(1);
    }

    @Test
    void emptyMonthGeneratesNothing() {
        assertThat(service.generateSettlements(JUN)).isZero();
    }

    @Test
    void singleVetAggregateForCurrentMonthReads() {
        long vetId = 8020L;
        seedCompleted(vetId, 30000L, IN_JUNE);
        seedCompleted(vetId, 30000L, IN_JUNE);
        Instant start = JUN.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant();
        Instant end = JUN.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).toInstant();

        Optional<com.tailtopia.consult.dto.VetPayoutAggregate> agg =
                orders.aggregateCompletedForVet(vetId, start, end);

        assertThat(agg).isPresent();
        assertThat(agg.get().orderCount()).isEqualTo(2);
        assertThat(agg.get().payoutAmount()).isEqualTo(60000L);
    }
}
