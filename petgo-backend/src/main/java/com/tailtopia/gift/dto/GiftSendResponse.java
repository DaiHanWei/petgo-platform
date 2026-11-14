package com.tailtopia.gift.dto;

import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 发起送礼的响应（V1.4.0 gift Story 1.1 · AC5.5）。前三项形状<b>与 {@code KeepsakePurchaseResponse} 一致</b>（App 复用解析）。
 *
 * @param delivered  true = 已送达（PawCoin 当场成交 / 幂等重放时订单已送达）
 * @param payment    QRIS 待付时的意图（含 {@code displayNo}）；否则 null
 * @param payload    QRIS 二维码载荷；否则 null
 * @param orderToken 订单 {@code public_token}
 * @param status     订单状态（{@link GiftOrderStatus} 名）
 * @param senderExp  V1.5.0 gift-pack Story 1-3：送礼人本单可得 EXP（仅 {@code delivered = true} 有意义，其余 0）
 */
public record GiftSendResponse(boolean delivered, PaymentIntentResponse payment, String payload, String orderToken,
        String status, int senderExp) {

    /** 不带 EXP 的旧形状（senderExp = 0）。 */
    public GiftSendResponse(boolean delivered, PaymentIntentResponse payment, String payload, String orderToken,
            String status) {
        this(delivered, payment, payload, orderToken, status, 0);
    }

    public static GiftSendResponse delivered(String orderToken) {
        return new GiftSendResponse(true, null, null, orderToken, GiftOrderStatus.DELIVERED.name());
    }

    /** V1.5.0：已送达并带送礼人 EXP。 */
    public static GiftSendResponse delivered(String orderToken, int senderExp) {
        return new GiftSendResponse(true, null, null, orderToken, GiftOrderStatus.DELIVERED.name(), senderExp);
    }

    public static GiftSendResponse paymentRequired(PaymentIntentResponse payment, String payload, String orderToken) {
        return new GiftSendResponse(false, payment, payload, orderToken, GiftOrderStatus.PENDING.name());
    }

    public static GiftSendResponse ofStatus(String orderToken, GiftOrderStatus status) {
        return new GiftSendResponse(status == GiftOrderStatus.DELIVERED, null, null, orderToken, status.name());
    }
}
