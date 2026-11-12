package com.tailtopia.analytics.rollup;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 汇总装配（analytics-pipeline Story 4.1）。
 *
 * <p>🔴 {@code @EnableConfigurationProperties} 不能漏：没有它 {@link AnalyticsRollupProperties}（以及 Story 4.2 的
 * {@link Ga4SnapshotProperties} / {@link ReconcileProperties} / {@link AnalyticsDauSourceProperties}，Story 4.4 的
 * {@link PushRollupProperties}）不会被绑定，配了 env 也永远取默认值，
 * 且没有任何报错（见 {@code DailyReportConfig} 注释的教训）。
 *
 * <p>指标族在这里注册成 {@link RollupFamily} bean，{@link RollupService} 按 {@link RollupFamily#dependsOn()} 排序执行。
 * 后续 story（4.2–4.7）在此追加。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AnalyticsRollupProperties.class, Ga4SnapshotProperties.class,
        ReconcileProperties.class, AnalyticsDauSourceProperties.class, PushRollupProperties.class})
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

    /**
     * 运营位（Story 4.4）：{@code analytics/rollup/placement.sql} → {@code agg_daily_placement} / {@code agg_weekly_placement} /
     * {@code agg_monthly_placement}；业务库窄查询（运营位配置、已付款订单）在单元事务之外。{@code recomputeDays = 10}：7 天带单窗口 + 3 天晚到。
     */
    @Bean
    public RollupFamily placementRollupFamily(BusinessPlacementReader businessPlacementReader) {
        return new PlacementFamily(businessPlacementReader);
    }

    /**
     * 推送（Story 4.4）：{@code analytics/rollup/push.sql} → {@code agg_daily_push} / {@code agg_weekly_push} /
     * {@code agg_monthly_push}；运营推送活动经业务库窄查询（单元事务之外）。配置非法只停用累计打开人数并在就绪时 TECH 告警。
     */
    @Bean
    public PushFamily pushRollupFamily(BusinessPlacementReader businessPlacementReader, PushRollupProperties props,
            OpsAlertService alerts, @Value("${analytics.app-env:dev}") String appEnv) {
        return new PushFamily(businessPlacementReader, props, alerts, appEnv);
    }

    /**
     * Toko 浏览、漏斗与加购转化（Story 4.5）：{@code analytics/rollup/toko.sql} → {@code agg_daily_toko} / {@code agg_weekly_toko} /
     * {@code agg_monthly_toko}；业务库窄查询（已付款订单、订单计数）在单元事务之外，复用 {@link BusinessPlacementReader}。
     * {@code recomputeDays = 8}：7 天窗口每次回补 {@code [昨天 − 7, 昨天]}；周 / 月只在结束后计算。
     */
    @Bean
    public RollupFamily tokoRollupFamily(BusinessPlacementReader businessPlacementReader) {
        return new TokoFamily(businessPlacementReader);
    }

    /**
     * 购物车停留（Story 4.6，电商 #7）：{@code analytics/rollup/cart.sql} → {@code agg_daily_cart}（只存聚合）；业务库购物车订单行
     * 窄查询在单元事务之外，复用 {@link BusinessPlacementReader}。{@code recomputeDays = 8}：7 天观察窗每次回补 {@code [昨天 − 7, 昨天]}。
     */
    @Bean
    public RollupFamily cartRollupFamily(BusinessPlacementReader businessPlacementReader) {
        return new CartFamily(businessPlacementReader);
    }

    /**
     * 商品维度（Story 4.6，电商 #8 / #9）：{@code analytics/rollup/product.sql} → {@code agg_daily_product}；业务库商品 / 库存 /
     * 近 30 天已付款件数窄查询在单元事务之外。{@code recomputeDays = 3}；库存快照只在行首次写入时记录。
     */
    @Bean
    public RollupFamily productRollupFamily(BusinessPlacementReader businessPlacementReader) {
        return new ProductFamily(businessPlacementReader);
    }

    /**
     * 以日活为分母的比率（Story 4.7，S4 / 产品 #3 / 内容 #1）：{@code analytics/rollup/ratio.sql} → {@code agg_daily_ratio}；
     * 分子经 {@link PayerCountReader} 窄查询业务库（单元事务之外），分母按 {@link AnalyticsDauSourceProperties}（GA4 / SELF）。
     * 依赖 activity；{@code recomputeDays = 3}（D-6，与 GA4 回拉 3 天一致）。
     */
    @Bean
    public RollupFamily ratioRollupFamily(PayerCountReader payerCountReader, AnalyticsDauSourceProperties dauSource) {
        return new RatioRollup(payerCountReader, dauSource);
    }
}
