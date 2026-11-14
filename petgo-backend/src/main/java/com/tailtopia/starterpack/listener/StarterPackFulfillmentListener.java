package com.tailtopia.starterpack.listener;

import com.tailtopia.starterpack.event.StarterPackPaidEvent;
import com.tailtopia.starterpack.service.StarterPackFulfillmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 礼包 QRIS 到账后的发放（V1.5.0 gift-pack Story 4-2 · AC5.2 · D-24）：回调事务提交后，在新事务里调
 * {@link StarterPackFulfillmentService#fulfill}；失败只记 ERROR（{@code StarterPackFulfillmentRetryJob} 每 5 分钟兜底）。
 */
@Component
public class StarterPackFulfillmentListener {

    private static final Logger log = LoggerFactory.getLogger(StarterPackFulfillmentListener.class);

    private final StarterPackFulfillmentService fulfillment;

    public StarterPackFulfillmentListener(StarterPackFulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaid(StarterPackPaidEvent event) {
        try {
            fulfillment.fulfill(event.purchaseId());
        } catch (RuntimeException e) {
            log.error("starter pack fulfillment failed purchaseId={}", event.purchaseId(), e);
        }
    }
}
