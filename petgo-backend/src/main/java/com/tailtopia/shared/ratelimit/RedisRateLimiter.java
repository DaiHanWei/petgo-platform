package com.petgo.shared.ratelimit;

import com.petgo.shared.error.AppException;
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
}
