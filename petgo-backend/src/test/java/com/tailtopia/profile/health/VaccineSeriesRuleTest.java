package com.tailtopia.profile.health;

import static com.tailtopia.profile.domain.VaccinePreset.COMBO_DHPPI_L;
import static com.tailtopia.profile.domain.VaccinePreset.F4;
import static com.tailtopia.profile.domain.VaccinePreset.FELV;
import static com.tailtopia.profile.domain.VaccinePreset.LEPTO;
import static com.tailtopia.profile.domain.VaccinePreset.OTHER;
import static com.tailtopia.profile.domain.VaccinePreset.PUPPY_DP;
import static com.tailtopia.profile.domain.VaccinePreset.RABIES;
import static com.tailtopia.profile.health.HealthFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC2）：PRD FR-159 系列规则，用例名写 PRD AC 编号。 */
class VaccineSeriesRuleTest {

    private static final LocalDate KITTEN = d("2026-10-01");
    private static final LocalDate ADULT_CAT = d("2024-01-01");

    static Stream<Arguments> cases() {
        return Stream.of(
                // name, preset, dose, event, birthday, hasDp, expectedNext, dueDose
                Arguments.of("AC1 小猫 F4 第1剂 9 周", F4, 1, "2026-12-03", KITTEN, false, "2026-12-31", 2),
                Arguments.of("AC7 小猫 F4 第2剂 +4 周", F4, 2, "2026-12-31", KITTEN, false, "2027-01-28", 3),
                Arguments.of("AC7 小猫 F4 第3剂 +1 年", F4, 3, "2027-01-28", KITTEN, false, "2028-01-28", null),
                Arguments.of("AC7 小猫空剂次 8 周 +4 周", F4, null, "2026-11-26", KITTEN, false, "2026-12-24", null),
                Arguments.of("AC7 小猫空剂次 12 周 +4 周", F4, null, "2026-12-24", KITTEN, false, "2027-01-21", null),
                Arguments.of("AC7 小猫空剂次 16 周 +1 年", F4, null, "2027-01-21", KITTEN, false, "2028-01-21", null),
                Arguments.of("AC7 成年猫 F4 第1剂 +4 周（AC56 到期剂次 2）", F4, 1, "2026-03-01", ADULT_CAT, false,
                        "2026-03-29", 2),
                Arguments.of("AC7 成年猫 F4 第2剂 +1 年（AC56 到期剂次空）", F4, 2, "2026-03-01", ADULT_CAT, false,
                        "2027-03-01", null),
                Arguments.of("AC7 成年猫空剂次 +1 年", F4, null, "2026-03-01", ADULT_CAT, false, "2027-03-01", null),
                Arguments.of("AC7 无生日猫第1剂 = 成年", F4, 1, "2026-03-01", null, false, "2026-03-29", 2),
                Arguments.of("AC7 无生日猫第2剂 = 成年", F4, 2, "2026-03-01", null, false, "2027-03-01", null),
                Arguments.of("AC7 无生日猫空剂次 = 成年", F4, null, "2026-03-01", null, false, "2027-03-01", null),
                Arguments.of("AC7 成年狗 Combo 第1剂 +4 周", COMBO_DHPPI_L, 1, "2026-03-01", ADULT_CAT, false,
                        "2026-03-29", 2),
                Arguments.of("AC7 成年狗 Combo 第2剂 +1 年", COMBO_DHPPI_L, 2, "2026-03-01", ADULT_CAT, false,
                        "2027-03-01", null),
                Arguments.of("AC7 FeLV 第1剂 小猫 +4 周", FELV, 1, "2026-12-03", KITTEN, false, "2026-12-31", 2),
                Arguments.of("AC7 Lepto 第1剂 成年 +4 周", LEPTO, 1, "2026-03-01", ADULT_CAT, false, "2026-03-29", 2),
                Arguments.of("AC7 12 周幼犬 Rabies +1 年", RABIES, null, "2026-12-24", KITTEN, false, "2027-12-24",
                        null),
                Arguments.of("AC7 有 DP 的幼犬 Combo 第1剂 +4 周", COMBO_DHPPI_L, 1, "2026-12-03", KITTEN, true,
                        "2026-12-31", 2),
                Arguments.of("AC7 有 DP 的幼犬 Combo 第2剂 +1 年", COMBO_DHPPI_L, 2, "2026-12-31", KITTEN, true,
                        "2027-12-31", null),
                Arguments.of("AC7 无 DP 的幼犬 Combo 第2剂 +4 周", COMBO_DHPPI_L, 2, "2026-12-31", KITTEN, false,
                        "2027-01-28", 3),
                Arguments.of("AC7 无 DP 的幼犬 Combo 第3剂 +1 年", COMBO_DHPPI_L, 3, "2027-01-28", KITTEN, false,
                        "2028-01-28", null),
                Arguments.of("AC9 DP 第1剂 +4 周", PUPPY_DP, 1, "2026-12-01", KITTEN, false, "2026-12-29", 2),
                Arguments.of("6 个月跨越：5 个月第1剂 young +4 周", F4, 1, "2027-03-01", KITTEN, false,
                        "2027-03-29", 2),
                Arguments.of("6 个月跨越：6.5 个月第2剂已是 adult → +1 年", F4, 2, "2027-04-15", KITTEN, false,
                        "2028-04-15", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void seriesRule(String name, VaccinePreset preset, Integer dose, String event, LocalDate birthday, boolean hasDp,
            String expected, Integer dueDose) {
        NextDateSuggestion s = VaccineSeriesRule.next(preset, dose, d(event), birthday, hasDp);
        assertThat(s.nextDate()).isEqualTo(d(expected));
        assertThat(s.dueVaccinePreset()).isEqualTo(preset);
        assertThat(s.dueDose()).isEqualTo(dueDose);
    }

    @Test
    void ac9PuppyDpDose2OrBlankGoesToComboDose1In3Weeks() {
        for (Integer dose : new Integer[] {2, null, 4}) {
            NextDateSuggestion s = VaccineSeriesRule.next(PUPPY_DP, dose, d("2027-03-01"), KITTEN, false);
            assertThat(s.nextDate()).isEqualTo(d("2027-03-22"));
            assertThat(s.dueVaccinePreset()).isEqualTo(COMBO_DHPPI_L);
            assertThat(s.dueDose()).isEqualTo(1);
        }
    }

    @Test
    void ac7KittenBlankAt8_12_16WeeksGetsExactlyTwoSeriesPrompts() {
        int prompts = 0;
        LocalDate event = KITTEN.plusWeeks(8);
        for (int i = 0; i < 3; i++) {
            NextDateSuggestion s = VaccineSeriesRule.next(F4, null, event, KITTEN, false);
            if (s.nextDate().equals(event.plusWeeks(4))) {
                prompts++;
            }
            event = event.plusWeeks(4);
        }
        assertThat(prompts).isEqualTo(2);
    }

    @Test
    void otherAndNoPresetHaveNoPrefill() {
        assertThat(VaccineSeriesRule.next(OTHER, 1, d("2026-03-01"), null, false)).isNull();
        assertThat(VaccineSeriesRule.next(null, 1, d("2026-03-01"), null, false)).isNull();
    }
}
