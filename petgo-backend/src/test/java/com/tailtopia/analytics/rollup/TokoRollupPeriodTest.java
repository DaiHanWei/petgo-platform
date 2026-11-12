package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * L0：指标族 toko 的重算区间、周 / 月选择（跨月、跨年、周一起始、月末 31 / 30 / 28 / 29 日）、{@code is_final} 判定、
 * WIB 边界时间戳换算（analytics-pipeline Story 4.5 T6）。纯计算，无库、无 Spring。
 */
class TokoRollupPeriodTest {

    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static List<Period> periods(String from, String to, String asOf) {
        return TokoFamily.finishedPeriods(d(from), d(to), d(asOf), 92);
    }

    private static List<Period> months(List<Period> ps) {
        return ps.stream().filter(p -> p.kind() == Kind.MONTH).toList();
    }

    /** 运行日 R、Y = R − 1：日表重算 [Y − 7, Y]（8 天）；进度落后时从断点续算（取较早者）。 */
    @Test
    void dailyRecomputeWindowIsYMinus7ToY() {
        assertThat(TokoFamily.RECOMPUTE_DAYS).isEqualTo(8);
        TokoFamily f = new TokoFamily(null);
        assertThat(f.recomputeDays()).isEqualTo(8);
        assertThat(f.hasPeriods()).isTrue();
        assertThat(f.name()).isEqualTo("toko");
        LocalDate y = d("2026-11-20");
        assertThat(RollupService.startDate(null, y, f.recomputeDays())).isEqualTo(d("2026-11-13"));
        assertThat(RollupService.startDate(d("2026-11-19"), y, f.recomputeDays())).isEqualTo(d("2026-11-13"));
        // 进度落后：从 last_done + 1 续算
        assertThat(RollupService.startDate(d("2026-11-01"), y, f.recomputeDays())).isEqualTo(d("2026-11-02"));
    }

    /** 跨月：只算已结束、结束日落在区间内的周与月；周一起始。 */
    @Test
    void finishedPeriodsAcrossMonth() {
        assertThat(periods("2026-11-25", "2026-12-02", "2026-12-02")).containsExactly(
                new Period(Kind.WEEK, d("2026-11-23"), d("2026-11-29")),
                new Period(Kind.MONTH, d("2026-11-01"), d("2026-11-30")));
        // 周中（结束日未到）不算
        assertThat(periods("2026-11-25", "2026-11-28", "2026-11-28")).isEmpty();
    }

    /** 跨年：12 月与跨年那周（12-28 周一 – 01-03 周日）都在 2027-01-03 结束 / 之前结束。 */
    @Test
    void finishedPeriodsAcrossYear() {
        assertThat(periods("2026-12-27", "2027-01-03", "2027-01-03")).containsExactly(
                new Period(Kind.WEEK, d("2026-12-21"), d("2026-12-27")),
                new Period(Kind.WEEK, d("2026-12-28"), d("2027-01-03")),
                new Period(Kind.MONTH, d("2026-12-01"), d("2026-12-31")));
    }

    /** 月末 30 / 31 / 28 / 29 日。 */
    @Test
    void monthEnds() {
        assertThat(months(periods("2026-04-30", "2026-05-07", "2026-05-07")))
                .containsExactly(new Period(Kind.MONTH, d("2026-04-01"), d("2026-04-30")));
        assertThat(months(periods("2026-10-31", "2026-11-07", "2026-11-07")))
                .containsExactly(new Period(Kind.MONTH, d("2026-10-01"), d("2026-10-31")));
        assertThat(months(periods("2027-02-24", "2027-03-03", "2027-03-03")))
                .containsExactly(new Period(Kind.MONTH, d("2027-02-01"), d("2027-02-28")));
        assertThat(periods("2028-02-27", "2028-03-05", "2028-03-05")).containsExactly(
                new Period(Kind.WEEK, d("2028-02-21"), d("2028-02-27")),
                new Period(Kind.WEEK, d("2028-02-28"), d("2028-03-05")),
                new Period(Kind.MONTH, d("2028-02-01"), d("2028-02-29")));
    }

