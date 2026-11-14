package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC1）：WIB 今天、月末钳制、young / adult、下一次日跑。 */
class HealthDatesTest {

    @Test
    void todayIsWib() {
        // 2026-11-14 17:30 UTC = 2026-11-15 00:30 WIB
        Clock c = Clock.fixed(Instant.parse("2026-11-14T17:30:00Z"), ZoneOffset.UTC);
        assertThat(HealthDates.today(c)).isEqualTo(d("2026-11-15"));
    }

    @Test
    void plusMonthsClampsMonthEnd() {
        assertThat(d("2027-01-31").plusMonths(1)).isEqualTo(d("2027-02-28"));
    }

    @Test
    void youngAdultBoundaryAndNoBirthdayIsAdult() {
        assertThat(HealthDates.isYoung(d("2026-01-01"), d("2026-06-30"))).isTrue();
        assertThat(HealthDates.isYoung(d("2026-01-01"), d("2026-07-01"))).isFalse();
        assertThat(HealthDates.isYoung(null, d("2026-07-01"))).isFalse();
    }

    @Test
    void ages() {
        assertThat(HealthDates.ageInWeeks(d("2026-10-01"), d("2026-12-03"))).isEqualTo(9);
        assertThat(HealthDates.ageInMonths(d("2026-01-31"), d("2026-03-30"))).isEqualTo(1);
        assertThat(HealthDates.ageInDays(d("2026-01-01"), d("2026-01-15"))).isEqualTo(14);
    }

    @Test
    void nextRunDateBeforeAndAfterTenWib() {
        assertThat(HealthDates.nextRunDate(Instant.parse("2026-11-14T02:59:59Z"))).isEqualTo(d("2026-11-14"));
        assertThat(HealthDates.nextRunDate(Instant.parse("2026-11-14T03:00:00Z"))).isEqualTo(d("2026-11-15"));
        // 2026-11-14 18:00 UTC = 11-15 01:00 WIB → 当天（11-15）
        assertThat(HealthDates.nextRunDate(Instant.parse("2026-11-14T18:00:00Z"))).isEqualTo(d("2026-11-15"));
    }
}
