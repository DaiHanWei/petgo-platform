package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.store.RetentionPolicy.RawDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：保留期删除判定（analytics-pipeline Story 1.7 AC4）。纯函数，无 Spring、无库。 */
class RetentionPolicyTest {

    private static final LocalDate TODAY_UTC = LocalDate.of(2026, 12, 10);
    /** 游标远在未来：年龄够的一律可删。 */
    private static final Instant FAR_CURSOR = Instant.parse("2099-01-01T00:00:00Z");

    // ---------------------------------------------------------------- 原始层：35 天

    @Test
    void rawExactly35DaysOldIsKept36IsDropped() {
        LocalDate d35 = TODAY_UTC.minusDays(35);
        LocalDate d36 = TODAY_UTC.minusDays(36);

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(d35, d36), TODAY_UTC, FAR_CURSOR);

        assertThat(r.drop()).containsExactly(d36);
        assertThat(r.blocked()).isEmpty();
    }

    @Test
    void rawRecentAndFuturePartitionsNeverDropped() {
        Set<LocalDate> dates = Set.of(TODAY_UTC.minusDays(1), TODAY_UTC, TODAY_UTC.plusDays(7));

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(dates, TODAY_UTC, FAR_CURSOR);

        assertThat(r.drop()).isEmpty();
        assertThat(r.blocked()).isEmpty();
    }

    @Test
    void rawCursorNotPastDayBlocksDeletion() {
        LocalDate d = TODAY_UTC.minusDays(40);
        // 游标停在该日 23:59:59.999999 UTC：那天还有行没转
        Instant cursor = d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusNanos(1000);

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(d), TODAY_UTC, cursor);

        assertThat(r.drop()).isEmpty();
        assertThat(r.blocked()).containsExactly(d);
    }

    @Test
    void rawCursorExactlyAtNextUtcMidnightAllowsDeletion() {
        LocalDate d = TODAY_UTC.minusDays(40);
        Instant cursor = d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(d), TODAY_UTC, cursor);

        assertThat(r.drop()).containsExactly(d);
        assertThat(r.blocked()).isEmpty();
    }

    @Test
    void rawCursorSplitsDropAndBlockedSorted() {
        LocalDate a = TODAY_UTC.minusDays(50);
        LocalDate b = TODAY_UTC.minusDays(45);
        LocalDate c = TODAY_UTC.minusDays(40);
        // 游标刚越过 b 那天
        Instant cursor = b.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(1);

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(c, a, b), TODAY_UTC, cursor);

        assertThat(r.drop()).containsExactly(a, b);
        assertThat(r.blocked()).containsExactly(c);
    }

    @Test
    void rawMissingCursorBlocksEverythingOld() {
        LocalDate d = TODAY_UTC.minusDays(60);

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(d, TODAY_UTC), TODAY_UTC, null);

        assertThat(r.drop()).isEmpty();
        assertThat(r.blocked()).containsExactly(d);
    }

    @Test
    void rawAcrossYearBoundary() {
        LocalDate today = LocalDate.of(2027, 1, 20);
        LocalDate keep = LocalDate.of(2026, 12, 16); // 恰好 35 天
        LocalDate drop = LocalDate.of(2026, 12, 15); // 36 天

        RawDecision r = RetentionPolicy.rawPartitionsToDrop(Set.of(keep, drop), today, FAR_CURSOR);

        assertThat(r.drop()).containsExactly(drop);
    }

    @Test
    void defaultPartitionNeverBecomesCandidate() {
        Set<LocalDate> dates = RetentionPolicy.rawPartitionDates(
                List.of("events_raw_pdefault", "events_raw_p20200101", "events_raw_p2020010", "events_raw_p20201399",
                        "events_detail_p20200101", "other_table"));

        assertThat(dates).containsExactly(LocalDate.of(2020, 1, 1));
        RawDecision r = RetentionPolicy.rawPartitionsToDrop(dates, TODAY_UTC, FAR_CURSOR);
        assertThat(r.drop()).containsExactly(LocalDate.of(2020, 1, 1));
        assertThat(RetentionPolicy.detailPartitionDates(List.of("events_detail_pdefault", "events_detail_p20200101",
                "events_raw_p20200101"))).containsExactly(LocalDate.of(2020, 1, 1));
    }

    // ---------------------------------------------------------------- 明细层：3 个自然月

    @Test
    void detailThreeMonthBoundary() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        LocalDate edge = LocalDate.of(2026, 8, 12);

        List<LocalDate> drop = RetentionPolicy.detailPartitionsToDrop(
                Set.of(edge, edge.minusDays(1), edge.plusDays(1), today, today.plusDays(7)), today);

        assertThat(drop).containsExactly(edge.minusDays(1));
    }

    @Test
    void detailMonthEndUsesMinusMonthsSemantics() {
        // 2026-05-31 − 3 个月 = 2026-02-28（2 月没有 31 日）
        LocalDate today = LocalDate.of(2026, 5, 31);

        List<LocalDate> drop = RetentionPolicy.detailPartitionsToDrop(Set.of(LocalDate.of(2026, 2, 27),
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1)), today);

        assertThat(drop).containsExactly(LocalDate.of(2026, 2, 27));
    }

    @Test
    void detailMarch31MapsToDecember31() {
        LocalDate today = LocalDate.of(2027, 3, 31);

        List<LocalDate> drop = RetentionPolicy.detailPartitionsToDrop(Set.of(LocalDate.of(2026, 12, 30),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)), today);

        assertThat(drop).as("跨年：3 月 31 日 → 前一年 12 月 31 日").containsExactly(LocalDate.of(2026, 12, 30));
    }

    @Test
    void detailEmptyInputYieldsNothing() {
        assertThat(RetentionPolicy.detailPartitionsToDrop(Set.of(), LocalDate.of(2026, 1, 1))).isEmpty();
        assertThat(RetentionPolicy.rawPartitionsToDrop(Set.of(), LocalDate.of(2026, 1, 1), FAR_CURSOR).drop())
                .isEmpty();
    }
}
