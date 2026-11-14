package com.tailtopia.starterpack.listener;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 礼包 QRIS 付款失败 / 过期（V1.5.0 gift-pack Story 4-2 · AC5.4，照 {@code GiftPaymentFailedHandler}）：
 * PENDING → EXPIRED（过期）/ FAILED（其余）。之后若仍到账，{@link StarterPackPaidHandler} 照样置 PAID 并发放。
 */
@Component
public class StarterPackPaymentFailedHandler {

    private final StarterPackPurchaseRepository purchases;

    @PersistenceContext
    private EntityManager entityManager;

    public StarterPackPaymentFailedHandler(StarterPackPurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @EventListener
    @Transactional
    public void onFailed(PaymentIntentFailedEvent event) {
        if (event.purpose() != PaymentPurpose.STARTER_PACK || event.failureCategory() == null) {
            return;
        }
        purchases.findByPaymentIntentId(event.intentId())
                .filter(p -> {
                    // 锁行重读：并发到账已置 PAID 时不覆盖（4-2 复审 #1）
                    entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE);
                    return p.isPending();
                })
                .ifPresent(p -> {
                    if (event.failureCategory() == PaymentFailureCategory.EXPIRED) {
                        p.markExpired();
                    } else {
                        p.markFailed();
                    }
                    purchases.save(p);
                });
    }
}
