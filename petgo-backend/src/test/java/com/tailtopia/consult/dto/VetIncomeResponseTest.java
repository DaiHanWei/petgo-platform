package com.tailtopia.consult.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.dto.VetIncomeResponse.VetIncomePeriodItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0（无 DB/Redis）。Story 3.7 收入 DTO 契约：当月待结算（空聚合零值 + 有值映射）、历史月结映射。
 */
class VetIncomeResponseTest {

    @Test
    void currentMonthFromAggregate() {
        VetPayoutAggregate agg = new VetPayoutAggregate(5L, 2L, 100000L, 60000L);
        VetIncomePeriodItem item = VetIncomePeriodItem.currentMonth("2026-07", agg);

        assertThat(item.period()).isEqualTo("2026-07");
        assertThat(item.orderCount()).isEqualTo(2);
        assertThat(item.grossAmount()).isEqualTo(100000L);
        assertThat(item.payoutAmount()).isEqualTo(60000L);
        assertThat(item.status()).isEqualTo("PENDING"); // 当月恒待结算
    }

    @Test
    void currentMonthZeroWhenNoAggregate() {
        VetIncomePeriodItem item = VetIncomePeriodItem.currentMonth("2026-07", null);
        assertThat(item.orderCount()).isZero();
        assertThat(item.payoutAmount()).isZero();
        assertThat(item.grossAmount()).isZero();
        assertThat(item.status()).isEqualTo("PENDING");
    }

    @Test
    void historyItemFromSettlement() {
        VetSettlement s = VetSettlement.of(5L, "2026-05", 3, 150000L, 90000L, Instant.now());
        VetIncomePeriodItem item = VetIncomePeriodItem.ofSettlement(s);

        assertThat(item.period()).isEqualTo("2026-05");
        assertThat(item.orderCount()).isEqualTo(3);
        assertThat(item.payoutAmount()).isEqualTo(90000L);
        assertThat(item.status()).isEqualTo("PENDING"); // of() 建 PENDING
    }
}
