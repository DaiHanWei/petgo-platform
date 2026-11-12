package com.tailtopia.analytics.store;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 原始层 / 明细层保留期判定（analytics-pipeline Story 1.7，架构 D-8）。纯函数：无 Spring、无 IO，
 * <b>不在内部取当前时间</b>——「今天」与进度游标一律由调用方传入。
 *
 * <ul>
 *   <li>原始层（UTC 日分区）：分区日 {@code d < todayUtc − 35} <b>且</b> 明细进度游标 {@code >= (d + 1) 00:00 UTC}
 *       （那一天的原始行已全部转进明细层）才删；年龄够了但游标没越过的，列为「被进度挡住」交调用方告警。</li>
 *   <li>明细层（WIB 日分区）：{@code d < todayWib.minusMonths(3)} 才删（自然月减 3，月末按 {@link LocalDate#minusMonths} 语义）。</li>
 *   <li>DEFAULT 分区（{@code *_pdefault}）永不进入候选：分区名 → 日期的解析只认 {@code _pYYYYMMDD}。</li>
 * </ul>
 */
public final class RetentionPolicy {

    /** 原始层保留天数。 */
    public static final int RAW_RETENTION_DAYS = 35;
    /** 明细层保留月数。 */
    public static final int DETAIL_RETENTION_MONTHS = 3;

    private static final Pattern RAW_NAME = Pattern.compile("^events_raw_p\\d{8}$");
    private static final Pattern DETAIL_NAME = Pattern.compile("^events_detail_p\\d{8}$");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd");

    /**
     * 原始层判定结果。
     *
     * @param drop    可以删的分区日（升序）
     * @param blocked 年龄已超保留期、但明细进度游标还没越过的分区日（升序），不删、告警
     */
    public record RawDecision(List<LocalDate> drop, List<LocalDate> blocked) {
    }

    private RetentionPolicy() {
    }

    /**
     * 原始层哪些分区该删。
     *
     * @param rawPartitionUtcDates 已建原始层日分区（UTC 日期，不含 DEFAULT）
     * @param todayUtc             UTC 今天
     * @param detailCursor         {@code pipeline_progress} 中 {@code pipeline='detail'} 的 {@code last_received_at}；
     *                             {@code null}（进度行缺失）视为一天都没转，全部挡住
     */
    public static RawDecision rawPartitionsToDrop(Set<LocalDate> rawPartitionUtcDates, LocalDate todayUtc,
            Instant detailCursor) {
        LocalDate cutoff = todayUtc.minusDays(RAW_RETENTION_DAYS);
        List<LocalDate> drop = new ArrayList<>();
        List<LocalDate> blocked = new ArrayList<>();
        for (LocalDate d : new TreeSet<>(rawPartitionUtcDates)) {
            if (!d.isBefore(cutoff)) {
                continue;
            }
            Instant dayEnd = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (detailCursor != null && !detailCursor.isBefore(dayEnd)) {
                drop.add(d);
            } else {
                blocked.add(d);
            }
        }
        return new RawDecision(List.copyOf(drop), List.copyOf(blocked));
    }

    /**
     * 明细层哪些分区该删（升序）。
     *
     * @param detailPartitionWibDates 已建明细层日分区（WIB 日期）
     * @param todayWib                WIB 今天（{@link EventTimeCorrector#wibDate}）
     */
    public static List<LocalDate> detailPartitionsToDrop(Set<LocalDate> detailPartitionWibDates, LocalDate todayWib) {
        LocalDate cutoff = todayWib.minusMonths(DETAIL_RETENTION_MONTHS);
        List<LocalDate> drop = new ArrayList<>();
        for (LocalDate d : new TreeSet<>(detailPartitionWibDates)) {
            if (d.isBefore(cutoff)) {
                drop.add(d);
            }
        }
        return List.copyOf(drop);
    }

    /** 原始层分区名 → UTC 日期集合；DEFAULT 与不合命名规则的名字忽略。 */
    public static Set<LocalDate> rawPartitionDates(Collection<String> partitionNames) {
        return datesOf(partitionNames, RAW_NAME, "events_raw_p".length());
    }

    /** 明细层分区名 → WIB 日期集合；不合命名规则的名字忽略。 */
    public static Set<LocalDate> detailPartitionDates(Collection<String> partitionNames) {
        return datesOf(partitionNames, DETAIL_NAME, "events_detail_p".length());
    }

    private static Set<LocalDate> datesOf(Collection<String> names, Pattern pattern, int prefixLength) {
        Set<LocalDate> out = new TreeSet<>();
        for (String name : names) {
            if (name == null || !pattern.matcher(name).matches()) {
                continue;
            }
            try {
                out.add(LocalDate.parse(name.substring(prefixLength), NAME_FORMAT));
            } catch (DateTimeParseException e) {
                // 形如 _p20261399 的非法日期：不是本模块建的，忽略
            }
        }
        return Set.copyOf(out);
    }
}
