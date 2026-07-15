package com.tailtopia.triage.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;

/**
 * 解锁端点响应（Story 2.3，{@code POST /triage/{id}/unlock}）。两形态（Jackson NON_NULL 省略空侧）：
 * <ul>
 *   <li>{@code unlocked=true} + {@code result}：同步解锁成功 / 已解锁 / 红色永不锁——直接回分诊结果（详建已放行）。</li>
 *   <li>{@code unlocked=false} + {@code payment}：现金（QRIS）待支付——回支付信息（QR/deeplink），前端引导支付，
 *       到账后详建由 {@code AiUnlockPaidHandler} 解锁，用户再 {@code GET /triage/{id}} 复看。</li>
 * </ul>
 */
public record UnlockResponse(boolean unlocked, TriageResultResponse result, PaymentIntentResponse payment) {

    public static UnlockResponse unlocked(TriageResultResponse result) {
        return new UnlockResponse(true, result, null);
    }

    public static UnlockResponse paymentRequired(PaymentIntentResponse payment) {
        return new UnlockResponse(false, null, payment);
    }
}
