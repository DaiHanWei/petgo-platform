package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.domain.PetStatus;
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

/** L0：V1.4.0 multi-pet Story 2.2 · AC3 —— {@code quotaOf} 计入月租（只数 ACTIVE 且未过期）、封顶 5、rentalSlots 有效值。 */
class PetSlotServiceQuotaTest {

    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private final GrowthQueryService growth = mock(GrowthQueryService.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetSlotRentalRepository rentals = mock(PetSlotRentalRepository.class);
    private PetSlotService service;

    @BeforeEach
    void setUp() {
        service = new PetSlotService(growth, profiles, rentals, mock(PlatformConfigService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void level(int lv) {
        when(growth.getLevel(USER)).thenReturn(GrowthLevel.of(lv, GrowthTier.NEWBIE));
    }

    private void liveRentals(long n) {
        when(rentals.countByUserIdAndStatusAndExpiresAtAfter(USER, PetSlotRentalStatus.ACTIVE, NOW)).thenReturn(n);
    }

    @ParameterizedTest(name = "Lv{0} + 月租 {1} → total {2} / rentalSlots {3} / rentable {4}")
    @CsvSource({
        "1, 0, 1, 0, 4",
        "1, 2, 3, 2, 2",
        "1, 4, 5, 4, 0",
        "5, 3, 5, 3, 0",
        "10, 2, 5, 2, 0",
        "10, 4, 5, 2, 0", // 租用期间升级：多出的 2 个本期不退、不显示为闲置
        "1, 9, 5, 4, 0",
    })
    void rentalsCountedAndCappedAtFive(int lv, long rented, int total, int rentalSlots, int rentable) {
        level(lv);
        liveRentals(rented);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.total()).isEqualTo(total);
        assertThat(q.rentalSlots()).isEqualTo(rentalSlots);
        assertThat(q.total()).isEqualTo(q.defaultSlots() + q.levelSlots() + q.rentalSlots());
        assertThat(q.rentable()).isEqualTo(rentable);
        assertThat(q.canRent()).isEqualTo(rentable > 0);
        assertThat(q.hasActiveRental()).isEqualTo(rented > 0);
    }

    @Test
    void onlyActiveAndNotExpiredRowsAreCountedAtCurrentInstant() {
        level(1);
        // 仓储按 status = ACTIVE AND expires_at > now 计数（已 EXPIRED / 已过到期时刻的行不算）
        when(rentals.countByUserIdAndStatusAndExpiresAtAfter(any(Long.class), any(), any())).thenReturn(0L);
        PetSlotQuota q = service.quotaOf(USER);
        verify(rentals).countByUserIdAndStatusAndExpiresAtAfter(eq(USER), eq(PetSlotRentalStatus.ACTIVE), eq(NOW));
        assertThat(q.rentalSlots()).isZero();
        assertThat(q.hasActiveRental()).isFalse();
        assertThat(q.total()).isEqualTo(1);
    }

    @Test
    void notParticipatingGetsNoLevelSlotsButRentalsCount() {
        when(growth.getLevel(USER)).thenReturn(GrowthLevel.NOT_PARTICIPATING);
        liveRentals(2);
        PetSlotQuota q = service.quotaOf(USER);
        assertThat(q.levelSlots()).isZero();
        assertThat(q.rentalSlots()).isEqualTo(2);
        assertThat(q.total()).isEqualTo(3);
        assertThat(q.hasActiveRental()).isTrue();
    }

    @ParameterizedTest(name = "Lv{0} → renewableMax {1}")
    @CsvSource({"1, 4", "5, 3", "10, 2"})
    void renewableMaxIsFiveMinusDefaultMinusLevelSlots(int lv, int expected) {
        level(lv);
        assertThat(PetSlotService.renewableMax(service.quotaOf(USER))).isEqualTo(expected);
    }

    @Test
    void readOnlyPetCountReadsStatusCount() {
        when(profiles.countByOwnerIdAndStatus(USER, PetStatus.READ_ONLY)).thenReturn(2L);
        assertThat(service.readOnlyPetCount(USER)).isEqualTo(2);
    }

    @Test
    void activeRentalsQueryUsesSameLiveWindow() {
        service.activeRentals(USER);
        verify(rentals).findByUserIdAndStatusAndExpiresAtAfterOrderByStartsAtAscIdAsc(USER,
                PetSlotRentalStatus.ACTIVE, NOW);
    }
}
