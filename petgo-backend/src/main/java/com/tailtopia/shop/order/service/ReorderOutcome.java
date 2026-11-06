package com.tailtopia.shop.order.service;

/**
 * 迟到款重新下单结果（v1.4.2 shop Story 4.2 AC4.10）。只含对外标识与金额，无内部 id。
 *
 * @param excessToPawcoin 迟到款多出新单现金段、已退为 PawCoin 的部分（S-6）；无多出为 0
 */
public record ReorderOutcome(String newOrderToken, String newDisplayNo, long coinAmount, long cashAmount,
        long excessToPawcoin) {
}
