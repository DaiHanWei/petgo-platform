package com.tailtopia.shared.ratelimit;

import com.tailtopia.shared.error.AppException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的固定窗口限流（令牌桶近似）。
 *
 * <p>Redis 仅用于 auth 限流 / 幂等 / 防重锁等收窄用途（架构护栏：禁当通用缓存/队列）。
 * 超限抛 429 {@link AppException#rateLimited}。
 */
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 在 {@code window} 窗口内对 {@code key} 限 {@code limit} 次；超限抛 429。
     *
     * @param key    限流键（如 {@code rl:auth:google:<ip>}）
     * @param limit  窗口内最大次数
     * @param window 窗口时长
     */
    public void check(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        if (count != null && count > limit) {
            throw AppException.rateLimited("操作过于频繁，请稍后再试");
        }
    }

    /**
     * 与 {@link #check} 同一固定窗口计数，但<b>不抛异常</b>，返回是否放行（analytics-pipeline Story 1.4：
     * 埋点接口要自己构造带 {@code Retry-After} 的 429）。Redis 异常照常向外抛，由调用方决定放行还是拒绝。
     *
     * @return {@code true} = 本次计数后未超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null || count <= limit;
    }

    /**
     * 窗口剩余秒数（向上取整，至少 1），给 {@code Retry-After} 用。键没有过期时间（上次 {@code expire} 失败）时补设
     * 并返回窗口长度；取不到（键不存在 / Redis 返回空）也返回窗口长度。
     */
    public long secondsUntilReset(String key, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        Long ttl = redis.getExpire(key);
        if (ttl == null || ttl == -2L) {
            return windowSeconds;
        }
        if (ttl == -1L) {
            redis.expire(key, window);
            return windowSeconds;
        }
        return Math.max(1, Math.min(ttl, windowSeconds));
    }
}
