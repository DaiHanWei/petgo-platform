package com.tailtopia.content.autolike;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 自动点赞派发的防重入锁（V1.5.0 Story 2.3，架构 L-6）：Redis {@code SET NX PX} + uuid 比对释放。
 *
 * <p>派发每条赞一个事务，整轮不能包在一个事务里，所以不用 advisory xact 锁（照 {@code BiteshipJobLock} 的写法在本包内
 * 自写，不跨包引用那个包私有类）。锁只省重复劳动；同一行只点一次靠行级条件 UPDATE 认领 + 唯一键。
 */
final class AutoLikeDispatchLock {

    private static final Logger log = LoggerFactory.getLogger(AutoLikeDispatchLock.class);

    static final String KEY = "lock:auto-like-dispatch";
    static final Duration TTL = Duration.ofMinutes(5);

    static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private AutoLikeDispatchLock() {
    }

    /** 拿到锁则执行并返回 true；拿不到不执行、返回 false。 */
    static boolean runLocked(StringRedisTemplate redis, Runnable body) {
        return runLocked(redis, () -> UUID.randomUUID().toString(), body);
    }

    static boolean runLocked(StringRedisTemplate redis, Supplier<String> ownerSupplier, Runnable body) {
        String owner = ownerSupplier.get();
        Boolean locked = redis.opsForValue().setIfAbsent(KEY, owner, TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }
        try {
            body.run();
            return true;
        } finally {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(KEY), owner);
            } catch (RuntimeException e) {
                log.warn("auto like dispatch lock release failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
