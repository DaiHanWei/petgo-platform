package com.tailtopia.admin.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.settlement.service.AdminSettlementService;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.5）：真 pg——V80 迁移后对账流转 PENDING_FINANCE→PAID→ARCHIVED + 凭证 + 越级拒。
 * schema validate 由上下文启动隐式验证（V80 加列 + CHECK 与实体一致）。
 */
class AdminSettlementIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminSettlementService service;
    @Autowired
    private VetSettlementRepository settlements;

    @Test
    void payThenArchiveFlowPersists() {
        VetSettlement s = settlements.save(
                VetSettlement.of(8100L + SEQ.incrementAndGet(), "2026-05", 2, 100000L, 60000L, Instant.now()));
        assertThat(s.getStatus()).isEqualTo("PENDING_FINANCE");

        service.markPaid(s.getId(), "TRX-INT-1", 1L);
        VetSettlement paid = settlements.findById(s.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaymentProof()).isEqualTo("TRX-INT-1");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getSettledBy()).isEqualTo(1L);

        service.archive(s.getId(), 1L);
        assertThat(settlements.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void illegalTransitionsRejected() {
        VetSettlement s = settlements.save(
                VetSettlement.of(8200L + SEQ.incrementAndGet(), "2026-04", 1, 50000L, 30000L, Instant.now()));
        // 待打款直接归档 → 422
        assertThatThrownBy(() -> service.archive(s.getId(), 1L)).isInstanceOf(AppException.class);
        // 打款后重复打款 → 422
        service.markPaid(s.getId(), "p", 1L);
        assertThatThrownBy(() -> service.markPaid(s.getId(), "p2", 1L)).isInstanceOf(AppException.class);
    }

    @Test
    void listReturnsSeeded() {
        VetSettlement s = settlements.save(
                VetSettlement.of(8300L + SEQ.incrementAndGet(), "2026-03", 1, 50000L, 30000L, Instant.now()));
        assertThat(service.list()).extracting("id").contains(s.getId());
    }
}
