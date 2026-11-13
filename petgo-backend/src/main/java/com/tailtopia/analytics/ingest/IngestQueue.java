package com.tailtopia.analytics.ingest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 埋点接收的有界内存队列（analytics-pipeline Story 1.4，架构 D2 / §三 队列 —— 记录在案的护栏破例：单实例部署，
 * {@code @Async} + DB 状态机之外唯一的内存缓冲）。
 *
 * <ul>
 *   <li>上限按<b>估算字节</b>（{@link RawEvent#estimatedBytes()} 之和，{@code analytics.ingest.queue-max-bytes}，默认 64 MB）。</li>
 *   <li>{@link #offerAll}：整批要么全进、要么全不进（防止半批入队后 App 整批重试造成重复）。</li>
 *   <li>{@link #offer}：Story 1.9 服务端事件用，满了返回 {@code false}，调用方丢弃计数。</li>
 *   <li>{@link #drain}：只给 {@link IngestFlusher} 用。</li>
 *   <li>{@link #close}：停机时由 flusher 调用，之后一律拒收（controller 据 {@link #isClosed()} 返回 503）。</li>
 *   <li>停写门（Story 1.10）：{@link IngestCircuitBreaker} 打开时 {@link #offerAll} / {@link #offer} 一律拒收
 *       （controller 据 {@link #isWriteStopped()} 返回 429，{@code SelfHostedAnalyticsClient} 计入丢弃）。
 *       只拦新写入，{@link #drain} 不受影响 —— 已入队的照常写完。</li>
 * </ul>
 *
 * <p>🔴 架构 §实现规范 5 反例：<b>业务模块不准直接注入本队列</b>，只能走 {@code AnalyticsClient}
 * （唯一例外是 Story 1.9 的 {@code SelfHostedAnalyticsClient}，所以本类与 {@link #offer} 只能是 public）。
 *
 * <p>线程安全：一把锁保护队列与关闭标志，保证 {@link #close} 之后不会再有事件进来（否则停机时最后一次排空之后
 * 入队的事件会丢）。当前字节数另用 {@link AtomicLong} 记，便于无锁读取水位。
 */
@Component
@EnableConfigurationProperties(IngestProperties.class)
public class IngestQueue {

    private final long maxBytes;
    /** Story 1.10 停写门：为真时拒收新事件。 */
    private final BooleanSupplier writeStopped;
    private final ArrayDeque<RawEvent> deque = new ArrayDeque<>();
    private final AtomicLong bytes = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean closed;

    @Autowired
    IngestQueue(IngestProperties props, IngestCircuitBreaker breaker) {
        this(props.queueMaxBytes(), breaker::isOpen);
    }

    /** 测试用：不接停写门（永不熔断）。 */
    IngestQueue(long maxBytes) {
        this(maxBytes, () -> false);
    }

    IngestQueue(long maxBytes, BooleanSupplier writeStopped) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("queue-max-bytes 必须为正");
        }
        this.maxBytes = maxBytes;
        this.writeStopped = writeStopped;
    }

    /**
     * 整批入队：全进或全不进。
     *
     * @return {@code false} = 放不下（字节超上限）、队列已关闭或停写熔断中，一条都没进
     */
    boolean offerAll(List<RawEvent> events) {
        if (events.isEmpty()) {
            return !isClosed();
        }
        if (isWriteStopped()) {
            return false;
        }
        long sum = 0;
        for (RawEvent e : events) {
            sum += e.estimatedBytes();
        }
        lock.lock();
        try {
            if (closed || bytes.get() + sum > maxBytes) {
                return false;
            }
            deque.addAll(events);
            bytes.addAndGet(sum);
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 单条入队（Story 1.9 服务端事件）。
     *
     * @return {@code false} = 满了、已关闭或停写熔断中，调用方丢弃并计数
     */
    public boolean offer(RawEvent event) {
        return offerAll(List.of(event));
    }

    /**
     * 取一批：等到攒够 {@code max} 条或等满 {@code wait}（或队列关闭）为止，再取至多 {@code max} 条。
     * 队列关闭后不再等待，有多少取多少。被中断时恢复中断标志并返回已有的。
     */
    List<RawEvent> drain(int max, Duration wait) {
        long nanos = wait.toNanos();
        lock.lock();
        try {
            while (deque.size() < max && !closed && nanos > 0) {
                try {
                    nanos = changed.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            int n = Math.min(max, deque.size());
            if (n == 0) {
                return List.of();
            }
            List<RawEvent> out = new ArrayList<>(n);
            long sum = 0;
            for (int i = 0; i < n; i++) {
                RawEvent e = deque.poll();
                out.add(e);
                sum += e.estimatedBytes();
            }
            bytes.addAndGet(-sum);
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 关闭：之后入队一律拒绝；唤醒正在等待的 {@link #drain}。幂等。 */
    void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重新打开（{@link IngestFlusher#start()} 在 stop 之后再次启动时调用）。
     *
     * <p>生产只在停机时 stop 一次，用不到；但 SmartLifecycle 允许 stop 后再 start —— Spring 7 的测试上下文缓存会
     * 「暂停」不在用的上下文（stop 全部 SmartLifecycle）、复用时再 start，不重开队列就会永久拒收（返回 503）。
     */
    void reopen() {
        lock.lock();
        try {
            closed = false;
        } finally {
            lock.unlock();
        }
    }

    /** 是否已关闭（停机中）。 */
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** 是否停写熔断中（Story 1.10，{@link IngestCircuitBreaker} 打开）。 */
    public boolean isWriteStopped() {
        return writeStopped.getAsBoolean();
    }

    /** 当前条数。 */
    public int size() {
        lock.lock();
        try {
            return deque.size();
        } finally {
            lock.unlock();
        }
    }

    /** 当前估算字节水位。 */
    public long currentBytes() {
        return bytes.get();
    }

    /** 估算字节上限。 */
    public long maxBytes() {
        return maxBytes;
    }
}
