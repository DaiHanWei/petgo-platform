package com.tailtopia.analytics.store;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 存储水位评估的纯计算（analytics-pipeline Story 1.10，FR-16）。无状态、无 IO、无 Spring 依赖，供 L0 测试。
 *
 * <ul>
 *   <li>{@link #monthGrowthBytes}：{@code db_bytes} 与 30 天前快照之差；不足 30 天时取最早快照按天数折算为 30 天；没有更早快照 → {@code null}。</li>
 *   <li>{@link #evaluate}：{@code overAlert = dbBytes > alertBytes}；
 *       {@code switchBackHint = overAlert || monthEvents >= monthEventsHint
 *       || (monthGrowthBytes > 0 && (alertBytes − dbBytes) / monthGrowthBytes < hintMonths)}。</li>
 * </ul>
 */
public final class StorageWatermarkPolicy {

    /** 增速折算的基准天数。 */
    public static final int GROWTH_WINDOW_DAYS = 30;

    private StorageWatermarkPolicy() {
    }

    /** 一条历史快照（只需日期与整库占用）。 */
    public record Snapshot(LocalDate date, long dbBytes) {
    }

    /** 阈值（取自 {@code AnalyticsStorageProperties}）。 */
    public record Thresholds(long alertBytes, long monthEventsHint, int hintMonths) {
    }

    /**
     * 评估结果。
     *
     * @param overAlert      占用已超过告警线
     * @param switchBackHint 需提示「评估切回第三方」
     * @param monthsToAlert  按月增速外推到告警线的月数；已超线为 0；增速未知 / 为 0 / 为负时为 {@code null}（不会触及）
     */
    public record Evaluation(boolean overAlert, boolean switchBackHint, Double monthsToAlert) {
    }

    /**
     * 30 天增长量。
     *
     * @param today   本次快照日期（WIB）
     * @param dbBytes 本次整库占用
     * @param prior   早于 {@code today} 的候选快照（顺序不限；等于或晚于 {@code today} 的忽略）
     * @return 30 天折算增长（可为负）；没有更早快照时 {@code null}
     */
    public static Long monthGrowthBytes(LocalDate today, long dbBytes, List<Snapshot> prior) {
        LocalDate cutoff = today.minusDays(GROWTH_WINDOW_DAYS);
        Snapshot ref = null;
        // 优先：30 天前或更早里最近的一条
        for (Snapshot s : prior) {
            if (!s.date().isAfter(cutoff) && (ref == null || s.date().isAfter(ref.date()))) {
                ref = s;
            }
        }
        // 不足 30 天：取最早的一条
        if (ref == null) {
            for (Snapshot s : prior) {
                if (s.date().isBefore(today) && (ref == null || s.date().isBefore(ref.date()))) {
                    ref = s;
                }
            }
        }
        if (ref == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(ref.date(), today);
        long diff = dbBytes - ref.dbBytes();
        if (days == GROWTH_WINDOW_DAYS) {
            return diff;
        }
        return Math.round((double) diff * GROWTH_WINDOW_DAYS / days);
    }

    /**
     * 评估是否超线 / 是否提示切回第三方。
     *
     * @param dbBytes          当前整库占用
     * @param monthEvents      本 WIB 自然月截至当天的事件量
     * @param monthGrowthBytes 30 天增长；{@code null} = 未知
     */
    public static Evaluation evaluate(long dbBytes, long monthEvents, Long monthGrowthBytes, Thresholds t) {
        boolean overAlert = dbBytes > t.alertBytes();
        Double months;
        if (dbBytes >= t.alertBytes()) {
            months = 0.0;
        } else if (monthGrowthBytes != null && monthGrowthBytes > 0) {
            months = (double) (t.alertBytes() - dbBytes) / monthGrowthBytes;
        } else {
            months = null;
        }
        boolean hint = overAlert
                || monthEvents >= t.monthEventsHint()
                || (monthGrowthBytes != null && monthGrowthBytes > 0 && months != null && months < t.hintMonths());
        return new Evaluation(overAlert, hint, months);
    }
}
