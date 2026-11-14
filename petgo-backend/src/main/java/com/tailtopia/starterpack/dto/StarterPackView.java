package com.tailtopia.starterpack.dto;

import com.tailtopia.starterpack.domain.StarterPackContent;
import com.tailtopia.starterpack.domain.StarterPackSlotState;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/starter-pack}（V1.5.0 gift-pack Story 4-2 · AC3 · 架构 §4.2）。全局 JSON non_null：
 * {@code cooldown} / {@code pending} / 档 1 的 {@code slotState} 为 null 时省略。
 */
public record StarterPackView(boolean enabled, int defaultTier, long maxSaving, long balance, List<Tier> tiers,
        Cooldown cooldown, Pending pending) {

    public record Tier(int tier, long price, long separateTotal, long saving, List<StarterPackContent> contents,
            StarterPackSlotState slotState) {
    }

    public record Cooldown(Instant until, int daysLeft) {
    }

    public record Pending(String purchaseToken, int tier, long price, Instant payExpiresAt) {
    }
}
