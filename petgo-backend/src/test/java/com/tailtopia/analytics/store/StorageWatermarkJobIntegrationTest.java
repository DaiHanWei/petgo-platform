package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import com.tailtopia.analytics.store.StorageWatermarkJob.Outcome;
import com.tailtopia.analytics.store.StorageWatermarkJob.RunResult;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * L1：存储水位每日快照（analytics-pipeline Story 1.10 AC1 / AC2），跑在埋点测试库 {@code petgo_analytics_test}。
 *
 * <ul>
 *   <li>日期取 2098 年，远离真实日期与其他测试；结束按日期删本类写的快照行。</li>
 *   <li>告警：手工 new 带 mock {@link OpsAlertService} 的任务（同 1.3 / 1.4 的做法，不另起上下文）。</li>
 * </ul>
 */
class StorageWatermarkJobIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2098, 3, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2098-03-14T21:30:00Z"), ZoneOffset.UTC);

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;

    private OpsAlertService alerts;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        alerts = mock(OpsAlertService.class);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM storage_watermarks WHERE snapshot_date >= ? AND snapshot_date < ?",
                Date.valueOf(LocalDate.of(2098, 1, 1)), Date.valueOf(LocalDate.of(2099, 1, 1)));
    }

    private StorageWatermarkJob job(AnalyticsStorageProperties props) {
        return new StorageWatermarkJob(state, analyticsJdbc, analyticsTx, alerts, props, "test", CLOCK);
    }

    @Test
    void recordsSnapshotAndRerunOverwrites() {
        StorageWatermarkJob job = job(AnalyticsStorageProperties.defaults());
        analyticsJdbc.update("INSERT INTO storage_watermarks (snapshot_date, db_bytes, raw_bytes, detail_bytes, "
                + "events_on_day, month_events) VALUES (?, 1, 0, 0, 7, 7)", Date.valueOf(DAY.minusDays(5)));

        RunResult first = job.run(DAY, DAY.minusDays(1));
        RunResult second = job.run(DAY, DAY.minusDays(1));

        assertThat(first.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(second.outcome()).isEqualTo(Outcome.RECORDED);
        Map<String, Object> row = analyticsJdbc.queryForMap("SELECT * FROM storage_watermarks WHERE snapshot_date = ?",
                Date.valueOf(DAY));
        assertThat((Long) row.get("db_bytes")).isPositive();
        assertThat((Long) row.get("raw_bytes")).isNotNegative();
        assertThat((Long) row.get("detail_bytes")).isNotNegative();
        // 2098-03-14 的原始层分区不存在 → 0；本月此前（03-10）累计 7
        assertThat(row.get("events_on_day")).isEqualTo(0L);
        assertThat(row.get("month_events")).isEqualTo(7L);
        // 只有 5 天前一条 → 按天数折算为 30 天
        assertThat(row.get("month_growth_bytes")).isNotNull();
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM storage_watermarks WHERE snapshot_date = ?",
                Long.class, Date.valueOf(DAY))).isEqualTo(1L);
        verify(alerts, never()).send(eq(OpsChannel.TECH), argThat(k -> k.startsWith("analytics:storage-over:")),
                anyString());
    }

    @Test
    void firstSnapshotHasNullGrowth() {
        RunResult r = job(AnalyticsStorageProperties.defaults()).run(DAY, DAY.minusDays(1));

        assertThat(r.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(r.watermark().monthGrowthBytes()).isNull();
        assertThat(analyticsJdbc.queryForObject(
                "SELECT month_growth_bytes FROM storage_watermarks WHERE snapshot_date = ?", Long.class,
                Date.valueOf(DAY))).isNull();
    }

    @Test
    void alertBytesOneByteTriggersOverAlertWithSwitchBackHint() {
        AnalyticsStorageProperties d = AnalyticsStorageProperties.defaults();
        AnalyticsStorageProperties tiny = new AnalyticsStorageProperties(DataSize.ofBytes(1), d.stopBytes(),
                d.monthEventsHint(), d.hintMonths(), d.breakerCheckMs(), d.breakerRetryAfterSeconds());

        RunResult r = job(tiny).run(DAY, DAY.minusDays(1));

        assertThat(r.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(r.evaluation().overAlert()).isTrue();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:storage-over:" + DAY),
                argThat(t -> t.contains("评估切回第三方")));
    }
}
