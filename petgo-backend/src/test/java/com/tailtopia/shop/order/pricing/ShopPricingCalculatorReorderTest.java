package com.tailtopia.shop.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：v1.4.2 shop Story 4.2 AC3 —— 迟到款重新下单多退少补（S-6，计价器单点）。 */
class ShopPricingCalculatorReorderTest {

    @ParameterizedTest(name = "#{0} total={1} origCoin={2} late={3} → coin={4} cash={5} excess={6}")
    @CsvSource({
            // 常见：迟到款 = 原现金段
            "1, 329575, 100000, 229575, 100000, 229575, 0",
            // 多出退为 PawCoin
            "2, 329575, 100000, 250000, 100000, 229575, 20425",
            // 差额 29,575 用币补
            "3, 329575, 100000, 200000, 129575, 200000, 0",
            // 纯 QRIS 原单
            "4, 150000, 0, 150000, 0, 150000, 0",
            "5, 150000, 0, 120000, 30000, 120000, 0"})
    @DisplayName("AC3.2 表驱动五例：coin + cash = total，全部 ≥ 0")
    void settlement(int no, long total, long origCoin, long late, long coin, long cash, long excess) {
        ReorderSettlement s = ShopPricingCalculator.reorderSettlement(total, origCoin, late);
        assertThat(s.coinAmount()).isEqualTo(coin);
        assertThat(s.cashAmount()).isEqualTo(cash);
        assertThat(s.excessToPawcoin()).isEqualTo(excess);
        assertThat(s.coinAmount() + s.cashAmount()).isEqualTo(total);
        assertThat(s.coinAmount()).isNotNegative();
        assertThat(s.cashAmount()).isPositive();
        assertThat(s.excessToPawcoin()).isNotNegative();
    }

    @Test
    @DisplayName("AC3.2 #6：迟到款 0 / 负数 → IllegalArgumentException")
    void nonPositiveLateCashRejected() {
        assertThatThrownBy(() -> ShopPricingCalculator.reorderSettlement(150_000L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShopPricingCalculator.reorderSettlement(150_000L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("原币额超过应付 / 应付非正 → IllegalArgumentException（不产生负现金段）")
    void invalidOriginalAmountsRejected() {
        assertThatThrownBy(() -> ShopPricingCalculator.reorderSettlement(100_000L, 100_001L, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShopPricingCalculator.reorderSettlement(0L, 0L, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShopPricingCalculator.reorderSettlement(100_000L, -1L, 10L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
