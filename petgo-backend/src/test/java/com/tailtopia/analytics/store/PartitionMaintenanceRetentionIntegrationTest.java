package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.Outcome;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.RunResult;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：过期分区删除（analytics-pipeline Story 1.7 AC1 / AC2），跑在埋点测试库 {@code petgo_analytics_test}
 * （前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>真实时钟：用 2001 年的分区当「过期分区」，与真实日期的分区互不相干（真实时钟下共享库里本就过期的分区被一并删掉，
 *       正是生产行为）。</li>
 *   <li>共享的 {@code pipeline_progress} 游标：用例按需设置，结束恢复原值；造的 2001 分区结束一律 DROP。</li>
 *   <li>告警：手工 new 一个带 mock {@link OpsAlertService} 的任务实例（同 {@link PartitionMaintenanceJobIntegrationTest}）。</li>
 * </ul>
 */
class PartitionMaintenanceRetentionIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate OLD = LocalDate.of(2001, 1, 5);

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;

    private OpsAlertService alerts;
    private PartitionMaintenanceJob job;
    private OffsetDateTime savedCursor;
    private String prefix;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        alerts = mock(OpsAlertService.class);
        job = new PartitionMaintenanceJob(state, analyticsJdbc, analyticsTx, alerts, "test", Clock.systemUTC());
        savedCursor = analyticsJdbc.queryForObject(
                "SELECT last_received_at FROM pipeline_progress WHERE pipeline = 'detail'", OffsetDateTime.class);
        prefix = "it-ret-" + SEQ.incrementAndGet() + "-";
        String raw = RawPartitionPlanner.partitionName(OLD);
        analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + raw + " PARTITION OF " + RawPartitionPlanner.PARENT
                + " FOR VALUES FROM (" + RawPartitionPlanner.lowerBound(OLD) + ") TO ("
                + RawPartitionPlanner.upperBound(OLD) + ")");
        analyticsTx.executeWithoutResult(s -> DetailPartitions.ensure(analyticsJdbc, OLD));
        Instant received = OLD.atTime(12, 0).toInstant(ZoneOffset.UTC);
        analyticsJdbc.update("""
                INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, source)
                VALUES (?, 'it_retention_probe', ?, ?, ?, 'SERVER')""", prefix + "old", utc(received), utc(received),
                utc(received));
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'",
                savedCursor);
        analyticsJdbc.execute("DROP TABLE IF EXISTS " + RawPartitionPlanner.partitionName(OLD));
        analyticsJdbc.execute("DROP TABLE IF EXISTS " + DetailPartitions.partitionName(OLD));
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private void setCursor(Instant t) {
        analyticsJdbc.update("UPDATE pipeline_progress SET last_received_at = ? WHERE pipeline = 'detail'", utc(t));
    }

    private boolean exists(String relation) {
        return Boolean.TRUE.equals(analyticsJdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL",
                Boolean.class, relation));
    }

    @Test
    void dropsExpiredRawAndDetailPartitionsWhenCursorPast() {
        // 游标恰好等于次日 00:00 UTC：那天已全部转进明细层
        setCursor(OLD.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        RunResult r = job.run();

        assertThat(r.retention()).isNotNull();
        assertThat(r.retention().rawDropped()).contains(RawPartitionPlanner.partitionName(OLD));
        assertThat(r.retention().detailDropped()).contains(DetailPartitions.partitionName(OLD));
        assertThat(exists(RawPartitionPlanner.partitionName(OLD))).isFalse();
        assertThat(exists(DetailPartitions.partitionName(OLD))).isFalse();
        assertThat(exists(RawPartitionPlanner.DEFAULT_PARTITION)).as("DEFAULT 永不删").isTrue();
        assertThat(r.retention().failed()).isEmpty();
        assertThat(r.outcome()).isIn(Outcome.DONE);
    }

    @Test
    void rawKeptAndAlertedWhenCursorBehind() {
        setCursor(OLD.atTime(18, 0).toInstant(ZoneOffset.UTC));

        RunResult r = job.run();

        assertThat(r.retention()).isNotNull();
        assertThat(r.retention().rawBlocked()).contains(OLD);
        assertThat(exists(RawPartitionPlanner.partitionName(OLD))).as("进度没越过，原始分区保留").isTrue();
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM events_raw WHERE message_id = ?", Integer.class,
                prefix + "old")).isEqualTo(1);
        // 明细层只看年龄，与游标无关
        assertThat(exists(DetailPartitions.partitionName(OLD))).isFalse();
        verify(alerts).send(eq(OpsChannel.TECH), startsWith(PartitionMaintenanceJob.ALERT_RAW_RETENTION_BLOCKED),
                contains(OLD.toString()));
    }

    @Test
    void recentPartitionsSurvive() {
        setCursor(Instant.now().minus(Duration.ofMinutes(5)));

        job.run();

        LocalDate todayUtc = RawPartitionPlanner.utcDay(Instant.now());
        LocalDate todayWib = EventTimeCorrector.wibDate(Instant.now());
        assertThat(exists(RawPartitionPlanner.partitionName(todayUtc))).isTrue();
        assertThat(exists(DetailPartitions.partitionName(todayWib))).isTrue();
        verify(alerts, never()).send(eq(OpsChannel.TECH),
                startsWith(PartitionMaintenanceJob.ALERT_RETENTION_FAILED), anyString());
    }
}
