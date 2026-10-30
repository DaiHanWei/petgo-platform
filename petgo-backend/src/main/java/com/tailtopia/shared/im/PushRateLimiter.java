package com.tailtopia.shared.im;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 离线推送全局限速（bug 604）：腾讯 {@code /v4/timpush/batch} 按 SDKAppID 限 <b>30 人/秒</b>，超出回
 * {@code ErrorCode=60007}。运营全员通知逐人同步连发、定时推送 8 线程并发，都会撞上限——每次全员通知
 * 稳定失败几百人。
 *
 * <p>做法：按固定间隔（{@code 1s / ratePerSecond}）给每次推送排一个发送时刻，调用线程睡到该时刻再发。
 * <b>进程内</b>即可（单机单实例，见架构 V1 姿态），不引中间件。所有推送共用同一个实例（运营通知 / 定时推送 /
 * 业务实时推送），所以总速率不超限。
 *
 * <p>⚠️ stag 与生产共用 SDKAppID，限额是两边相加的；默认 25 给 stag 留了余量，stag 不应调高。
 */
final class PushRateLimiter {

    /** 睡眠钩子（测试注入，免真睡）。 */
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;

    /** 下一个可用发送时刻（nanoClock 时基）。 */
    private long nextSlot;

    PushRateLimiter(int ratePerSecond) {
        this(ratePerSecond, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    PushRateLimiter(int ratePerSecond, LongSupplier nanoClock, Sleeper sleeper) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("push rate must be > 0: " + ratePerSecond);
        }
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / ratePerSecond;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.nextSlot = nanoClock.getAsLong();
    }

    /**
     * 占一个发送名额，必要时阻塞到名额时刻。只在锁内算时刻、锁外睡，多线程按到达顺序排队。
     *
     * @return {@code false} = 等待中被中断（已恢复中断标记，调用方应放弃本次发送）
     */
    boolean acquire() {
        long wait;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            // 空闲后不攒额度：最早从「现在」开始排，避免空闲很久后瞬间连发一串
            long slot = Math.max(nextSlot, now);
            nextSlot = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait <= 0) {
            return true;
        }
        try {
            sleeper.sleepNanos(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
