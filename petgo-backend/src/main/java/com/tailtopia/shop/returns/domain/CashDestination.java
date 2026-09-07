package com.tailtopia.shop.returns.domain;

/**
 * 现金段（QRIS 段）的去向（Story 5.5 / 5.8）。
 *
 * <p>🔴 <b>本枚举只描述【现金段】。PawCoin 段没有对应枚举，因为它没有第二个去向</b>
 * （FR-100A 规则 1）—— 这不是遗漏，是刻意的能力缺席：不存在一个「PawCoin 段去哪」的选项，
 * 也就不存在任何人（包括 CS）把它设成「银行账户」的可能。
 */
public enum CashDestination {
    /** 退回银行账户 / e-wallet。渠道费按 {@code PayoutChannel} 权威计算，前端不得传费。 */
    TO_BANK,
    /** 转 PawCoin：即时到账 + <b>激励</b>溢价（C-1 反套利；🔴 与补偿溢价是两个独立配置项）。 */
    TO_PAWCOIN
}
