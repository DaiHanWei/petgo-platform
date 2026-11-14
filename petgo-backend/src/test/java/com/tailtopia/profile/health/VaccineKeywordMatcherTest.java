package com.tailtopia.profile.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC6）：PRD FR-168 关键词匹配，AC50 / AC51。 */
class VaccineKeywordMatcherTest {

    @ParameterizedTest(name = "{0} ({1}) → {2}")
    @CsvSource(delimiter = '|', value = {
        "Vaksin tricat|CAT|F3",
        "F4 + rabies|CAT|OTHER",
        "Vaksin DHPPi+L|DOG|COMBO_DHPPI_L",
        "dhpp + lepto|DOG|COMBO_DHPPI_L",
        "Lepto|DOG|LEPTO",
        "Vaksin Leptospirosis|DOG|LEPTO",
        "dhpp|CAT|OTHER",
        "TETRACAT!!|CAT|F4",
        "f35|CAT|OTHER",
        "FeLV booster|CAT|FELV",
        "Kennel cough|DOG|KENNEL_COUGH",
        "Rabies|DOG|RABIES",
        "felv|DOG|OTHER",
        "obat|CAT|OTHER"})
    void match(String text, PetType type, VaccinePreset expected) {
        assertThat(VaccineKeywordMatcher.match(text, type)).isEqualTo(expected);
    }

    @Test
    void otherSpeciesGetsNoPreset() {
        assertThat(VaccineKeywordMatcher.match("Rabies", PetType.OTHER)).isNull();
    }

    @Test
    void blankIsOther() {
        assertThat(VaccineKeywordMatcher.match(null, PetType.CAT)).isEqualTo(VaccinePreset.OTHER);
    }
}
