package com.tailtopia.gift.listener;

import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.service.GiftRefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 付款后无法送达 → 首次退回（V1.4.0 gift Story 1.2 · AC2 · AD-9）。
 *
 * <p>交付（{@code applyCallback}）事务<b>提交后</b>才退：不能在交付同事务里 {@code credit} —— 钱包抛错会把支付回调事务
 * 标成 rollback-only，连已到账状态一起回滚（见 Story 1.1「偏离架构 ④」）。
 *
 * <p>失败 → 另起新事务 {@code recordFailure}（计次，余下交给 {@code GiftRefundRetryJob}）；任何异常最终<b>只记 warn、不向上抛</b>：
 * AFTER_COMMIT 里抛出会冒到网关回调的 HTTP 响应，触发无意义的回调重试。
 */
@Component
public class GiftUndeliveredListener {

    private static final Logger log = LoggerFactory.getLogger(GiftUndeliveredListener.class);

    private final GiftRefundService refunds;

    public GiftUndeliveredListener(GiftRefundService refunds) {
        this.refunds = refunds;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUndelivered(GiftUndeliveredEvent event) {
        long id = event.giftOrderId();
        try {
            try {
                refunds.attempt(id);
            } catch (RuntimeException e) {
                refunds.recordFailure(id, e.getClass().getSimpleName());
                log.warn("gift refund first attempt failed orderId={} cause={}", id, e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.warn("gift refund failure not recorded orderId={} cause={}", id, e.getClass().getSimpleName());
        }
    }
}
