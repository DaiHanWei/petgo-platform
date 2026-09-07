package com.tailtopia.shop.returns.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.returns.domain.ReturnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：转币【激励】溢价的 <b>C-1 反套利门</b>（D-16，2026-09-02）。
 *
 * <h2>这条守的是资损</h2>
 * {@code premium_rate} / {@code premium_fixed} 这对配置，
 * 迁移 {@code V20260817_2330} 的头注释（「①激励溢价…**「未交付+转币」分支的反套利激励**」）
 * 与 {@code PawCoinConfig.refundPawcoinPremium} 的 javadoc <b>两处都写明</b>是给「未交付」用的，
 * 而 {@code RefundExecutionService} 此前对<b>任何</b> TO_PAWCOIN 退货都给 —— 代码漏了这道门。
 *
 * <p>已交付的退货也给激励，等于<b>付钱请人「买 → 收货 → 退 → 转币」</b>：
 * <ul>
 *   <li>{@code QUALITY_ISSUE} 最糟：平台还承担回程运费、另给补偿溢价；</li>
 *   <li>{@code NON_QUALITY_ISSUE} 自带刹车（用户掏回程运费），但只要激励 &gt; 运费仍有利可图。</li>
 * </ul>
 *
 * <p>⚠️ 当时 {@code premiumRate = 0} 把这件事完全掩盖着 —— 而那是个<b>后台随时可改的值</b>，
 * 改了不会有任何报错。所以这道门必须在调非 0 之前就位，这条测试就是钉住它。
 */
class RefundIncentiveGateTest {

    private static final long CASH = 100_000L;

    @Test
    @DisplayName("🔴 已交付的退货 → 不给转币激励（这正是套利口子）")
    void deliveredReturnsGetNoIncentive() {
        assertThat(RefundExecutionService.incentiveApplies(ReturnType.QUALITY_ISSUE, CASH, true))
                .as("买 → 报质量问题 → 退 → 转币：平台掏回程运费 + 补偿溢价 + 激励溢价")
                .isFalse();
        assertThat(RefundExecutionService.incentiveApplies(ReturnType.NON_QUALITY_ISSUE, CASH, true))
                .as("用户掏回程运费是刹车，但激励 > 运费时仍有利可图")
                .isFalse();
    }

    @Test
    @DisplayName("未交付的退货 → 给（这才是 C-1 想激励的那一支）")
    void undeliveredReturnsGetIncentive() {
        // 拒收：货没离开承运商；发货前取消：无实物往返。都没有"买了再退"这回事。
        assertThat(RefundExecutionService.incentiveApplies(
                ReturnType.REFUSED_ON_DELIVERY, CASH, true)).isTrue();
        assertThat(RefundExecutionService.incentiveApplies(
                ReturnType.CANCEL_BEFORE_SHIPMENT, CASH, true)).isTrue();
    }

    @Test
    @DisplayName("🔴 没有现金段 → 不给（否则 premiumFixed 会凭空发一笔）")
    void noCashSegmentGetsNothing() {
        // 领域公式是 base × rate% + premiumFixed，base=0 时仍会加上 fixed。
        // 而没有现金段就没有「转币」这回事。
        assertThat(RefundExecutionService.incentiveApplies(
                ReturnType.CANCEL_BEFORE_SHIPMENT, 0L, true)).isFalse();
        assertThat(RefundExecutionService.incentiveApplies(
                ReturnType.CANCEL_BEFORE_SHIPMENT, -1L, true)).isFalse();
    }

    @Test
    @DisplayName("无配置 / 类型缺失 → 不给，且不抛")
    void missingConfigOrTypeIsSafe() {
        assertThat(RefundExecutionService.incentiveApplies(
                ReturnType.CANCEL_BEFORE_SHIPMENT, CASH, false)).isFalse();
        assertThat(RefundExecutionService.incentiveApplies(null, CASH, true)).isFalse();
    }

    @Test
    @DisplayName("🔴 「未交付」的判据必须逐类型对齐 —— 加新 ReturnType 时会在这里被拦下")
    void undeliveredMappingIsExhaustive() {
        // 新增枚举值时本条会红：那是提醒去想「这一档该不该给转币激励」，
        // 而不是让它默默继承某个默认值。
        for (ReturnType t : ReturnType.values()) {
            boolean expected = t == ReturnType.REFUSED_ON_DELIVERY
                    || t == ReturnType.CANCEL_BEFORE_SHIPMENT;
            assertThat(t.isUndelivered())
                    .as("%s 的「未交付」判定", t)
                    .isEqualTo(expected);
        }
    }
}
