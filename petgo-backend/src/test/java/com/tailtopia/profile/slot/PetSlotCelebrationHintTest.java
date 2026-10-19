package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：V1.4.0 multi-pet Story 4.4 · AC1.2 —— 跨 Lv5 / Lv10 门槛 且 此刻有生效中月租 → petSlotHint。 */
class PetSlotCelebrationHintTest {

    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    private final GrowthQueryService growth = mock(GrowthQueryService.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetSlotRentalRepository rentals = mock(PetSlotRentalRepository.class);
    private PetSlotCelebrationHint hint;

    @BeforeEach
    void setUp() {
        PetSlotService service = new PetSlotService(growth, profiles, rentals, mock(PlatformConfigService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        hint = new PetSlotCelebrationHint(service);
        when(growth.getLevel(USER)).thenReturn(GrowthLevel.of(5, GrowthTier.BUDDY));
    }

    /** 「生效中」= ACTIVE 且 expires_at > now（RENT 与运营补发 ADMIN_GRANT 同表同状态，一并计数）。 */
    private void liveRentals(long n) {
        when(rentals.countByUserIdAndStatusAndExpiresAtAfter(USER, PetSlotRentalStatus.ACTIVE, NOW)).thenReturn(n);
    }

    @ParameterizedTest(name = "Lv{0} → {1} 等级名额")
    @CsvSource({"1, 0", "4, 0", "5, 1", "9, 1", "10, 2", "20, 2"})
    void levelSlotsAtLevelBoundaries(int level, int expected) {
        assertThat(PetSlotService.levelSlotsAtLevel(level)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "Lv{0} → Lv{1}，月租 {2} → {3}")
    @CsvSource({
        "4, 5, 1, true",   // AC13a：默认 1 + 月租 1 升到 Lv5
        "4, 5, 0, false",  // 无生效月租
        "5, 7, 1, false",  // 不跨门槛
        "9, 10, 1, true",
        "4, 11, 1, true",  // 连跳两道门槛
        "4, 11, 3, true",
        "10, 14, 2, false",
    })
    void hintNeedsThresholdCrossAndLiveRental(int from, int to, long live, boolean expected) {
        liveRentals(live);
        assertThat(hint.petSlotHint(USER, from, to)).isEqualTo(expected);
    }

    @Test
    void noThresholdCrossDoesNotQueryRentals() {
        assertThat(hint.petSlotHint(USER, 5, 9)).isFalse();
        verify(rentals, never()).countByUserIdAndStatusAndExpiresAtAfter(anyLong(), any(), any());
    }
}
