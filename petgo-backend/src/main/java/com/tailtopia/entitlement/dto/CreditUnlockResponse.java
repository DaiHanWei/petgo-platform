package com.tailtopia.entitlement.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 用免费次数解锁的响应（V1.5.0 gift-pack Story 4-3 · 架构 §4.2）：前三项与 {@code HdPurchaseResponse.granted()} /
 * {@code KeepsakePurchaseResponse} 已解锁分支同形（App 复用解析；全局 non_null 省略 {@code payment} / {@code payload}），
 * 另带 {@code remaining}（KTP = 该款 + 任意款剩余合计；性格测试 = Tailsonality 剩余）。
 */
public record CreditUnlockResponse(boolean unlocked, PaymentIntentResponse payment, String payload, int remaining) {

    public static CreditUnlockResponse unlocked(int remaining) {
        return new CreditUnlockResponse(true, null, null, remaining);
    }
}
