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
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of("events_raw_p20261231"), List.of(), 0, 0));

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
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 3));

        assertThat(job.run().outcome()).isEqualTo(Outcome.DONE);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-raw-default-nonempty:2026-12-31"), contains("行数：3"));
    }

    @Test
    void emptyDefaultDoesNotAlert() {
        when(tx.execute(any())).thenReturn(new RawResult(true, List.of(), List.of(), 0, 0));

        assertThat(job.run().outcome()).isEqualTo(Outcome.DONE);
        verifyNoInteractions(alerts);
    }

    @Test
    void describeHasClassNamesOnly() {
        String d = PartitionMaintenanceJob.describe(
                new DataAccessResourceFailureException("password=hunter2", new IllegalStateException("host")));
        assertThat(d).isEqualTo("DataAccessResourceFailureException <- IllegalStateException");
    }
}
