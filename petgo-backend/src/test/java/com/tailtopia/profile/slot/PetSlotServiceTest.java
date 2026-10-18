package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.repository.PetProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：V1.4.0 multi-pet Story 1.4 · AC1 名额计算（GrowthQueryService mock 设定等级）。 */
class PetSlotServiceTest {

    private static final long USER = 7L;

    private final GrowthQueryService growth = mock(GrowthQueryService.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private PetSlotService service;

    @BeforeEach
    void setUp() {
        service = new PetSlotService(growth, profiles);
    }

    private void level(int lv) {
        when(growth.getLevel(USER)).thenReturn(GrowthLevel.of(lv, GrowthTier.NEWBIE));
    }

    @ParameterizedTest(name = "Lv{0} → levelSlots {1} / total {2} / next {3}")
    @CsvSource({
        "1, 0, 1, 5",
        "4, 0, 1, 5",
        "5, 1, 2, 10",
        "9, 1, 2, 10",
        "10, 2, 3,",
        "20, 2, 3,",
    })
    void levelSlotsByLevel(int lv, int levelSlots, int total, Integer next) {
        level(lv);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.levelSlots()).isEqualTo(levelSlots);
        assertThat(q.total()).isEqualTo(total);
        assertThat(q.nextLevelSlot()).isEqualTo(next);
        assertThat(q.defaultSlots()).isEqualTo(1);
        assertThat(q.rentalSlots()).isZero();
        assertThat(q.hasActiveRental()).isFalse();
        assertThat(q.rentable()).isEqualTo(5 - total);
        assertThat(q.canRent()).isTrue();
    }

    @Test
    void notParticipatingGetsDefaultOnlyAndNoNextLevel() {
        when(growth.getLevel(USER)).thenReturn(GrowthLevel.NOT_PARTICIPATING);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.levelSlots()).isZero();
        assertThat(q.total()).isEqualTo(1);
        assertThat(q.nextLevelSlot()).isNull();
        assertThat(q.rentable()).isEqualTo(4);
    }

    @Test
    void placeholderRealUserIsLv1() {
        level(1);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.total()).isEqualTo(1);
        assertThat(q.nextLevelSlot()).isEqualTo(5);
    }

    @Test
    void petCountPassedThroughAndRentableIndependentOfIt() {
        level(5);
        when(profiles.countByOwnerId(USER)).thenReturn(3L);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.petCount()).isEqualTo(3);
        assertThat(q.rentable()).isEqualTo(3); // 5 − 名额 2，与已建只数无关
    }

    @Test
    void totalCappedAtFive() {
        assertThat(PetSlotService.MAX_PETS).isEqualTo(5);
        assertThat(PetSlotService.LEVEL_SLOT_THRESHOLDS).containsExactly(5, 10);
        // 等级最多 +2，月租 2.2 接入；此处仅断言封顶公式与 canRent 语义的一致性。
        level(99);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.total()).isLessThanOrEqualTo(PetSlotService.MAX_PETS);
        assertThat(q.canRent()).isEqualTo(q.rentable() > 0);
    }

    @Test
    void unitPriceIsConstantForNow() {
        assertThat(service.unitPrice()).isEqualTo(5000L);
    }
}
