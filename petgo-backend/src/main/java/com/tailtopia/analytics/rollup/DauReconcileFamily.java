package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.DauReconcileCalculator.Input;
import com.tailtopia.analytics.rollup.DauReconcileCalculator.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 指标族 {@code dau_reconcile}（analytics-pipeline Story 4.2 AC3 / AC4 / AC5，架构 D-11）：自建对账日活 vs GA4 V1.5.0+ 日活。
 *
 * <ol>
 *   <li>{@code analytics/rollup/dau_reconcile.sql} 写当天的数值列（自建对账日活、GA4 V1.5.0+、参照的自建日活 / 登录日活），
 *       {@link #dependsOn()} {@code activity}：activity 本轮失败 / 跳过时本指标族跳过。</li>
 *   <li>同一单元事务内，读出整张 {@code agg_daily_dau_reconcile}（每天一行，量很小）左连 {@code ga4_daily_snapshot}，
 *       用 {@link DauReconcileCalculator} 重算全部行的 GA4 数、差值、覆盖率、观察期起点与状态，只更新有变化的行——
 *       观察期起点会随 GA4 回拉变化；补拉窗口（14 天）内的旧日期快照更新后，对应行的 GA4 数也随之刷新。</li>
 * </ol>
 * 🔴 结果只存库，不推飞书（FR-25）：本类不碰 {@code DailyReportService} / {@code LarkWebhookClient}。
 */
public class DauReconcileFamily extends SqlRollupFamily {

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "dau_reconcile";

    static final String SELECT_ALL_SQL = """
            SELECT r.stat_date, r.self_reconcile_dau, r.ga4_new_version_dau, r.diff_pct, r.coverage, r.status,
                   r.observation_start, s.active_users_new_version AS snap_new, s.active_users_all AS snap_all
            FROM agg_daily_dau_reconcile r
            LEFT JOIN ga4_daily_snapshot s ON s.stat_date = r.stat_date
            ORDER BY r.stat_date""";

    static final String UPDATE_SQL = """
            UPDATE agg_daily_dau_reconcile
            SET ga4_new_version_dau = ?, diff_pct = ?, coverage = ?, status = ?, observation_start = ?
            WHERE stat_date = ?""";

    /** 表里现存的一行（重算前）。 */
    record Stored(LocalDate statDate, long selfReconcileDau, Long ga4NewVersion, BigDecimal diffPct,
            BigDecimal coverage, String status, LocalDate observationStart, Long snapNew, Long snapAll) {

        Input input() {
            return new Input(statDate, selfReconcileDau, snapNew, snapAll);
        }
    }

    public DauReconcileFamily() {
        super(NAME, 3, Set.of("activity"));
    }

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        super.computeDay(statDate, asOf, ctx);
        recomputeAll(ctx);
    }

    /** 整表重算并写回有变化的行（在调用方的单元事务内）。返回更新的行数。 */
    int recomputeAll(RollupContext ctx) {
        List<Stored> stored = ctx.jdbcTemplate().query(SELECT_ALL_SQL, (rs, i) -> new Stored(
                rs.getObject("stat_date", LocalDate.class),
                rs.getLong("self_reconcile_dau"),
                rs.getObject("ga4_new_version_dau", Long.class),
                rs.getBigDecimal("diff_pct"),
                rs.getBigDecimal("coverage"),
                rs.getString("status"),
                rs.getObject("observation_start", LocalDate.class),
                rs.getObject("snap_new", Long.class),
                rs.getObject("snap_all", Long.class)));
        List<Object[]> updates = updates(stored);
        if (!updates.isEmpty()) {
            ctx.jdbcTemplate().batchUpdate(UPDATE_SQL, updates);
        }
        return updates.size();
    }

    /** 纯计算：整表重算，返回需要更新的行的 UPDATE 参数（顺序同 {@link #UPDATE_SQL}）。 */
    static List<Object[]> updates(List<Stored> stored) {
        List<Result> results = DauReconcileCalculator.compute(stored.stream().map(Stored::input).toList());
        List<Object[]> out = new ArrayList<>();
        // compute 按日期升序；stored 也按日期升序（SELECT ORDER BY），这里仍按日期配对以防万一
        Map<LocalDate, Stored> byDate = new HashMap<>();
        for (Stored s : stored) {
            byDate.put(s.statDate(), s);
        }
        for (Result r : results) {
            Stored s = byDate.get(r.statDate());
            if (changed(s, r)) {
                out.add(new Object[] {r.ga4NewVersion(), r.diffPct(), r.coverage(), r.status().name(),
                        r.observationStart(), r.statDate()});
            }
        }
        return out;
    }

    /** 重算结果与现存行是否不同（数值按 {@link BigDecimal#compareTo} 比，忽略小数位差异）。 */
    static boolean changed(Stored s, Result r) {
        return !Objects.equals(s.ga4NewVersion(), r.ga4NewVersion())
                || !sameDecimal(s.diffPct(), r.diffPct())
                || !sameDecimal(s.coverage(), r.coverage())
                || !Objects.equals(s.status(), r.status().name())
                || !Objects.equals(s.observationStart(), r.observationStart());
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
