package com.tailtopia.starterpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.starterpack.domain.StarterPackSlotState;
import com.tailtopia.starterpack.dto.StarterPackView;
import com.tailtopia.starterpack.service.StarterPackViewAssembler;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 4-2 AC3 —— 名额三态、冷却天数向上取整、默认档、停售 / 虚拟号。 */
class StarterPackViewAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

    private static PetSlotQuota quota(int total, boolean hasActiveRental) {
        return new PetSlotQuota(total, 1, 0, Math.max(0, total - 1), total, 5 - total, total < 5, null,
                hasActiveRental);
    }

    @Test
    void slotStates() {
        assertThat(StarterPackViewAssembler.slotState(quota(4, true))).isEqualTo(StarterPackSlotState.GRANT);
        assertThat(StarterPackViewAssembler.slotState(quota(5, true))).isEqualTo(StarterPackSlotState.EXTEND);
        assertThat(StarterPackViewAssembler.slotState(quota(5, false))).isEqualTo(StarterPackSlotState.FULL);
    }

    @Test
    void cooldownDaysRoundUp() {
        Instant paid = NOW.minus(Duration.ofHours(1)); // 剩 6 天 23 小时
        Instant until = StarterPackViewAssembler.cooldownUntil(paid, NOW);
        assertThat(until).isEqualTo(paid.plus(Duration.ofDays(7)));
        assertThat(StarterPackViewAssembler.daysLeft(until, NOW)).isEqualTo(7);
        Instant almost = NOW.minus(Duration.ofDays(7)).plus(Duration.ofHours(1)); // 剩 1 小时
        assertThat(StarterPackViewAssembler.daysLeft(StarterPackViewAssembler.cooldownUntil(almost, NOW), NOW))
                .isEqualTo(1);
        assertThat(StarterPackViewAssembler.cooldownUntil(NOW.minus(Duration.ofDays(7)), NOW)).isNull();
        assertThat(StarterPackViewAssembler.cooldownUntil(null, NOW)).isNull();
    }

    @Test
    void assembleDefaultsAndFullFallback() {
        StarterPackView v = StarterPackViewAssembler.assemble(StarterPackTierTest.defaults(), false, 20_000,
                quota(5, false), null, null, NOW);
        assertThat(v.enabled()).isTrue();
        assertThat(v.defaultTier()).isEqualTo(1);
        assertThat(v.maxSaving()).isEqualTo(10000);
        assertThat(v.balance()).isEqualTo(20_000);
        assertThat(v.tiers()).hasSize(3);
        assertThat(v.tiers().get(0).slotState()).isNull();
        assertThat(v.tiers().get(2).slotState()).isEqualTo(StarterPackSlotState.FULL);
        assertThat(v.cooldown()).isNull();

        StarterPackView g = StarterPackViewAssembler.assemble(StarterPackTierTest.defaults(), false, 0,
                quota(2, false), NOW.minus(Duration.ofDays(1)), null, NOW);
        assertThat(g.defaultTier()).isEqualTo(3);
        assertThat(g.cooldown()).isNotNull();
        assertThat(g.cooldown().daysLeft()).isEqualTo(6);
    }

    @Test
    void virtualAccountIsDisabled() {
        StarterPackView v = StarterPackViewAssembler.assemble(StarterPackTierTest.defaults(), true, 0,
                quota(1, false), null, null, NOW);
        assertThat(v.enabled()).isFalse();
    }
}
