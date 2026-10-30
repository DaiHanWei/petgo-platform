package com.tailtopia.shared.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** L0：离线推送全局限速（bug 604）。假时钟 + 假睡眠，不真睡。 */
class PushRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Long> sleeps = new ArrayList<>();

    /** 睡眠即推进假时钟（模拟真实阻塞后的时刻）。 */
    private PushRateLimiter limiter(int rate) {
        return new PushRateLimiter(rate, now::get, nanos -> {
            sleeps.add(nanos);
            now.addAndGet(nanos);
        });
    }

    @Test
    void firstCallGoesImmediatelyThenSpacedByInterval() {
        PushRateLimiter l = limiter(25);
        for (int i = 0; i < 3; i++) {
            assertThat(l.acquire()).isTrue();
        }
        long interval = TimeUnit.SECONDS.toNanos(1) / 25; // 40ms
        assertThat(sleeps).containsExactly(interval, interval);
    }

    @Test
    void burstOfOneSecondNeverExceedsRate() {
        PushRateLimiter l = limiter(25);
        long start = now.get();
        int sent = 0;
        while (now.get() - start < TimeUnit.SECONDS.toNanos(1)) {
            l.acquire();
            sent++;
        }
        // 1 秒窗口内（含 0 时刻那一发）最多 25 发
        assertThat(sent).isLessThanOrEqualTo(26);
        assertThat(sent - 1).isLessThanOrEqualTo(25);
    }

    @Test
    void idleTimeDoesNotAccumulateBurstCredit() {
        PushRateLimiter l = limiter(25);
        l.acquire();
        now.addAndGet(TimeUnit.SECONDS.toNanos(60)); // 空闲 1 分钟
        l.acquire(); // 立刻发
        l.acquire(); // 仍要等一个间隔，不因空闲攒出连发额度
        assertThat(sleeps).containsExactly(TimeUnit.SECONDS.toNanos(1) / 25);
    }

    @Test
    void interruptedWhileWaitingReturnsFalseAndKeepsFlag() {
        PushRateLimiter l = new PushRateLimiter(25, now::get, nanos -> {
            throw new InterruptedException();
        });
        assertThat(l.acquire()).isTrue(); // 首发不等
        assertThat(l.acquire()).isFalse();
        assertThat(Thread.interrupted()).isTrue(); // 断言并清除标记
    }

    @Test
    void nonPositiveRateRejected() {
        assertThatThrownBy(() -> new PushRateLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultRateLeavesHeadroomUnderTencentLimit() {
        assertThat(new ImProperties().getPushRatePerSecond()).isBetween(1, 29);
    }
}
