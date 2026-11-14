package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.DewormType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0（V1.5.0 Story 1.2 · AC3）：PRD FR-160 / FR-163 驱虫间隔，用例写 PRD AC 编号。 */
class DewormRuleTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
        "AC11 External 3 个月产品, EXTERNAL, 3, 2026-10-01, 2024-01-01, 2027-01-01",
        "External 缺省时长 1 个月, EXTERNAL, NULL, 2026-10-01, 2024-01-01, 2026-11-01",
        "AC19 Internal ≥6 个月 +3 个月, INTERNAL, NULL, 2026-10-01, 2026-01-01, 2027-01-01",
        "AC19 Internal 3–6 个月 +1 个月, INTERNAL, NULL, 2026-10-01, 2026-06-15, 2026-11-01",
        "AC19 Internal 5 周 +14 天, INTERNAL, NULL, 2026-10-01, 2026-08-27, 2026-10-15",
        "AC19 Internal 7 周 6 天 +14 天, INTERNAL, NULL, 2026-10-21, 2026-08-27, 2026-11-04",
        "Internal 恰好 8 周 +1 个月, INTERNAL, NULL, 2026-10-22, 2026-08-27, 2026-11-22",
        "Internal 无生日 +3 个月, INTERNAL, NULL, 2026-10-01, NULL, 2027-01-01",
        "Flea+worm +1 个月, FLEA_WORM, 3, 2026-01-31, 2024-01-01, 2026-02-28",
        "Heartworm +1 个月, HEARTWORM, NULL, 2026-10-01, 2024-01-01, 2026-11-01",
        "AC53 Legacy +3 个月, NULL, NULL, 2026-10-15, NULL, 2027-01-15"})
    void interval(String name, DewormType type, Integer months, String event, String birthday, String expected) {
        assertThat(DewormRule.next(type, months, d(event), birthday == null ? null : d(birthday)))
                .isEqualTo(d(expected));
    }
}
