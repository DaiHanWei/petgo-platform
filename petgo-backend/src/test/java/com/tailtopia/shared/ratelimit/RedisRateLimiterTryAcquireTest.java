package com.tailtopia.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0：analytics-pipeline Story 1.4 —— {@code tryAcquire} 不抛、返回是否放行；{@code secondsUntilReset} 取窗口剩余秒数。 */
class RedisRateLimiterTryAcquireTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis);
    private static final Duration W = Duration.ofSeconds(60);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    @DisplayName("首次计数设过期；未超限放行、超限拒绝（不抛）")
    void tryAcquire() {
        when(ops.increment("k")).thenReturn(1L, 2L, 3L);

        assertThat(limiter.tryAcquire("k", 2, W)).isTrue();
        verify(redis).expire("k", W);
        assertThat(limiter.tryAcquire("k", 2, W)).isTrue();
        assertThat(limiter.tryAcquire("k", 2, W)).isFalse();
    }

    @Test
    @DisplayName("剩余秒数：正常取 TTL；无过期补设并返回窗口；键不存在返回窗口")
    void secondsUntilReset() {
        when(redis.getExpire("a")).thenReturn(17L);
        when(redis.getExpire("b")).thenReturn(-1L);
        when(redis.getExpire("c")).thenReturn(-2L);

        assertThat(limiter.secondsUntilReset("a", W)).isEqualTo(17);
        assertThat(limiter.secondsUntilReset("b", W)).isEqualTo(60);
        verify(redis).expire("b", W);
        assertThat(limiter.secondsUntilReset("c", W)).isEqualTo(60);
        verify(redis, never()).expire("c", W);
    }
}
