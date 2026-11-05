package com.tailtopia.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0：幂等「处理中」占位（v1.4.2 shop Story 3.2 复审修复：SET NX + TTL）。 */
class IdempotencyClaimTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final IdempotencyService service = new IdempotencyService(redis);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    @DisplayName("SET NX 成功 → true；已被占 → false；键前缀 idem-claim:")
    void claim() {
        when(ops.setIfAbsent("idem-claim:k", "1", Duration.ofMinutes(2))).thenReturn(true, false);
        assertThat(service.tryClaim("k", Duration.ofMinutes(2))).isTrue();
        assertThat(service.tryClaim("k", Duration.ofMinutes(2))).isFalse();
    }

    @Test
    @DisplayName("Redis 返回 null（管道 / 事务模式）按未抢到处理")
    void nullIsNotClaimed() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThat(service.tryClaim("k", Duration.ofMinutes(2))).isFalse();
    }

    @Test
    @DisplayName("空 key：恒放行、释放为 no-op，不碰 Redis")
    void blankKey() {
        assertThat(service.tryClaim(null, Duration.ofMinutes(2))).isTrue();
        assertThat(service.tryClaim(" ", Duration.ofMinutes(2))).isTrue();
        service.releaseClaim(null);
        verify(redis, never()).opsForValue();
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("释放删除占位键")
    void release() {
        service.releaseClaim("k");
        verify(redis).delete("idem-claim:k");
    }
}
