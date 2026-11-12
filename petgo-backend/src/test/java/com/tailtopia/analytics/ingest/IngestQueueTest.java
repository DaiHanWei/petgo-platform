package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：analytics-pipeline Story 1.4 AC6 —— 按估算字节上限拒绝、整批原子、出队后字节归还、关闭后拒收。 */
class IngestQueueTest {

    private static final Instant T = Instant.parse("2026-11-13T01:00:00Z");

    static RawEvent event(String messageId) {
        return RawEvent.of(messageId, "e", T, T, T, "3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f", null, null,
                RawEvent.Source.APP, null, null, false, false);
    }

    private static List<RawEvent> events(int n) {
        List<RawEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(event("m" + i));
        }
        return out;
    }

    @Test
    @DisplayName("估算字节：字符串长度 ×2 + JSON 长度 ×2 + 固定开销")
    void estimate() {
        RawEvent e = RawEvent.of("abc", "ev", T, T, T, null, "uk", null, RawEvent.Source.SERVER, "{\"a\":1}", null,
                false, false);

        assertThat(e.estimatedBytes()).isEqualTo(RawEvent.FIXED_OVERHEAD_BYTES + 2 * (3 + 2 + 2) + 2 * (7 + 2));
    }

    @Test
    @DisplayName("按字节上限拒绝：放得下进、放不下整批不进")
    void rejectsOverByteLimit() {
        long one = event("m0").estimatedBytes();
        IngestQueue q = new IngestQueue(one * 3);

        assertThat(q.offerAll(events(2))).isTrue();
        assertThat(q.offerAll(events(2))).as("2+2 > 3").isFalse();
        assertThat(q.size()).isEqualTo(2);
        assertThat(q.currentBytes()).isEqualTo(one * 2);
        assertThat(q.offer(event("x"))).isTrue();
        assertThat(q.offer(event("y"))).isFalse();
    }

    @Test
    @DisplayName("整批原子：失败时一条都没进")
    void allOrNothing() {
        long one = event("m0").estimatedBytes();
        IngestQueue q = new IngestQueue(one * 10);

        assertThat(q.offerAll(events(11))).isFalse();
        assertThat(q.size()).isZero();
        assertThat(q.currentBytes()).isZero();
    }

    @Test
    @DisplayName("出队后字节归还，按 FIFO、至多 max 条")
    void drainReturnsBytes() {
        long one = event("m0").estimatedBytes();
        IngestQueue q = new IngestQueue(one * 5);
        q.offerAll(events(5));

        List<RawEvent> first = q.drain(3, Duration.ZERO);

        assertThat(first).extracting(RawEvent::messageId).containsExactly("m0", "m1", "m2");
        assertThat(q.currentBytes()).isEqualTo(one * 2);
        assertThat(q.offerAll(events(3))).isTrue();
        assertThat(q.drain(100, Duration.ZERO)).hasSize(5);
        assertThat(q.currentBytes()).isZero();
    }

    @Test
    @DisplayName("drain 不够 max 条时最多等 wait；攒够 max 立即返回")
    void drainWaitsUntilFullOrTimeout() throws Exception {
        IngestQueue q = new IngestQueue(1_000_000);
        q.offerAll(events(1));

        long start = System.nanoTime();
        assertThat(q.drain(10, Duration.ofMillis(150))).hasSize(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));

        CompletableFuture<List<RawEvent>> waiting = CompletableFuture.supplyAsync(() -> q.drain(3, Duration.ofSeconds(10)));
        Thread.sleep(50);
        q.offerAll(events(3));
        assertThat(waiting.get(2, TimeUnit.SECONDS)).hasSize(3);
    }

    @Test
    @DisplayName("关闭后拒收、唤醒等待中的 drain，剩余的仍可取出")
    void closeRejectsAndWakesDrain() throws Exception {
        IngestQueue q = new IngestQueue(1_000_000);
        CompletableFuture<List<RawEvent>> waiting = CompletableFuture.supplyAsync(() -> q.drain(3, Duration.ofSeconds(10)));
        Thread.sleep(50);
        q.offerAll(events(1));
        q.close();

        assertThat(waiting.get(2, TimeUnit.SECONDS)).hasSize(1);
        assertThat(q.isClosed()).isTrue();
        assertThat(q.offerAll(events(1))).isFalse();
        assertThat(q.offer(event("late"))).isFalse();
        assertThat(q.offerAll(List.of())).isFalse();
    }
}
