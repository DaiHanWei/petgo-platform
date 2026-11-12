package com.tailtopia.analytics.store;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 原始层分区的纯计算（analytics-pipeline Story 1.3，D-2 / D-8）。无状态、无 IO，供 L0 测试。
 *
 * <p>🔴 原始层按 {@code received_at} 的 <b>UTC</b> 日分区：这里一切日期计算只用 UTC，出现 {@code Asia/Jakarta} 就是写错了。
 * WIB 口径日期只在明细层算（Story 1.6）。
 *
 * <p>分区名与边界只由 {@link LocalDate} 生成（DDL 不能用绑定参数，只能拼接）；拼接前一律经
 * {@link #requireSafeName} 校验，杜绝注入。
 */
public final class RawPartitionPlanner {

    /** 父表。 */
    public static final String PARENT = "events_raw";
    /** DEFAULT 兜底分区。 */
    public static final String DEFAULT_PARTITION = "events_raw_pdefault";
    /** 预建天数（不含今天）：今天 + 未来 7 天，共 8 个分区。 */
    public static final int DAYS_AHEAD = 7;

    private static final Pattern SAFE_NAME = Pattern.compile("^events_raw_p\\d{8}$");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd");
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    private RawPartitionPlanner() {
    }

    /** 按 UTC 取日期。 */
    public static LocalDate utcDay(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC);
    }

    /** 需要保证存在的分区日期：{@code todayUtc} 到 {@code todayUtc + 7} 天，含两端，共 8 天，升序。 */
    public static List<LocalDate> daysToEnsure(LocalDate todayUtc) {
        List<LocalDate> days = new ArrayList<>(DAYS_AHEAD + 1);
        for (int i = 0; i <= DAYS_AHEAD; i++) {
            days.add(todayUtc.plusDays(i));
        }
        return List.copyOf(days);
    }

    /** 分区名，如 {@code events_raw_p20261114}。 */
    public static String partitionName(LocalDate d) {
        return requireSafeName("events_raw_p" + NAME_FORMAT.format(d));
    }

    /** 下界字面量（含单引号），如 {@code '2026-11-14 00:00:00+00'}。必须带 {@code +00}，不依赖会话 TimeZone。 */
    public static String lowerBound(LocalDate d) {
        return "'" + BOUND_FORMAT.format(d) + " 00:00:00+00'";
    }

    /** 上界字面量（含单引号）= 下一天 00:00 UTC，如 {@code '2026-11-15 00:00:00+00'}。 */
    public static String upperBound(LocalDate d) {
        return lowerBound(d.plusDays(1));
    }

    /** 校验分区名只可能是 {@code events_raw_pYYYYMMDD}，否则抛 {@link IllegalArgumentException}。 */
    public static String requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法的原始层分区名");
        }
        return name;
    }
}
