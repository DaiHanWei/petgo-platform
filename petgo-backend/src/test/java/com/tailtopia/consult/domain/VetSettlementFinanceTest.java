package com.tailtopia.consult.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.consult.dto.VetIncomeResponse.VetIncomePeriodItem;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0（Story 9.5）：月结财务状态机 PENDING_FINANCE→PAID→ARCHIVED + guard + 兽医侧 3→2 态映射。 */
class VetSettlementFinanceTest {

    private VetSettlement pending() {
        return VetSettlement.of(9L, "2026-06", 3, 150000L, 90000L, Instant.now());
    }

    @Test
    void generatedIsPendingFinance() {
        assertThat(pending().getStatus()).isEqualTo("PENDING_FINANCE");
    }

    @Test
    void payThenArchiveHappyPath() {
        VetSettlement s = pending();
        s.markPaid("TRX-123", 7L);
        assertThat(s.getStatus()).isEqualTo("PAID");
        assertThat(s.getPaymentProof()).isEqualTo("TRX-123");
        assertThat(s.getPaidAt()).isNotNull();
        assertThat(s.getSettledBy()).isEqualTo(7L);

        s.archive(7L);
        assertThat(s.getStatus()).isEqualTo("ARCHIVED");
        assertThat(s.getArchivedAt()).isNotNull();
    }

    @Test
    void cannotArchiveWhilePendingFinance() {
        assertThatThrownBy(() -> pending().archive(7L)).isInstanceOf(AppException.class);
    }

    @Test
    void cannotPayTwice() {
        VetSettlement s = pending();
        s.markPaid("TRX-1", 7L);
        assertThatThrownBy(() -> s.markPaid("TRX-2", 7L)).isInstanceOf(AppException.class);
    }

    @Test
    void cannotArchiveWhenAlreadyArchived() {
        VetSettlement s = pending();
        s.markPaid("TRX-1", 7L);
        s.archive(7L);
        assertThatThrownBy(() -> s.archive(7L)).isInstanceOf(AppException.class);
    }

    @Test
    void vetFacingMappingCollapses3To2States() {
        VetSettlement s = pending();
        // PENDING_FINANCE → PENDING
        assertThat(VetIncomePeriodItem.ofSettlement(s).status()).isEqualTo("PENDING");
        // PAID → SETTLED
        s.markPaid("TRX", 7L);
        assertThat(VetIncomePeriodItem.ofSettlement(s).status()).isEqualTo("SETTLED");
        // ARCHIVED → SETTLED
        s.archive(7L);
        assertThat(VetIncomePeriodItem.ofSettlement(s).status()).isEqualTo("SETTLED");
    }
}
