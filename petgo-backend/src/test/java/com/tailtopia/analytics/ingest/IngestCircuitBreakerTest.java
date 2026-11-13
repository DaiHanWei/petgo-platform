package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.config.AnalyticsStorageProperties;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker.Outcome;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker.State;
import com.tailtopia.analytics.ingest.IngestCircuitBreaker.Transition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** L0：analytics-pipeline Story 1.10 AC3 / AC4 —— 停写熔断状态切换（假的占用读取 + mock 告警）。 */
class IngestCircuitBreakerTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final long STOP = 50 * GB;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T05:07:42Z"), ZoneOffset.UTC);

    private final AtomicLong dbBytes = new AtomicLong();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private AnalyticsPipelineState pipeline;
    private OpsAlertService alerts;
    private IngestCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        pipeline = new AnalyticsPipelineState();
        alerts = mock(OpsAlertService.class);
        breaker = new IngestCircuitBreaker(pipeline, () -> {
            RuntimeException e = failure.get();
            if (e != null) {
                throw e;
            }
            return dbBytes.get();
        }, alerts, AnalyticsStorageProperties.defaults(), "test", CLOCK);
    }

    @Test
    @DisplayName("纯迁移：CLOSED→OPEN 只在严格大于阈值；OPEN→CLOSED 在小于等于阈值；其他保持")
    void pureTransitions() {
        assertThat(IngestCircuitBreaker.next(State.CLOSED, STOP, STOP)).isEqualTo(new Transition(State.CLOSED, false));
        assertThat(IngestCircuitBreaker.next(State.CLOSED, STOP + 1, STOP)).isEqualTo(new Transition(State.OPEN, true));
        assertThat(IngestCircuitBreaker.next(State.OPEN, STOP + 1, STOP)).isEqualTo(new Transition(State.OPEN, false));
        assertThat(IngestCircuitBreaker.next(State.OPEN, STOP, STOP)).isEqualTo(new Transition(State.CLOSED, true));
        assertThat(IngestCircuitBreaker.next(State.CLOSED, 0, STOP)).isEqualTo(new Transition(State.CLOSED, false));
    }

    @Test
    @DisplayName("CLOSED→OPEN：打开并告警一次（去重键到分钟、文案含占用与阈值）")
    void closedToOpenAlertsOnce() {
        dbBytes.set(STOP + GB);

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.OPENED);

        assertThat(breaker.isOpen()).isTrue();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics:breaker-open:2026-11-13T05:07"),
                argThat(t -> t.contains("51.00 GB") && t.contains("50.00 GB") && t.contains("429")));
    }

    @Test
    @DisplayName("OPEN 保持：仍超线时不再告警")
    void openStaysOpenWithoutAlert() {
        dbBytes.set(STOP + GB);
        breaker.check(STOP);
        dbBytes.set(STOP + 2 * GB);

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.UNCHANGED);
        assertThat(breaker.check(STOP)).isEqualTo(Outcome.UNCHANGED);

        assertThat(breaker.isOpen()).isTrue();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    @Test
    @DisplayName("OPEN→CLOSED：降回阈值及以下自动恢复并告警一次")
    void openToClosedAlertsOnce() {
        dbBytes.set(STOP + GB);
        breaker.check(STOP);
        dbBytes.set(STOP);

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.CLOSED);

        assertThat(breaker.isOpen()).isFalse();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics:breaker-open:2026-11-13T05:07"), anyString());
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics:breaker-closed:2026-11-13T05:07"),
                argThat(t -> t.contains("恢复")));
    }

    @Test
    @DisplayName("CLOSED 且未超线：不切换、不告警")
    void closedStaysClosedWithoutAlert() {
        dbBytes.set(STOP - GB);

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.UNCHANGED);

        assertThat(breaker.isOpen()).isFalse();
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("查询失败：保持原状态（OPEN 不被误关、CLOSED 不被误开），不告警、不抛")
    void queryFailureKeepsState() {
        failure.set(new DataAccessResourceFailureException("down"));
        assertThat(breaker.check(STOP)).isEqualTo(Outcome.FAILED);
        assertThat(breaker.isOpen()).isFalse();

        failure.set(null);
        dbBytes.set(STOP + GB);
        breaker.check(STOP);
        failure.set(new DataAccessResourceFailureException("down"));

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.FAILED);
        assertThat(breaker.isOpen()).isTrue();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    @Test
    @DisplayName("告警发送异常不影响切换、不抛")
    void alertFailureDoesNotThrow() {
        doThrow(new IllegalStateException("lark")).when(alerts).send(eq(OpsChannel.TECH), anyString(), anyString());
        dbBytes.set(STOP + 1);

        assertThat(breaker.check(STOP)).isEqualTo(Outcome.OPENED);
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @DisplayName("Story 4.9 AC10：analytics.storage.* 非法 → 只停用按水位停收：超阈值也不打开、不告警、不查库")
    void invalidStorageConfigNeverOpens() {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        AnalyticsStorageProperties invalid = new AnalyticsStorageProperties(null, null, 60_000_000L, 3, 300_000L, 300);
        assertThat(invalid.valid()).isFalse();
        IngestCircuitBreaker b = new IngestCircuitBreaker(pipeline, () -> {
            reads.incrementAndGet();
            return STOP + GB;
        }, alerts, invalid, "test", CLOCK);

        assertThat(b.check(STOP)).isEqualTo(Outcome.SKIPPED_INVALID_CONFIG);
        assertThat(b.isOpen()).isFalse();
        assertThat(reads).hasValue(0);
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    @Test
    @DisplayName("复审 R3：只有非熔断键（alert-bytes 等）非法时熔断照常工作")
    void unrelatedInvalidKeyKeepsBreaker() {
        AnalyticsStorageProperties partlyInvalid = new AnalyticsStorageProperties(null,
                org.springframework.util.unit.DataSize.ofBytes(STOP), 60_000_000L, 3, 300_000L, 300);
        assertThat(partlyInvalid.valid()).isFalse();
        assertThat(partlyInvalid.breakerEnabled()).isTrue();
        IngestCircuitBreaker b = new IngestCircuitBreaker(pipeline, () -> STOP + GB, alerts, partlyInvalid, "test",
                CLOCK);

        assertThat(b.check(STOP)).isEqualTo(Outcome.OPENED);
    }

    @Test
    @DisplayName("管道因库不可用停用：跳过检查、保持原状态；清单无效时库是好的，照常检查")
    void pipelineDisabled() {
        AnalyticsPipelineState down = new AnalyticsPipelineState();
        down.disable(Reason.DB_UNAVAILABLE, "x");
        IngestCircuitBreaker b = new IngestCircuitBreaker(down, () -> STOP + GB, alerts,
                AnalyticsStorageProperties.defaults(), "test", CLOCK);
        assertThat(b.check(STOP)).isEqualTo(Outcome.SKIPPED_DISABLED);
        assertThat(b.isOpen()).isFalse();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());

        AnalyticsPipelineState catalogBad = new AnalyticsPipelineState();
        catalogBad.disable(Reason.CATALOG_INVALID, "x");
        IngestCircuitBreaker c = new IngestCircuitBreaker(catalogBad, () -> STOP + GB, alerts,
                AnalyticsStorageProperties.defaults(), "test", CLOCK);
        assertThat(c.check(STOP)).isEqualTo(Outcome.OPENED);
    }

    @Test
    @DisplayName("接入入队门：熔断打开后 IngestQueue 拒收新事件（含服务端单条 offer），已入队的照常可取")
    void queueGateFollowsBreaker() {
        IngestQueue queue = new IngestQueue(64L * 1024 * 1024, breaker::isOpen);
        RawEvent queued = IngestQueueTest.event("m-1");
        assertThat(queue.offer(queued)).isTrue();

        dbBytes.set(STOP + 1);
        breaker.check(STOP);

        assertThat(queue.isWriteStopped()).isTrue();
        assertThat(queue.offer(IngestQueueTest.event("m-2"))).isFalse();
        assertThat(queue.offerAll(List.of(IngestQueueTest.event("m-3")))).isFalse();
        assertThat(queue.isClosed()).isFalse();
        assertThat(queue.drain(10, Duration.ZERO)).containsExactly(queued);

        dbBytes.set(STOP);
        breaker.check(STOP);
        assertThat(queue.isWriteStopped()).isFalse();
        assertThat(queue.offer(IngestQueueTest.event("m-4"))).isTrue();
    }
}
