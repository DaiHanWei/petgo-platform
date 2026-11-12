package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** L0：install_profile（按月）与 retention 的注册参数、月份选择（analytics-pipeline Story 4.3 T2 / T3 / T5）。 */
class InstallProfileFamilyTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RollupContext ctx(JdbcTemplate jdbc, LocalDate asOf) {
        return new RollupContext(asOf, Instant.parse("2026-12-01T20:00:00Z"), List.of(),
                AnalyticsRollupProperties.defaults(), jdbc);
    }

    @Test
    void registration() {
        InstallProfileFamily f = new InstallProfileFamily();
        assertThat(f.name()).isEqualTo("install_profile");
        assertThat(f.recomputeDays()).isEqualTo(3);
        assertThat(f.hasPeriods()).isTrue();
        assertThat(f.dependsOn()).isEmpty();

        RollupFamily retention = new RollupConfig().retentionRollupFamily();
        assertThat(retention.name()).isEqualTo("retention");
        // 13 天观察窗口 + 3 天晚到：组日期最早回到 asOf − 15
        assertThat(retention.recomputeDays()).isEqualTo(16);
        assertThat(RollupService.startDate(null, d("2026-11-30"), retention.recomputeDays())).isEqualTo(d("2026-11-15"));
        assertThat(retention.hasPeriods()).isFalse();

        List<String> order = RollupService.orderByDependencies(List.of(new RollupConfig().activityRollupFamily(),
                new RollupConfig().installRollupFamily(), new RollupConfig().dauReconcileRollupFamily(), retention,
                f)).stream().map(RollupFamily::name).toList();
        assertThat(order).containsExactly("activity", "install", "dau_reconcile", "retention", "install_profile");
    }

    /** 跨月重算窗口取到两个月；窗口在月内只取一个月（T5 L0）。 */
    @Test
    void monthsOverlappingWindow() {
        assertThat(RollupPeriods.overlapping(Kind.MONTH, d("2026-11-29"), d("2026-12-01"))).containsExactly(
                new Period(Kind.MONTH, d("2026-11-01"), d("2026-11-30")),
                new Period(Kind.MONTH, d("2026-12-01"), d("2026-12-31")));
        assertThat(RollupPeriods.overlapping(Kind.MONTH, d("2026-11-10"), d("2026-11-12"))).singleElement()
                .isEqualTo(new Period(Kind.MONTH, d("2026-11-01"), d("2026-11-30")));
        // 月末次日：上月已完成、本月未完成
        assertThat(RollupPeriods.monthOf(d("2026-11-15")).isComplete(d("2026-11-30"))).isTrue();
        assertThat(RollupPeriods.monthOf(d("2026-12-01")).isComplete(d("2026-11-30"))).isFalse();
    }

    @Test
    void computeDayWritesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new InstallProfileFamily().computeDay(d("2026-11-30"), d("2026-11-30"), ctx(jdbc, d("2026-11-30")));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
    }

    /** 跨月窗口：两个月各执行一组 DELETE + INSERT；参数全部来自 Java。 */
    @Test
    void computePeriodsRunsEachMonth() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDate asOf = d("2026-12-01");
        new InstallProfileFamily().computePeriods(d("2026-11-29"), asOf, asOf, ctx(jdbc, asOf));

        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(4)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(0)).startsWith("DELETE FROM agg_monthly_install_profile").doesNotContain(":month_start");
        assertThat(sqls.get(1)).startsWith("INSERT INTO agg_monthly_install_profile");
        assertThat(sqls.get(2)).startsWith("DELETE FROM agg_monthly_install_profile");
    }

    @Test
    void monthParams() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDate asOf = d("2026-12-01");
        MapSqlParameterSource p = InstallProfileFamily.monthParams(RollupPeriods.monthOf(d("2026-11-29")), asOf,
                ctx(jdbc, asOf));
        assertThat(p.getValue("month_start")).isEqualTo(d("2026-11-01"));
        assertThat(p.getValue("month_end")).isEqualTo(d("2026-11-30"));
        assertThat(p.getValue("as_of")).isEqualTo(asOf);
        // 回看起点 = 月初 − 92 天（与 install.sql 的 lookback 天数相同）
        assertThat(p.getValue("lookback_start")).isEqualTo(d("2026-11-01").minusDays(92));
        assertThat(p.getValue("computed_at")).isInstanceOf(OffsetDateTime.class);
    }

    /** 月初早于汇总下限（明细已清理）的月份不算，避免把已有终值改小。 */
    @Test
    void monthsBeforeFloorAreSkipped() {
        LocalDate asOf = d("2026-11-20");
        LocalDate floor = RollupService.floor(asOf, 92);
        assertThat(floor).isEqualTo(d("2026-08-21"));
        assertThat(InstallProfileFamily.computable(RollupPeriods.monthOf(floor), floor)).isFalse();
        assertThat(InstallProfileFamily.computable(RollupPeriods.monthOf(d("2026-09-01")), floor)).isTrue();

        // 重跑窗口贴着下限：8 月跳过，只算 9 月（一组 DELETE + INSERT）
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new InstallProfileFamily().computePeriods(floor, d("2026-09-01"), asOf, ctx(jdbc, asOf));
        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class));
    }
}
