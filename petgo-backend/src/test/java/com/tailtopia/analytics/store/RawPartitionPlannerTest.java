package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：原始层分区日期计算（analytics-pipeline Story 1.3 AC5）。 */
class RawPartitionPlannerTest {

    private static List<String> names(LocalDate today) {
        return RawPartitionPlanner.daysToEnsure(today).stream().map(RawPartitionPlanner::partitionName).toList();
    }

    @Test
    void ensuresTodayPlusSevenDays() {
        List<LocalDate> days = RawPartitionPlanner.daysToEnsure(LocalDate.of(2026, 11, 13));
        assertThat(days).hasSize(8);
        assertThat(days.get(0)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(days.get(7)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(days).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void crossesMonth() {
        assertThat(names(LocalDate.of(2026, 11, 28)))
                .containsExactly("events_raw_p20261128", "events_raw_p20261129", "events_raw_p20261130",
                        "events_raw_p20261201", "events_raw_p20261202", "events_raw_p20261203",
                        "events_raw_p20261204", "events_raw_p20261205");
    }

    @Test
    void crossesYear() {
        List<String> n = names(LocalDate.of(2026, 12, 28));
        assertThat(n).contains("events_raw_p20261231", "events_raw_p20270101", "events_raw_p20270104");
        assertThat(n.get(7)).isEqualTo("events_raw_p20270104");
    }

    @Test
    void leapYear() {
        assertThat(names(LocalDate.of(2028, 2, 28)).subList(0, 3))
                .containsExactly("events_raw_p20280228", "events_raw_p20280229", "events_raw_p20280301");
        assertThat(RawPartitionPlanner.upperBound(LocalDate.of(2028, 2, 28))).isEqualTo("'2028-02-29 00:00:00+00'");
        // 非闰年 2 月 28 日的下一天是 3 月 1 日
        assertThat(RawPartitionPlanner.upperBound(LocalDate.of(2027, 2, 28))).isEqualTo("'2027-03-01 00:00:00+00'");
    }

    @Test
    void utcDayIgnoresWib() {
        // WIB 已是 2027-01-01 01:00，原始层仍按 UTC 归 2026-12-31
        LocalDate d = RawPartitionPlanner.utcDay(Instant.parse("2026-12-31T18:00:00Z"));
        assertThat(d).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(RawPartitionPlanner.partitionName(d)).isEqualTo("events_raw_p20261231");
        // UTC 17:30 = WIB 次日 00:30：依旧归 UTC 当天
        assertThat(RawPartitionPlanner.utcDay(Instant.parse("2026-11-13T17:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void utcMidnightBoundary() {
        assertThat(RawPartitionPlanner.partitionName(RawPartitionPlanner.utcDay(Instant.parse("2026-11-13T23:59:59Z"))))
                .isEqualTo("events_raw_p20261113");
        assertThat(RawPartitionPlanner.partitionName(RawPartitionPlanner.utcDay(Instant.parse("2026-11-14T00:00:00Z"))))
                .isEqualTo("events_raw_p20261114");
    }

    @Test
    void boundsCarryUtcOffsetAndUpperIsNextMidnight() {
        LocalDate d = LocalDate.of(2026, 11, 14);
        assertThat(RawPartitionPlanner.lowerBound(d)).isEqualTo("'2026-11-14 00:00:00+00'");
        assertThat(RawPartitionPlanner.upperBound(d)).isEqualTo("'2026-11-15 00:00:00+00'");
        assertThat(RawPartitionPlanner.upperBound(LocalDate.of(2026, 12, 31))).isEqualTo("'2027-01-01 00:00:00+00'");
        assertThat(RawPartitionPlanner.upperBound(LocalDate.of(2026, 11, 30))).isEqualTo("'2026-12-01 00:00:00+00'");
        // 相邻两天首尾相接，没有缝也没有重叠
        assertThat(RawPartitionPlanner.upperBound(d)).isEqualTo(RawPartitionPlanner.lowerBound(d.plusDays(1)));
    }

    @Test
    void partitionNameIsZeroPadded() {
        assertThat(RawPartitionPlanner.partitionName(LocalDate.of(2027, 1, 4))).isEqualTo("events_raw_p20270104");
    }

    @Test
    void rejectsUnsafeNames() {
        assertThatThrownBy(() -> RawPartitionPlanner.requireSafeName("events_raw_p2026111; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawPartitionPlanner.requireSafeName("events_raw_pdefault"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawPartitionPlanner.requireSafeName(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RawPartitionPlanner.requireSafeName("events_raw_p20261114")).isEqualTo("events_raw_p20261114");
    }
}
