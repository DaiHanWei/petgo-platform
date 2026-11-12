package com.tailtopia.analytics.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 日活对账的纯计算（analytics-pipeline Story 4.2 AC3 / AC4，FR-24 / FR-25，架构 D-11）。无 IO，L0 可测。
 *
 * <h2>数值</h2>
 * <ul>
 *   <li>差值 {@code diff_pct = (自建对账 − GA4 V1.5.0+) ÷ GA4 V1.5.0+}；GA4 为空或 0 → 空（不除零）。</li>
 *   <li>覆盖率 {@code coverage = GA4 V1.5.0+ ÷ GA4 全版本}（近似）；任一为空或全版本为 0 → 空。</li>
 *   <li>两者都是小数、四位小数四舍五入（与 {@code numeric(9,4)} 列一致）；阈值比较用<b>四舍五入后</b>的值，
 *       保证表里看到的数与状态一致（差值恰好 0.1000 算正常）。</li>
 * </ul>
 *
 * <h2>状态（输入整张表）</h2>
 * <ol>
 *   <li>观察期起点 = 最早一个 {@code coverage ≥ 0.90} 的日期；观察期 = {@code [起点, 起点 + 27]}（4 周，含两端）。</li>
 *   <li>观察期之后 → {@link Status#ENDED}。</li>
 *   <li>否则当天 GA4 没拉到（无快照）→ {@link Status#NO_GA4}（含观察期之前：GA4 缺数时覆盖率也无从判断；
 *       stag 不拉 GA4，全部是 NO_GA4）。</li>
 *   <li>否则观察期之前或尚无起点 → {@link Status#NOT_STARTED}。</li>
 *   <li>期内 {@code |差值| ≤ 0.10} → {@link Status#NORMAL}，否则 {@link Status#ABNORMAL}；GA4 V1.5.0+ 为 0 时
 *       自建也为 0 算正常、否则异常。</li>
 * </ol>
 */
public final class DauReconcileCalculator {

    /** 观察期起点的覆盖率阈值。 */
    public static final BigDecimal COVERAGE_THRESHOLD = new BigDecimal("0.90");
    /** 期内正常的 |差值| 上限（含）。 */
    public static final BigDecimal DIFF_THRESHOLD = new BigDecimal("0.10");
    /** 观察期天数（4 周，含起点当天）。 */
    public static final int OBSERVATION_DAYS = 28;
    /** 差值 / 覆盖率的小数位（= 列 numeric(9,4)）。 */
    static final int SCALE = 4;

    /** 对账状态（落库为枚举名，见 {@code ck_agg_daily_dau_reconcile_status}）。 */
    public enum Status {
        NOT_STARTED,
        NORMAL,
        ABNORMAL,
        ENDED,
        NO_GA4
    }

    /**
     * 一天的输入。
     *
     * @param ga4NewVersion GA4 V1.5.0+ 日活；null = GA4 没拉到
     * @param ga4All        GA4 全版本日活；null = GA4 没拉到
     */
    public record Input(LocalDate statDate, long selfReconcileDau, Long ga4NewVersion, Long ga4All) {
    }

    /** 一天的结果。{@code ga4NewVersion} 原样带出，便于调用方连同 GA4 回拉后的新值一起写回。 */
    public record Result(LocalDate statDate, Long ga4NewVersion, BigDecimal diffPct, BigDecimal coverage,
            LocalDate observationStart, Status status) {
    }

    private DauReconcileCalculator() {
    }

    /** 差值；GA4 为空或 0 → null。 */
    public static BigDecimal diffPct(long selfReconcileDau, Long ga4NewVersion) {
        if (ga4NewVersion == null || ga4NewVersion == 0) {
            return null;
        }
        return BigDecimal.valueOf(selfReconcileDau - ga4NewVersion)
                .divide(BigDecimal.valueOf(ga4NewVersion), SCALE, RoundingMode.HALF_UP);
    }

    /** 覆盖率；任一为空或全版本为 0 → null。 */
    public static BigDecimal coverage(Long ga4NewVersion, Long ga4All) {
        if (ga4NewVersion == null || ga4All == null || ga4All == 0) {
            return null;
        }
        return BigDecimal.valueOf(ga4NewVersion).divide(BigDecimal.valueOf(ga4All), SCALE, RoundingMode.HALF_UP);
    }

    /** 整表计算：按日期升序返回每一天的差值、覆盖率、观察期起点与状态。 */
    public static List<Result> compute(List<Input> rows) {
        List<Input> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(Input::statDate));
        List<BigDecimal> coverages = new ArrayList<>(sorted.size());
        LocalDate start = null;
        for (Input in : sorted) {
            BigDecimal c = coverage(in.ga4NewVersion(), in.ga4All());
            coverages.add(c);
            if (start == null && c != null && c.compareTo(COVERAGE_THRESHOLD) >= 0) {
                start = in.statDate();
            }
        }
        LocalDate end = start == null ? null : start.plusDays(OBSERVATION_DAYS - 1L);
        List<Result> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Input in = sorted.get(i);
            BigDecimal diff = diffPct(in.selfReconcileDau(), in.ga4NewVersion());
            out.add(new Result(in.statDate(), in.ga4NewVersion(), diff, coverages.get(i), start,
                    status(in, diff, start, end)));
        }
        return List.copyOf(out);
    }

    private static Status status(Input in, BigDecimal diff, LocalDate start, LocalDate end) {
        LocalDate d = in.statDate();
        if (end != null && d.isAfter(end)) {
            return Status.ENDED;
        }
        if (in.ga4NewVersion() == null) {
            return Status.NO_GA4;
        }
        if (start == null || d.isBefore(start)) {
            return Status.NOT_STARTED;
        }
        if (diff == null) {
            // GA4 V1.5.0+ 为 0：不除零，自建也为 0 才算对得上
            return in.selfReconcileDau() == 0 ? Status.NORMAL : Status.ABNORMAL;
        }
        return diff.abs().compareTo(DIFF_THRESHOLD) <= 0 ? Status.NORMAL : Status.ABNORMAL;
    }
}
