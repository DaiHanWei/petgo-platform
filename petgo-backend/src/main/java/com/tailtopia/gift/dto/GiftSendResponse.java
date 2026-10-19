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
 */
public record GiftSendResponse(boolean delivered, PaymentIntentResponse payment, String payload, String orderToken,
        String status) {

    public static GiftSendResponse delivered(String orderToken) {
        return new GiftSendResponse(true, null, null, orderToken, GiftOrderStatus.DELIVERED.name());
    }

    public static GiftSendResponse paymentRequired(PaymentIntentResponse payment, String payload, String orderToken) {
        return new GiftSendResponse(false, payment, payload, orderToken, GiftOrderStatus.PENDING.name());
    }

    public static GiftSendResponse ofStatus(String orderToken, GiftOrderStatus status) {
        return new GiftSendResponse(status == GiftOrderStatus.DELIVERED, null, null, orderToken, status.name());
    }
}
