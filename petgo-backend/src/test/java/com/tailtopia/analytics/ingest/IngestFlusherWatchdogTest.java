package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import com.tailtopia.analytics.ingest.IngestFlusherWatchdog.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：analytics-pipeline Story 4.9 AC2 —— 写库线程看门狗：线程死了重启 + 告警；停滞只告警；管道停用 / 停机跳过。 */
class IngestFlusherWatchdogTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private IngestFlusher flusher;
    private AnalyticsPipelineState pipeline;
    private OpsAlertService alerts;
    private IngestFlusherWatchdog watchdog;

    @BeforeEach
    void setUp() {
        flusher = mock(IngestFlusher.class);
        pipeline = new AnalyticsPipelineState();
        alerts = mock(OpsAlertService.class);
        watchdog = new IngestFlusherWatchdog(flusher, pipeline, alerts, Duration.ofMinutes(5), "test",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("线程活着、5 分钟内有进展 → HEALTHY，不告警")
    void healthy() {
        when(flusher.isWorkerAlive()).thenReturn(true);
        when(flusher.lastProgressAt()).thenReturn(NOW.minusSeconds(299));

        assertThat(watchdog.check()).isEqualTo(Outcome.HEALTHY);
        verify(flusher, never()).restartIfDead();
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("线程不在 → restartIfDead + TECH 告警（restarted:<UTC 日>）")
    void deadThreadRestarted() {
        when(flusher.isWorkerAlive()).thenReturn(false);
        when(flusher.restartIfDead()).thenReturn(true);

        assertThat(watchdog.check()).isEqualTo(Outcome.RESTARTED);
        verify(alerts).send(eq(OpsChannel.TECH), eq(IngestFlusherWatchdog.ALERT_RESTARTED + "2026-11-13"),
                startsWith("【埋点写库线程已重启】"));
    }

    @Test
    @DisplayName("线程在但超过阈值没进展 → TECH 告警（stalled:<UTC 日>），不重启")
    void stalledAlertsWithoutRestart() {
        when(flusher.isWorkerAlive()).thenReturn(true);
        when(flusher.lastProgressAt()).thenReturn(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1));

        assertThat(watchdog.check()).isEqualTo(Outcome.STALLED);
        verify(flusher, never()).restartIfDead();
        verify(alerts).send(eq(OpsChannel.TECH), eq(IngestFlusherWatchdog.ALERT_STALLED + "2026-11-13"),
                startsWith("【埋点写库线程停滞】"));
    }

    @Test
    @DisplayName("管道停用 → 跳过（不重启、不告警）")
    void pipelineDisabledSkips() {
        pipeline.disable(Reason.DB_UNAVAILABLE, "down");
        when(flusher.isWorkerAlive()).thenReturn(false);

        assertThat(watchdog.check()).isEqualTo(Outcome.SKIPPED);
        verify(flusher, never()).restartIfDead();
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("停机过程中 → 跳过（不复活线程）")
    void stoppingSkips() {
        when(flusher.isStopping()).thenReturn(true);
        when(flusher.isWorkerAlive()).thenReturn(false);

        assertThat(watchdog.check()).isEqualTo(Outcome.SKIPPED);
        verify(flusher, never()).restartIfDead();
    }

    @Test
    @DisplayName("并发：restartIfDead 返回 false（刚被别处启动 / 进入停机）→ 跳过、不告警")
    void restartRaceSkips() {
        when(flusher.isWorkerAlive()).thenReturn(false);
        when(flusher.restartIfDead()).thenReturn(false);

        assertThat(watchdog.check()).isEqualTo(Outcome.SKIPPED);
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("告警抛异常也不抛出")
    void alertFailureSwallowed() {
        when(flusher.isWorkerAlive()).thenReturn(false);
        when(flusher.restartIfDead()).thenReturn(true);
        doThrow(new IllegalStateException("x")).when(alerts).send(any(), any(), any());

        assertThat(watchdog.check()).isEqualTo(Outcome.RESTARTED);
    }
}