    /** 月表在月结束后 8 次运行内都会重算：结束日仍在 [Y − 7, Y] 内就算，第 9 次起不再算。 */
    @Test
    void monthRecomputedForEightRuns() {
        LocalDate monthEnd = d("2026-11-30");
        for (int k = 0; k < 8; k++) {
            LocalDate y = monthEnd.plusDays(k);
            assertThat(months(TokoFamily.finishedPeriods(y.minusDays(7), y, y, 92))).as("Y = %s", y)
                    .containsExactly(new Period(Kind.MONTH, d("2026-11-01"), monthEnd));
        }
        LocalDate y = monthEnd.plusDays(8);
        assertThat(months(TokoFamily.finishedPeriods(y.minusDays(7), y, y, 92))).isEmpty();
    }

    /** 首日早于汇总下限（明细已清理）的周期不算。 */
    @Test
    void periodsBeforeFloorSkipped() {
        LocalDate asOf = d("2026-11-20");
        assertThat(RollupService.floor(asOf, 92)).isEqualTo(d("2026-08-21"));
        assertThat(TokoFamily.finishedPeriods(d("2026-08-24"), d("2026-08-31"), asOf, 92)).containsExactly(
                new Period(Kind.WEEK, d("2026-08-24"), d("2026-08-30")));
    }

    /** is_final = 周期末日 ≤ Y − 7。 */
    @Test
    void isFinal() {
        LocalDate y = d("2026-11-20");
        assertThat(TokoFamily.isFinal(d("2026-11-13"), y)).isTrue();
        assertThat(TokoFamily.isFinal(d("2026-11-12"), y)).isTrue();
        assertThat(TokoFamily.isFinal(d("2026-11-14"), y)).isFalse();
        assertThat(TokoFamily.isFinal(y, y)).isFalse();
        // 周（周日 11-15）在 Y = 11-22 才终；月（11-30）在 12-07 才终
        assertThat(TokoFamily.isFinal(d("2026-11-15"), d("2026-11-21"))).isFalse();
        assertThat(TokoFamily.isFinal(d("2026-11-15"), d("2026-11-22"))).isTrue();
        assertThat(TokoFamily.isFinal(d("2026-11-30"), d("2026-12-06"))).isFalse();
        assertThat(TokoFamily.isFinal(d("2026-11-30"), d("2026-12-07"))).isTrue();
    }

    /** WIB 0 点 = 前一天 17:00Z；窗口末日 = 周期末日 + 7（跨年）。 */
    @Test
    void wibBoundaries() {
        assertThat(TokoFamily.wibStart(d("2026-11-10"))).isEqualTo(Instant.parse("2026-11-09T17:00:00Z"));
        assertThat(TokoFamily.wibStart(d("2027-01-01"))).isEqualTo(Instant.parse("2026-12-31T17:00:00Z"));
        assertThat(TokoFamily.windowEnd(d("2026-12-28"))).isEqualTo(d("2027-01-04"));
        assertThat(TokoFamily.windowEnd(d("2028-02-25"))).isEqualTo(d("2028-03-03"));
    }

    /** SQL 参数：日期区间、窗口末日、is_final、业务库计数、computed_at（UTC）。 */
    @Test
    void sqlParameters() {
        TokoFamily.Unit u = new TokoFamily.Unit("weekly", d("2026-11-02"), d("2026-11-08"), 5, 7);
        MapSqlParameterSource p = TokoFamily.params(u, d("2026-11-15"), RUN_AT);
        assertThat(p.getValue("period_start")).isEqualTo(d("2026-11-02"));
        assertThat(p.getValue("period_end")).isEqualTo(d("2026-11-08"));
        assertThat(p.getValue("window_end")).isEqualTo(d("2026-11-15"));
        assertThat(p.getValue("is_final")).isEqualTo(true);
        assertThat(p.getValue("orders_paid")).isEqualTo(5);
        assertThat(p.getValue("orders_created_biz")).isEqualTo(7);
        assertThat(p.getValue("computed_at").toString()).isEqualTo("2026-11-21T20:00Z");
        assertThat(p.getParameterNames()).containsExactlyInAnyOrder("period_start", "period_end", "window_end",
                "is_final", "orders_paid", "orders_created_biz", "computed_at");
        assertThat(TokoFamily.params(u, d("2026-11-14"), RUN_AT).getValue("is_final")).isEqualTo(false);
    }
}
