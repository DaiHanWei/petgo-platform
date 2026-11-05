package com.tailtopia.shop.logistics;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Biteship 定时任务的防重入锁（v1.4.2 shop Story 5.1）。
 *
 * <p>🔴 <b>用 Redis 锁而非 {@code pg_try_advisory_xact_lock}</b>（照 3.3 {@code ShopPaymentReconcileJob} 拍板⑤）：
 * xact 锁要求整轮包在一个事务里，与「Biteship 回查必须在事务外」（P-7）冲突。{@code SET NX PX}，结束按 uuid 比对后删除。
 */
final class BiteshipJobLock {

    private static final Logger log = LoggerFactory.getLogger(BiteshipJobLock.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private BiteshipJobLock() {
    }

    /** 拿到锁则执行并返回结果；拿不到返回 -1。 */
    static int runLocked(StringRedisTemplate redis, String key, Duration ttl, IntSupplier body) {
        String owner = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(key, owner, ttl);
        if (!Boolean.TRUE.equals(locked)) {
            return -1;
        }
        try {
            return body.getAsInt();
        } finally {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(key), owner);
            } catch (RuntimeException e) {
                log.warn("Biteship 任务释放锁失败 key={} cause={}", key, e.getClass().getSimpleName());
            }
        }
    }
}
