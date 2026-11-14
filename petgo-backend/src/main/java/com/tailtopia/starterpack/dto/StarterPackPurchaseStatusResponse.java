package com.tailtopia.starterpack.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.starterpack.domain.StarterPackContent;
import com.tailtopia.starterpack.domain.StarterPackSlotOutcome;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/me/starter-pack-purchases/{token}}（AC7 · 架构 §4.2）。PENDING（窗内）带 {@code intent} +
 * {@code qrPayload}；{@code slot} 档 1 / 未发放为 null；{@code slot.petName} 只在 EXTENDED 时给（待拍板 Q-5 默认规则）。
 */
public record StarterPackPurchaseStatusResponse(String purchaseToken, int tier, long price, String channel,
        String status, boolean fulfilled, Instant paidAt, Instant creditsExpireAt, Slot slot,
        List<StarterPackContent> contents, PaymentIntentResponse intent, String qrPayload) {

    public record Slot(StarterPackSlotOutcome outcome, Instant expiresAt, String petName) {
    }
}
