package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC4）：PRD FR-171 平均间隔，AC31 / AC32。 */
class HeatRuleTest {

    @Test
    void ac31OneHeatPlusSixMonths() {
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2027-01-01")))).isEqualTo(d("2027-07-01"));
    }

    @Test
    void ac31SecondHeatUsesGap181() {
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2027-01-01"), d("2027-07-01"))))
                .isEqualTo(d("2027-12-29"));
    }

    @Test
    void ac32ThirdHeatAveragesTwoGaps() {
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2027-01-01"), d("2027-07-01"), d("2027-12-29"))))
                .isEqualTo(d("2028-06-27"));
    }

    @Test
    void ac32GapUnder120DaysIgnored() {
        // 20 天的间隔被忽略 → 无可用间隔 → +6 个月
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2027-01-01"), d("2027-01-21"))))
                .isEqualTo(d("2027-07-21"));
    }

    @Test
    void onlyLastThreeHeatsCount() {
        // 第一个间隔 400 天不在最近 3 次里
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2025-01-01"), d("2026-02-05"), d("2026-08-05"),
                d("2027-02-05")))).isEqualTo(d("2027-02-05").plusDays(Math.round((181 + 184) / 2.0)));
    }

    @Test
    void clampedBetweenFourAndTwelveMonths() {
        // 间隔 500 天 → 钳到 +12 个月
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2025-01-01"), d("2026-05-16"))))
                .isEqualTo(d("2027-05-16"));
        // 间隔 121 天 → 钳到 +4 个月
        assertThat(HeatRule.nextForFemaleDog(List.of(d("2026-01-01"), d("2026-05-02"))))
                .isEqualTo(d("2026-09-02"));
    }
}
