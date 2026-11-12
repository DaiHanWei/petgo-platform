package com.tailtopia.analytics.rollup;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * WIB 周 / 月的起止日期（analytics-pipeline Story 4.1，Epic 4 通用规则：周 = 周一至周日；月 = WIB 自然月）。
 *
 * <p>入参都是已换算好的 WIB 日期（{@code event_date} 口径），这里只做日历运算，不碰时区。
 * 本 story 建好并单测，Story 4.3 / 4.4 起用于周表 / 月表（D-12：周期结束次日计算并按 D-6 回补）。
 */
public final class RollupPeriods {

    /** 周期粒度。 */
    public enum Kind {
        WEEK,
        MONTH
    }

    /** 一个周期：{@code [start, end]} 闭区间（WIB 日期）。 */
    public record Period(Kind kind, LocalDate start, LocalDate end) {

        public Period {
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("周期起点晚于终点");
            }
        }

        /** 周期是否已结束：{@code end ≤ asOf}（asOf = 运行时的 WIB 昨天）。 */
        public boolean isComplete(LocalDate asOf) {
            return !end.isAfter(asOf);
        }

        public boolean contains(LocalDate d) {
            return !d.isBefore(start) && !d.isAfter(end);
        }
    }

    private RollupPeriods() {
    }

    /** {@code d} 所在的 WIB 周（周一至周日）。 */
    public static Period weekOf(LocalDate d) {
        LocalDate start = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Period(Kind.WEEK, start, start.plusDays(6));
    }

    /** {@code d} 所在的 WIB 自然月。 */
    public static Period monthOf(LocalDate d) {
        LocalDate start = d.withDayOfMonth(1);
        return new Period(Kind.MONTH, start, start.with(TemporalAdjusters.lastDayOfMonth()));
    }

    /** {@code d} 所在的周期。 */
    public static Period of(Kind kind, LocalDate d) {
        return kind == Kind.WEEK ? weekOf(d) : monthOf(d);
    }

    /** 周期是否已结束（{@code end ≤ asOf}）。 */
    public static boolean isComplete(Period period, LocalDate asOf) {
        return period.isComplete(asOf);
    }

    /** 与 {@code [from, to]} 相交的全部周期，按时间升序。 */
    public static List<Period> overlapping(Kind kind, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from 晚于 to");
        }
        List<Period> out = new ArrayList<>();
        Period p = of(kind, from);
        while (!p.start().isAfter(to)) {
            out.add(p);
            p = of(kind, p.end().plusDays(1));
        }
        return List.copyOf(out);
    }
}
