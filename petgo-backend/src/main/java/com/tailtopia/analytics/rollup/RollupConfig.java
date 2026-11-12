package com.tailtopia.analytics.rollup;

import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 汇总装配（analytics-pipeline Story 4.1）。
 *
 * <p>🔴 {@code @EnableConfigurationProperties} 不能漏：没有它 {@link AnalyticsRollupProperties} 不会被绑定，配了 env 也永远取默认值，
 * 且没有任何报错（见 {@code DailyReportConfig} 注释的教训）。
 *
 * <p>指标族在这里注册成 {@link RollupFamily} bean，{@link RollupService} 按 {@link RollupFamily#dependsOn()} 排序执行。
 * 后续 story（4.2–4.7）在此追加。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsRollupProperties.class)
public class RollupConfig {

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
}
