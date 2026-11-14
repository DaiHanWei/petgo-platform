package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.autolike.AutoLikeTargetRepository.CheckCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** L0 · V1.5.0 likes Story 2.1 AC5 / AC6：首轮判定先于关闭、三种入表决策、边界时间。 */
class AutoLikeCheckStepTest {

    private static final Instant RUN = Instant.parse("2026-11-20T03:30:00Z"); // WIB 10:30

    private final AutoLikeTargetRepository targets = mock(AutoLikeTargetRepository.class);
    private final AutoLikeProperties props = new AutoLikeProperties();
    private final AutoLikeHoldout holdout = mock(AutoLikeHoldout.class);
    private final AutoLikeCheckStep step = new AutoLikeCheckStep(targets, holdout, props);

    private static CheckCandidate cand(long postId, long authorId, int likes) {
        return new CheckCandidate(postId, authorId, RUN.minus(Duration.ofHours(40)), likes);
    }

    private void insertsDelegate(int result) {
        when(targets.insertCheck(any(), any(), any())).thenCallRealMethod();
        when(targets.insertIfAbsent(anyLong(), anyLong(), any(), any(), anyInt(), anyString())).thenReturn(result);
    }

    @Test
    void launchIsDecidedBeforeClosingAndChecking() {
        when(targets.existsAny()).thenReturn(false);
        when(targets.findCheckCandidates(any(), any())).thenReturn(List.of());

        AutoLikeCheckStep.CheckResult r = step.run(RUN);

        assertThat(r.launch()).isTrue();
        InOrder o = inOrder(targets);
        o.verify(targets).existsAny();
        o.verify(targets).closeMaxAge(RUN, RUN.minus(Duration.ofDays(7)));
        o.verify(targets).closeTooManyFailures(RUN, 3);
        o.verify(targets).findCheckCandidates(RUN.minus(Duration.ofHours(36)), RUN.minus(Duration.ofDays(7)));
    }

    @Test
    void normalRunUsesSixtyHourUpperBound() {
        when(targets.existsAny()).thenReturn(true);
        when(targets.findCheckCandidates(any(), any())).thenReturn(List.of());

        AutoLikeCheckStep.CheckResult r = step.run(RUN);

        assertThat(r.launch()).isFalse();
        verify(targets).findCheckCandidates(RUN.minus(Duration.ofHours(36)), RUN.minus(Duration.ofHours(60)));
    }

    @Test
    void threeDecisionsAndCounts() {
        when(targets.existsAny()).thenReturn(true);
        when(targets.closeMaxAge(any(), any())).thenReturn(2);
        when(targets.closeTooManyFailures(any(), anyInt())).thenReturn(1);
        when(holdout.isHoldout(anyLong())).thenReturn(false);
        when(holdout.isHoldout(77L)).thenReturn(true);
        when(targets.findCheckCandidates(any(), any())).thenReturn(List.of(
                cand(1L, 77L, 0),   // 对照组作者：不论赞数 → HOLDOUT
                cand(2L, 8L, 5),    // 已达标 → AT_TARGET_ON_CHECK
                cand(3L, 9L, 4)));  // 进集合
        insertsDelegate(1);

        AutoLikeCheckStep.CheckResult r = step.run(RUN);

        verify(targets).insertIfAbsent(eq(1L), eq(77L), any(), eq(RUN), eq(0), eq("HOLDOUT"));
        verify(targets).insertIfAbsent(eq(2L), eq(8L), any(), eq(RUN), eq(5), eq("AT_TARGET_ON_CHECK"));
        verify(targets).insertIfAbsent(eq(3L), eq(9L), any(), eq(RUN), eq(4), eq(""));
        assertThat(r).isEqualTo(new AutoLikeCheckStep.CheckResult(false, 3, 1, 2, 1));
    }

    @Test
    void conflictRowsAreNotCounted() {
        when(targets.existsAny()).thenReturn(true);
        when(targets.findCheckCandidates(any(), any())).thenReturn(List.of(cand(3L, 9L, 0)));
        insertsDelegate(0);

        AutoLikeCheckStep.CheckResult r = step.run(RUN);

        assertThat(r.checked()).isZero();
        assertThat(r.joined()).isZero();
    }

    @Test
    void holdoutWinsOverLikeCount() {
        when(holdout.isHoldout(5L)).thenReturn(true);
        assertThat(step.decide(5L, 10L)).isEqualTo(AutoLikeLeaveReason.HOLDOUT);
        assertThat(step.decide(5L, 0L)).isEqualTo(AutoLikeLeaveReason.HOLDOUT);
        assertThat(step.decide(6L, 4L)).isNull();
        assertThat(step.decide(6L, 5L)).isEqualTo(AutoLikeLeaveReason.AT_TARGET_ON_CHECK);
    }

    /** A-AC5：恰好 36h 检查（<= minCut），35h 不查；恰好 60h 不查（> maxCut 开区间）。 */
    @Test
    void boundaryCuts() {
        Instant minCut = step.minCut(RUN);
        Instant maxCut = step.maxCut(RUN, false);
        Instant at36 = RUN.minus(Duration.ofHours(36));
        Instant at35 = RUN.minus(Duration.ofHours(35));
        Instant at60 = RUN.minus(Duration.ofHours(60));
        assertThat(!at36.isAfter(minCut) && at36.isAfter(maxCut)).isTrue();
        assertThat(!at35.isAfter(minCut)).isFalse();
        assertThat(at60.isAfter(maxCut)).isFalse();
        assertThat(step.maxCut(RUN, true)).isEqualTo(RUN.minus(Duration.ofDays(7)));
    }
}
