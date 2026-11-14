package com.tailtopia.starterpack.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 下单响应（架构 §4.2）：PawCoin → {@code {status: PAID, purchaseToken}}；QRIS → {@code {status: PENDING, purchaseToken,
 * intent, qrPayload}}；出码失败 → {@code {status: FAILED, purchaseToken}}。重放按当前状态返回。
 */
public record StarterPackPurchaseResponse(String status, String purchaseToken, PaymentIntentResponse intent,
        String qrPayload) {

    public static StarterPackPurchaseResponse ofStatus(String status, String purchaseToken) {
        return new StarterPackPurchaseResponse(status, purchaseToken, null, null);
    }
}
