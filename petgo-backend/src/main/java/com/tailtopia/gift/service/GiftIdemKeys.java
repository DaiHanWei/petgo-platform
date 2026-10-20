package com.tailtopia.gift.service;

/**
 * 送礼全部幂等键的<b>唯一</b>出处（V1.4.0 gift Story 1.1 · AC10 · 架构 §5）。其他地方不得手拼这些串。
 *
 * <p>每一步各用一把键：发起（订单 {@code idem_key}）、扣币（{@code PawCoinWalletService.debit}）、
 * 出码（{@code PaymentIntentService.createIntent}）、退回（Story 1.2 {@code credit}）。扣币 / 出码 / 退回都按订单
 * {@code public_token} 区分 —— 每笔订单独立意图键，不会命中 {@code createIntent} 的跨订单复用。
 */
public final class GiftIdemKeys {

    private GiftIdemKeys() {
    }

    /** 发起：{@code GIFT:{senderId}:{requestId}}（App 每次点「送出」生成一个 requestId，防连点）。 */
    public static String send(long senderId, String requestId) {
        return "GIFT:" + senderId + ":" + requestId;
    }

    /** PawCoin 扣币：{@code GIFT_COIN:{orderToken}}。 */
    public static String coin(String orderToken) {
        return "GIFT_COIN:" + orderToken;
    }

    /** QRIS 出码：{@code GIFT_QRIS:{orderToken}}。 */
    public static String qris(String orderToken) {
        return "GIFT_QRIS:" + orderToken;
    }

    /** 未送达退回 PawCoin：{@code GIFT_REFUND:{orderToken}}（Story 1.2 使用）。 */
    public static String refund(String orderToken) {
        return "GIFT_REFUND:" + orderToken;
    }
}
