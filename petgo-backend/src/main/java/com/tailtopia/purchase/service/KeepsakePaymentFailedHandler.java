package com.tailtopia.purchase.service;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性解锁 QRIS 失败（V1.3.2 Story 3.1 · AC5.5）：对应 PENDING 购买行按失败类别收尾；非 PENDING 不动。
 *
 * <p>同步监听、加入发布方事务：意图置终态与购买行收尾一起提交。{@code EXPIRED} → EXPIRED；
 * {@code GATEWAY_DECLINED} / {@code USER_CANCELLED} → CANCELED。
 *
 * <p>⚠️ {@code PaymentIntentService} 并非每个置终态点都发失败事件（见其 {@code publishFailed} 注释）：
 * {@code createIntent} 的懒过期不发 —— 那一处由 {@code KeepsakePurchaseService} 在下一次发起时把旧行置 EXPIRED 补上。
 */
@Component
public class KeepsakePaymentFailedHandler {

    private final KeepsakePurchaseRepository purchases;

    public KeepsakePaymentFailedHandler(KeepsakePurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @EventListener
    @Transactional
    public void onFailed(PaymentIntentFailedEvent event) {
        if (KeepsakeSku.fromPurpose(event.purpose()).isEmpty() || event.failureCategory() == null) {
            return;
        }
        purchases.findByPaymentIntentId(event.intentId())
                .filter(p -> p.getStatus() == KeepsakePurchaseStatus.PENDING)
                .ifPresent(p -> {
                    p.markStatus(event.failureCategory() == PaymentFailureCategory.EXPIRED
                            ? KeepsakePurchaseStatus.EXPIRED
                            : KeepsakePurchaseStatus.CANCELED);
                    purchases.save(p);
                });
    }
}
