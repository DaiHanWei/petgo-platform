package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.DetailRecomputeJob.Outcome;
import com.tailtopia.analytics.store.DetailRecomputeJob.RunResult;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：从原始层重算明细（analytics-pipeline Story 1.7 AC3），跑在埋点测试库 {@code petgo_analytics_test}
 * （前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>定时关闭（基类 {@code analytics.recompute.initial-delay-ms=1 天}），用例手工 new 带 mock 告警的
 *       {@link DetailBuilder} / {@link DetailRecomputeJob} 直接调 {@code runOnce()}。</li>
 *   <li>真实时钟，原始行收到时间取最近十几分钟（同 {@link DetailBuilderIntegrationTest}）。用例开始把共享游标回拨到这批行之前，
 *       并补建 UTC 今天往前 5 天的原始层分区（重算的保留期校验要求最早原始分区不晚于区间下界）。</li>
 *   <li>⚠️ 重算会清空并重写区间内<b>所有</b>明细行（共享库里别的用例遗留的也一样，按原始层重写回来）；本类的行用唯一前缀，
 *       结束按前缀删原始层 / 明细层，并删掉本类插的请求行。开始前把别处遗留的 PENDING / RUNNING 请求置 FAILED，
 *       免得挡住实时增量或被本类误消费。</li>
 * </ul>
 */
class DetailRecomputeIntegrationTest extends ApiIntegrationTest {

    private static final String EVENT = "bottom_nav_tab_switched";
    private static final String CTX = "{\"platform\":\"android\",\"appVersion\":\"1.5.0\",\"osVersion\":\"14\","
            + "\"deviceModel\":\"Google Pixel 8\",\"isPhysicalDevice\":true,\"installSource\":\"store\","
            + "\"isProduction\":true,\"installerStore\":\"com.android.vending\"}";
    private static final String SNAPSHOT_SQL = """
            SELECT message_id, event, client_ts, event_time, event_date, is_suspicious, source, anonymous_id, user_id,
                   user_key, platform, app_version, os_version, device_model, is_production, properties::text AS props
            FROM events_detail WHERE message_id LIKE ? ORDER BY message_id, event_date""";

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
    private EventCatalog catalog;

