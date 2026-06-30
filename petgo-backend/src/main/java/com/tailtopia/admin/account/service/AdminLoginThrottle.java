package com.tailtopia.admin.account.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 后台紧急账密登录限流/锁定（Story 1.1 AC4）。Redis 失败计数（按 username），达阈值短时锁定。
 *
 * <p>护栏：仅用于紧急账密入口的暴力破解缓解；与 App 限流一脉，复用 Redis、不引入新中间件。
 * 失败 {@value #MAX_FAILURES} 次后锁定 {@value #WINDOW_MINUTES} 分钟；成功登录清零。
 */
@Service
public class AdminLoginThrottle {

    static final int MAX_FAILURES = 5;
    static final long WINDOW_MINUTES = 15;
    private static final String KEY_PREFIX = "admin:login:fail:";

    private final StringRedisTemplate redis;

    public AdminLoginThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(String username) {
        return KEY_PREFIX + (username == null ? "" : username.trim().toLowerCase());
    }

    /** 记一次失败；首次失败时设置窗口 TTL。返回当前失败计数。 */
    public long recordFailure(String username) {
        String k = key(username);
        Long n = redis.opsForValue().increment(k);
        if (n != null && n == 1L) {
            redis.expire(k, Duration.ofMinutes(WINDOW_MINUTES));
        }
        return n == null ? 0L : n;
    }

    /** 是否已锁定（失败计数达阈值）。 */
    public boolean isLocked(String username) {
        String v = redis.opsForValue().get(key(username));
        if (v == null) {
            return false;
        }
        try {
            return Long.parseLong(v) >= MAX_FAILURES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 登录成功后清零。 */
    public void clear(String username) {
        redis.delete(key(username));
    }
}
