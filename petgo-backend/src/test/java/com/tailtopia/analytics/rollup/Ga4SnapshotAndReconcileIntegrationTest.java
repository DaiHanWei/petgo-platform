package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient.Ga4VersionSplit;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.Ga4DailySnapshotJob.Outcome;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：GA4 快照 + 日活对账（analytics-pipeline Story 4.2 AC1 / AC2 / AC3 / AC4），真业务库 + 埋点测试库
 * {@code petgo_analytics_test}（前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>GA4：Mockito 假 {@link Ga4ActiveUsersClient}（不出网、无真凭证）；快照任务定时关闭（基类
 *       {@code analytics.ga4-snapshot.cron=-}），用例手工 new 一个带固定时钟与 mock 告警的 {@link Ga4DailySnapshotJob}。</li>
 *   <li>隔离：对账日活按整天计数、状态按整表重算，与共享库里别的测试的事件分不开——本类一律用<b>远期日期</b>（2099 年），
 *       自建分区并在结束后删掉；{@code ga4_daily_snapshot} / {@code agg_daily_dau_reconcile} 只有本类写，每个用例前清空。</li>
 * </ul>
 */
class Ga4SnapshotAndReconcileIntegrationTest extends ApiIntegrationTest {

    /** 远期统计日：WIB 昨天 = F。 */
    private static final LocalDate F = LocalDate.of(2099, 3, 10);
    /** F+1 的 WIB 01:00。 */
    private static final Instant NOW = F.plusDays(1).atTime(1, 0).atZone(EventTimeCorrector.WIB).toInstant();
    private static final LocalDate FAR = LocalDate.of(2099, 1, 1);

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Autowired
    private JdbcTemplate businessJdbc;
    @Autowired
    private Ga4SnapshotRepository snapshots;

