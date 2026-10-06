package com.tailtopia.purchase.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 发起购买的响应（V1.3.2 Story 3.1）。前三项形状<b>与 {@code HdPurchaseResponse} 一致</b>（App 复用解析）。
 *
 * @param unlocked      true = 已解锁（PawCoin 当场成交 / 已付款）
 * @param payment       QRIS 待付时的意图（含 {@code displayNo}）；否则 null
 * @param payload       QRIS 二维码载荷；否则 null
 * @param purchaseToken 购买行 {@code public_token}（订单中心 orderToken）；无购买行时 null
 */
public record KeepsakePurchaseResponse(boolean unlocked, PaymentIntentResponse payment, String payload,
        String purchaseToken) {

    public static KeepsakePurchaseResponse unlocked(String purchaseToken) {
        return new KeepsakePurchaseResponse(true, null, null, purchaseToken);
    }

    public static KeepsakePurchaseResponse paymentRequired(PaymentIntentResponse payment, String payload,
            String purchaseToken) {
        return new KeepsakePurchaseResponse(false, payment, payload, purchaseToken);
    }
}
