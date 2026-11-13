package com.tailtopia.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.erasure.ErasureJob;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker;
import com.tailtopia.analytics.ingest.IngestFlusherWatchdog;
import com.tailtopia.analytics.ingest.IngestProperties;
import com.tailtopia.analytics.ingest.UnknownEventAlertJob;
import com.tailtopia.analytics.rollup.AnalyticsRollupProperties;
import com.tailtopia.analytics.rollup.Ga4DailySnapshotJob;
import com.tailtopia.analytics.rollup.Ga4SnapshotProperties;
import com.tailtopia.analytics.rollup.RollupJob;
import com.tailtopia.analytics.rollup.RollupRerunJob;
import com.tailtopia.analytics.store.DetailBuilder;
import com.tailtopia.analytics.store.DetailRecomputeJob;
import com.tailtopia.analytics.store.PartitionMaintenanceJob;
import com.tailtopia.analytics.store.StorageWatermarkJob;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * L0：analytics-pipeline Story 4.9 AC8–AC12 —— 埋点配置非法时上下文照常启动，只停用 / 回退对应功能，其余埋点 bean 正常；
 * 告警 / 日志只含键名、不含配置值。用 {@link ApplicationContextRunner}，不连库、不起 Web。
 */
class AnalyticsConfigIsolationTest {

