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
    /** 处理中占位键前缀（v1.4.2 shop Story 3.2 复审修复：同 key 并发提交只放行一个）。 */
    private static final String CLAIM_PREFIX = "idem-claim:";

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

    /**
     * 抢占「处理中」占位（{@code SET NX} + TTL）。同 key 并发时只有一个调用方拿到 true；
     * 空 key 视为无需幂等、恒 true。
     *
     * <p>Redis 不可用时与本类其它方法一致：不吞异常（调用方请求失败，不降级放行）。
     */
    public boolean tryClaim(String idempotencyKey, Duration ttl) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(CLAIM_PREFIX + idempotencyKey, "1", ttl));
    }

    /** 释放占位（成功时映射已在提交后写入；失败回滚时释放后可重试）。空 key 为 no-op。 */
    public void releaseClaim(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        redis.delete(CLAIM_PREFIX + idempotencyKey);
    }
}
