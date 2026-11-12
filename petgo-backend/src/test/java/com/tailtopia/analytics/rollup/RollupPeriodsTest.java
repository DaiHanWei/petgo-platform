package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：WIB 周（周一起）/ 自然月的起止与完成判定（Story 4.1 T8）。 */
class RollupPeriodsTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void weekStartsOnMonday() {
        // 2026-11-16 是周一
        assertThat(RollupPeriods.weekOf(d("2026-11-16"))).isEqualTo(new Period(Kind.WEEK, d("2026-11-16"), d("2026-11-22")));
        assertThat(RollupPeriods.weekOf(d("2026-11-22"))).isEqualTo(new Period(Kind.WEEK, d("2026-11-16"), d("2026-11-22")));
        assertThat(RollupPeriods.weekOf(d("2026-11-15")).start()).isEqualTo(d("2026-11-09"));
    }

    @Test
    void weekAcrossMonthAndYear() {
        // 2026-12-31 周四 → 周一 2026-12-28 ~ 周日 2027-01-03
        Period w = RollupPeriods.weekOf(d("2027-01-02"));
        assertThat(w.start()).isEqualTo(d("2026-12-28"));
        assertThat(w.end()).isEqualTo(d("2027-01-03"));
        assertThat(RollupPeriods.weekOf(d("2026-12-31"))).isEqualTo(w);
    }

    @Test
    void monthBoundaries() {
        assertThat(RollupPeriods.monthOf(d("2026-02-14"))).isEqualTo(new Period(Kind.MONTH, d("2026-02-01"), d("2026-02-28")));
        assertThat(RollupPeriods.monthOf(d("2028-02-29")).end()).isEqualTo(d("2028-02-29"));
        assertThat(RollupPeriods.monthOf(d("2026-12-31"))).isEqualTo(new Period(Kind.MONTH, d("2026-12-01"), d("2026-12-31")));
    }

    @Test
    void isCompleteWhenEndNotAfterAsOf() {
        Period w = RollupPeriods.weekOf(d("2026-11-18"));
        assertThat(RollupPeriods.isComplete(w, d("2026-11-21"))).isFalse();
        assertThat(RollupPeriods.isComplete(w, d("2026-11-22"))).isTrue();
        assertThat(w.isComplete(d("2026-11-23"))).isTrue();
        Period m = RollupPeriods.monthOf(d("2026-11-05"));
        assertThat(m.isComplete(d("2026-11-29"))).isFalse();
        assertThat(m.isComplete(d("2026-11-30"))).isTrue();
        assertThat(m.contains(d("2026-11-30"))).isTrue();
        assertThat(m.contains(d("2026-12-01"))).isFalse();
    }

    @Test
    void overlappingCrossesMonthAndYear() {
        List<Period> months = RollupPeriods.overlapping(Kind.MONTH, d("2026-12-30"), d("2027-01-01"));
        assertThat(months).extracting(Period::start).containsExactly(d("2026-12-01"), d("2027-01-01"));

        List<Period> weeks = RollupPeriods.overlapping(Kind.WEEK, d("2026-11-22"), d("2026-11-23"));
        assertThat(weeks).extracting(Period::start).containsExactly(d("2026-11-16"), d("2026-11-23"));

        assertThat(RollupPeriods.overlapping(Kind.WEEK, d("2026-11-17"), d("2026-11-17"))).hasSize(1);
        assertThatThrownBy(() -> RollupPeriods.overlapping(Kind.WEEK, d("2026-11-18"), d("2026-11-17")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
