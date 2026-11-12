package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.Outcome;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.RunResult;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：原始层父表与分区维护（analytics-pipeline Story 1.3 AC1–AC4），跑在埋点测试库 {@code petgo_analytics_test}。
 *
 * <p>前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}（CI 由 backend-ci.yml 建）。
 *
 * <ul>
 *   <li>固定时钟：UTC 2099-01-10 —— 远离真实日期，与启动时那次真实运行建的分区互不相干；分区跨次运行保留，
 *       任务本就幂等。</li>
 *   <li>告警：手工 new 一个带 mock {@link OpsAlertService} 的任务实例（等价于 story 写的 {@code @MockitoBean}，
 *       但不额外起一个 Spring 上下文 / 连接池，见 {@link ApiIntegrationTest} 类注释）。</li>
 *   <li>隔离：埋点测试库共享，本类造的行一律用唯一 {@code message_id} 前缀，结束按前缀删除，不 TRUNCATE。</li>
 * </ul>
 */
class PartitionMaintenanceJobIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2099, 1, 10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2099-01-10T08:00:00Z"), ZoneOffset.UTC);

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
    private String prefix;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        alerts = mock(OpsAlertService.class);
        job = new PartitionMaintenanceJob(state, analyticsJdbc, analyticsTx, alerts, "test", CLOCK);
        prefix = "it-pm-" + SEQ.incrementAndGet() + "-";
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM events_raw WHERE message_id LIKE ?", prefix + "%");
    }

    private boolean exists(String relation) {
        return Boolean.TRUE.equals(analyticsJdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL",
                Boolean.class, relation));
    }

    private List<String> attachedPartitions() {
        return analyticsJdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'events_raw'::regclass ORDER BY c.relname""", String.class);
    }

    private void insertRaw(String messageId, Instant receivedAt) {
        analyticsJdbc.update("""
                INSERT INTO events_raw (message_id, event, client_ts, sent_at, received_at, source)
                VALUES (?, 'it_partition_probe', ?::timestamptz, ?::timestamptz, ?::timestamptz, 'SERVER')""",
                messageId, receivedAt.toString(), receivedAt.toString(), receivedAt.toString());
    }

    private String partitionOf(String messageId) {
        return analyticsJdbc.queryForObject("SELECT tableoid::regclass::text FROM events_raw WHERE message_id = ?",
                String.class, messageId);
    }

    @Test
    void createsEightDailyPartitionsAndParentHasOnlyBrin() {
        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        List<String> attached = attachedPartitions();
        for (LocalDate d : RawPartitionPlanner.daysToEnsure(TODAY)) {
            String name = RawPartitionPlanner.partitionName(d);
            assertThat(exists(name)).as(name).isTrue();
            assertThat(attached).contains(name);
        }
        assertThat(attached).contains("events_raw_pdefault");
        // 边界带 +00：UTC 午夜前后一秒落进相邻两天
        assertThat(analyticsJdbc.queryForObject(
                "SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c WHERE c.relname = 'events_raw_p20990110'",
                String.class)).contains("2099-01-10").contains("2099-01-11");
        insertRaw(prefix + "late", Instant.parse("2099-01-10T23:59:59Z"));
        insertRaw(prefix + "early", Instant.parse("2099-01-11T00:00:00Z"));
        assertThat(partitionOf(prefix + "late")).isEqualTo("events_raw_p20990110");
        assertThat(partitionOf(prefix + "early")).isEqualTo("events_raw_p20990111");

        List<String> indexes = analyticsJdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'events_raw'", String.class);
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0)).contains("ix_events_raw_received_at_brin").containsIgnoringCase("USING brin")
                .contains("received_at");
    }

    @Test
    void repeatedRunsAreIdempotent() {
        job.run();
        int before = attachedPartitions().size();

        RunResult second = job.run();
        RunResult third = job.run();

        assertThat(second.outcome()).isEqualTo(Outcome.DONE);
        assertThat(third.outcome()).isEqualTo(Outcome.DONE);
        assertThat(second.raw().created()).isEmpty();
        assertThat(attachedPartitions()).hasSize(before);
    }

    @Test
    void rowsInDefaultAreMovedIntoNewPartition() {
        job.run();
        String tomorrow = RawPartitionPlanner.partitionName(TODAY.plusDays(1));
        analyticsJdbc.execute("DROP TABLE " + tomorrow);
        String id = prefix + "moved";
        insertRaw(id, Instant.parse("2099-01-11T12:00:00Z"));
        assertThat(partitionOf(id)).as("明天的分区被删后应落进 DEFAULT").isEqualTo("events_raw_pdefault");

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.raw().movedFromDefault()).containsExactly(tomorrow);
        assertThat(partitionOf(id)).isEqualTo(tomorrow);
        assertThat(analyticsJdbc.queryForObject(
                "SELECT count(*) FROM events_raw_pdefault WHERE message_id LIKE ?", Integer.class, prefix + "%"))
                .isZero();
        // DEFAULT 仍以 DEFAULT 身份挂在父表上
        assertThat(analyticsJdbc.queryForObject("""
                SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c JOIN pg_inherits i ON i.inhrelid = c.oid
                WHERE c.relname = 'events_raw_pdefault' AND i.inhparent = 'events_raw'::regclass""", String.class))
                .isEqualTo("DEFAULT");
        if (r.raw().defaultRows() == 0) {
            // 共享库 DEFAULT 里没有别处遗留的行时：搬完不应告警
            verify(alerts, never()).send(eq(OpsChannel.TECH), eq("analytics-raw-default-nonempty:2099-01-10"),
                    anyString());
        }
    }

    @Test
    void leftoverRowInDefaultAlertsOnce() {
        job.run();
        // 30 天后：不在任何待建区间，留在 DEFAULT
        String id = prefix + "far";
        insertRaw(id, Instant.parse("2099-02-09T12:00:00Z"));
        assertThat(partitionOf(id)).isEqualTo("events_raw_pdefault");

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.raw().defaultRows()).isPositive();
        assertThat(partitionOf(id)).isEqualTo("events_raw_pdefault");
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics-raw-default-nonempty:2099-01-10"),
                anyString());
    }

    @Test
    void startupRunCreatedTodaysRealPartition() {
        // ApplicationReadyEvent 那一次用的是真实时钟
        String today = RawPartitionPlanner.partitionName(RawPartitionPlanner.utcDay(Instant.now()));
        assertThat(exists(today)).as(today).isTrue();
    }
}
