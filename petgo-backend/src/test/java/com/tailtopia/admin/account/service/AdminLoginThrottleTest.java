package com.tailtopia.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0：紧急账密登录限流/锁定（AC4）—— 失败计数、首次设 TTL、阈值锁定、成功清零、username 规范化。 */
class AdminLoginThrottleTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AdminLoginThrottle throttle;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        throttle = new AdminLoginThrottle(redis);
    }

    @Test
    void firstFailureSetsTtl() {
        when(ops.increment("admin:login:fail:ops@tailtopia.id")).thenReturn(1L);
        long n = throttle.recordFailure("Ops@Tailtopia.id"); // 大小写/空白规范化
        assertThat(n).isEqualTo(1L);
        verify(redis).expire(eq("admin:login:fail:ops@tailtopia.id"), any(Duration.class));
    }

    @Test
    void subsequentFailureDoesNotResetTtl() {
        when(ops.increment("admin:login:fail:u")).thenReturn(3L);
        long n = throttle.recordFailure("u");
        assertThat(n).isEqualTo(3L);
        verify(redis, org.mockito.Mockito.never()).expire(any(), any());
    }

    @Test
    void lockedWhenCountAtThreshold() {
        when(ops.get("admin:login:fail:u")).thenReturn("5");
        assertThat(throttle.isLocked("u")).isTrue();
    }

    @Test
    void notLockedBelowThresholdOrAbsent() {
        when(ops.get("admin:login:fail:u")).thenReturn("4");
        assertThat(throttle.isLocked("u")).isFalse();
        when(ops.get("admin:login:fail:v")).thenReturn(null);
        assertThat(throttle.isLocked("v")).isFalse();
    }

    @Test
    void clearDeletesKey() {
        throttle.clear("U");
        verify(redis).delete("admin:login:fail:u");
    }
}
