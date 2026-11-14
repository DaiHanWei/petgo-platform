package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

/** L0 · V1.5.0 likes Story 2.2 AC2：开关关不调 planner；planner 抛异常不外抛；cron 带 Asia/Jakarta。 */
class AutoLikeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:30:00Z");

    private final AutoLikePlanner planner = mock(AutoLikePlanner.class);
    private final AutoLikeProperties props = new AutoLikeProperties();
    private final AutoLikeJob job = new AutoLikeJob(planner, props, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void disabledDoesNotPlan() {
        props.setEnabled(false);
        job.morning();
        job.evening();
        verify(planner, never()).plan(any(), any());
    }

    @Test
    void enabledPlansWithClockInstant() {
        job.morning();
        verify(planner).plan(AutoLikeSlot.MORNING, NOW);
        job.evening();
        verify(planner).plan(AutoLikeSlot.EVENING, NOW);
    }

    @Test
    void plannerFailureIsSwallowed() {
        when(planner.plan(any(), any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(job::morning).doesNotThrowAnyException();
    }

    @Test
    void cronsAreInJakarta() throws NoSuchMethodException {
        for (String m : new String[] {"morning", "evening"}) {
            Method method = AutoLikeJob.class.getMethod(m);
            Scheduled s = method.getAnnotation(Scheduled.class);
            assertThat(s.zone()).isEqualTo("Asia/Jakarta");
            assertThat(s.cron()).startsWith("${petgo.auto-like.");
        }
    }
}
