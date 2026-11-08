package com.tailtopia.shop.logistics;

import com.tailtopia.shop.logistics.event.BiteshipWebhookReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Webhook 收件行落表提交后异步处理（v1.4.2 shop Story 5.1 AC7.1）。
 *
 * <p>🔴 {@code @Async} + {@code AFTER_COMMIT}；<b>监听方法本身不开事务</b>（回查要在事务外，落库由
 * {@link BiteshipTrackingService} 自己开 REQUIRES_NEW 短事务）。失败留给 {@link BiteshipWebhookRetryJob}。
 */
@Component
public class BiteshipWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(BiteshipWebhookListener.class);

    private final BiteshipTrackingService tracking;

    public BiteshipWebhookListener(BiteshipTrackingService tracking) {
        this.tracking = tracking;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceived(BiteshipWebhookReceivedEvent e) {
        try {
            tracking.process(e.eventId());
        } catch (RuntimeException ex) {
            log.warn("Biteship Webhook 异步处理失败（留待重试）eventId={} cause={}", e.eventId(),
                    ex.getClass().getSimpleName());
        }
    }
}
