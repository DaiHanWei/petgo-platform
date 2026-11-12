package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code install_profile}（analytics-pipeline Story 4.3 AC3，产品 #1，D-12 / D-13）：按 WIB 自然月写
 * {@code agg_monthly_install_profile}（当月新装人数、其中当月 {@code pet_added} 的人数）。
 *
 * <ul>
 *   <li>没有日表：{@link #computeDay} 不写数据（每日单元只推进 {@code agg_progress}，作「数据截至哪天」的新鲜度标记）。</li>
 *   <li>{@link #computePeriods}：用 {@link RollupPeriods} 取与本次重算窗口相交的月份（跨月窗口取到两个月），逐月执行
 *       {@code analytics/rollup/install_profile.sql}；月中写「截至昨天」的值（{@code is_complete = false}），月末次日起算终值，
 *       之后 3 天内（{@link #recomputeDays()} = 3，D-6）随回补再刷新。</li>
 *   <li>月初早于汇总下限（{@link RollupService#floor}：明细已清理）的月份不算，避免用残缺明细把已有终值改小。
 *       定时运行的窗口只回到 {@code asOf − 2}，不会碰到；只有贴着下限的手动重跑会跳过（INFO 日志）。</li>
 * </ul>
 */
public class InstallProfileFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(InstallProfileFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "install_profile";

    private final List<String> statements;

    public InstallProfileFamily() {
        this.statements = SqlRollupFamily.splitStatements(SqlRollupFamily.readScript(NAME));
        if (statements.isEmpty()) {
            throw new IllegalStateException(SqlRollupFamily.resourcePath(NAME) + " 没有任何语句");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 拆好的语句（不含注释与结尾分号）。 */
    List<String> statements() {
        return statements;
    }

    /** 没有日表：每日单元只推进进度。 */
    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        // 按月计算见 computePeriods
    }

    @Override
    public boolean hasPeriods() {
        return true;
    }

    @Override
    public void computePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        LocalDate floor = RollupService.floor(asOf, ctx.properties().retentionDays());
        for (Period month : RollupPeriods.overlapping(Kind.MONTH, windowStart, windowEnd)) {
            if (!computable(month, floor)) {
                log.info("analytics rollup month skipped: family={} month={} floor={} (detail already purged)", NAME,
                        month.start(), floor);
                continue;
            }
            MapSqlParameterSource params = monthParams(month, asOf, ctx);
            for (String sql : statements) {
                ctx.jdbc().update(sql, params);
            }
        }
    }

    /** 月初不早于汇总下限才算（明细完整）。 */
    static boolean computable(Period month, LocalDate floor) {
        return !month.start().isBefore(floor);
    }

    /** 月 SQL 的参数：{@code month_start}、{@code month_end}、{@code as_of}、{@code lookback_start}、{@code computed_at}。 */
    static MapSqlParameterSource monthParams(Period month, LocalDate asOf, RollupContext ctx) {
        return new MapSqlParameterSource()
                .addValue("month_start", month.start())
                .addValue("month_end", month.end())
                .addValue("as_of", asOf)
                .addValue("lookback_start", ctx.lookbackStart(month.start()))
                .addValue("computed_at", OffsetDateTime.ofInstant(ctx.runAt(), ZoneOffset.UTC));
    }
}
