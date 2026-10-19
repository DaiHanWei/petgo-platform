package com.tailtopia.gift.service;

import com.tailtopia.gift.repository.GiftOrderRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退回失败的自动重试（V1.4.0 gift Story 1.2 · AC3 · P-2）。DB 状态机驱动（{@code @Scheduled}，<b>禁 MQ / 延迟队列</b>），
 * 写法照 {@code PaymentIntentExpiryScanner}。
 *
 * <p>每 10 分钟扫一批（≤ {@value #BATCH_LIMIT} 条，id 升序）：{@code UNDELIVERED_REFUND_PENDING AND refund_attempts < 5}；
 * 逐笔 {@link GiftRefundService#attempt}，失败 {@link GiftRefundService#recordFailure}（第 5 次置 REFUND_MANUAL，之后不再被扫到）。
 * 单笔失败不阻断批次；整批 try/catch 只记笔数，不记 PII。
 */
@Component
public class GiftRefundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(GiftRefundRetryJob.class);

    static final int BATCH_LIMIT = 100;

    private final GiftOrderRepository orders;
    private final GiftRefundService refunds;

    public GiftRefundRetryJob(GiftOrderRepository orders, GiftRefundService refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${petgo.gift.refund-retry-ms:600000}")
    public void run() {
        try {
            List<Long> ids = orders.findRefundRetryIds(GiftRefundService.MAX_ATTEMPTS, BATCH_LIMIT);
            if (ids.isEmpty()) {
                return;
            }
            int refunded = 0;
            int failed = 0;
            for (Long id : ids) {
                if (retryOne(id)) {
                    refunded++;
                } else {
                    failed++;
                }
            }
            log.info("gift refund retry batch scanned={} refunded={} failed={}", ids.size(), refunded, failed);
        } catch (RuntimeException e) {
            log.warn("gift refund retry batch failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** 重试一笔；返回 true = 本次退回成功或已无需退回。失败已计次，不向上抛。 */
    private boolean retryOne(long id) {
        try {
            refunds.attempt(id);
            return true;
        } catch (RuntimeException e) {
            try {
                refunds.recordFailure(id, e.getClass().getSimpleName());
            } catch (RuntimeException e2) {
                log.warn("gift refund failure not recorded orderId={} cause={}", id, e2.getClass().getSimpleName());
            }
            return false;
        }
    }
}
