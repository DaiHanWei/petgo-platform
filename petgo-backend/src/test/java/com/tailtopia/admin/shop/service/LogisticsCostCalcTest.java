package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.2 shop-admin Story 8.1 · AD-15 · AC2）：{@code shipping_discount} 存负数 → 用户实付运费 = fee + discount；
 * 运费净成本 = Biteship 实际运费 + 重发运费 − 用户实付运费，减免不重复计。
 */
class LogisticsCostCalcTest {

    @Test
    void userPaidShippingAddsTheNegativeDiscount() {
        assertThat(LogisticsCostCalc.userPaidShipping(20_000, -20_000)).as("满额全免").isZero();
        assertThat(LogisticsCostCalc.userPaidShipping(20_000, -5_000)).as("封顶减免").isEqualTo(15_000);
        assertThat(LogisticsCostCalc.userPaidShipping(20_000, 0)).isEqualTo(20_000);
    }

    @Test
    void netCostDoesNotCountDiscountTwice() {
        // 运费 20.000、减免 5.000 → 用户实付 15.000；Biteship 实际 18.000、重发 12.000
        long paid = LogisticsCostCalc.userPaidShipping(20_000, -5_000);
        long net = LogisticsCostCalc.netShippingCost(18_000, 12_000, paid);
        assertThat(net).isEqualTo(18_000 + 12_000 - 15_000);
        // 错误写法（再加一次减免）会得到 20.000 —— 钉住不是它
        assertThat(net).isNotEqualTo(18_000 + 12_000 - 15_000 + 5_000);
    }

    @Test
    void netCostCanBeNegativeWhenUsersPaidMore() {
        assertThat(LogisticsCostCalc.netShippingCost(10_000, 0, 15_000)).isEqualTo(-5_000);
    }
}