    private OpsAlertService alerts;
    private DetailBuilder builder;
    private DetailRecomputeJob job;
    private String prefix;
    private Instant now;
    private LocalDate todayWib;
    private final List<Long> requestIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        assertThat(catalog.isRegistered(EVENT)).isTrue();
        alerts = mock(OpsAlertService.class);
        builder = new DetailBuilder(state, analyticsJdbc, analyticsTx, businessJdbc, catalog, alerts, "test",
                Clock.systemUTC(), Duration.ofSeconds(50));
        job = new DetailRecomputeJob(state, analyticsJdbc, builder, alerts, "test");
        prefix = "it-rc-" + SEQ.incrementAndGet() + "-";
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        todayWib = EventTimeCorrector.wibDate(now);
        analyticsJdbc.update("UPDATE detail_recompute_requests SET status = 'FAILED', error_code = 'TEST_RESET', "
                + "done_at = now() WHERE status IN ('PENDING', 'RUNNING')");
        LocalDate todayUtc = RawPartitionPlanner.utcDay(now);
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = todayWib.minusDays(4); !d.isAfter(todayWib.plusDays(1)); d = d.plusDays(1)) {
                DetailPartitions.ensure(analyticsJdbc, d);
            }
            for (LocalDate d = todayUtc.minusDays(5); !d.isAfter(todayUtc.plusDays(1)); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + RawPartitionPlanner.partitionName(d)
                        + " PARTITION OF " + RawPartitionPlanner.PARENT + " FOR VALUES FROM ("
                        + RawPartitionPlanner.lowerBound(d) + ") TO (" + RawPartitionPlanner.upperBound(d) + ")");
            }
        });
        setProgress(now.minus(Duration.ofMinutes(20)));
    }

    @AfterEach
    void cleanUp() {
        for (Long id : requestIds) {
            analyticsJdbc.update("DELETE FROM detail_recompute_requests WHERE id = ?", id);
        }
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private void setProgress(Instant t) {
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'", utc(t));
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private void insertRaw(String messageId, Instant clientTs, Instant sentAt, Instant receivedAt, boolean unknown) {
        analyticsJdbc.update("""
                INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, anonymous_id, source,
                                        properties, context, unknown_event)
                VALUES (?, ?, ?, ?, ?, ?, 'APP', ?::jsonb, ?::jsonb, ?)""",
                messageId, EVENT, utc(clientTs), utc(sentAt), utc(receivedAt), "4b0e3c0e-5f6a-4c41-9a39-0d1f6c2b7a10",
                "{\"from_tab\":\"home\",\"to_tab\":\"shop\",\"phone\":\"08123456789\"}", CTX, unknown);
    }

    private long request(LocalDate from, LocalDate to) {
        Long id = analyticsJdbc.queryForObject(
                "INSERT INTO detail_recompute_requests (from_date, to_date) VALUES (?, ?) RETURNING id", Long.class,
                from, to);
        requestIds.add(id);
        return id;
    }

    private Map<String, Object> requestRow(long id) {
        return analyticsJdbc.queryForMap("SELECT status, error_code, started_at, done_at FROM detail_recompute_requests "
                + "WHERE id = ?", id);
    }

    private List<Map<String, Object>> snapshot() {
        return analyticsJdbc.queryForList(SNAPSHOT_SQL, prefix + "%");
    }

    @Test
    void recomputeReproducesFirstPassRowByRowIncludingMidnightRetry() {
        // 普通行
        Instant r0 = now.minus(Duration.ofMinutes(16));
        insertRaw(prefix + "a", r0.minusSeconds(3), r0.minusSeconds(1), r0, false);
        insertRaw(prefix + "b", r0.minusSeconds(10), r0.minusSeconds(2), r0.plusSeconds(1), false);
        // 同片重复：取最早收到的那条
        insertRaw(prefix + "dup", r0.minusSeconds(30), r0, r0.plusSeconds(2), false);
        insertRaw(prefix + "dup", r0.minusSeconds(20), r0.plusSeconds(60), r0.plusSeconds(60), false);
        // 跨 WIB 午夜重试（两次收到相隔 7 分钟 > 5 分钟时间片）：只留第一次那天的一行
        LocalDate d = todayWib.minusDays(2);
        Instant r1 = now.minus(Duration.ofMinutes(14));
        Instant r2 = now.minus(Duration.ofMinutes(7));
        insertRaw(prefix + "midnight", d.atTime(23, 59, 59).atZone(EventTimeCorrector.WIB).toInstant(), r1, r1, false);
        insertRaw(prefix + "midnight", d.plusDays(1).atTime(0, 0, 1).atZone(EventTimeCorrector.WIB).toInstant(), r2,
                r2, false);

        assertThat(builder.runOnce().outcome()).isEqualTo(DetailBuilder.Outcome.DONE);
        List<Map<String, Object>> first = snapshot();
        assertThat(first).hasSize(4);
        Instant cursorBefore = builder.progressOrNull();

        long id = request(d, todayWib);
        RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.requestId()).isEqualTo(id);
        assertThat(r.cleared()).isGreaterThanOrEqualTo(4);
        assertThat(snapshot()).as("重算结果与首次处理逐行一致").isEqualTo(first);
        assertThat(builder.progressOrNull()).as("重算不推进实时游标").isEqualTo(cursorBefore);
        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("started_at")).isNotNull();
        assertThat(row.get("done_at")).isNotNull();
    }

    @Test
    void lateRegisteredEventIsRecomputedIntoDetail() {
        // 收到时未登记（unknown_event = true，原始层只追加、不回写），现在清单里已有该事件
        String id = prefix + "late-registered";
        Instant r0 = now.minus(Duration.ofMinutes(10));
        insertRaw(id, r0.minusSeconds(2), r0.minusSeconds(1), r0, true);

        builder.runOnce();
        assertThat(snapshot()).as("实时增量按原始层标记跳过").isEmpty();

        request(todayWib.minusDays(1), todayWib);
        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.DONE);

        List<Map<String, Object>> rows = snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("props").toString()).contains("from_tab").doesNotContain("phone");
    }

    @Test
    void beyondRawRetentionIsRejectedAndDetailUntouched() {
        String id = prefix + "keep";
        Instant r0 = now.minus(Duration.ofMinutes(10));
        insertRaw(id, r0.minusSeconds(2), r0.minusSeconds(1), r0, false);
        builder.runOnce();
        List<Map<String, Object>> before = snapshot();

        long req = request(LocalDate.of(1990, 1, 1), todayWib);
        RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        Map<String, Object> row = requestRow(req);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("error_code")).isEqualTo(DetailRecomputeJob.ERROR_RAW_RETENTION_EXCEEDED);
        assertThat(snapshot()).isEqualTo(before);
        verify(alerts).send(eq(OpsChannel.TECH), eq(DetailRecomputeJob.ALERT_REJECTED + req), anyString());
    }

    @Test
    void detailBuilderPausesWhileRecomputeRunning() {
        String id = prefix + "paused";
        Instant r0 = now.minus(Duration.ofMinutes(10));
        insertRaw(id, r0.minusSeconds(2), r0.minusSeconds(1), r0, false);
        long req = request(todayWib, todayWib);
        analyticsJdbc.update("UPDATE detail_recompute_requests SET status = 'RUNNING' WHERE id = ?", req);
        Instant cursor = builder.progressOrNull();

        assertThat(builder.runOnce().outcome()).isEqualTo(DetailBuilder.Outcome.SKIPPED_RECOMPUTING);
        assertThat(builder.progressOrNull()).isEqualTo(cursor);
        assertThat(snapshot()).isEmpty();

        // 崩溃遗留的 RUNNING：下一轮从头重跑到 DONE，之后实时增量恢复
        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.DONE);
        assertThat(requestRow(req).get("status")).isEqualTo("DONE");
        assertThat(builder.runOnce().outcome()).isEqualTo(DetailBuilder.Outcome.DONE);
        assertThat(snapshot()).hasSize(1);
    }
}
