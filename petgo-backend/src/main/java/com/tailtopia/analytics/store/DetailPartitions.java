package com.tailtopia.analytics.store;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 明细层分区的命名、预建与已建查询（analytics-pipeline Story 1.6，D-3 / D-8）。
 *
 * <p>明细层按 {@code event_date}（WIB 日期）每天一个分区 {@code events_detail_pYYYYMMDD}，<b>没有 DEFAULT 分区</b>。
 * WIB「今天」由调用方经 {@link EventTimeCorrector#wibDate} 算好传入，本类不做时区换算。
 *
 * <p>分区名与边界只由 {@link LocalDate} 生成（DDL 不能用绑定参数，只能拼接）；拼接前一律经 {@link #requireSafeName} 校验。
 */
public final class DetailPartitions {

    /** 父表。 */
    public static final String PARENT = "events_detail";
    /** 预建天数（不含今天）：今天 + 未来 7 天，共 8 个分区。 */
    public static final int DAYS_AHEAD = 7;

    private static final String PREFIX = "events_detail_p";
    private static final Pattern SAFE_NAME = Pattern.compile("^events_detail_p\\d{8}$");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd");
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    static final String BUILT_SQL = "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
            + "WHERE i.inhparent = '" + PARENT + "'::regclass";

    private DetailPartitions() {
    }

    /** 需要保证存在的分区日期：{@code todayWib} 到 {@code todayWib + 7} 天，含两端，升序。 */
    public static List<LocalDate> daysToEnsure(LocalDate todayWib) {
        List<LocalDate> days = new ArrayList<>(DAYS_AHEAD + 1);
        for (int i = 0; i <= DAYS_AHEAD; i++) {
            days.add(todayWib.plusDays(i));
        }
        return List.copyOf(days);
    }

    /** 分区名，如 {@code events_detail_p20261114}。 */
    public static String partitionName(LocalDate d) {
        return requireSafeName(PREFIX + NAME_FORMAT.format(d));
    }

    /** 由分区名反解日期；不是本类命名规则的名字返回 {@code null}。 */
    static LocalDate dateOf(String partitionName) {
        if (partitionName == null || !SAFE_NAME.matcher(partitionName).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(partitionName.substring(PREFIX.length()), NAME_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 建分区 DDL（{@code IF NOT EXISTS}，重复执行不报错）。 */
    static String createSql(LocalDate d) {
        return "CREATE TABLE IF NOT EXISTS " + partitionName(d) + " PARTITION OF " + PARENT
                + " FOR VALUES FROM ('" + BOUND_FORMAT.format(d) + "') TO ('" + BOUND_FORMAT.format(d.plusDays(1))
                + "')";
    }

    /**
     * 某天分区不存在则建。调用方负责事务与锁（{@code PartitionMaintenanceJob} 在拿到维护锁的埋点事务内调用）。
     *
     * @return {@code true} = 本次新建
     */
    static boolean ensure(JdbcTemplate jdbc, LocalDate d) {
        String name = partitionName(d);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class,
                name))) {
            return false;
        }
        jdbc.execute(createSql(d));
        return true;
    }

    /** 当前已挂在 {@code events_detail} 上的分区日期集合（查 {@code pg_inherits} + {@code pg_class}）。 */
    public static Set<LocalDate> builtDates(JdbcTemplate jdbc) {
        Set<LocalDate> out = new HashSet<>();
        for (String name : jdbc.queryForList(BUILT_SQL, String.class)) {
            LocalDate d = dateOf(name);
            if (d != null) {
                out.add(d);
            }
        }
        return Set.copyOf(out);
    }

    /** 校验分区名只可能是 {@code events_detail_pYYYYMMDD}，否则抛 {@link IllegalArgumentException}。 */
    public static String requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法的明细层分区名");
        }
        return name;
    }
}
