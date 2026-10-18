package com.tailtopia.profile.slot.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 租用 / 续租下单响应（V1.4.0 multi-pet Story 2.2 · AC8.4）——<b>与 {@code KeepsakePurchaseResponse} 同形</b>，
 * App 复用解析与 {@code runKeepsakePurchase} 流程。
 *
 * @param unlocked      true = 已完成发放（PawCoin 当场成交 / 已付款）
 * @param payment       QRIS 待付时的意图（含 {@code displayNo}）；否则 null
 * @param payload       QRIS 二维码载荷；否则 null
 * @param purchaseToken 购买行 {@code public_token}（App 轮询 {@code /pet-slots/me/purchases/{purchaseToken}}）
 */
public record PetSlotPurchaseResponse(boolean unlocked, PaymentIntentResponse payment, String payload,
        String purchaseToken) {

    public static PetSlotPurchaseResponse unlocked(String purchaseToken) {
        return new PetSlotPurchaseResponse(true, null, null, purchaseToken);
    }

    public static PetSlotPurchaseResponse paymentRequired(PaymentIntentResponse payment, String payload,
            String purchaseToken) {
        return new PetSlotPurchaseResponse(false, payment, payload, purchaseToken);
    }
}
