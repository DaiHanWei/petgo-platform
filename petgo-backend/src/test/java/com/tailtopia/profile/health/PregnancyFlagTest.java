package com.tailtopia.profile.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.3 · AC3）：怀孕标记 4 个日历月自动关闭，PRD AC45。 */
class PregnancyFlagTest {

    private static Instant wibNoon(String day) {
        return LocalDate.parse(day).atTime(12, 0).atZone(ScheduleWindow.WIB).toInstant();
    }

    @Test
    void ac45FourCalendarMonthsAndMonthEndClamp() {
        assertThat(PregnancyFlag.autoOffDate(wibNoon("2027-01-01"))).isEqualTo(LocalDate.parse("2027-05-01"));
        assertThat(PregnancyFlag.autoOffDate(wibNoon("2027-01-31"))).isEqualTo(LocalDate.parse("2027-05-31"));
        assertThat(PregnancyFlag.autoOffDate(wibNoon("2027-10-31"))).isEqualTo(LocalDate.parse("2028-02-29"));
    }

    @Test
    void usesWibDateOfSetAt() {
        // 2026-12-31 18:00 UTC = 2027-01-01 01:00 WIB
        assertThat(PregnancyFlag.autoOffDate(Instant.parse("2026-12-31T18:00:00Z")))
                .isEqualTo(LocalDate.parse("2027-05-01"));
    }

    @Test
    void activeUntilAutoOffDateAndMaleNever() {
        PetProfile p = PetFixtures.pet(7L, 1L, PetType.DOG, Instant.EPOCH);
        p.setSex(PetSex.FEMALE);
        p.setPregnantNursing(true, wibNoon("2027-01-01"));
        assertThat(PregnancyFlag.isActiveOn(p, LocalDate.parse("2027-04-30"))).isTrue();
        assertThat(PregnancyFlag.isActiveOn(p, LocalDate.parse("2027-05-01"))).isFalse();
        assertThat(PregnancyFlag.expiredOn(p, LocalDate.parse("2027-05-01"))).isTrue();
        assertThat(PregnancyFlag.expiredOn(p, LocalDate.parse("2027-04-30"))).isFalse();
        // 再次设为是 → 重新起算
        p.setPregnantNursing(true, wibNoon("2027-04-20"));
        assertThat(PregnancyFlag.isActiveOn(p, LocalDate.parse("2027-05-01"))).isTrue();
        p.setSex(PetSex.MALE);
        assertThat(PregnancyFlag.isActiveOn(p, LocalDate.parse("2027-05-01"))).isFalse();
    }
}
