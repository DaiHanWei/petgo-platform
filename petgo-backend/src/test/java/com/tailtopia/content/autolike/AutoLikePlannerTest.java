package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.autolike.AutoLikeTurnPicker.OpenTarget;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** L0 · V1.5.0 likes Story 2.2 AC3 / AC5：三种跳过、号池为空仍检查、排期随机落点与轮流。 */
class AutoLikePlannerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:30:00Z"); // WIB 10:30

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private final AutoLikeCheckStep checkStep = mock(AutoLikeCheckStep.class);
    private final AutoLikeVirtualPool pool = mock(AutoLikeVirtualPool.class);
    private final AutoLikeProperties props = new AutoLikeProperties();
    private final Deque<Long> jitters = new ArrayDeque<>();
    private final List<Long> jitterBounds = new ArrayList<>();
    private AutoLikePlanner planner;

    @BeforeEach
    void setUp() {
        when(txm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        planner = new AutoLikePlanner(jdbc, txm, checkStep, pool, props, bound -> {
            jitterBounds.add(bound);
            return jitters.isEmpty() ? 0L : jitters.poll();
        });
        when(checkStep.run(NOW)).thenReturn(new AutoLikeCheckStep.CheckResult(false, 4, 3, 0, 0));
    }

    private void lock(boolean got) {
        when(jdbc.queryForObject(eq(AutoLikePlanner.TRY_LOCK_SQL), anyMap(), eq(Boolean.class))).thenReturn(got);
    }

    private void slotExists(boolean exists) {
        when(jdbc.queryForObject(eq(AutoLikePlanner.SLOT_EXISTS_SQL), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(exists);
    }

    private void overlap(boolean overlap) {
        when(jdbc.queryForObject(eq(AutoLikePlanner.OVERLAP_SQL), anyMap(), eq(Boolean.class))).thenReturn(overlap);
    }

    private void runInserted(Long id) {
        when(jdbc.queryForList(eq(AutoLikePlanner.INSERT_RUN_SQL), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(id == null ? List.of() : List.of(id));
    }

    @SuppressWarnings("unchecked")
    private void openTargets(List<OpenTarget> open) {
        when(jdbc.query(eq(AutoLikePlanner.OPEN_TARGETS_SQL), any(RowMapper.class))).thenReturn((List) open);
    }

    private static OpenTarget t(long postId, long authorId, int hoursOld, Instant lastLiked) {
        return new OpenTarget(postId, authorId, NOW.minus(Duration.ofHours(hoursOld)), lastLiked);
    }

    @Test
    void lockNotAcquiredSkipsEverything() {
        lock(false);
        AutoLikePlanner.PlanResult r = planner.plan(AutoLikeSlot.MORNING, NOW);
        assertThat(r.skipped()).isEqualTo(AutoLikePlanner.SkipReason.LOCKED);
        verify(checkStep, never()).run(any());
        verify(jdbc, never()).queryForList(eq(AutoLikePlanner.INSERT_RUN_SQL), any(SqlParameterSource.class),
                eq(Long.class));
    }

    @Test
    void slotAlreadyPlannedSkips() {
        lock(true);
        slotExists(true);
        assertThat(planner.plan(AutoLikeSlot.MORNING, NOW).skipped())
                .isEqualTo(AutoLikePlanner.SkipReason.ALREADY_PLANNED);
        verify(checkStep, never()).run(any());
    }

    @Test
    void overlapSkipsWithoutWritingRun() {
        lock(true);
        slotExists(false);
        overlap(true);
        assertThat(planner.plan(AutoLikeSlot.EVENING, NOW).skipped()).isEqualTo(AutoLikePlanner.SkipReason.OVERLAP);
        verify(jdbc, never()).queryForList(eq(AutoLikePlanner.INSERT_RUN_SQL), any(SqlParameterSource.class),
                eq(Long.class));
        verify(checkStep, never()).run(any());
    }

    @Test
    void conflictOnRunInsertSkips() {
        lock(true);
        slotExists(false);
        overlap(false);
        runInserted(null);
        assertThat(planner.plan(AutoLikeSlot.MORNING, NOW).skipped())
                .isEqualTo(AutoLikePlanner.SkipReason.ALREADY_PLANNED);
        verify(checkStep, never()).run(any());
    }

    @Test
    void emptyPoolStopsRunButStillChecks() {
        lock(true);
        slotExists(false);
        overlap(false);
        runInserted(42L);
        when(pool.load()).thenReturn(List.of());

        AutoLikePlanner.PlanResult r = planner.plan(AutoLikeSlot.MORNING, NOW);

        assertThat(r.skipped()).isNull();
        assertThat(r.stopped()).isTrue();
        assertThat(r.checked()).isEqualTo(4);
        verify(checkStep).run(NOW);
        verify(jdbc).update(eq(AutoLikePlanner.STOP_NO_POOL_SQL), anyMap());
        verify(jdbc, never()).update(eq(AutoLikePlanner.INSERT_LOG_SQL), any(SqlParameterSource.class));
    }

    @Test
    void plansOnePerAuthorWithRandomTimesInsideWindow() {
        lock(true);
        slotExists(false);
        overlap(false);
        runInserted(42L);
        when(pool.load()).thenReturn(List.of(mock(User.class)));
        openTargets(List.of(
                t(1L, 100L, 40, null),
                t(2L, 100L, 50, null),     // 同作者更老的那帖先排
                t(3L, 200L, 45, null),
                t(4L, 300L, 70, NOW.minus(Duration.ofHours(10)))));
        long spread = Duration.ofHours(3).toMillis();
        jitters.addAll(List.of(spread - 1, 0L, 5_000L));
        when(jdbc.update(eq(AutoLikePlanner.INSERT_LOG_SQL), any(SqlParameterSource.class))).thenReturn(1);

        AutoLikePlanner.PlanResult r = planner.plan(AutoLikeSlot.MORNING, NOW);

        assertThat(r.planned()).isEqualTo(3);
        ArgumentCaptor<SqlParameterSource> c = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(3)).update(eq(AutoLikePlanner.INSERT_LOG_SQL), c.capture());
        List<Long> postIds = c.getAllValues().stream().map(p -> (Long) p.getValue("postId")).toList();
        assertThat(postIds).containsExactly(4L, 2L, 3L); // 最老优先
        List<Instant> times = c.getAllValues().stream()
                .map(p -> ((Timestamp) p.getValue("plannedAt")).toInstant()).toList();
        assertThat(times).allSatisfy(at -> assertThat(at).isAfterOrEqualTo(NOW).isBefore(NOW.plusMillis(spread)));
        assertThat(times.get(0)).isEqualTo(NOW.plusMillis(spread - 1));
        assertThat(jitterBounds).containsOnly(spread);
        ArgumentCaptor<MapSqlParameterSource> runId = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(eq(AutoLikePlanner.UPDATE_RUN_CHECKED_SQL), runId.capture());
        assertThat(runId.getValue().getValue("runId")).isEqualTo(42L);
    }

    @Test
    void turnPickerRotatesWithinAuthorAndKeepsOldestFirst() {
        Instant earlier = NOW.minus(Duration.ofHours(20));
        Instant later = NOW.minus(Duration.ofHours(8));
        List<OpenTarget> open = List.of(
                t(1L, 7L, 40, later),
                t(2L, 7L, 60, earlier),
                t(3L, 7L, 50, null),       // 从没被赞过的先
                t(4L, 8L, 45, null));
        assertThat(AutoLikeTurnPicker.pick(open, 1)).extracting(OpenTarget::postId).containsExactly(3L, 4L);
        // 下一轮：3 已被赞（last_liked_at 最新）→ 轮到最久没被赞的 2
        List<OpenTarget> next = List.of(
                t(1L, 7L, 40, later),
                t(2L, 7L, 60, earlier),
                t(3L, 7L, 50, NOW));
        assertThat(AutoLikeTurnPicker.pick(next, 1)).extracting(OpenTarget::postId).containsExactly(2L);
        assertThat(AutoLikeTurnPicker.pick(open, 2)).extracting(OpenTarget::postId).containsExactly(2L, 3L, 4L);
    }
}
