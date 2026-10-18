package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：默认宠物 = 列表第一只（调用方已按 created_at, id 升序排好）；空列表 → empty。 */
class DefaultPetPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void picksFirstOfOrderedList() {
        var a = pet(1L, 10L, T0);
        var b = pet(1L, 11L, T0.plusSeconds(60));
        assertThat(DefaultPetPolicy.pick(List.of(a, b))).containsSame(a);
    }

    @Test
    void emptyOrNullListYieldsEmpty() {
        assertThat(DefaultPetPolicy.pick(List.of())).isEmpty();
        assertThat(DefaultPetPolicy.pick(null)).isEmpty();
    }
}
