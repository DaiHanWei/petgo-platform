package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 1.1 · AC5.4 —— 冷却 90 天按 WIB 日期，「第 91 天可改」。 */
class PetSpeciesCooldownTest {

    private static PetProfile pet(Instant changedAt) {
        PetProfile p = PetProfile.create(1L, PetType.OTHER, "Momo", null, null, LocalDate.of(2022, 1, 1), null, "T");
        if (changedAt != null) {
            p.markTypeChangedAt(changedAt);
        }
        return p;
    }

    @Test
    void changeableFromIsWibDatePlus90() {
        // 2026-11-24 19:05 WIB = 12:05Z
        assertThat(PetSpeciesCooldown.changeableFrom(Instant.parse("2026-11-24T12:05:00Z")))
                .isEqualTo(LocalDate.of(2027, 2, 22));
    }

    @Test
    void orNullBoundary() {
        PetProfile p = pet(Instant.parse("2026-11-24T12:05:00Z"));
        assertThat(PetSpeciesCooldown.changeableFromOrNull(p, LocalDate.of(2027, 2, 21)))
                .isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(PetSpeciesCooldown.changeableFromOrNull(p, LocalDate.of(2027, 2, 22))).isNull();
    }

    @Test
    void utcCrossDayUsesWibDate() {
        // 2026-11-24T17:30Z = 11-25 00:30 WIB
        assertThat(PetSpeciesCooldown.changeableFrom(Instant.parse("2026-11-24T17:30:00Z")))
                .isEqualTo(LocalDate.of(2027, 2, 23));
    }

    @Test
    void neverChangedIsNull() {
        assertThat(PetSpeciesCooldown.changeableFromOrNull(pet(null), LocalDate.of(2026, 11, 14))).isNull();
    }
}
