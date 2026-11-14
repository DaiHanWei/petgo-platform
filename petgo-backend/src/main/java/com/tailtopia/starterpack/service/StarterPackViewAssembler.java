package com.tailtopia.starterpack.service;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.starterpack.domain.StarterPackSlotState;
import com.tailtopia.starterpack.domain.StarterPackTier;
import com.tailtopia.starterpack.dto.StarterPackView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 礼包查询的纯计算（V1.5.0 gift-pack Story 4-2 · AC3）：名额状态、冷却、默认档、各档价格。无 IO，可单测。
 */
public final class StarterPackViewAssembler {

    /** 冷却：最近一笔 PAID 的 {@code paid_at} 起满 7 × 24 小时（不是自然日）。 */
    public static final Duration COOLDOWN = Duration.ofDays(7);
    /** 默认选中档。 */
    static final int DEFAULT_TIER = 3;
    /** 档 3 名额 FULL 时的默认档。 */
    static final int FALLBACK_TIER = 1;

    private StarterPackViewAssembler() {
    }

    /** 名额状态（档 2 / 3）：名额 &lt; 5 → GRANT；否则有生效租用名额 → EXTEND；否则 FULL。 */
    public static StarterPackSlotState slotState(PetSlotQuota quota) {
        if (quota.total() < PetSlotService.MAX_PETS) {
            return StarterPackSlotState.GRANT;
        }
        return quota.hasActiveRental() ? StarterPackSlotState.EXTEND : StarterPackSlotState.FULL;
    }

    /** 冷却截止；未在冷却中（含从未买过）→ null。 */
    public static Instant cooldownUntil(Instant lastPaidAt, Instant now) {
        if (lastPaidAt == null) {
            return null;
        }
        Instant until = lastPaidAt.plus(COOLDOWN);
        return until.isAfter(now) ? until : null;
    }

    /** 剩余天数 = ceil(剩余时长 / 24 小时)（6 天 23 小时 → 7；1 小时 → 1）。 */
    public static int daysLeft(Instant until, Instant now) {
        long millis = Duration.between(now, until).toMillis();
        if (millis <= 0) {
            return 0;
        }
        long day = Duration.ofDays(1).toMillis();
        return (int) ((millis + day - 1) / day);
    }

    /** 组装（{@code pending} 已由调用方处理过期）。 */
    public static StarterPackView assemble(PricingConfig pricing, boolean virtual, long balance, PetSlotQuota quota,
            Instant lastPaidAt, StarterPackView.Pending pending, Instant now) {
        StarterPackSlotState state = slotState(quota);
        List<StarterPackView.Tier> tiers = new ArrayList<>();
        long maxSaving = Long.MIN_VALUE;
        for (StarterPackTier t : StarterPackTier.values()) {
            long saving = t.saving(pricing);
            maxSaving = Math.max(maxSaving, saving);
            tiers.add(new StarterPackView.Tier(t.number(), t.price(pricing), t.separateTotal(pricing), saving,
                    t.contents(), t.hasSlot() ? state : null));
        }
        int defaultTier = state == StarterPackSlotState.FULL ? FALLBACK_TIER : DEFAULT_TIER;
        Instant until = cooldownUntil(lastPaidAt, now);
        StarterPackView.Cooldown cooldown = until == null ? null
                : new StarterPackView.Cooldown(until, daysLeft(until, now));
        return new StarterPackView(pricing.isStarterPackEnabled() && !virtual, defaultTier, maxSaving, balance,
                List.copyOf(tiers), cooldown, pending);
    }
}
