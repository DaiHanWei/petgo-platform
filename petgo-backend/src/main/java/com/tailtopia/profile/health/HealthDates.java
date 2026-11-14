package com.tailtopia.profile.health;

import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 健康规则的日期工具（V1.5.0 Story 1.2，架构增量 §2 / §4.2）。🔴 一律 WIB；「+N 个月」= {@link LocalDate#plusMonths}
 * （月末钳制：1 月 31 日 + 1 = 2 月 28 日），永远从记录自己的 event date 起算。
 */
public final class HealthDates {

    /** 日跑时刻 10:00 WIB（cron {@code 0 0 3 * * *} UTC）。 */
    public static final LocalTime DAILY_RUN_TIME = LocalTime.of(10, 0);

    /** young / adult 分界：未满 6 个月 = young。 */
    public static final int YOUNG_MONTHS = 6;

    private HealthDates() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ScheduleWindow.WIB));
    }

    public static LocalDate today() {
        return today(Clock.systemUTC());
    }

    public static LocalDate toWibDate(Instant instant) {
        return instant.atZone(ScheduleWindow.WIB).toLocalDate();
    }

    public static long ageInDays(LocalDate birthday, LocalDate at) {
        return ChronoUnit.DAYS.between(birthday, at);
    }

    public static long ageInWeeks(LocalDate birthday, LocalDate at) {
        return ageInDays(birthday, at) / 7;
    }

    public static long ageInMonths(LocalDate birthday, LocalDate at) {
        return ChronoUnit.MONTHS.between(birthday, at);
    }

    /** young = 有生日且 {@code at} 时未满 6 个月；无生日一律 adult（PRD FR-159 / FR-163 Pet age）。 */
    public static boolean isYoung(LocalDate birthday, LocalDate at) {
        return birthday != null && at.isBefore(birthday.plusMonths(YOUNG_MONTHS));
    }

    /** 有生日且 {@code at} 时未满 {@code weeks} 周（无生日 → false）。 */
    public static boolean isUnderWeeks(LocalDate birthday, LocalDate at, int weeks) {
        return birthday != null && at.isBefore(birthday.plusWeeks(weeks));
    }

    /** 「下一次日跑」：WIB 时刻早于当天 10:00 → 当天，否则次日（架构 §4.2）。 */
    public static LocalDate nextRunDate(Instant now) {
        var wib = now.atZone(ScheduleWindow.WIB);
        LocalDate day = wib.toLocalDate();
        return wib.toLocalTime().isBefore(DAILY_RUN_TIME) ? day : day.plusDays(1);
    }
}
