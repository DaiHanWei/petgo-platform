package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.pay.service.PaymentReconciliationService;
import com.tailtopia.shared.pay.ChargeQueryResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 电商定时查单（v1.4.2 shop Story 3.3 AC8，D-11 / FR-134.1）：回调丢了也能查到付款。
 *
 * <p>候选：已出码、建单满 3 分钟的电商意图 —— 仍 PENDING 的（回调丢失），或因关单 / 扫描置 FAILED / EXPIRED 不足
 * 24 小时的（找回关单后到账的迟到款，I1）。每笔<b>事务外</b>查单（P-7），PAID → {@code applyResult}；网关明确
 * FAILED / EXPIRED 且意图仍 PENDING → {@code applyResult} 推进终态；其余不动。<b>重复执行无副作用</b>
 * （{@code applyResult} 幂等，{@code markPaidAfterClose} 只在非 PAID 时生效）。
 *
 * <p>🔴 <b>防重入用 Redis 锁而非 {@code pg_try_advisory_xact_lock}</b>（拍板⑤）：xact 锁要求整轮包在一个事务里，
 * 与「网关调用必须在事务外」（I2）冲突。{@code SET NX PX 110000}，结束按 uuid 比对后删除。不引新中间件。
 */
@Component
public class ShopPaymentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ShopPaymentReconcileJob.class);

    static final String LOCK_KEY = "lock:shop-payment-reconcile";
    static final Duration LOCK_TTL = Duration.ofMillis(110_000);

    /** 按 uuid 比对后删除（防误删下一轮的锁）。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final PaymentIntentService paymentIntents;
    private final PaymentReconciliationService reconciliation;
    private final StringRedisTemplate redis;

    public ShopPaymentReconcileJob(PaymentIntentService paymentIntents,
            PaymentReconciliationService reconciliation, StringRedisTemplate redis) {
        this.paymentIntents = paymentIntents;
        this.reconciliation = reconciliation;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${petgo.shop.payment-reconcile-ms:120000}",
            initialDelayString = "${petgo.shop.payment-reconcile-initial-delay-ms:60000}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            // 整轮兜底：不让调度线程崩
            log.warn("电商定时查单整轮失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 跑一轮。拿不到锁 → 跳过本轮，返回 -1；否则返回推进的笔数。
     */
    public int run() {
        String owner = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, owner, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return -1;
        }
        try {
            return reconcileBatch(Instant.now());
        } finally {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(LOCK_KEY), owner);
            } catch (RuntimeException e) {
                log.warn("电商定时查单释放锁失败 cause={}", e.getClass().getSimpleName());
            }
        }
    }

    int reconcileBatch(Instant now) {
        int advanced = 0;
        for (PaymentIntent intent : paymentIntents.findShopReconcileCandidates(now)) {
            try {
                ChargeQueryResult r = reconciliation.query(intent);       // 事务外
                if (r.outcome() == ChargeQueryResult.Outcome.PAID) {
                    if (intent.getStatus() != PaymentStatus.PAID) {
                        paymentIntents.applyResult(r.callback());
                        advanced++;
                    }
                } else if (r.isTerminalNotPaid() && intent.getStatus() == PaymentStatus.PENDING) {
                    paymentIntents.applyResult(r.callback());
                    advanced++;
                }
            } catch (RuntimeException e) {
                log.warn("电商定时查单单笔失败 intentId={} cause={}", intent.getId(), e.getClass().getSimpleName());
            }
        }
        return advanced;
    }
}
