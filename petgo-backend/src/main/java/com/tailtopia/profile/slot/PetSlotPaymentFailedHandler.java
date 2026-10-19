package com.tailtopia.profile.slot;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物名额月租 QRIS 失败 / 过期（V1.4.0 multi-pet Story 2.2 · AC7，照 {@code KeepsakePaymentFailedHandler}）：
 * 对应 PENDING 购买行按失败类别收尾，<b>不增加名额</b>；非 PENDING 不动。
 *
 * <p>同步监听、加入发布方事务。类别 {@code EXPIRED} → EXPIRED；{@code GATEWAY_DECLINED} / {@code USER_CANCELLED} → FAILED。
 * ⚠️ {@code createIntent} / {@code statusOf} 的懒过期不发失败事件 —— 那两处由 {@code PetSlotPurchaseService}
 * （同键重开改挂新意图 / 购买状态查询自愈）补上。
 */
@Component
public class PetSlotPaymentFailedHandler {

    private final PetSlotPurchaseRepository purchases;

    public PetSlotPaymentFailedHandler(PetSlotPurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @EventListener
    @Transactional
    public void onFailed(PaymentIntentFailedEvent event) {
        if (event.purpose() != PaymentPurpose.PET_SLOT_RENT || event.failureCategory() == null) {
            return;
        }
        purchases.findByPaymentIntentId(event.intentId())
                .filter(p -> p.getStatus() == PetSlotPurchaseStatus.PENDING)
                .ifPresent(p -> {
                    p.markStatus(event.failureCategory() == PaymentFailureCategory.EXPIRED
                            ? PetSlotPurchaseStatus.EXPIRED
                            : PetSlotPurchaseStatus.FAILED);
                    purchases.save(p);
                });
    }
}
