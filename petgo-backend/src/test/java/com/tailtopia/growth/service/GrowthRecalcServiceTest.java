package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthRecalcJob;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthRecalcJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** V1.4.0 ① Story 4.1 · AC7（L0）：改等级表后的重算——逐个锁行经 handler、状态机、失败只记类名、僵死判定。 */
class GrowthRecalcServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthAccountRepository accounts = mock(GrowthAccountRepository.class);
    private final GrowthRecalcJobRepository jobs = mock(GrowthRecalcJobRepository.class);
    private final GrowthLevelChangeHandler handler = mock(GrowthLevelChangeHandler.class);

    /** 直接执行回调的事务模板（L0 无事务管理器）。 */
    private final TransactionTemplate tx = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    private final GrowthRecalcService service = new GrowthRecalcService(jdbc, accounts, jobs, handler, tx,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private GrowthAccount account(long id, int level, int newLevel) {
        GrowthAccount a = GrowthAccount.of(id, 0, level);
        when(accounts.lockById(id)).thenReturn(Optional.of(a));
        doAnswer(inv -> {
            a.changeLevel(newLevel, NOW);
            return null;
        }).when(handler).onLevelTableChanged(a);
        return a;
    }

    @Test
    void doneLocksEachAffectedAccountAndGoesThroughTheHandler() {
        when(jobs.markRunning(9L, NOW)).thenReturn(1);
        when(jdbc.queryForList(eq(GrowthRecalcService.AFFECTED_SQL), anyMap(), eq(Long.class)))
                .thenReturn(List.of(1L, 2L, 3L));
        GrowthAccount a1 = account(1L, 9, 10);
        GrowthAccount a2 = account(2L, 9, 10);
        GrowthAccount a3 = account(3L, 12, 11);

        service.run(9L);

        verify(handler).onLevelTableChanged(a1);
        verify(handler).onLevelTableChanged(a2);
        verify(handler).onLevelTableChanged(a3);
        // 只走等级表重算入口，不冒充入账来源（尤其不是 LAUNCH_BACKFILL）。
        verify(handler, never()).onLifetimeChanged(any(), any(RuleCode.class));
        verify(jobs).markDone(9L, 2, 1, NOW);
        // 本类不直接改 level（只读 SQL，无 UPDATE）。
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void notPendingAnymoreDoesNothing() {
        when(jobs.markRunning(9L, NOW)).thenReturn(0);
        service.run(9L);
        verify(jdbc, never()).queryForList(anyString(), anyMap(), eq(Long.class));
        verify(jobs, never()).markDone(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void failureMarksFailedWithClassNameOnly() {
        when(jobs.markRunning(9L, NOW)).thenReturn(1);
        when(jdbc.queryForList(eq(GrowthRecalcService.AFFECTED_SQL), anyMap(), eq(Long.class)))
                .thenThrow(new IllegalStateException("user 42 nickname Budi broke it"));

        service.run(9L);

        verify(jobs).markFailed(9L, "IllegalStateException", NOW);
        verify(jobs, never()).markDone(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    void affectedSqlOnlyLooksAtRealNotDeletedUsersAgainstTheTable() {
        assertThat(GrowthRecalcService.AFFECTED_SQL).contains("u.account_type = 'REAL'")
                .contains("u.deleted_at IS NULL").contains("FROM growth_levels l WHERE l.threshold <= a.lifetime_total")
                .doesNotContain("UPDATE");
    }

    @Test
    void staleRunningAfterThirtyMinutesIsRetryable() {
        GrowthRecalcJob running = GrowthRecalcJob.pending(1L, NOW.minus(Duration.ofHours(1)));
        ReflectionTestUtils.setField(running, "status", GrowthRecalcJob.Status.RUNNING);
        ReflectionTestUtils.setField(running, "startedAt", NOW.minus(Duration.ofMinutes(29)));
        assertThat(running.isRetryable(NOW)).isFalse();
        ReflectionTestUtils.setField(running, "startedAt", NOW.minus(Duration.ofMinutes(31)));
        assertThat(running.isRetryable(NOW)).isTrue();

        GrowthRecalcJob failed = GrowthRecalcJob.pending(1L, NOW);
        ReflectionTestUtils.setField(failed, "status", GrowthRecalcJob.Status.FAILED);
        assertThat(failed.isRetryable(NOW)).isTrue();
        // 提交后异步丢失的 PENDING：30 分钟后也可重新执行。
        assertThat(GrowthRecalcJob.pending(1L, NOW.minus(Duration.ofMinutes(10))).isRetryable(NOW)).isFalse();
        assertThat(GrowthRecalcJob.pending(1L, NOW.minus(Duration.ofMinutes(31))).isRetryable(NOW)).isTrue();
        GrowthRecalcJob done = GrowthRecalcJob.pending(1L, NOW);
        ReflectionTestUtils.setField(done, "status", GrowthRecalcJob.Status.DONE);
        assertThat(done.isRetryable(NOW)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewBindsTheCandidateTableAsParameters() {
        when(jdbc.queryForMap(anyString(), any(MapSqlParameterSource.class))).thenReturn(Map.of("up", 3L, "down", 1L));

        GrowthRecalcService.Preview p = service.preview(List.of(new GrowthRecalcService.LevelRow(1, 0),
                new GrowthRecalcService.LevelRow(2, 80)));

        assertThat(p).isEqualTo(new GrowthRecalcService.Preview(3, 1));
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<MapSqlParameterSource> params = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(1)).queryForMap(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("VALUES (CAST(:l0 AS INT), CAST(:t0 AS BIGINT))").doesNotContain("80");
        assertThat(params.getValue().getValue("t1")).isEqualTo(80L);
    }
}
