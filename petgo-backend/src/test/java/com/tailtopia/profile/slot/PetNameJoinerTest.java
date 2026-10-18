package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 2.3 · AC3.2 —— 宠物名连接「A」/「A &amp; B」/「A, B &amp; C」（{@code &} 前不加逗号）。 */
class PetNameJoinerTest {

    @Test
    void one() {
        assertThat(PetNameJoiner.join(List.of("Mochi"))).isEqualTo("Mochi");
    }

    @Test
    void two() {
        assertThat(PetNameJoiner.join(List.of("Mochi", "Kopi"))).isEqualTo("Mochi & Kopi");
    }

    @Test
    void three() {
        assertThat(PetNameJoiner.join(List.of("Mochi", "Kopi", "Teh"))).isEqualTo("Mochi, Kopi & Teh");
    }

    @Test
    void four() {
        assertThat(PetNameJoiner.join(List.of("A", "B", "C", "D"))).isEqualTo("A, B, C & D");
    }

    @Test
    void emptyOrNull() {
        assertThat(PetNameJoiner.join(List.of())).isEmpty();
        assertThat(PetNameJoiner.join(null)).isEmpty();
    }
}
