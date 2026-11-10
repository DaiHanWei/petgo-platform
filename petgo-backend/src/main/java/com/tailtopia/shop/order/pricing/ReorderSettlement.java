package com.tailtopia.shop.order.pricing;

/**
 * 迟到款重新下单的多退少补结果（v1.4.2 shop Story 4.2 AC3，决策 S-6，P-1 计价器单点）。
 *
 * @param coinAmount      新单 PawCoin 段（= 原币额，或少补时原币 + 差额）
 * @param cashAmount      新单现金段（取自原单迟到款，恒 &gt; 0）
 * @param excessToPawcoin 迟到款多出新单现金段的部分，退为 PawCoin（无补偿、不扣成长值）；无多出为 0
 */
public record ReorderSettlement(long coinAmount, long cashAmount, long excessToPawcoin) {
}
