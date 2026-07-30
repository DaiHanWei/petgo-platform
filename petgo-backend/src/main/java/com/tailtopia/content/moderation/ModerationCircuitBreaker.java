package com.tailtopia.content.moderation;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 进程内极简熔断器（内容审核 Story 1 · 方案 §4.3「持续宕机」）。<b>自研，不引入 Resilience4j/Hystrix
 * 等新依赖</b>（护栏：不加中间件/框架）。
 *
 * <p>状态机：连续失败达阈值 → 打开（OPEN），窗口期内 {@link #allowRequest()} 返回 false 直接短路
 * （不打三方，门面返回 {@code DEGRADED/CIRCUIT_OPEN}）；窗口过后放行一次半开探测（HALF_OPEN），
 * 探测成功 → 关闭复位，探测失败 → 重新打开。计数用 {@link AtomicInteger} + 时间戳，无锁无中间件。
 *
 * <p>时钟经 {@link LongSupplier} 注入，便于单测推进时间验证半开恢复（AC6）。
 */
public class ModerationCircuitBreaker {

    /** 状态（仅用于日志/测试观测）。 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationMs;
    private final LongSupplier clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean open = false;
    private volatile long openedAtMs = 0L;
    private volatile boolean probing = false;

    public ModerationCircuitBreaker() {
        this(5, 30_000L, System::currentTimeMillis);
    }

    public ModerationCircuitBreaker(int failureThreshold, long openDurationMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
        this.clock = clock;
    }

    /**
     * @return true 放行（关闭 / 半开探测）；false 短路（打开且在窗口内）。
     */
    public boolean allowRequest() {
        if (!open) {
            return true; // CLOSED
        }
        if (clock.getAsLong() - openedAtMs >= openDurationMs) {
            probing = true; // 进入半开，放行一次探测
            return true;
        }
        return false; // OPEN，窗口内短路
    }

    /** 三方调用成功：复位关闭。 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        open = false;
        probing = false;
    }

    /** 三方调用失败：累计；半开探测失败重新打开，连续失败达阈值首次打开。 */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (probing) {
            probing = false;
            open = true;
            openedAtMs = clock.getAsLong(); // 半开探测失败 → 重新打开
        } else if (failures >= failureThreshold && !open) {
            open = true;
            openedAtMs = clock.getAsLong(); // 首次打开
        }
    }

    public State state() {
        if (!open) {
            return State.CLOSED;
        }
        if (clock.getAsLong() - openedAtMs >= openDurationMs) {
            return State.HALF_OPEN;
        }
        return State.OPEN;
    }
}
