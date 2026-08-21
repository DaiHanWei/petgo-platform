package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.PaymentSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：支付拆分算法（Story 3.4，FR-100A 规则 2/3/4，🔒 资金攸关）。
 *
 * <p>AC 点名的五种组合全覆盖：纯 QRIS / 纯 Coin / 混合 / 余额为 0 / 余额超上限。
 */
class PaymentSplitTest {

    private static final long CAP = 1_000_000L;

    private static PaymentSplit split(long total, long shipping, long balance) {
        return PaymentSplit.compute(total, shipping, balance, CAP, true, true);
    }

    @Test
    @DisplayName("纯 QRIS：余额为 0 → 全额现金，且【不阻断下单】（规则 2）")
    void zeroBalanceFallsBackToPureCash() {
        PaymentSplit s = split(305_000L, 20_000L, 0L);
        assertThat(s.coinAmount()).isZero();
        assertThat(s.cashAmount()).isEqualTo(305_000L);
        assertThat(s.isPureCash()).isTrue();
        assertThat(s.isMixed()).isFalse();
    }

    @Test
    @DisplayName("纯 Coin：余额足额 → 全额抵扣，现金段为 0")
    void sufficientBalanceCoversAll() {
        PaymentSplit s = split(305_000L, 20_000L, 500_000L);
        assertThat(s.coinAmount()).isEqualTo(305_000L);
        assertThat(s.cashAmount()).isZero();
        assertThat(s.isPureCoin()).isTrue();
        assertThat(s.isMixed()).isFalse();
    }

    @Test
    @DisplayName("混合：余额部分覆盖 → 余额优先全额抵扣，差额 QRIS 补足")
    void partialBalanceProducesMixed() {
        PaymentSplit s = split(305_000L, 20_000L, 100_000L);
        assertThat(s.coinAmount()).isEqualTo(100_000L);
        assertThat(s.cashAmount()).isEqualTo(205_000L);
        assertThat(s.isMixed()).isTrue();
        assertThat(s.coinAmount() + s.cashAmount())
                .as("AD-1 不变式：两段之和必须等于总额").isEqualTo(s.total());
    }

    @Test
    @DisplayName("🔴 规则 3：运费计入总额一并参与抵扣")
    void shippingParticipatesInDeductionByDefault() {
        PaymentSplit s = split(305_000L, 20_000L, 305_000L);
        assertThat(s.coinAmount())
                .as("运费 20.000 也被抵扣了，故 Coin 段是全额而非 285.000")
                .isEqualTo(305_000L);
    }

    @Test
    @DisplayName("规则 3 关闭时：运费部分不可抵扣，现金段至少等于运费")
    void shippingExcludedWhenRuleDisabled() {
        PaymentSplit s = PaymentSplit.compute(305_000L, 20_000L, 305_000L, CAP, true, false);
        assertThat(s.coinAmount()).isEqualTo(285_000L);
        assertThat(s.cashAmount()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("🔴 规则 4：余额超单笔上限 → Coin 段被上限截断，其余走现金")
    void balanceAboveCapIsTruncated() {
        PaymentSplit s = split(3_000_000L, 20_000L, 5_000_000L);
        assertThat(s.coinAmount()).isEqualTo(CAP);
        assertThat(s.cashAmount()).isEqualTo(2_000_000L);
        assertThat(s.isMixed()).isTrue();
    }

    @Test
    @DisplayName("总开关关闭 → 一律纯现金")
    void masterSwitchOffForcesPureCash() {
        PaymentSplit s = PaymentSplit.compute(305_000L, 20_000L, 999_999L, CAP, false, true);
        assertThat(s.coinAmount()).isZero();
        assertThat(s.cashAmount()).isEqualTo(305_000L);
    }

    @Test
    @DisplayName("🔴 coinRatio 只是展示值：即便它有舍入，两段金额仍精确相加等于总额")
    void ratioIsDisplayOnlyAndNeverBreaksTheSum() {
        // 取一个除不尽的比例
        PaymentSplit s = split(300_001L, 0L, 100_000L);
        assertThat(s.coinAmount()).isEqualTo(100_000L);
        assertThat(s.cashAmount()).isEqualTo(200_001L);
        // 🔴 用 ratio 反算会得到 100000.33…，正是 AD-2 禁止拿它参与计算的原因
        assertThat(s.coinAmount() + s.cashAmount()).isEqualTo(s.total());
        assertThat(s.coinRatio().scale()).as("与 NUMERIC(9,6) 对齐").isEqualTo(6);
    }

    @Test
    @DisplayName("边界：总额为 0（全额免运 + 0 元商品）不崩、不产生负数")
    void zeroTotalIsSafe() {
        PaymentSplit s = split(0L, 0L, 100_000L);
        assertThat(s.coinAmount()).isZero();
        assertThat(s.cashAmount()).isZero();
        assertThat(s.total()).isZero();
    }

    @Test
    @DisplayName("上限为 0 → 等同于关闭 Coin 抵扣")
    void zeroCapDisablesCoin() {
        PaymentSplit s = PaymentSplit.compute(305_000L, 20_000L, 999_999L, 0L, true, true);
        assertThat(s.coinAmount()).isZero();
    }
}
