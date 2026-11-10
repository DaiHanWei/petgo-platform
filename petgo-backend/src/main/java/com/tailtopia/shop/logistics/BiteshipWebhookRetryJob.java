package com.tailtopia.shop.logistics;

import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook 收件行重试（v1.4.2 shop Story 5.1 AC7.4）：每 5 分钟捞未处理、未告警、收到超过 1 分钟的行（每轮 ≤ 100），
 * 逐行 {@link BiteshipTrackingService#process}（回查在事务外）；顺带删除已处理超过 30 天的行（每轮 ≤ 1000）。
 *
 * <p>防重入：Redis 锁（见 {@link BiteshipJobLock}，取代 story 文字里的 advisory xact 锁）。
 */
@Component
public class BiteshipWebhookRetryJob {

    private static final Logger log = LoggerFactory.getLogger(BiteshipWebhookRetryJob.class);

    static final String LOCK_KEY = "lock:shop-biteship-webhook-retry";
    static final Duration LOCK_TTL = Duration.ofMinutes(4);
    static final int BATCH = 100;
    static final int PURGE_BATCH = 1000;

    private final BiteshipWebhookEventRepository inbox;
    private final BiteshipTrackingService tracking;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;

    public BiteshipWebhookRetryJob(BiteshipWebhookEventRepository inbox, BiteshipTrackingService tracking,
            StringRedisTemplate redis, PlatformTransactionManager txManager) {
        this.inbox = inbox;
        this.tracking = tracking;
        this.redis = redis;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${petgo.shop.biteship.webhook-retry-ms:300000}",
            initialDelayString = "${petgo.shop.biteship.webhook-retry-initial-delay-ms:120000}")
    public void scheduled() {
        try {
            int n = run();
            if (n > 0) {
                log.info("Biteship Webhook 重试一轮 processed={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("Biteship Webhook 重试整轮失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 跑一轮：拿不到锁返回 -1，否则返回尝试处理的行数。 */
    public int run() {
        return BiteshipJobLock.runLocked(redis, LOCK_KEY, LOCK_TTL, () -> {
            Instant now = Instant.now();
            Integer purged = tx.execute(s -> inbox.deleteProcessedBefore(now.minus(Duration.ofDays(30)), PURGE_BATCH));
            if (purged != null && purged > 0) {
                log.info("Biteship Webhook 收件表清理 deleted={}", purged);
            }
            int tried = 0;
            for (BiteshipWebhookEvent e : inbox.findRetryable(now.minus(Duration.ofMinutes(1)),
                    PageRequest.of(0, BATCH))) {
                try {
                    tracking.process(e.getId());
                } catch (RuntimeException ex) {
                    log.warn("Biteship Webhook 重试单条失败 eventId={} cause={}", e.getId(),
                            ex.getClass().getSimpleName());
                }
                tried++;
            }
            return tried;
        });
    }
}
