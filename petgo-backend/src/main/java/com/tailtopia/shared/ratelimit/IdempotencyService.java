package com.tailtopia.shared.ratelimit;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 幂等去重（Story 2.3）。基于 Redis 的「Idempotency-Key → 已创建资源 id」短期映射。
 *
 * <p>同一 Idempotency-Key 重复提交只落一条：首次创建后存映射，重放时取回既有 id。
 * Redis 仅用于幂等/限流等收窄用途（架构护栏：禁当通用缓存/队列）。
 */
@Component
public class IdempotencyService {

    private static final String PREFIX = "idem:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 取该 key 已创建的资源 id（若有）。 */
    public Optional<Long> findResourceId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String val = redis.opsForValue().get(PREFIX + idempotencyKey);
        if (val == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(val));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 记录该 key 已创建的资源 id（TTL 24h）。 */
    public void store(String idempotencyKey, long resourceId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        // 🔴 事务内调用时把 Redis 写推迟到【提交后】：否则事务回滚（乐观锁撞车 / 瞬时 DB 错）
        //    后键已在 Redis，重试一律被当成重放短路 —— 支付回调上就是「现金收了、币没扣」。
        //    事务外调用保持原语义（立即写）。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.opsForValue().set(PREFIX + idempotencyKey, Long.toString(resourceId), TTL);
                }
            });
            return;
        }
        redis.opsForValue().set(PREFIX + idempotencyKey, Long.toString(resourceId), TTL);
    }
}
