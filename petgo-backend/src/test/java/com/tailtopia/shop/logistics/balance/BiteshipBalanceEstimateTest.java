package com.tailtopia.shop.logistics.balance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.logistics.balance.BiteshipBalanceEstimate.Waybill;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 4.1 · AC5）：预估余额 = 上次录入 − 此后未取消 Biteship 运单的运费与保价。 */
class BiteshipBalanceEstimateTest {

    @Test
    void notRecordedShowsNothingAndNeverAlerts() {
        var r = BiteshipBalanceEstimate.estimate(null, List.of(new Waybill(10_000, 500, false)), false);
        assertThat(r.recorded()).isFalse();
        assertThat(r.below(500_000)).isFalse();
    }

    @Test
    void deductsCarrierCostPlusInsuranceCostOfNonCancelledWaybills() {
        var r = BiteshipBalanceEstimate.estimate(2_000_000L, List.of(
                new Waybill(20_000, 1_000, false),
                new Waybill(15_000, 0, false),
                new Waybill(30_000, 2_000, true)), false);   // 已取消不扣
        assertThat(r.recorded()).isTrue();
        assertThat(r.lastAmount()).isEqualTo(2_000_000L);
        assertThat(r.count()).isEqualTo(2);
        assertThat(r.deducted()).isEqualTo(36_000L);
        assertThat(r.estimate()).isEqualTo(1_964_000L);
    }

    @Test
    void priceIncludingInsuranceDoesNotDeductInsuranceTwice() {
        var r = BiteshipBalanceEstimate.estimate(100_000L, List.of(new Waybill(20_000, 1_000, false)), true);
        assertThat(r.deducted()).isEqualTo(20_000L);
        assertThat(r.estimate()).isEqualTo(80_000L);
    }

    @Test
    void recordedWithNoWaybillsEqualsLastAmount() {
        var r = BiteshipBalanceEstimate.estimate(500_000L, List.of(), false);
        assertThat(r.count()).isZero();
        assertThat(r.estimate()).isEqualTo(500_000L);
    }

    @Test
    void estimateMayGoNegative() {
        var r = BiteshipBalanceEstimate.estimate(10_000L, List.of(new Waybill(25_000, 0, false)), false);
        assertThat(r.estimate()).isEqualTo(-15_000L);
        assertThat(r.below(1)).isTrue();
    }

    @Test
    void belowIsStrictAndZeroThresholdMeansNoAlert() {
        var r = BiteshipBalanceEstimate.estimate(500_000L, List.of(), false);
        assertThat(r.below(500_000)).isFalse();
        assertThat(r.below(500_001)).isTrue();
        assertThat(r.below(0)).isFalse();
    }

    @Test
    void rupiahFormatUsesDotGrouping() {
        assertThat(BiteshipBalanceService.rp(1_284_000)).isEqualTo("Rp 1.284.000");
        assertThat(BiteshipBalanceService.rp(0)).isEqualTo("Rp 0");
    }
}
