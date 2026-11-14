package com.tailtopia.starterpack.service;

import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 礼包发放补跑（V1.5.0 gift-pack Story 4-2 · AC5.3 · D-24）：DB 状态机驱动（{@code @Scheduled}，禁 MQ）。
 * 每 5 分钟取 {@code status = PAID AND fulfilled_at IS NULL AND paid_at ∈ [now − 7 天, now − 2 分钟)}，逐笔独立事务调
 * {@link StarterPackFulfillmentService#fulfill}；单笔失败不阻断批次，只记 purchaseId 与异常类名。
 */
@Component
public class StarterPackFulfillmentRetryJob {

    private static final Logger log = LoggerFactory.getLogger(StarterPackFulfillmentRetryJob.class);

    static final Duration GRACE = Duration.ofMinutes(2);
    /** 自动补跑窗口：到账 7 天内（每 5 分钟一次）；超窗仍未发放 = 持续性失败（如用户已注销），停止自动重试、交人工。 */
    static final Duration RETRY_WINDOW = Duration.ofDays(7);
    static final int BATCH_LIMIT = 100;

    private final StarterPackPurchaseRepository purchases;
    private final StarterPackFulfillmentService fulfillment;
    private final Clock clock;

    @Autowired
    public StarterPackFulfillmentRetryJob(StarterPackPurchaseRepository purchases,
            StarterPackFulfillmentService fulfillment) {
        this(purchases, fulfillment, Clock.systemUTC());
    }

    StarterPackFulfillmentRetryJob(StarterPackPurchaseRepository purchases,
            StarterPackFulfillmentService fulfillment, Clock clock) {
        this.purchases = purchases;
        this.fulfillment = fulfillment;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${petgo.starter-pack.fulfillment-retry-ms:300000}")
    public void run() {
        try {
            java.time.Instant now = clock.instant();
            List<Long> ids = purchases.findUnfulfilledPaidBetween(now.minus(RETRY_WINDOW), now.minus(GRACE),
                    PageRequest.of(0, BATCH_LIMIT));
            if (ids.isEmpty()) {
                return;
            }
            int failed = 0;
            for (Long id : ids) {
                try {
                    fulfillment.fulfill(id); // 经代理：本任务无事务，每笔各自一个新事务
                } catch (RuntimeException e) {
                    failed++;
                    log.error("starter pack fulfillment retry failed purchaseId={} cause={}", id,
                            e.getClass().getSimpleName());
                }
            }
            log.info("starter pack fulfillment retry batch scanned={} failed={}", ids.size(), failed);
        } catch (RuntimeException e) {
            log.warn("starter pack fulfillment retry batch failed cause={}", e.getClass().getSimpleName());
        }
    }
}
