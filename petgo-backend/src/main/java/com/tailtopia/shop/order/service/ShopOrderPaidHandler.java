package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.time.Instant;
import java.util.Objects;
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
 * <p>🔴 v1.4.2 shop Story 3.2（S-3）：<b>PawCoin 在提交订单时已扣</b>，新单（{@code checkout_source} 非空）
 * 到账只做「已付款」迁移（{@code markPaid(order, GATEWAY)}），<b>不再扣币</b> —— 提交后用户把剩余币花光，
 * 到账也不会因币不足让已到账的回调回滚。存量单（{@code checkout_source IS NULL}，币尚未扣）按旧规则在这里扣
 * （过渡，{@code TODO(v1.4.3)} 删除）。
 *
 * <p>🔴 <b>同步 {@link EventListener} + {@code MANDATORY}，与意图推进在同一事务</b>
 * （照 {@code TopupPaidHandler} 的血泪范式）：{@code markPaid}、库存扣减、状态迁移（及存量单的 PawCoin 段扣减）
 * <b>要么一起提交、要么一起回滚</b>。
 * <b>绝不用 {@code AFTER_COMMIT} 异步</b> —— notify 曾因此让 INSERT 静默不提交，
 * 资金与库存重蹈会同时丢账和丢货。
 *
 * <p>🔴 <b>幂等三重</b>：① 意图已终态时 {@code applyCallback} 直接返回、根本不发事件；
 * ② {@link ShopOrderPaymentService#markPaid} 见到非待支付状态就返回（回调与轮询同时到达时，
 * 后到者看到的已是 {@code PENDING_SHIPMENT}，一件库存也不会被扣第二次）；
 * ③ 存量单的 PawCoin 扣减走钱包自带的幂等键 {@code shop-order:{orderToken}}（与提交时扣同一个键）。
 *
 * <p>🔴 v1.4.2 shop Story 3.3（AC6.2，I1）—— <b>按订单状态分支，迟到款不再丢弃</b>：
 * <ul>
 *   <li>找订单：意图的 {@code shop_order_id} → 订单；为空回退 {@code payment_intent_token}（存量）；都找不到 →
 *       ERROR + 飞书，<b>不抛</b>（抛会回滚意图 PAID，那才是真丢账）。</li>
 *   <li>{@code PENDING_PAYMENT}（含宽限期、含 {@code paid_after_close} 意图）→ {@code markPaid(GATEWAY)} 转待发货（S-7）。</li>
 *   <li>{@code CANCELLED} → 记迟到款到原单（{@code late_paid_*} 条件更新），不改状态、不动库存 / 券 / 币。</li>
 *   <li>其他状态（已被另一意图付过 = 重复付款）→ 同样记录 + ERROR + 飞书；4.2 只允许对这类单退款。</li>
 * </ul>
 * 迟到款分支任何意外都 catch 后 ERROR，不向上抛。
 */
@Component
public class ShopOrderPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderPaidHandler.class);

    private final ShopOrderRepository orders;
    private final ShopOrderPaymentService payments;
    private final CheckoutService checkout;
    private final PaymentIntentService paymentIntents;
    private final ShopPaymentAlerts alerts;
    /** V1.4.2 shop-admin 7.2 · AD-5 / AD-11：迟到款落库后提醒客服（CS，提交后发送，去重键 late-paid:{orderToken}）。 */
    private final com.tailtopia.admin.alert.OpsAlertService opsAlerts;

    public ShopOrderPaidHandler(ShopOrderRepository orders, ShopOrderPaymentService payments,
            CheckoutService checkout, PaymentIntentService paymentIntents, ShopPaymentAlerts alerts,
            com.tailtopia.admin.alert.OpsAlertService opsAlerts) {
        this.opsAlerts = opsAlerts;
        this.orders = orders;
        this.payments = payments;
        this.checkout = checkout;
        this.paymentIntents = paymentIntents;
        this.alerts = alerts;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.SHOP_ORDER) {
            return;     // 其余 purpose 由各自 story 的处理器接
        }
        PaymentIntent intent = paymentIntents.findById(event.intentId()).orElse(null);
        ShopOrder order = locate(event, intent);
        if (order == null) {
            // 不改任何状态、不抛：抛异常会把整个回调事务连同意图的 markPaid 一起回滚 —— 那才是真的丢账。
            // 这是唯一可能「钱进来了系统不认」的路径，必须有人知道（拍板③）。
            log.error("电商到账找不到订单 intentId={}", event.intentId());
            alerts.alert(com.tailtopia.admin.alert.OpsChannel.TECH, "有一笔电商到账找不到订单：intentId=" + event.intentId());
            return;
        }
        if (order.getStatus() == ShopOrderStatus.PENDING_PAYMENT) {
            // 🔴 顺序：**先状态迁移、确认订单确实还在待支付** —— 与取消 / 关单事务的真并发由
            //    shop_orders.version 乐观锁裁决（后提交者整体回滚）。宽限期内到账同样走这里（S-7 变待发货）。
            if (!payments.markPaid(order, PaidSource.GATEWAY)) {
                log.warn("电商到账事件订单已非待支付（已取消/已处理），跳过 order={} intent={}",
                        order.getPublicToken(), event.publicToken());
                return;
            }
            if (!order.isCoinDebitedAtSubmit()) {
                // TODO(v1.4.3)：上线过渡规则 —— 存量待付款单（checkout_source IS NULL）币尚未扣，到账时按旧规则扣；
                //    同事务，余额不足整体回滚（存量行为不变）。过渡期 ≤ 75 分钟，下一版本删除。
                checkout.settlePawCoinSegment(order);
            }
            log.info("电商订单支付到账 order={} intent={}", order.getPublicToken(), event.publicToken());
            // 🔴 Story 3-4 的「登记待提醒」已于 v1.3.0 shop-v2 复审 #6 / #13 挪走：
            //    改由 ShopOrderPaidNotifyListener 监听 ShopOrderPaidEvent（AFTER_COMMIT）完成。
            //    ① #6：纯 PawCoin 单走 settlePureCoin，**根本不到这个方法**，挂在这里等于
            //       整条纯币路径永不入队；挂在状态迁移发出的事件上才两条都覆盖。
            //    ② #13：在本事务内 REQUIRES_NEW 抢先提交，外层一旦回滚就留下指向
            //       「从未付款成功」订单的孤儿提醒行；AFTER_COMMIT 从机制上消除它。
            //    **不要把它加回来。**
            return;
        }
        try {
            recordLate(order, intent, event);
        } catch (RuntimeException e) {
            log.error("电商迟到款记录失败 intentId={} cause={}", event.intentId(), e.getClass().getSimpleName());
        }
    }

    /** 找订单：意图的 shop_order_id 优先，存量回退 payment_intent_token。 */
    private ShopOrder locate(PaymentIntentPaidEvent event, PaymentIntent intent) {
        if (intent != null && intent.getShopOrderId() != null) {
            ShopOrder byId = orders.findById(intent.getShopOrderId()).orElse(null);
            if (byId != null) {
                return byId;
            }
        }
        return orders.findByPaymentIntentToken(event.publicToken()).orElse(null);
    }

    /**
     * 记迟到款（CANCELLED）/ 重复付款（其他非待支付状态）：{@code amount} = 网关实收 = 意图现金段（MIXED）或总额（QRIS）。
     * 不改订单状态，不动库存 / 券 / 币；处置（重新下单 / 退款）归 4.2。
     */
    private void recordLate(ShopOrder order, PaymentIntent intent, PaymentIntentPaidEvent event) {
        long amount = gatewayReceived(intent, event);
        boolean duplicate = order.getStatus() != ShopOrderStatus.CANCELLED;
        int rows = orders.recordLatePayment(order.getId(), amount, Instant.now(), event.intentId());
        if (rows == 0) {
            if (!Objects.equals(order.getLatePaidIntentId(), event.intentId())) {
                log.error("同一订单第二笔迟到款 displayNo={} intentId={}", order.getDisplayNo(), event.intentId());
                alerts.alert(com.tailtopia.admin.alert.OpsChannel.TECH, "同一订单第二笔迟到款：" + order.getDisplayNo() + "，intentId=" + event.intentId()
                        + "，请人工处理");
            }
            return;
        }
        opsAlerts.send(com.tailtopia.admin.alert.OpsChannel.CS, "late-paid:" + order.getPublicToken(),
                (duplicate ? "[商城] 订单 %s 重复付款 %s（订单仍在履约），请到「订单」页退还到账款"
                        : "[商城] 订单 %s 取消后到账 %s，请联系用户后到「订单」页重新下单或退还到账款")
                        .formatted(order.getDisplayNo(), com.tailtopia.shop.logistics.balance.BiteshipBalanceService.rp(amount)));
        if (duplicate) {
            log.error("电商订单重复付款 displayNo={} status={} intentId={}", order.getDisplayNo(),
                    order.getStatus(), event.intentId());
            alerts.alert(com.tailtopia.admin.alert.OpsChannel.TECH, "电商订单重复付款：" + order.getDisplayNo() + "（" + order.getStatus() + "），intentId="
                    + event.intentId() + "，请人工处理");
        } else {
            log.info("电商订单关单后到账已记迟到款 order={} amount={}", order.getPublicToken(), amount);
        }
    }

    /** 网关实收：MIXED 取现金段（币段站内扣，不经网关），否则取意图总额。 */
    static long gatewayReceived(PaymentIntent intent, PaymentIntentPaidEvent event) {
        if (intent != null) {
            if (intent.getChannel() == PayChannel.MIXED && intent.getCashAmount() != null) {
                return intent.getCashAmount();
            }
            return intent.getAmount();
        }
        return event.amount();
    }
}