    private OpsAlertService alerts;
    private Ga4ActiveUsersClient ga4;
    private String prefix;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        alerts = mock(OpsAlertService.class);
        ga4 = mock(Ga4ActiveUsersClient.class);
        when(ga4.isConfigured()).thenReturn(true);
        prefix = "it-ga4-" + SEQ.incrementAndGet() + "-";
        analyticsJdbc.update("DELETE FROM agg_daily_dau_reconcile");
        analyticsJdbc.update("DELETE FROM ga4_daily_snapshot");
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = F.minusDays(2); !d.isAfter(F); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM agg_daily_dau_reconcile");
        analyticsJdbc.update("DELETE FROM ga4_daily_snapshot");
        analyticsJdbc.update("DELETE FROM agg_daily_activity WHERE stat_date >= ?", FAR);
        analyticsJdbc.update("DELETE FROM agg_progress WHERE last_done_date >= ?", FAR);
        for (LocalDate d = F.minusDays(2); !d.isAfter(F); d = d.plusDays(1)) {
            analyticsJdbc.execute("DROP TABLE IF EXISTS " + DetailPartitions.partitionName(d));
        }
    }

    private Ga4DailySnapshotJob job() {
        return new Ga4DailySnapshotJob(state, ga4, snapshots, Ga4SnapshotProperties.defaults(),
                ReconcileProperties.defaults(), alerts, "test", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RollupService rollup() {
        return new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc),
                List.of(new SqlRollupFamily("activity"), new DauReconcileFamily()),
                AnalyticsRollupProperties.defaults(), alerts, "test", Clock.systemUTC());
    }

    private Map<String, Object> snapshot(LocalDate d) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList("SELECT active_users_all, active_users_new_version, "
                + "min_app_version FROM ga4_daily_snapshot WHERE stat_date = ?", d);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> reconcile(LocalDate d) {
        return analyticsJdbc.queryForMap("SELECT * FROM agg_daily_dau_reconcile WHERE stat_date = ?", d);
    }

    private static long n(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    private void event(String anon, LocalDate day, boolean production, boolean suspicious, String source) {
        Instant at = day.atTime(10, 0).atZone(EventTimeCorrector.WIB).toInstant();
        OffsetDateTime t = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, 'Application Opened', ?, ?, ?, ?, ?, ?, NULL, NULL, 'android', ?, '{}'::jsonb)""",
                prefix + SEQ.incrementAndGet(), t, t, day, suspicious, source, anon, production);
    }

    // ---------------------------------------------------------------- 快照（AC1 / AC2）

    @Test
    void snapshotWrittenThenLastThreeDaysOverwritten() {
        when(ga4.activeUsersByVersion(any(), eq("1.5.0"))).thenReturn(new Ga4VersionSplit(1000, 600));
        Ga4DailySnapshotJob.RunResult r1 = job().run();
        assertThat(r1.outcome()).isEqualTo(Outcome.DONE);
        // 空表：D−1..D−3 + 补拉窗口 14 天内缺的 = 14 天
        assertThat(r1.written()).hasSize(14);
        assertThat(n(snapshot(F), "active_users_all")).isEqualTo(1000);
        assertThat(n(snapshot(F.minusDays(13)), "active_users_new_version")).isEqualTo(600);
        assertThat(snapshot(F).get("min_app_version")).isEqualTo("1.5.0");
        assertThat(snapshot(F.minusDays(14))).isNull();

        // 第二次 GA4 返回不同值：最近 3 天覆盖，更早的不再拉
        when(ga4.activeUsersByVersion(any(), eq("1.5.0"))).thenReturn(new Ga4VersionSplit(1100, 700));
        Ga4DailySnapshotJob.RunResult r2 = job().run();
        assertThat(r2.written()).containsExactly(F.minusDays(2), F.minusDays(1), F);
        assertThat(n(snapshot(F), "active_users_all")).isEqualTo(1100);
        assertThat(n(snapshot(F.minusDays(2)), "active_users_new_version")).isEqualTo(700);
        assertThat(n(snapshot(F.minusDays(3)), "active_users_all")).isEqualTo(1000);
        verifyNoInteractions(alerts);

        // 补拉：删掉一行旧日期，下次运行补上
        analyticsJdbc.update("DELETE FROM ga4_daily_snapshot WHERE stat_date = ?", F.minusDays(9));
        assertThat(job().run().written()).contains(F.minusDays(9));
        assertThat(n(snapshot(F.minusDays(9)), "active_users_all")).isEqualTo(1100);
    }

    @Test
    void notConfiguredSkipsWithoutAlert() {
        when(ga4.isConfigured()).thenReturn(false);
        assertThat(job().run().outcome()).isEqualTo(Outcome.SKIPPED_NOT_CONFIGURED);
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM ga4_daily_snapshot", Long.class)).isZero();
        verifyNoInteractions(alerts);
    }

    @Test
    void failureAlertsAndKeepsOldRow() {
        when(ga4.activeUsersByVersion(any(), anyString())).thenReturn(new Ga4VersionSplit(500, 400));
        assertThat(job().run().outcome()).isEqualTo(Outcome.DONE);

        when(ga4.activeUsersByVersion(eq(F), anyString())).thenReturn(null);
        when(ga4.activeUsersByVersion(eq(F.minusDays(1)), anyString())).thenReturn(new Ga4VersionSplit(510, 410));
        Ga4DailySnapshotJob.RunResult r = job().run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.failed()).containsExactly(F);
        assertThat(n(snapshot(F), "active_users_all")).isEqualTo(500);
        assertThat(n(snapshot(F.minusDays(1)), "active_users_all")).isEqualTo(510);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-ga4:" + F), anyString());
    }

    // ---------------------------------------------------------------- 对账（AC3 / AC4）

    @Test
    void reconcileMatchesHandCalculation() {
        // F−1：两台设备（一台非生产也计入）；GA4 全版本 2、新版 2 → 覆盖率 1.0（观察期起点）、差值 0 → 正常
        event(UUID.randomUUID().toString(), F.minusDays(1), true, false, "APP");
        event(UUID.randomUUID().toString(), F.minusDays(1), false, false, "APP");
        // F：设备 a 打开两次（按设备去重）、设备 b 非生产（计入）；H5 / SERVER / 可疑不计
        String a = UUID.randomUUID().toString();
        event(a, F, true, false, "APP");
        event(a, F, true, false, "APP");
        event(UUID.randomUUID().toString(), F, false, false, "APP");
        event(UUID.randomUUID().toString(), F, true, false, "H5");
        event(UUID.randomUUID().toString(), F, true, false, "SERVER");
        event(UUID.randomUUID().toString(), F, true, true, "APP");
        snapshots.upsert(F.minusDays(1), 2, 2, "1.5.0", NOW);
        snapshots.upsert(F, 4, 3, "1.5.0", NOW);

        RollupService.RunResult rr = rollup().rerun(F.minusDays(2), F, List.of("activity", "dau_reconcile"), F);
        assertThat(rr.outcome()).isEqualTo(RollupService.Outcome.DONE);

        Map<String, Object> d1 = reconcile(F.minusDays(1));
        assertThat(n(d1, "self_reconcile_dau")).isEqualTo(2);
        assertThat(n(d1, "ga4_new_version_dau")).isEqualTo(2);
        assertThat((BigDecimal) d1.get("diff_pct")).isEqualByComparingTo("0");
        assertThat((BigDecimal) d1.get("coverage")).isEqualByComparingTo("1");
        assertThat(d1.get("status")).isEqualTo("NORMAL");
        // 参照：自建日活按人、只算生产 → 1
        assertThat(n(d1, "dau_person")).isEqualTo(1);

        Map<String, Object> d = reconcile(F);
        assertThat(n(d, "self_reconcile_dau")).isEqualTo(2);
        assertThat(n(d, "ga4_new_version_dau")).isEqualTo(3);
        assertThat((BigDecimal) d.get("diff_pct")).isEqualByComparingTo("-0.3333");
        assertThat((BigDecimal) d.get("coverage")).isEqualByComparingTo("0.75");
        assertThat(d.get("status")).isEqualTo("ABNORMAL");
        assertThat(n(d, "dau_person")).isEqualTo(1);
        assertThat(n(d, "dau_logged_in")).isZero();
        assertThat(d.get("observation_start").toString()).isEqualTo(F.minusDays(1).toString());

        // F−2：没有事件也没有 GA4 → 0 与 NO_GA4
        Map<String, Object> d2 = reconcile(F.minusDays(2));
        assertThat(n(d2, "self_reconcile_dau")).isZero();
        assertThat(d2.get("ga4_new_version_dau")).isNull();
        assertThat(d2.get("status")).isEqualTo("NO_GA4");

        // GA4 回拉改了 F−1：覆盖率掉到 0.5 → 观察期起点消失，整表重算（只重跑 F 也会刷新 F−1）
        snapshots.upsert(F.minusDays(1), 100, 50, "1.5.0", NOW);
        assertThat(rollup().rerun(F, F, List.of("dau_reconcile"), F).outcome()).isEqualTo(RollupService.Outcome.DONE);
        Map<String, Object> d1b = reconcile(F.minusDays(1));
        assertThat(n(d1b, "ga4_new_version_dau")).isEqualTo(50);
        assertThat((BigDecimal) d1b.get("coverage")).isEqualByComparingTo("0.5");
        assertThat(d1b.get("status")).isEqualTo("NOT_STARTED");
        assertThat(d1b.get("observation_start")).isNull();
        assertThat(reconcile(F).get("status")).isEqualTo("NOT_STARTED");

        // 幂等：再跑一次结果不变
        Map<String, Object> before = new HashMap<>(reconcile(F));
        before.remove("computed_at");
        assertThat(rollup().rerun(F, F, List.of("dau_reconcile"), F).outcome()).isEqualTo(RollupService.Outcome.DONE);
        Map<String, Object> again = new HashMap<>(reconcile(F));
        again.remove("computed_at");
        assertThat(again).isEqualTo(before);

        // 对账表没有可识别个人的列
        List<String> cols = analyticsJdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name IN ('agg_daily_dau_reconcile', 'ga4_daily_snapshot')", String.class);
        assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key", "user_key");
    }
}
