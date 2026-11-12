package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import com.tailtopia.analytics.store.StorageWatermarkJob.Outcome;
import com.tailtopia.analytics.store.StorageWatermarkJob.Watermark;
import com.tailtopia.analytics.store.StorageWatermarkPolicy.Evaluation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：analytics-pipeline Story 1.10 —— 水位任务的日期口径、停用跳过、失败只告警不抛、文案、迁移文本。 */
class StorageWatermarkJobTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T21:30:00Z"), ZoneOffset.UTC);

    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private StorageWatermarkJob job(AnalyticsPipelineState state) {
        return new StorageWatermarkJob(state, mock(JdbcTemplate.class), tx, alerts,
                AnalyticsStorageProperties.defaults(), "test", CLOCK);
    }

    @Test
    @DisplayName("WIB 04:30 运行：快照日 = WIB 当天；计数日 = 前一个完整 UTC 日")
    void dates() {
        Instant now = Instant.parse("2026-11-12T21:30:00Z"); // = WIB 2026-11-13 04:30
        assertThat(StorageWatermarkJob.snapshotDateOf(now)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(StorageWatermarkJob.eventsDayOf(now)).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    @DisplayName("管道因库不可用停用 → 跳过，不碰库、不告警")
    void skippedWhenDbUnavailable() {
        AnalyticsPipelineState down = new AnalyticsPipelineState();
        down.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(job(down).run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11)).outcome())
                .isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(tx, alerts);
    }

    @Test
    @DisplayName("采集失败 → FAILED，按天去重告警一次，不抛")
    void failureAlertsAndDoesNotThrow() {
        when(tx.execute(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(job(new AnalyticsPipelineState()).run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11))
                .outcome()).isEqualTo(Outcome.FAILED);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:storage-watermark-failed:2026-11-13"), anyString());
    }

    @Test
    @DisplayName("拿不到锁（事务返回 null）→ SKIPPED_LOCKED，不告警")
    void lockedSkips() {
        when(tx.execute(any())).thenReturn(null);

        assertThat(job(new AnalyticsPipelineState()).run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11))
                .outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("超线：只发 storage-over（文案含「评估切回第三方」、占用、月事件量、外推月数），不另发切回提示")
    void overAlertOnly() {
        Watermark w = new Watermark(LocalDate.of(2026, 11, 13), 41 * GB, 30 * GB, 10 * GB, 2_000_000, 20_000_000,
                2 * GB);
        when(tx.execute(any())).thenReturn(w);

        StorageWatermarkJob.RunResult r = job(new AnalyticsPipelineState())
                .run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11));

        assertThat(r.evaluation().overAlert()).isTrue();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:storage-over:2026-11-13"),
                argThat(t -> t.contains("评估切回第三方") && t.contains("41.00 GB")
                        && t.contains("20000000") && t.contains("0.0 个月")));
        verify(alerts, never()).send(eq(OpsChannel.TECH),
                eq("analytics:storage-switch-back:2026-11-13"), anyString());
    }

    @Test
    @DisplayName("未超线但月事件量达标：只发 storage-switch-back 提示")
    void switchBackHintOnly() {
        Watermark w = new Watermark(LocalDate.of(2026, 11, 13), 10 * GB, 8 * GB, 2 * GB, 2_000_000, 60_000_000, null);
        when(tx.execute(any())).thenReturn(w);

        job(new AnalyticsPipelineState()).run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11));

        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:storage-switch-back:2026-11-13"),
                argThat(t -> t.contains("评估切回第三方") && t.contains("未知")));
        verify(alerts, never()).send(eq(OpsChannel.TECH),
                eq("analytics:storage-over:2026-11-13"), anyString());
    }

    @Test
    @DisplayName("正常水位：不告警")
    void normalNoAlert() {
        Evaluation ev = StorageWatermarkPolicy.evaluate(5 * GB, 1_000_000, GB / 10,
                new StorageWatermarkPolicy.Thresholds(40 * GB, 60_000_000, 3));
        assertThat(ev.switchBackHint()).isFalse();
        when(tx.execute(any())).thenReturn(new Watermark(LocalDate.of(2026, 11, 13), 5 * GB, 4 * GB, GB, 100,
                1_000_000, GB / 10));

        job(new AnalyticsPipelineState()).run(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 11));

        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("迁移：建 storage_watermarks、不授权只读角色、不用 CURRENT_DATE")
    void migrationText() throws IOException {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/analytics-migration/V20261113_1140__init_storage_watermarks.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String code = sql.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
        assertThat(code).contains("CREATE TABLE storage_watermarks").contains("snapshot_date       date         PRIMARY KEY");
        assertThat(code.toUpperCase()).doesNotContain("GRANT").doesNotContain("CURRENT_DATE");
    }
}
