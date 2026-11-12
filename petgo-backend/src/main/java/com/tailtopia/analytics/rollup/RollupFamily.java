package com.tailtopia.analytics.rollup;

import java.time.LocalDate;
import java.util.Set;

/**
 * 一个指标族（analytics-pipeline Story 4.1，架构 D-5）：一组写进同一批 {@code agg_*} 表、共用一条 {@code agg_progress} 进度的指标。
 *
 * <p>注册方式：声明成 Spring bean（见 {@link RollupConfig}），{@link RollupService} 按 {@link #dependsOn()} 排序后串行执行。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #computeDay} 在 {@link RollupService} 开好的<b>埋点库单元事务</b>内被调用：事务已拿到
 *       {@code ROLLUP} advisory lock、已 {@code SET LOCAL statement_timeout}、已建好临时表 {@code tmp_excluded_users}。
 *       实现只经 {@link RollupContext#jdbc()} / {@link RollupContext#jdbcTemplate()} 访问埋点库，<b>不得</b>在其中查业务库、
 *       发网络请求、自开事务。</li>
 *   <li>必须幂等：同一 {@code statDate} 重跑结果相同（先删后插或 upsert）。</li>
 *   <li>日期全部来自参数，不得在 SQL 里用当前日期函数推算日期；只按 {@code event_date} 过滤明细。</li>
 *   <li>写入的 {@code agg_*} 表不得含 {@code user_id} / 匿名 id / {@code person_key} 等可识别个人的字段。</li>
 * </ul>
 */
public interface RollupFamily {

    /** 指标族名：{@code agg_progress.family} 的值，SQL 型指标族同时是 {@code analytics/rollup/<name>.sql} 的文件名。 */
    String name();

    /** 每次定时运行重算最近多少个 WIB 日（架构 D-6：默认 3）。 */
    default int recomputeDays() {
        return 3;
    }

    /** 依赖的指标族名：依赖本轮失败 / 被跳过时，本指标族本轮跳过。 */
    default Set<String> dependsOn() {
        return Set.of();
    }

    /**
     * 计算一个 WIB 日。
     *
     * @param statDate 统计日（WIB）
     * @param asOf     本次运行的基准日 = 运行时的 WIB 昨天（手动重跑也传当前的 asOf）
     * @param ctx      本次运行的上下文
     */
    void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx);

    /** 是否有周 / 月表要算（Story 4.3 / 4.4 起使用）。为 true 时 {@link RollupService} 在逐日计算全部成功后调 {@link #computePeriods}。 */
    default boolean hasPeriods() {
        return false;
    }

    /**
     * 计算与本次重算窗口 {@code [windowStart, windowEnd]} 相交的周 / 月（用 {@link RollupPeriods} 取周期，
     * {@code is_complete = 周期末 ≤ asOf}）。在一个单独的单元事务内调用，契约同 {@link #computeDay}。
     */
    default void computePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
    }
}
