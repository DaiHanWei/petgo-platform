package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电商订单到账处理（Story 3.8）。监听 {@code PaymentIntentService.applyCallback} 在
 * {@code PENDING→PAID} 后发布的 {@link PaymentIntentPaidEvent}，只处理 {@code SHOP_ORDER}。
 *
 * <p>🔴 <b>同步 {@link EventListener} + {@code MANDATORY}，与意图推进在同一事务</b>
 * （照 {@code TopupPaidHandler} 的血泪范式）：{@code markPaid}、PawCoin 段扣减、库存扣减、
 * 状态迁移<b>要么一起提交、要么一起回滚</b>。
 * <b>绝不用 {@code AFTER_COMMIT} 异步</b> —— notify 曾因此让 INSERT 静默不提交，
 * 资金与库存重蹈会同时丢账和丢货。
 *
 * <p>🔴 <b>幂等三重</b>：① 意图已终态时 {@code applyCallback} 直接返回、根本不发事件；
 * ② {@link ShopOrderPaymentService#fulfillPaid} 见到非待支付状态就返回（回调与轮询同时到达时，
 * 后到者看到的已是 {@code PENDING_SHIPMENT}，一件库存也不会被扣第二次）；
 * ③ PawCoin 扣减走钱包自带的幂等键 {@code shop-order:{orderToken}}。
 */
@Component
public class ShopOrderPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderPaidHandler.class);

    private final ShopOrderRepository orders;
    private final ShopOrderPaymentService payments;
    private final CheckoutService checkout;

    public ShopOrderPaidHandler(ShopOrderRepository orders, ShopOrderPaymentService payments,
            CheckoutService checkout) {
        this.orders = orders;
        this.payments = payments;
        this.checkout = checkout;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.SHOP_ORDER) {
            return;     // 其余 purpose 由各自 story 的处理器接
        }
        ShopOrder order = orders.findByPaymentIntentToken(event.publicToken()).orElse(null);
        if (order == null) {
            // 不改任何状态、不抛：找不到订单的到账只可能是脏数据或跨环境回调，
            // 抛异常会把整个回调事务连同意图的 markPaid 一起回滚 —— 那才是真的丢账。
            log.warn("电商到账事件无匹配订单，忽略 intent={}", event.publicToken());
            return;
        }
        // 🔴 顺序：先扣 Coin 段再扣库存 —— 两者同事务，失败一起回滚，顺序不影响正确性；
        //    但 Coin 段失败（余额被别处花掉）时不该已经把库存扣了，日志上也更好读。
        checkout.settlePawCoinSegment(order);
        payments.fulfillPaid(order);
        log.info("电商订单支付到账 order={} intent={}", order.getPublicToken(), event.publicToken());
    }
}