    /** 配置里故意放一个「像凭证」的值，断言它不会出现在 invalidReason / 告警里。 */
    private static final String SECRET_LOOKING = "S3cr3t-Value";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AnalyticsRollupProperties.class, Ga4SnapshotProperties.class,
            AnalyticsStorageProperties.class, IngestProperties.class})
    static class PropsConfig {
    }

    private final OpsAlertService alerts = mock(OpsAlertService.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class, AnalyticsSchedulingConfig.class)
                .withBean(AnalyticsConfigProblems.class)
                .withBean(OpsAlertService.class, () -> alerts)
                .withBean(AnalyticsConfigAlert.class)
                .withBean(RollupJob.class, () -> mock(RollupJob.class))
                .withBean(RollupRerunJob.class, () -> mock(RollupRerunJob.class))
                .withBean(Ga4DailySnapshotJob.class, () -> mock(Ga4DailySnapshotJob.class))
                .withBean(ErasureJob.class, () -> mock(ErasureJob.class))
                .withBean(UnknownEventAlertJob.class, () -> mock(UnknownEventAlertJob.class))
                .withBean(StorageWatermarkJob.class, () -> mock(StorageWatermarkJob.class))
                .withBean(PartitionMaintenanceJob.class, () -> mock(PartitionMaintenanceJob.class))
                .withBean(DetailBuilder.class, () -> mock(DetailBuilder.class))
                .withBean(DetailRecomputeJob.class, () -> mock(DetailRecomputeJob.class))
                .withBean(IngestCircuitBreaker.class, () -> mock(IngestCircuitBreaker.class))
                .withBean(IngestFlusherWatchdog.class, () -> mock(IngestFlusherWatchdog.class));
    }

    /** 跑一次调度注册，返回注册结果（任务名=调度）。 */
    private static List<String> schedule(AssertableApplicationContext ctx) {
        AnalyticsSchedulingConfig cfg = ctx.getBean(AnalyticsSchedulingConfig.class);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        cfg.configureTasks(registrar);
        return cfg.registered();
    }

    private static boolean has(List<String> registered, String prefix) {
        return registered.stream().anyMatch(s -> s.startsWith(prefix + "="));
    }

    @Test
    @DisplayName("全部默认：上下文启动，全部任务按原调度注册，不告警")
    void defaultsRegisterEverything() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            List<String> r = schedule(ctx);
            assertThat(r).contains("rollup=cron(0 0 3 * * * Asia/Jakarta)",
                    "rollup-rerun=fixedDelay(PT1M, initial PT1M)",
                    "ga4-snapshot=cron(0 30 2 * * * Asia/Jakarta)",
                    "storage-watermark=cron(0 30 4 * * * Asia/Jakarta)",
                    "ingest-breaker=fixedDelay(PT5M, initial PT0S)",
                    "ingest-flusher-watchdog=fixedDelay(PT1M, initial PT1M)",
                    "partition-maintenance=cron(0 5 0 * * * Z)",
                    "unknown-event-alert=cron(0 30 1 * * * Z)",
                    "erasure=cron(0 0 2 * * * Asia/Jakarta)",
                    "detail-builder=fixedDelay(PT1M, initial PT1M)",
                    "detail-recompute=fixedDelay(PT1M, initial PT1M)");
            ctx.getBean(AnalyticsConfigAlert.class).onReady();
            verify(alerts, never()).send(any(), any(), any());
        });
    }

    @ParameterizedTest(name = "analytics.rollup.{0}")
    @ValueSource(strings = {"enabled=flase", "cron=0 0 25 * * *", "statement-timeout=600s; DROP", "install-lookback-days=-1",
            "retention-days=abc", "rerun-fixed-delay-ms=-5", "rerun-initial-delay-ms=soon"})
    @DisplayName("AC8 / AC9：汇总配置非法 → 启动正常、汇总停用、两个汇总任务都不注册，其余埋点任务照常；就绪告警只含键名")
    void rollupInvalidDisablesRollupOnly(String kv) {
        runner().withPropertyValues("analytics.rollup." + kv).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AnalyticsRollupProperties p = ctx.getBean(AnalyticsRollupProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.invalidReason()).contains("analytics.rollup." + kv.substring(0, kv.indexOf('=')));
            assertThat(p.statementTimeout()).isEqualTo("600s");
            List<String> r = schedule(ctx);
            assertThat(has(r, "rollup")).isFalse();
            assertThat(has(r, "rollup-rerun")).isFalse();
            assertThat(has(r, "ga4-snapshot")).isTrue();
            assertThat(has(r, "ingest-flusher-watchdog")).isTrue();
            assertThat(has(r, "erasure")).isTrue();

            ctx.getBean(AnalyticsConfigAlert.class).onReady();
            verify(alerts).send(eq(OpsChannel.TECH), argThat(k -> k.startsWith(AnalyticsConfigAlert.ALERT_PREFIX)),
                    argThat(t -> t.startsWith("【埋点配置非法】") && t.contains("指标汇总与手动重跑已停用")));
        });
    }

    @Test
    @DisplayName("AC8：非法值不进 invalidReason / 告警文案（只含键名）")
    void reasonsNeverContainValues() {
        runner().withPropertyValues("analytics.rollup.statement-timeout=" + SECRET_LOOKING,
                "analytics.storage.stop-bytes=" + SECRET_LOOKING,
                "analytics.ingest.flush-batch-size=" + SECRET_LOOKING,
                "analytics.partition.cron=" + SECRET_LOOKING).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    schedule(ctx);
                    List<String> problems = ctx.getBean(AnalyticsConfigAlert.class).problems();
                    assertThat(problems).hasSize(4);
                    assertThat(String.join("\n", problems)).doesNotContain(SECRET_LOOKING);
                    ctx.getBean(AnalyticsConfigAlert.class).onReady();
                    verify(alerts).send(eq(OpsChannel.TECH), any(),
                            argThat(t -> !t.contains(SECRET_LOOKING) && t.contains("analytics.partition.cron")));
                });
    }

    @ParameterizedTest(name = "analytics.storage.{0}")
    @ValueSource(strings = {"stop-bytes=-1", "alert-bytes=lots", "month-events-hint=0", "hint-months=flase",
            "breaker-check-ms=abc", "breaker-initial-delay-ms=-1", "breaker-retry-after-seconds=0",
            "watermark-cron=every day"})
    @DisplayName("AC10：存储配置非法 → 启动正常、非法键回退默认、任务照常注册；只有熔断相关键（stop-bytes / breaker-*）非法才停用按水位停收")
    void storageInvalidDisablesBreakerOnly(String kv) {
        runner().withPropertyValues("analytics.storage." + kv).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AnalyticsStorageProperties p = ctx.getBean(AnalyticsStorageProperties.class);
            assertThat(p.valid()).isFalse();
            String key = kv.substring(0, kv.indexOf('='));
            assertThat(p.invalidReason()).contains("analytics.storage." + key);
            boolean breakerKey = key.equals("stop-bytes") || key.startsWith("breaker-");
            assertThat(p.breakerEnabled()).as("复审 R3：无关键写错不连带关掉磁盘保护").isEqualTo(!breakerKey);
            assertThat(p.stopBytes().toGigabytes()).isEqualTo(50);
            assertThat(p.alertBytes().toGigabytes()).isEqualTo(40);
            assertThat(p.watermarkCron()).isEqualTo("0 30 4 * * *");
            List<String> r = schedule(ctx);
            assertThat(has(r, "storage-watermark")).isTrue();
            assertThat(has(r, "ingest-breaker")).isTrue();
            assertThat(has(r, "rollup")).isTrue();
        });
    }

    @ParameterizedTest(name = "analytics.ingest.{0}")
    @ValueSource(strings = {"max-request-bytes=500KB", "queue-max-bytes=-1", "flush-batch-size=flase",
            "flush-interval-ms=0", "shutdown-drain-timeout=forever", "watchdog-interval=-5s", "stall-threshold=0",
            "rate-limit.per-ip=many"})
    @DisplayName("AC11：接收配置非法 → 启动正常、该键回退默认、看门狗照常注册")
    void ingestInvalidFallsBackToDefaults(String kv) {
        runner().withPropertyValues("analytics.ingest." + kv).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            IngestProperties p = ctx.getBean(IngestProperties.class);
            assertThat(p.invalidReason()).contains("analytics.ingest." + kv.substring(0, kv.indexOf('=')));
            assertThat(p.maxRequestBytes()).isEqualTo(512_000);
            assertThat(p.queueMaxBytes()).isEqualTo(67_108_864L);
            assertThat(p.flushBatchSize()).isEqualTo(500);
            assertThat(p.flushIntervalMs()).isEqualTo(1000);
            assertThat(p.shutdownDrainTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(p.watchdogInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.stallThreshold()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.rateLimit().perIp()).isEqualTo(3000);
            assertThat(schedule(ctx)).contains("ingest-flusher-watchdog=fixedDelay(PT1M, initial PT1M)");
        });
    }

    @Test
    @DisplayName("AC11：合法的非默认值照常生效（字符串绑定不改变语义）")
    void validOverridesStillApply() {
        runner().withPropertyValues("analytics.ingest.flush-batch-size=200", "analytics.ingest.watchdog-interval=30s",
                "analytics.ingest.stall-threshold=120", "analytics.ingest.rate-limit.per-device=60",
                "analytics.storage.stop-bytes=1", "analytics.storage.breaker-initial-delay-ms=86400000",
                "analytics.rollup.retention-days=30", "analytics.rollup.cron=-",
                "analytics.ga4-snapshot.enabled=FALSE").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    IngestProperties i = ctx.getBean(IngestProperties.class);
                    assertThat(i.invalidReason()).isNull();
                    assertThat(i.flushBatchSize()).isEqualTo(200);
                    assertThat(i.watchdogInterval()).isEqualTo(Duration.ofSeconds(30));
                    // 复审 R4：这两个新键（不带 -ms）纯数字按秒
                    assertThat(i.stallThreshold()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(i.rateLimit().perDevice()).isEqualTo(60);
                    AnalyticsStorageProperties s = ctx.getBean(AnalyticsStorageProperties.class);
                    assertThat(s.valid()).isTrue();
                    assertThat(s.stopBytes().toBytes()).isEqualTo(1);
                    AnalyticsRollupProperties ro = ctx.getBean(AnalyticsRollupProperties.class);
                    assertThat(ro.enabled()).isTrue();
                    assertThat(ro.retentionDays()).isEqualTo(30);
                    assertThat(ctx.getBean(Ga4SnapshotProperties.class).enabled()).isFalse();
                    assertThat(ctx.getBean(Ga4SnapshotProperties.class).invalidReason()).isNull();
                    List<String> r = schedule(ctx);
                    assertThat(r).contains("rollup=disabled", "ingest-breaker=fixedDelay(PT5M, initial PT24H)",
                            "ingest-flusher-watchdog=fixedDelay(PT30S, initial PT30S)");
                });
    }

    @Test
    @DisplayName("AC11：GA4 快照 cron 非法 → 启动正常、快照停用且不注册")
    void ga4CronInvalid() {
        runner().withPropertyValues("analytics.ga4-snapshot.cron=at 2:30").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Ga4SnapshotProperties p = ctx.getBean(Ga4SnapshotProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.invalidReason()).contains("analytics.ga4-snapshot.cron");
            List<String> r = schedule(ctx);
            assertThat(has(r, "ga4-snapshot")).isFalse();
            assertThat(has(r, "rollup")).isTrue();
        });
    }

    @Test
    @DisplayName("AC11：分区 / 未登记 / 擦除 cron、明细 / 重算时长非法 → 启动正常、回退默认调度照常注册、登记问题")
    void otherSchedulesFallBackToDefaults() {
        runner().withPropertyValues("analytics.partition.cron=0 5 0 * *", "analytics.unknown-event-alert.cron=x",
                "analytics.erasure.cron=nightly", "analytics.detail.fixed-delay-ms=fast",
                "analytics.detail.initial-delay-ms=-1", "analytics.recompute.fixed-delay-ms=0",
                "analytics.recompute.initial-delay-ms=1m").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    List<String> r = schedule(ctx);
                    assertThat(r).contains("partition-maintenance=cron(0 5 0 * * * Z)",
                            "unknown-event-alert=cron(0 30 1 * * * Z)", "erasure=cron(0 0 2 * * * Asia/Jakarta)",
                            "detail-builder=fixedDelay(PT1M, initial PT1M)",
                            "detail-recompute=fixedDelay(PT1M, initial PT1M)");
                    assertThat(ctx.getBean(AnalyticsConfigProblems.class).all()).hasSize(6)
                            .allSatisfy(p -> assertThat(p).startsWith("analytics."));
                });
    }

    @Test
    @DisplayName("\"-\" 仍表示不注册（测试基类靠它关闭定时任务），不算非法")
    void dashDisables() {
        runner().withPropertyValues("analytics.partition.cron=-", "analytics.erasure.cron=-",
                "analytics.storage.watermark-cron=-", "analytics.ga4-snapshot.cron=-").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    List<String> r = schedule(ctx);
                    assertThat(r).contains("partition-maintenance=disabled", "erasure=disabled",
                            "storage-watermark=disabled", "ga4-snapshot=disabled");
                    assertThat(ctx.getBean(AnalyticsConfigAlert.class).problems()).isEmpty();
                    assertThat(ctx.getBean(AnalyticsStorageProperties.class).valid()).isTrue();
                });
    }

    // ---------------------------------------------------------------- 数据源

    @Test
    @DisplayName("AC11：analytics.datasource.hikari.* 非法 → 不抛，按安全默认建池并登记；合法值照常绑定")
    void hikariBinding() {
        try (HikariDataSource bad = new HikariDataSource()) {
            String reason = AnalyticsDataSourceConfig.bindHikari(new Binder(new MapConfigurationPropertySource(
                    Map.of("analytics.datasource.hikari.maximum-pool-size", "3x",
                            "analytics.datasource.hikari.pool-name", "evil"))), bad);
            assertThat(reason).contains("analytics.datasource.hikari").doesNotContain("3x");
            assertThat(bad.getMaximumPoolSize()).isEqualTo(3);
            assertThat(bad.getPoolName()).isEqualTo("analytics");
            assertThat(bad.getConnectionTimeout()).isEqualTo(5000);
            assertThat(bad.getInitializationFailTimeout()).isEqualTo(-1);
        }
        try (HikariDataSource zero = new HikariDataSource()) {
            assertThat(AnalyticsDataSourceConfig.bindHikari(new Binder(new MapConfigurationPropertySource(
                    Map.of("analytics.datasource.hikari.maximum-pool-size", "0"))), zero)).isNotNull();
            assertThat(zero.getMaximumPoolSize()).isEqualTo(3);
        }
        try (HikariDataSource ok = new HikariDataSource()) {
            assertThat(AnalyticsDataSourceConfig.bindHikari(new Binder(new MapConfigurationPropertySource(
                    Map.of("analytics.datasource.hikari.maximum-pool-size", "2",
                            "analytics.datasource.hikari.connection-timeout", "4000"))), ok)).isNull();
            assertThat(ok.getMaximumPoolSize()).isEqualTo(2);
            assertThat(ok.getConnectionTimeout()).isEqualTo(4000);
            assertThat(ok.getInitializationFailTimeout()).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("AC4：请求线程查询超时 —— 默认 2s；纯数字按秒；不足 1 秒按 1 秒；非法回退 2s 并登记")
    void requestQueryTimeout() {
        AnalyticsConfigProblems problems = new AnalyticsConfigProblems();
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("2s", problems)).isEqualTo(2);
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("5", problems)).isEqualTo(5);
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("500ms", problems)).isEqualTo(1);
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("PT3S", problems)).isEqualTo(3);
        assertThat(problems.all()).isEmpty();
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("2 seconds", problems)).isEqualTo(2);
        assertThat(AnalyticsDataSourceConfig.requestQueryTimeoutSeconds("-1s", problems)).isEqualTo(2);
        assertThat(problems.all()).hasSize(1)
                .allSatisfy(p -> assertThat(p).contains("analytics.datasource.request-query-timeout"));
    }
}
