package com.tailtopia.gift.listener;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftDeliveryService;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送礼 QRIS 到账（V1.4.0 gift Story 1.1 · AC8.4 · AD-7）：{@code purpose = GIFT} 的唯一 {@link PaymentIntentPaidEvent} 消费者。
 *
 * <p><b>同事务</b>：同步 {@link EventListener} + {@code MANDATORY} 加入 {@code applyCallback} 的事务（照 {@code KeepsakePaidHandler}，
 * <b>绝不 AFTER_COMMIT</b>）。按 {@code payment_intent_id} 找订单，非 PENDING 直接返回（重放幂等）；
 * 「回调与轮询只交付一次」由 {@code payment_intent_id} 唯一 + 交付的条件更新共同保证。
 *
 * <p><b>到账永不回滚</b>：任何路径都不向上抛 —— 向上抛会把 {@code markPaid} 一起回滚。日志只记意图 / 订单 id。
 * 不可送达时也<b>不在这里退回</b>（{@code credit} 抛异常会把本事务标成只能回滚），只置待退回并发事件，退回在 Story 1.2。
 */
@Component
public class GiftPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(GiftPaidHandler.class);

    private final GiftOrderRepository orders;
    private final GiftDeliveryService delivery;

    public GiftPaidHandler(GiftOrderRepository orders, GiftDeliveryService delivery) {
        this.orders = orders;
        this.delivery = delivery;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.GIFT) {
            return; // 非送礼，交各自的监听器
        }
        try {
            handle(event);
        } catch (RuntimeException e) {
            // 兜底：交付或仓库读写出错也不向上抛（否则 markPaid 回滚）。
            log.error("gift paid handling failed intentId={}", event.intentId(), e);
        }
    }

    private void handle(PaymentIntentPaidEvent event) {
        GiftOrder order = orders.findByPaymentIntentId(event.intentId()).orElse(null);
        if (order == null) {
            log.warn("gift paid without order intentId={}", event.intentId());
            return;
        }
        if (!order.isPending()) {
            return; // 重放幂等
        }
        GiftDeliveryService.Result result = delivery.deliver(order);
        log.info("gift paid handled intentId={} orderId={} result={}", event.intentId(), order.getId(), result);
    }
}
