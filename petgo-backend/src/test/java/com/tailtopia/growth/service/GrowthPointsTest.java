package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 1.1（L0）：按金额换算，BigDecimal + HALF_UP（§5.3）。 */
class GrowthPointsTest {

    @Test
    void giftExamplesFromPrd() {
        assertThat(GrowthPoints.ofAmount(12_500, 5, 1000)).isEqualTo(63); // 62.5 → 63
        assertThat(GrowthPoints.ofAmount(12_500, 4, 1000)).isEqualTo(50);
    }

    @Test
    void halfUpBoundary() {
        assertThat(GrowthPoints.ofAmount(2_500, 5, 1000)).isEqualTo(13); // 12.5 → 13
        assertThat(GrowthPoints.ofAmount(2_499, 5, 1000)).isEqualTo(12);
        assertThat(GrowthPoints.ofAmount(99, 5, 1000)).isEqualTo(0);   // 0.495 → 0
        assertThat(GrowthPoints.ofAmount(100, 5, 1000)).isEqualTo(1);  // 0.5 → 1
    }

    @Test
    void hugeAmountIsClampedNotThrown() {
        assertThat(GrowthPoints.ofAmount(Long.MAX_VALUE, 5, 1)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void zeroOrInvalidIsZero() {
        assertThat(GrowthPoints.ofAmount(0, 5, 1000)).isZero();
        assertThat(GrowthPoints.ofAmount(-1, 5, 1000)).isZero();
        assertThat(GrowthPoints.ofAmount(1000, 5, null)).isZero();
        assertThat(GrowthPoints.ofAmount(1000, 5, 0)).isZero();
    }
}
