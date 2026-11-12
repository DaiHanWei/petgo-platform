package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 汇总装配（analytics-pipeline Story 4.1）。
 *
 * <p>🔴 {@code @EnableConfigurationProperties} 不能漏：没有它 {@link AnalyticsRollupProperties}（以及 Story 4.2 的
 * {@link Ga4SnapshotProperties} / {@link ReconcileProperties} / {@link AnalyticsDauSourceProperties}）不会被绑定，配了 env 也永远取默认值，
 * 且没有任何报错（见 {@code DailyReportConfig} 注释的教训）。
 *
 * <p>指标族在这里注册成 {@link RollupFamily} bean，{@link RollupService} 按 {@link RollupFamily#dependsOn()} 排序执行。
 * 后续 story（4.2–4.7）在此追加。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AnalyticsRollupProperties.class, Ga4SnapshotProperties.class,
        ReconcileProperties.class, AnalyticsDauSourceProperties.class})
public class RollupConfig {

    /** 留存指标族名（= SQL 文件名）。 */
    public static final String RETENTION = "retention";
    /** 留存指标族每次重算的天数：13 天观察窗口 + 3 天晚到。 */
    public static final int RETENTION_RECOMPUTE_DAYS = 16;

    /** 日活：{@code analytics/rollup/activity.sql} → {@code agg_daily_activity}。 */
    @Bean
    public RollupFamily activityRollupFamily() {
        return new SqlRollupFamily("activity", 3, Set.of());
    }

    /** 新装 / 注册：{@code analytics/rollup/install.sql} → {@code agg_daily_install}。 */
    @Bean
    public RollupFamily installRollupFamily() {
        return new SqlRollupFamily("install", 3, Set.of());
    }

    /** 日活对账（Story 4.2）：{@code analytics/rollup/dau_reconcile.sql} + 整表重算状态 → {@code agg_daily_dau_reconcile}；依赖 activity。 */
    @Bean
    public RollupFamily dauReconcileRollupFamily() {
        return new DauReconcileFamily();
    }

    /**
     * 留存（Story 4.3）：{@code analytics/rollup/retention.sql} → {@code agg_cohort_retention}（INSTALL 组 D1 / D3 / D7、
     * ONBOARDING 组 W1）。{@code recomputeDays = 16}：W1 要看到 X+13，再留 3 天给晚到数据（D-6），组日期最早回到 {@code asOf − 15}。
     */
    @Bean
    public RollupFamily retentionRollupFamily() {
        return new SqlRollupFamily(RETENTION, RETENTION_RECOMPUTE_DAYS, Set.of());
    }

    /** 安装后建档（Story 4.3，按月）：{@code analytics/rollup/install_profile.sql} → {@code agg_monthly_install_profile}。 */
    @Bean
    public RollupFamily installProfileRollupFamily() {
        return new InstallProfileFamily();
    }
}
