package com.tailtopia.gift.listener;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftDeliveryService;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>OCR #7：订单已关（EXPIRED / FAILED）却收到到账 —— 不静默丢弃：同样置待退回 + 发 {@link GiftUndeliveredEvent}，
 * 走既有退回路径（QRIS 现金按送礼退回口径退成 PawCoin，提交后由 {@code GiftUndeliveredListener} 退），并记 WARN（只记 id）。
 */
@Component
public class GiftPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(GiftPaidHandler.class);

    private final GiftOrderRepository orders;
    private final GiftDeliveryService delivery;
    private final ApplicationEventPublisher events;

    public GiftPaidHandler(GiftOrderRepository orders, GiftDeliveryService delivery,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.delivery = delivery;
        this.events = events;
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
        if (order.getStatus() == GiftOrderStatus.EXPIRED || order.getStatus() == GiftOrderStatus.FAILED) {
            refundPaidAfterClose(event, order);
            return;
        }
        if (!order.isPending()) {
            return; // 重放幂等
        }
        GiftDeliveryService.Result result = delivery.deliver(order);
        log.info("gift paid handled intentId={} orderId={} result={}", event.intentId(), order.getId(), result);
    }

    /** 关单后到账 → 待退回 + 退回事件（条件更新：0 行 = 已被并发处理，什么都不发）。 */
    private void refundPaidAfterClose(PaymentIntentPaidEvent event, GiftOrder order) {
        GiftOrderStatus was = order.getStatus();
        if (orders.markRefundPendingIfClosed(order.getId()) == 0) {
            return;
        }
        log.warn("gift paid after order closed, refunding to PawCoin intentId={} orderId={} was={}",
                event.intentId(), order.getId(), was);
        events.publishEvent(new GiftUndeliveredEvent(order.getId()));
    }
}
