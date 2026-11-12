package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.DetailResult;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.Outcome;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.RawResult;
import com.tailtopia.analytics.store.PartitionMaintenanceJob.RunResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：分区维护的跳过 / 失败 / 告警分支（analytics-pipeline Story 1.3 AC4；无 Spring、无库，事务模板打桩）。 */
class PartitionMaintenanceJobTest {

    /** UTC 2026-12-31 18:00 = WIB 2027-01-01 01:00：告警日期必须按 UTC。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T18:00:00Z"), ZoneOffset.UTC);

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final PartitionMaintenanceJob job = new PartitionMaintenanceJob(state, jdbc, tx, alerts, "test", CLOCK);

    /** 第二个事务（Story 1.6 明细层预建）的正常返回。 */
    private static final DetailResult DETAIL_OK = new DetailResult(true, List.of());

    @Test
    void skipsWhenDbUnavailable() {
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(job.run().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(tx, jdbc, alerts);
    }

    @Test
    void skipsWhenMigrationFailed() {
        state.disable(Reason.MIGRATION_FAILED, "x");

        assertThat(job.run().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(tx, jdbc, alerts);
    }

    @Test
    void stillRunsWhenOnlyCatalogInvalid() {
        state.disable(Reason.CATALOG_INVALID, "x");
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of("events_raw_p20261231"), List.of(), 0, 0),
                DETAIL_OK);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.raw().created()).containsExactly("events_raw_p20261231");
        verifyNoInteractions(alerts);
    }

    @Test
    void lockHeldElsewhereSkipsQuietly() {
        when(tx.execute(any())).thenReturn(RawResult.notLocked());

        assertThat(job.run().outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        verifyNoInteractions(alerts);
    }

    @Test
    void failureAlertsAndNeverThrowsNorDisablesPipeline() {
        when(tx.execute(any())).thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret-host"));

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(state.isEnabled()).as("失败不改管道状态").isTrue();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-partition-failed:2026-12-31"),
                contains("DataAccessResourceFailureException"));
        verify(alerts, never()).send(any(), anyString(), contains("secret-host"));
    }

    @Test
    void alertFailureIsSwallowed() {
        when(tx.execute(any())).thenThrow(new IllegalStateException("boom"));
        doThrow(new RuntimeException("lark down")).when(alerts).send(any(), anyString(), anyString());

        assertThat(job.run().outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void nonEmptyDefaultAlertsWithRowCountKeyedByUtcDay() {
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 3), DETAIL_OK);

        assertThat(job.run().outcome()).isEqualTo(Outcome.DONE);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-raw-default-nonempty:2026-12-31"), contains("行数：3"));
    }

    @Test
    void emptyDefaultDoesNotAlert() {
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK);

        assertThat(job.run().outcome()).isEqualTo(Outcome.DONE);
        verifyNoInteractions(alerts);
    }

    // ---------------------------------------------------------------- Story 1.6：明细层预建

    @Test
    void detailFailureAlertsKeyedByWibDayAndRawResultKept() {
        RawResult raw = new RawResult(true, List.of(), List.of(), 0, 0);
        when(tx.execute(any())).thenReturn(raw).thenThrow(new IllegalStateException("password=x"));

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.raw()).isSameAs(raw);
        assertThat(r.detail()).isNull();
        assertThat(state.isEnabled()).isTrue();
        // UTC 2026-12-31 18:00 = WIB 2027-01-01 01:00：明细层告警按 WIB 日期
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-detail-partition-failed:2027-01-01"),
                contains("IllegalStateException"));
        verify(alerts, never()).send(any(), anyString(), contains("password"));
    }

    @Test
    void rawFailureStillRunsDetail() {
        when(tx.execute(any())).thenThrow(new IllegalStateException("boom")).thenReturn(
                new DetailResult(true, List.of("events_detail_p20270108")));

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.detail().created()).containsExactly("events_detail_p20270108");
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-partition-failed:2026-12-31"), anyString());
        verify(alerts, never()).send(any(), eq("analytics-detail-partition-failed:2027-01-01"), anyString());
    }

    @Test
    void detailLockHeldElsewhereIsQuiet() {
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0),
                DetailResult.notLocked());

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.detail().lockAcquired()).isFalse();
        verifyNoInteractions(alerts);
    }

    // ---------------------------------------------------------------- Story 1.7：过期删除
    // CLOCK：UTC 2026-12-31 → 原始层 35 天界线 2026-11-26；WIB 2027-01-01 → 明细层 3 个月界线 2026-10-01。

    private void stubPartitions(List<String> detail, List<String> raw) {
        when(jdbc.queryForList(DetailPartitions.BUILT_SQL, String.class)).thenReturn(detail);
        when(jdbc.queryForList(PartitionMaintenanceJob.RAW_BUILT_SQL, String.class)).thenReturn(raw);
    }

    private void stubCursor(Instant cursor) {
        when(jdbc.query(eq(DetailBuilder.PROGRESS_SQL),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Instant>>any(),
                eq(DetailBuilder.PIPELINE))).thenReturn(cursor);
    }

    @Test
    void dropsExpiredDetailAndRawPartitionsWhenCursorPast() {
        stubPartitions(List.of("events_detail_p20260930", "events_detail_p20261001"),
                List.of("events_raw_pdefault", "events_raw_p20261125", "events_raw_p20261126"));
        stubCursor(Instant.parse("2026-12-31T17:00:00Z"));
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK, Boolean.TRUE);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.retention().detailDropped()).containsExactly("events_detail_p20260930");
        assertThat(r.retention().rawDropped()).containsExactly("events_raw_p20261125");
        assertThat(r.retention().rawBlocked()).isEmpty();
        verifyNoInteractions(alerts);
    }

    @Test
    void rawBlockedByCursorIsKeptAndAlertedByWibDay() {
        stubPartitions(List.of(), List.of("events_raw_p20261120", "events_raw_p20261125"));
        // 游标停在 11-21 中午：11-20 可删，11-25 被挡
        stubCursor(Instant.parse("2026-11-21T12:00:00Z"));
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK, Boolean.TRUE);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.retention().rawDropped()).containsExactly("events_raw_p20261120");
        assertThat(r.retention().rawBlocked()).containsExactly(java.time.LocalDate.of(2026, 11, 25));
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics:raw-retention-blocked:2027-01-01"),
                contains("2026-11-25"));
    }

    @Test
    void rawDeletionDeferredWhileRecomputeRunning() {
        stubPartitions(List.of(), List.of("events_raw_p20261120"));
        stubCursor(Instant.parse("2026-12-31T17:00:00Z"));
        when(jdbc.queryForObject(DetailBuilder.RECOMPUTE_RUNNING_SQL, Boolean.class)).thenReturn(true);
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.retention().rawDropped()).isEmpty();
        assertThat(r.retention().rawDeferred()).containsExactly(java.time.LocalDate.of(2026, 11, 20));
        verifyNoInteractions(alerts);
    }

    @Test
    void dropFailureIsIsolatedPerPartitionAndAlerted() {
        stubPartitions(List.of("events_detail_p20260901", "events_detail_p20260902"), List.of());
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK)
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout on secret-host"))
                .thenReturn(Boolean.TRUE);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.retention().failed()).containsExactly("events_detail_p20260901");
        assertThat(r.retention().detailDropped()).containsExactly("events_detail_p20260902");
        assertThat(state.isEnabled()).isTrue();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-retention-failed:2026-12-31"),
                contains("CannotAcquireLockException"));
        verify(alerts, never()).send(any(), anyString(), contains("secret-host"));
    }

    @Test
    void dropLockHeldElsewhereSkipsQuietly() {
        stubPartitions(List.of("events_detail_p20260901"), List.of());
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK,
                Boolean.FALSE);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.retention().lockSkipped()).containsExactly("events_detail_p20260901");
        verifyNoInteractions(alerts);
    }

    @Test
    void retentionListingFailureAlertsAndNeverThrows() {
        when(jdbc.queryForList(DetailPartitions.BUILT_SQL, String.class))
                .thenThrow(new IllegalStateException("password=x"));
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0), DETAIL_OK);

        RunResult r = job.run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.retention()).isNull();
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-retention-failed:2026-12-31"),
                contains("IllegalStateException"));
        verify(alerts, never()).send(any(), anyString(), contains("password"));
    }

    @Test
    void describeHasClassNamesOnly() {
        String d = PartitionMaintenanceJob.describe(
                new DataAccessResourceFailureException("password=hunter2", new IllegalStateException("host")));
        assertThat(d).isEqualTo("DataAccessResourceFailureException <- IllegalStateException");
    }
}
