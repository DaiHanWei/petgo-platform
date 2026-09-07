package com.tailtopia.shop.returns.domain;

import com.tailtopia.shared.error.AppException;

/**
 * 🔴🔴 <b>按比例退款的整数累计法（Story 5.2，AD-2，安全攸关）。</b>
 *
 * <p>本类是本工作线唯一允许计算「这一次退款里有多少是 PawCoin 段」的地方。
 *
 * <h2>为什么必须按累计量取整，而不是按单次取整</h2>
 * IDR 无小数，金额一律 {@code long} 最小币种单位，比例除法必然要丢余数。
 * 若每次退款各自取整，余数会<b>每次都被丢一点</b>，退到最后一次时累计 Coin 段就与
 * {@code coinAmount} 对不上 —— 而对不上的方向是随机的：
 * <ul>
 *   <li><b>少退</b>是客诉：用户拿回的币比该拿的少几盾；</li>
 *   <li>🔴 <b>多退是 FR-100A 规则 1（防套现）的缺口</b>：真钱段被多退，意味着用户
 *       拿回的真钱超过了他投入的真钱。这不是精度问题，是资金缺口。</li>
 * </ul>
 * 累计法把「取整」这件事只做一次 —— 在<b>累计量</b>上做 —— 于是余数不会累积：
 * <pre>
 *   cumulativeTotal = refundedTotalBefore + thisRefund
 *   cumulativeCoin  = cumulativeTotal * coinAmount / amount   ← 整数除法，向下取整
 *   thisCoin        = cumulativeCoin - refundedCoinBefore
 *   thisCash        = thisRefund - thisCoin                   ← 减法得出，不独立计算
 * </pre>
 * 全额退完时 {@code cumulativeTotal == amount}，于是
 * {@code cumulativeCoin == amount * coinAmount / amount == coinAmount}，<b>精确归零漂移</b>。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 *   <li>🔴 <b>{@code coin_ratio} 列绝不参与任何资金计算</b> —— 它只用于后台展示与对账审计。
 *       比例是个已经取整过的展示值，拿它反算金额等于把一次取整误差乘回到金额上。
 *       本类的入参里<b>根本没有 ratio</b>，是能力缺席，不是纪律要求。</li>
 *   <li>🔴 <b>中间量一律 {@code long}，禁用 {@code double} / {@code float}</b>。
 *       {@code cumulativeTotal * coinAmount} 在客单 285k、上限 1M 的量级下远在 {@code long}
 *       范围内；换成浮点则 0.1 + 0.2 那类误差会直接变成钱。</li>
 *   <li>🔴 <b>{@code thisCash} 由减法得出</b>，不独立算一遍。独立算的话两段之和可能不等于
 *       本次退款额，而那个差额没有任何账能收留它。</li>
 * </ol>
 *
 * @param thisCoin 本次退款中的 PawCoin 段
 * @param thisCash 本次退款中的现金段
 */
public record RefundSplit(long thisCoin, long thisCash) {

    /**
     * 计算本次退款的两段拆分。
     *
     * @param amount              支付意图总额（= 订单总额；库级不变式 coin + cash = amount）
     * @param coinAmount          支付意图的 PawCoin 段
     * @param refundedTotalBefore 本次之前已累计退回的总额
     * @param refundedCoinBefore  本次之前已累计退回的 PawCoin 段
     * @param thisRefund          本次要退的总额
     */
    public static RefundSplit accumulate(long amount, long coinAmount, long refundedTotalBefore,
            long refundedCoinBefore, long thisRefund) {
        if (amount <= 0) {
            throw AppException.validation("订单金额必须为正");
        }
        if (coinAmount < 0 || coinAmount > amount) {
            throw AppException.validation("PawCoin 段超出订单金额");
        }
        if (thisRefund < 0 || refundedTotalBefore < 0 || refundedCoinBefore < 0) {
            throw AppException.validation("退款金额不能为负");
        }
        long cumulativeTotal = refundedTotalBefore + thisRefund;
        if (cumulativeTotal > amount) {
            // 🔴 超退直接拒绝而不是截断：截断会静默吞掉一次调用方的算错，
            //    而调用方算错退款额这件事必须炸出来。
            throw AppException.conflict("累计退款超过订单金额");
        }
        if (refundedCoinBefore > coinAmount) {
            throw AppException.conflict("已退 PawCoin 超过订单的 PawCoin 段");
        }

        // 🔴 整数除法向下取整；乘法在前，除法在后 —— 反过来先除会先丢掉整个余数。
        long cumulativeCoin = cumulativeTotal * coinAmount / amount;
        long thisCoin = cumulativeCoin - refundedCoinBefore;
        // 累计量单调不减，故 thisCoin 不会为负；这里的防御是为了让任何反例当场暴露而不是悄悄退负数。
        if (thisCoin < 0) {
            throw AppException.conflict("PawCoin 段拆分出现负数");
        }
        if (thisCoin > thisRefund) {
            throw AppException.conflict("PawCoin 段超过本次退款额");
        }
        // 🔴 减法得出，不独立计算 —— 保证 thisCoin + thisCash == thisRefund 恒成立
        long thisCash = thisRefund - thisCoin;
        return new RefundSplit(thisCoin, thisCash);
    }

    /** 本次退款总额（恒等式 {@code thisCoin + thisCash == thisRefund} 的另一半）。 */
    public long total() {
        return thisCoin + thisCash;
    }
}
