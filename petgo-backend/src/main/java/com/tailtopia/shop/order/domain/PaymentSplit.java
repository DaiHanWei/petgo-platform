package com.tailtopia.shop.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一笔订单的支付拆分（Story 3.4，FR-100A 规则 2/3/4 · AD-1/AD-2）。
 *
 * <p><b>算法：余额优先全额抵扣，差额由 QRIS 补足。</b>
 * <ul>
 *   <li>规则 2：余额不足<b>不阻断下单</b> —— 抵扣多少算多少，剩下的用现金补；</li>
 *   <li>规则 3：<b>运费计入总额一并参与抵扣</b>（可由配置关闭）；</li>
 *   <li>规则 4：PawCoin 段受<b>单笔上限</b>约束。</li>
 * </ul>
 *
 * <p>🔴 <b>{@code coinRatio} 只作展示与审计冗余，绝不参与计算</b>（AD-2）。
 * 退款拆分一律用 {@code coinAmount/total} 的整数累计法 —— 浮点比例参与计算必然凑不平，
 * 而 Epic 5 要求多次部分退款后全额退款<b>精确归零</b>。这里算出它只是为了写进
 * {@code payment_intents.coin_ratio} 供人看。
 *
 * <p>🔴 <b>三段金额在订单创建时固化，不随后续部分退款重算</b>。
 */
public record PaymentSplit(long total, long coinAmount, long cashAmount, BigDecimal coinRatio) {

    /**
     * @param total            订单应付总额（商品小计 + 运费 + 免运抵扣）
     * @param shippingPortion  总额中属于运费的部分（当规则 3 关闭时，这部分不可用 Coin 抵扣）
     * @param coinBalance      用户当前 PawCoin 余额
     * @param maxCoinPerOrder  规则 4 单笔上限
     * @param coinEnabled      电商 PawCoin 总开关
     * @param allowShippingDeduction 规则 3：运费是否可抵扣
     */
    public static PaymentSplit compute(long total, long shippingPortion, long coinBalance,
            long maxCoinPerOrder, boolean coinEnabled, boolean allowShippingDeduction) {
        if (total <= 0) {
            return new PaymentSplit(Math.max(total, 0), 0, Math.max(total, 0), BigDecimal.ZERO);
        }
        // 可被 Coin 抵扣的上限：规则 3 关闭时把运费排除在外
        long deductible = allowShippingDeduction ? total : Math.max(total - shippingPortion, 0);

        long coin = 0;
        if (coinEnabled) {
            // 🔴 三个上界依次收窄：可抵扣额 → 余额 → 单笔上限。
            //    顺序无关（取 min），但三者缺一都会造成真实资损或规则失效。
            coin = Math.min(deductible, Math.min(coinBalance, maxCoinPerOrder));
            coin = Math.max(coin, 0);
        }
        long cash = total - coin;

        // 只为展示：保留 6 位小数与 payment_intents.coin_ratio 的 NUMERIC(9,6) 对齐
        BigDecimal ratio = BigDecimal.valueOf(coin)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
        return new PaymentSplit(total, coin, cash, ratio);
    }

    /** 纯现金（Coin 段为 0）。 */
    public boolean isPureCash() {
        return coinAmount == 0;
    }

    /** 纯 Coin（现金段为 0 且总额 > 0）。 */
    public boolean isPureCoin() {
        return cashAmount == 0 && total > 0;
    }

    /** 🔴 两段都有 → channel = MIXED，三列写入；否则 channel 取原值、三列为 NULL。 */
    public boolean isMixed() {
        return coinAmount > 0 && cashAmount > 0;
    }
}
