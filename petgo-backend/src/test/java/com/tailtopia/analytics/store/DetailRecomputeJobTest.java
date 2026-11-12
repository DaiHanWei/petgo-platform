package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import com.tailtopia.analytics.store.DetailRecomputeJob.Outcome;
import com.tailtopia.analytics.store.DetailRecomputeJob.RawRange;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** L0：重算的范围推导、保留期校验与跳过 / 失败分支（analytics-pipeline Story 1.7 AC3；无 Spring、无库）。 */
class DetailRecomputeJobTest {

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DetailBuilder builder = mock(DetailBuilder.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final DetailRecomputeJob job = new DetailRecomputeJob(state, jdbc, builder, alerts, "test");

    @Test
    void rawRangeIsWibMidnightMinusOneMinuteToNextWibMidnightPlusSevenDaysOneMinute() {
        Instant cursor = Instant.parse("2099-01-01T00:00:00Z");

        RawRange r = DetailRecomputeJob.rawRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), cursor);

        // 2026-11-01 00:00 WIB = 2026-10-31 17:00 UTC
        assertThat(r.lower()).isEqualTo(Instant.parse("2026-10-31T16:59:00Z"));
        // 2026-11-08 00:00 WIB = 2026-11-07 17:00 UTC，+ 7 天 + 1 分钟
        assertThat(r.upper()).isEqualTo(Instant.parse("2026-11-14T17:01:00Z"));
    }

    @Test
    void rawRangeUpperIsCappedAtCursor() {
        Instant cursor = Instant.parse("2026-11-10T03:04:05.123456Z");

        RawRange r = DetailRecomputeJob.rawRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), cursor);

        assertThat(r.upper()).isEqualTo(cursor);
    }

    @Test
    void rawRangeCursorBehindLowerYieldsEmptyRange() {
        Instant cursor = Instant.parse("2026-10-01T00:00:00Z");

        RawRange r = DetailRecomputeJob.rawRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), cursor);

        assertThat(r.upper()).isEqualTo(r.lower());
    }

    @Test
    void withinRawRetentionComparesEarliestPartitionStartWithLowerBound() {
        Instant lower = Instant.parse("2026-10-31T16:59:00Z");

        assertThat(DetailRecomputeJob.withinRawRetention(
                Set.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 20)), lower)).isTrue();
        assertThat(DetailRecomputeJob.withinRawRetention(
                Set.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20)), lower))
                .as("最早分区 11-01 00:00 UTC 晚于下界：10-31 的原始行已清理").isFalse();
        assertThat(DetailRecomputeJob.withinRawRetention(Set.of(), lower)).isFalse();
    }

    @Test
    void errorCodeIsSimpleClassNameOnly() {
        assertThat(DetailRecomputeJob.errorCode(new IllegalStateException("password=hunter2")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void skipsWhenPipelineDisabled() {
        state.disable(Reason.CATALOG_INVALID, "x");

        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verifyNoInteractions(jdbc, builder, alerts);
    }

    @Test
    void idleWhenNoRequest() {
        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.IDLE);
        verifyNoInteractions(alerts);
    }

    @Test
    void pollFailureNeverThrows() {
        when(jdbc.query(eq(DetailRecomputeJob.NEXT_SQL),
                org.mockito.ArgumentMatchers.<RowMapper<DetailRecomputeJob.Request>>any()))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret-host"));

        assertThat(job.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        verify(alerts, never()).send(any(), anyString(), contains("secret-host"));
    }

    @Test
    void missingProgressRowFailsRequestWithClassNameOnly() {
        when(jdbc.query(eq(DetailRecomputeJob.NEXT_SQL),
                org.mockito.ArgumentMatchers.<RowMapper<DetailRecomputeJob.Request>>any()))
                .thenReturn(java.util.List.of(
                        new DetailRecomputeJob.Request(42, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2))));
        when(builder.progressOrNull()).thenReturn(null);

        DetailRecomputeJob.RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.requestId()).isEqualTo(42L);
        verify(jdbc).update(DetailRecomputeJob.FINISH_SQL, "FAILED", "IllegalStateException", 42L);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-detail-recompute-failed:42"),
                contains("IllegalStateException"));
        verify(builder, never()).clearForRecompute(any(), any());
    }

    @Test
    void rawAlreadyCleanedIsRejectedWithoutTouchingDetail() {
        when(jdbc.query(eq(DetailRecomputeJob.NEXT_SQL),
                org.mockito.ArgumentMatchers.<RowMapper<DetailRecomputeJob.Request>>any()))
                .thenReturn(java.util.List.of(
                        new DetailRecomputeJob.Request(7, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))));
        when(builder.progressOrNull()).thenReturn(Instant.parse("2026-11-12T00:00:00Z"));
        when(jdbc.queryForList(PartitionMaintenanceJob.RAW_BUILT_SQL, String.class))
                .thenReturn(java.util.List.of("events_raw_pdefault", "events_raw_p20261008"));

        DetailRecomputeJob.RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        verify(jdbc).update(DetailRecomputeJob.FINISH_SQL, "REJECTED", "RAW_RETENTION_EXCEEDED", 7L);
        verify(jdbc, never()).update(DetailRecomputeJob.CLAIM_SQL, 7L);
        verify(builder, never()).clearForRecompute(any(), any());
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-detail-recompute-rejected:7"), anyString());
    }

    @Test
    void scanUpperUsesCursorReadAfterClearNotBeforeClaim() {
        // 复审：认领前读到游标 C0；认领提交前在途的实时片把游标推进到 C1 并写了区间内的行，随后被清空。
        // 重算必须扫到 C1，否则 (C0, C1] 收到的行两边都不处理 → 丢行。
        Instant c0 = Instant.parse("2026-11-10T00:00:00Z");
        Instant c1 = Instant.parse("2026-11-10T00:05:00Z");
        when(jdbc.query(eq(DetailRecomputeJob.NEXT_SQL),
                org.mockito.ArgumentMatchers.<RowMapper<DetailRecomputeJob.Request>>any()))
                .thenReturn(java.util.List.of(
                        new DetailRecomputeJob.Request(9, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9))));
        when(jdbc.queryForList(PartitionMaintenanceJob.RAW_BUILT_SQL, String.class))
                .thenReturn(java.util.List.of("events_raw_p20261101"));
        when(jdbc.update(DetailRecomputeJob.CLAIM_SQL, 9L)).thenReturn(1);
        when(builder.progressOrNull()).thenReturn(c0, c1);
        // 只有 (c0 - 1µs, c1] 里有一行：sliceUpper 落后 > 1 小时会问 nextReceived
        Instant row = Instant.parse("2026-11-10T00:02:00Z");
        when(builder.nextReceived(any(), any())).thenAnswer(inv -> {
            Instant lo = inv.getArgument(0);
            Instant lim = inv.getArgument(1);
            return row.isAfter(lo) && !row.isAfter(lim) ? row : null;
        });
        when(builder.recomputeSlice(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DetailBuilder.SliceResult(DetailBuilder.SliceStatus.PROCESSED, 1, 1, 0, 0, 0, 0));

        DetailRecomputeJob.RunResult r = job.runOnce();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        // C0 之后、C1 之前收到的行必须被重算覆盖：最后一片的上界是 C1，而不是认领前的 C0
        verify(builder).recomputeSlice(any(), eq(c1), any(), any(), eq(LocalDate.of(2026, 11, 8)),
                eq(LocalDate.of(2026, 11, 9)));
        verify(builder, never()).recomputeSlice(any(), eq(c0), any(), any(), any(), any());
        verify(builder).clearForRecompute(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9));
    }
}
