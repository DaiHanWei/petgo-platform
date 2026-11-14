package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0 · V1.5.0 likes Story 2.3：派发锁拿不到不执行；释放按 uuid 比对。 */
class AutoLikeDispatchLockTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void notAcquiredDoesNotRunBody() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq(AutoLikeDispatchLock.KEY), eq("me"), eq(AutoLikeDispatchLock.TTL))).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean();

        boolean result = AutoLikeDispatchLock.runLocked(redis, () -> "me", () -> ran.set(true));

        assertThat(result).isFalse();
        assertThat(ran).isFalse();
        verify(redis, never()).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void acquiredRunsBodyAndReleasesWithOwnerCompare() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq(AutoLikeDispatchLock.KEY), eq("me"), eq(AutoLikeDispatchLock.TTL))).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();

        boolean result = AutoLikeDispatchLock.runLocked(redis, () -> "me", () -> ran.set(true));

        assertThat(result).isTrue();
        assertThat(ran).isTrue();
        verify(redis).execute(AutoLikeDispatchLock.RELEASE_SCRIPT, List.of(AutoLikeDispatchLock.KEY), "me");
        assertThat(AutoLikeDispatchLock.RELEASE_SCRIPT.getScriptAsString()).contains("== ARGV[1]");
    }

    @Test
    void releasedEvenWhenBodyThrows() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq(AutoLikeDispatchLock.KEY), eq("me"), eq(AutoLikeDispatchLock.TTL))).thenReturn(true);
        try {
            AutoLikeDispatchLock.runLocked(redis, () -> "me", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // ok
        }
        verify(redis).execute(AutoLikeDispatchLock.RELEASE_SCRIPT, List.of(AutoLikeDispatchLock.KEY), "me");
    }
}
