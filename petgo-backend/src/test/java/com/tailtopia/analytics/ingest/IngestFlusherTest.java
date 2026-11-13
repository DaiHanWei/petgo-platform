package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.identity.IdentityLink;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.InOrder;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：analytics-pipeline Story 1.4 AC7 / AC8 —— 写库批量、失败重试后丢弃告警、停机先关队列再写完。 */
class IngestFlusherTest {

    private static final Instant T = Instant.parse("2026-11-13T01:00:00Z");

    private IngestQueue queue;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OpsAlertService alerts;
    private final List<String> written = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        queue = new IngestQueue(64L * 1024 * 1024);
        jdbc = mock(JdbcTemplate.class);
        tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        alerts = mock(OpsAlertService.class);
    }

    @SuppressWarnings("unchecked")
    private void jdbcRecordsWrites() {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenAnswer(inv -> {
                    Collection<RawEvent> batch = inv.getArgument(1);
                    batch.forEach(e -> written.add(e.messageId()));
                    return new int[][] {};
                });
    }

    private IngestFlusher flusher(int batchSize, Duration interval) {
        return new IngestFlusher(queue, jdbc, tx, alerts, "test", batchSize, interval, Duration.ofSeconds(5),
                Duration.ofMillis(1), Clock.fixed(T, ZoneOffset.UTC));
    }

    private static List<RawEvent> events(int n) {
        List<RawEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(IngestQueueTest.event("m" + i));
        }
        return out;
    }

    @Test
    @DisplayName("phase 晚于 Web 服务器优雅停机（-1024）与停止（-2048）：Spring 按 phase 从大到小停")
    void phaseAfterWebServer() {
        assertThat(IngestFlusher.PHASE).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 4096);
        assertThat(IngestFlusher.PHASE).isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
        assertThat(IngestFlusher.PHASE).isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
    }

    @Test
    @DisplayName("停机：关闭队列（之后拒收）→ 已入队的全部写完（多批）→ 线程退出")
    void stopDrainsEverything() {
        jdbcRecordsWrites();
        IngestFlusher f = flusher(500, Duration.ofSeconds(10));
        assertThat(queue.offerAll(events(1200))).isTrue();

        f.start();
        f.stop();

        assertThat(written).hasSize(1200).doesNotHaveDuplicates();
        assertThat(f.writtenCount()).isEqualTo(1200);
        assertThat(f.isRunning()).isFalse();
        assertThat(queue.isClosed()).isTrue();
        assertThat(queue.offer(IngestQueueTest.event("late"))).isFalse();
        assertThat(queue.currentBytes()).isZero();
    }

    @Test
    @DisplayName("stop 之后再 start（Spring 7 测试上下文暂停 / 恢复）：队列重开，照常接收并写库")
    void restartAfterStopReopensQueue() throws Exception {
        jdbcRecordsWrites();
        IngestFlusher f = flusher(500, Duration.ofMillis(50));
        f.start();
        f.stop();
        assertThat(queue.isClosed()).isTrue();

        f.start();
        try {
            assertThat(f.isRunning()).isTrue();
            assertThat(queue.isClosed()).isFalse();
            assertThat(queue.offerAll(events(2))).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (written.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(written).containsExactly("m0", "m1");
        } finally {
            f.stop();
        }
        assertThat(queue.isClosed()).isTrue();
    }

    @Test
    @DisplayName("运行中按间隔把零散事件写出去")
    void flushesOnInterval() throws Exception {
        jdbcRecordsWrites();
        IngestFlusher f = flusher(500, Duration.ofMillis(50));
        f.start();
        try {
            queue.offerAll(events(3));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (written.size() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(written).containsExactly("m0", "m1", "m2");
        } finally {
            f.stop();
        }
    }

    @Test
    @DisplayName("写失败：重试 3 次后丢弃、计数、TECH 告警（按 UTC 日去重键），不抛")
    @SuppressWarnings("unchecked")
    void writeFailureDropsAndAlerts() {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        IngestFlusher f = flusher(500, Duration.ofSeconds(1));

        f.write(events(4));

        verify(jdbc, times(1 + IngestFlusher.MAX_RETRIES)).batchUpdate(anyString(), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(f.droppedCount()).isEqualTo(4);
        verify(alerts).send(eq(OpsChannel.TECH), eq(IngestFlusher.ALERT_WRITE_FAILED + "2026-11-13"),
                startsWith("【埋点写库失败】"));
    }

    @Test
    @DisplayName("失败后重试成功：不丢、不告警")
    @SuppressWarnings("unchecked")
    void retrySucceeds() {
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("blip"))
                .thenReturn(new int[][] {});
        IngestFlusher f = flusher(500, Duration.ofSeconds(1));

        f.write(events(2));

        assertThat(f.droppedCount()).isZero();
        assertThat(f.writtenCount()).isEqualTo(2);
        verify(alerts, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("绑定：13 列顺序、时间 UTC、jsonb 文本、user_id 可空、source 名")
    void bindColumns() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        RawEvent e = RawEvent.of("m", "ev", T, T.plusSeconds(1), T.plusSeconds(2), "aid", "uk", null,
                RawEvent.Source.APP, "{\"a\":1}", null, true, true);

        IngestFlusher.bind(ps, e);

        verify(ps).setString(1, "m");
        verify(ps).setString(2, "ev");
        verify(ps).setObject(3, OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        verify(ps).setObject(4, OffsetDateTime.ofInstant(T.plusSeconds(1), ZoneOffset.UTC));
        verify(ps).setObject(5, OffsetDateTime.ofInstant(T.plusSeconds(2), ZoneOffset.UTC));
        verify(ps).setString(6, "aid");
        verify(ps).setString(7, "uk");
        verify(ps).setNull(8, Types.BIGINT);
        verify(ps).setString(9, "APP");
        verify(ps).setString(10, "{\"a\":1}");
        verify(ps).setString(11, "{}");
        verify(ps).setBoolean(12, true);
        verify(ps).setBoolean(13, true);
        assertThat(IngestFlusher.INSERT_SQL).contains("?::jsonb, ?::jsonb").startsWith("INSERT INTO events_raw (");
    }

    // ---- Story 1.5：关联行与事件同一个埋点事务 ----

    private static RawEvent withLink(String messageId) {
        IdentityLink link = new IdentityLink("3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f", 7L, "k".repeat(64), T,
                messageId);
        return IngestQueueTest.event(messageId).withIdentityLink(link);
    }

    @Test
    @DisplayName("1.5：带关联的批 → 同一事务内先写 events_raw 再写 identity_links")
    @SuppressWarnings("unchecked")
    void linksWrittenAfterEventsInSameTransaction() {
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        tx = new TransactionTemplate(ptm);
        IngestFlusher f = flusher(500, Duration.ofSeconds(1));

        f.write(List.of(IngestQueueTest.event("a"), withLink("b"), withLink("c")));

        InOrder order = inOrder(ptm, jdbc);
        order.verify(ptm).getTransaction(any());
        order.verify(jdbc).batchUpdate(eq(IngestFlusher.INSERT_SQL), anyCollection(), eq(3),
                any(ParameterizedPreparedStatementSetter.class));
        order.verify(jdbc).batchUpdate(startsWith("INSERT INTO identity_links"),
                any(BatchPreparedStatementSetter.class));
        order.verify(ptm).commit(any());
        verify(ptm, times(1)).getTransaction(any());
        assertThat(f.writtenCount()).isEqualTo(3);
        assertThat(IngestFlusher.linksOf(List.of(IngestQueueTest.event("a"), withLink("b"), withLink("c"))))
                .extracting(IdentityLink::messageId).containsExactly("b", "c");
    }

    @Test
    @DisplayName("1.5：没有关联的批 → 只有一条 events_raw 语句")
    void noLinksNoSecondStatement() {
        IngestFlusher f = flusher(500, Duration.ofSeconds(1));

        f.write(events(2));

        verify(jdbc, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    @DisplayName("1.5：关联写失败 → 整批（含事件）按原语义重试，成功后不丢")
    @SuppressWarnings("unchecked")
    void linkFailureRetriesWholeBatch() {
        when(jdbc.batchUpdate(startsWith("INSERT INTO identity_links"), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new DataAccessResourceFailureException("blip"))
                .thenReturn(new int[] {1});
        IngestFlusher f = flusher(500, Duration.ofSeconds(1));

        f.write(List.of(withLink("b")));

        verify(jdbc, times(2)).batchUpdate(eq(IngestFlusher.INSERT_SQL), anyCollection(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(f.writtenCount()).isEqualTo(1);
        assertThat(f.droppedCount()).isZero();
        verify(alerts, never()).send(any(), any(), any());
    }
}
