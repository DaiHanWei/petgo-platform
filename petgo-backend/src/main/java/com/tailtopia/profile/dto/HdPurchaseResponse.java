package com.tailtopia.profile.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 身份证高清图购买响应（Story 6.3）。两形态（Jackson NON_NULL 省略空侧，照 {@code UnlockResponse} 范式）：
 * <ul>
 *   <li>{@code unlocked=true}：已解锁（PawCoin 同步扣费成功 / 已购买短路）——前端直接导出下载。</li>
 *   <li>{@code unlocked=false} + {@code payment}：QRIS 待支付——回支付信息，到账后经 {@code IdHdPaidHandler}
 *       解锁，前端再 {@code GET /id-card} 复看 {@code hdUnlocked}。</li>
 * </ul>
 */
public record HdPurchaseResponse(boolean unlocked, PaymentIntentResponse payment, String payload) {

    public static HdPurchaseResponse granted() {
        return new HdPurchaseResponse(true, null, null);
    }

    public static HdPurchaseResponse paymentRequired(PaymentIntentResponse payment, String payload) {
        return new HdPurchaseResponse(false, payment, payload);
    }
}
