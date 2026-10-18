package com.tailtopia.gift.listener;

import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送礼 QRIS 失败（V1.4.0 gift Story 1.1 · AC8.5 · PRD AC7）：对应 PENDING 订单按失败类别收尾；非 PENDING 不动。
 *
 * <p>同步监听、加入发布方事务（照 {@code KeepsakePaymentFailedHandler}）：意图置终态与订单收尾一起提交。
 * {@code EXPIRED} → EXPIRED；{@code GATEWAY_DECLINED} / {@code USER_CANCELLED} → FAILED。<b>不发事件、不通知</b>。
 *
 * <p>⚠️ {@code createIntent} 的懒过期不发失败事件 —— 送礼每笔订单独立意图键，不会走到那条复用分支；
 * 万一重放时撞上，由 {@code GiftSendService} 的重放取回把订单置 EXPIRED 补上。
 */
@Component
public class GiftPaymentFailedHandler {

    private final GiftOrderRepository orders;

    public GiftPaymentFailedHandler(GiftOrderRepository orders) {
        this.orders = orders;
    }

    @EventListener
    @Transactional
    public void onFailed(PaymentIntentFailedEvent event) {
        if (event.purpose() != PaymentPurpose.GIFT || event.failureCategory() == null) {
            return;
        }
        orders.findByPaymentIntentId(event.intentId())
                .filter(o -> o.isPending())
                .ifPresent(o -> {
                    if (event.failureCategory() == PaymentFailureCategory.EXPIRED) {
                        o.markExpired();
                    } else {
                        o.markFailed();
                    }
                    orders.save(o);
                });
    }
}
