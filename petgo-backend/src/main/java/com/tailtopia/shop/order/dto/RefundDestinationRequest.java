package com.tailtopia.shop.order.dto;

/**
 * 选择现金去向请求（v1.4.2 shop Story 4.1 AC4.2）：{@code destination = TO_PAWCOIN | TO_BANK}；
 * {@code channel / account / accountHolderName} 仅 TO_BANK 必填。
 *
 * <p>🔴 <b>没有「PawCoin 段去哪」参数，也没有手续费参数</b>（能力缺席，FR-100A 规则 1；渠道费后端按 {@code PayoutChannel} 算）。
 * 🔒 账号 / 户名是 PII，{@link #toString()} 不含它们。
 */
public record RefundDestinationRequest(String destination, String channel, String account,
        String accountHolderName) {

    @Override
    public String toString() {
        return "RefundDestinationRequest{destination=" + destination + ", channel=" + channel + "}";
    }
}
