package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.identity.IdentityLinkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：analytics-pipeline Story 4.9 AC1 / AC2 —— 写库线程遇 Error 不退出、lastProgressAt、restartIfDead。 */
class IngestFlusherResilienceTest {

    private static final Instant T = Instant.parse("2026-11-13T01:00:00Z");

    /** 可拨的时钟。 */
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private IngestQueue queue;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OpsAlertService alerts;
    private MutableClock clock;
    private IngestFlusher flusher;
    private final List<String> written = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        queue = new IngestQueue(64L * 1024 * 1024);
        jdbc = mock(JdbcTemplate.class);
        tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        alerts = mock(OpsAlertService.class);
        clock = new MutableClock(T);
        flusher = new IngestFlusher(queue, jdbc, tx, new IdentityLinkRepository(jdbc), alerts, "test", 500,
                Duration.ofMillis(20), Duration.ofSeconds(5), Duration.ofMillis(1), Duration.ofMillis(5), clock);
        logger = (Logger) LoggerFactory.getLogger(IngestFlusher.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        flusher.stop();
        logger.detachAppender(logs);
    }

    private static List<RawEvent> events(String prefix, int n) {
        List<RawEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(IngestQueueTest.event(prefix + i));
        }
        return out;
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(cond.getAsBoolean()).as("condition within 5s").isTrue();
    }

    @Test
    @DisplayName("AC1：写库时抛 OutOfMemoryError → 本批丢弃计数、不重试；线程不退出，ERROR 只记类名、TECH 告警按 UTC 日去重；下一批照常写")
    @SuppressWarnings("unchecked")
    void errorDuringWriteDoesNotKillThread() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenAnswer(inv -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                    Collection<RawEvent> batch = inv.getArgument(1);
                    batch.forEach(e -> written.add(e.messageId()));
                    return new int[][] {};
                });
        flusher.start();
        queue.offerAll(events("a", 2));
        await(() -> flusher.droppedCount() == 2);

        queue.offerAll(events("b", 1));
        await(() -> written.contains("b0"));

        assertThat(flusher.isWorkerAlive()).isTrue();
        assertThat(flusher.writtenCount()).isEqualTo(1);
        // 出 Error 的那批只调了一次（不重试）
        verify(jdbc, times(2)).batchUpdate(anyString(), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
        verify(alerts).send(eq(OpsChannel.TECH), eq(IngestFlusher.ALERT_FLUSHER_ERROR + "2026-11-13"),
                startsWith("【埋点写库线程异常】"));
        verify(alerts, never()).send(any(), startsWith(IngestFlusher.ALERT_WRITE_FAILED), any());
        assertThat(logs.list).anySatisfy(l -> {
            assertThat(l.getLevel()).isEqualTo(Level.ERROR);
            assertThat(l.getFormattedMessage()).contains("java.lang.OutOfMemoryError").doesNotContain("a0");
        });
    }

    @Test
    @DisplayName("AC1：告警本身抛 RuntimeException 也不影响线程")
    @SuppressWarnings("unchecked")
    void alertFailureSwallowed() throws Exception {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new StackOverflowError());
        org.mockito.Mockito.doThrow(new IllegalStateException("lark down")).when(alerts).send(any(), any(), any());
        flusher.start();
        queue.offerAll(events("a", 1));
        await(() -> flusher.droppedCount() == 1);
        queue.offerAll(events("b", 1));
        await(() -> flusher.droppedCount() == 2);

        assertThat(flusher.isWorkerAlive()).isTrue();
    }

    @Test
    @DisplayName("AC2：lastProgressAt —— 未启动为 null；每次 drain 返回（空的也算）都更新")
    void lastProgressAtUpdatedOnEveryDrain() throws Exception {
        assertThat(flusher.lastProgressAt()).isNull();
        flusher.start();
        assertThat(flusher.lastProgressAt()).isEqualTo(T);

        clock.advance(Duration.ofMinutes(3));
        Instant later = T.plus(Duration.ofMinutes(3));
        await(() -> later.equals(flusher.lastProgressAt()));
    }

    @Test
    @DisplayName("AC2：restartIfDead —— 线程活着不动；线程死了重启并继续写；停机中不复活")
    @SuppressWarnings("unchecked")
    void restartIfDead() throws Exception {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenAnswer(inv -> {
                    Collection<RawEvent> batch = inv.getArgument(1);
                    batch.forEach(e -> written.add(e.messageId()));
                    return new int[][] {};
                });
        assertThat(flusher.restartIfDead()).as("生命周期未启动不复活").isFalse();
        flusher.start();
        assertThat(flusher.restartIfDead()).isFalse();

        Thread first = flusher.worker();
        first.interrupt();
        first.join(5000);
        assertThat(flusher.isWorkerAlive()).isFalse();
        assertThat(flusher.isRunning()).as("生命周期状态不随线程死亡变化，Spring 停机仍会调 stop()").isTrue();

        assertThat(flusher.restartIfDead()).isTrue();
        assertThat(flusher.isWorkerAlive()).isTrue();
        assertThat(flusher.worker()).isNotSameAs(first);
        queue.offerAll(events("c", 2));
        await(() -> written.size() == 2);

        flusher.stop();
        assertThat(flusher.isStopping()).isTrue();
        assertThat(flusher.restartIfDead()).isFalse();
        assertThat(flusher.isRunning()).isFalse();
    }

    @Test
    @DisplayName("线程意外终止、看门狗还没重启时停机：stop() 补一个线程把队列写完")
    @SuppressWarnings("unchecked")
    void stopDrainsEvenIfWorkerDied() throws Exception {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenAnswer(inv -> {
                    Collection<RawEvent> batch = inv.getArgument(1);
                    batch.forEach(e -> written.add(e.messageId()));
                    return new int[][] {};
                });
        flusher.start();
        Thread first = flusher.worker();
        first.interrupt();
        first.join(5000);
        assertThat(flusher.isWorkerAlive()).isFalse();
        queue.offerAll(events("d", 3));

        flusher.stop();

        assertThat(written).containsExactlyInAnyOrder("d0", "d1", "d2");
        assertThat(flusher.isRunning()).isFalse();
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("shortMessage：超长异常消息截到 200 字符")
    void shortMessage() {
        assertThat(IngestFlusher.shortMessage(new Error("x".repeat(500)))).hasSize(200);
        assertThat(IngestFlusher.shortMessage(new Error((String) null))).isNull();
    }
}
