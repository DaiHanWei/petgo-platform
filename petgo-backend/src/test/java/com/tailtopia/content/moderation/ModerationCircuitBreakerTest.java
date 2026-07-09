package com.tailtopia.content.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** L0（AC6）：进程内熔断器状态机 —— 连续失败打开 → 窗口内短路 → 半开探测恢复。 */
class ModerationCircuitBreakerTest {

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        AtomicLong now = new AtomicLong(0);
        ModerationCircuitBreaker cb = new ModerationCircuitBreaker(3, 1000L, now::get);

        assertThat(cb.allowRequest()).isTrue();
        cb.recordFailure(); // 1
        cb.recordFailure(); // 2
        assertThat(cb.allowRequest()).isTrue(); // 尚未达阈值
        cb.recordFailure(); // 3 → 打开

        assertThat(cb.state()).isEqualTo(ModerationCircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse(); // 窗口内短路，不打三方
    }

    @Test
    void halfOpenProbeRecoversOnSuccess() {
        AtomicLong now = new AtomicLong(0);
        ModerationCircuitBreaker cb = new ModerationCircuitBreaker(1, 1000L, now::get);

        cb.recordFailure(); // 打开
        assertThat(cb.allowRequest()).isFalse();

        now.set(1000); // 窗口到期 → 半开
        assertThat(cb.state()).isEqualTo(ModerationCircuitBreaker.State.HALF_OPEN);
        assertThat(cb.allowRequest()).isTrue(); // 放行一次探测
        cb.recordSuccess(); // 探测成功 → 关闭复位

        assertThat(cb.state()).isEqualTo(ModerationCircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void halfOpenProbeFailureReopens() {
        AtomicLong now = new AtomicLong(0);
        ModerationCircuitBreaker cb = new ModerationCircuitBreaker(1, 1000L, now::get);

        cb.recordFailure(); // 打开 @0
        now.set(1000);
        assertThat(cb.allowRequest()).isTrue(); // 半开探测放行
        cb.recordFailure(); // 探测失败 → 重新打开 @1000

        assertThat(cb.allowRequest()).isFalse(); // 新窗口内再次短路
        now.set(2000); // 新窗口到期
        assertThat(cb.state()).isEqualTo(ModerationCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void successResetsFailureStreak() {
        AtomicLong now = new AtomicLong(0);
        ModerationCircuitBreaker cb = new ModerationCircuitBreaker(3, 1000L, now::get);

        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess(); // 连续失败清零
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(ModerationCircuitBreaker.State.CLOSED); // 未达连续 3 次
    }
}
