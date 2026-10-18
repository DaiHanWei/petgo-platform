package com.tailtopia.gift.service;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.event.GiftRefundedEvent;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 付款后无法送达的订单退回 PawCoin（V1.4.0 gift Story 1.2 · AD-9 / P-1 / P-2）。
 *
 * <p>退回给<b>送礼人自己</b>（单一 userId）：{@code wallet.credit(sender, total, TOPUP, "GIFT_REFUND", orderId,
 * GIFT_REFUND:{orderToken})}，总账走 {@code CASH_IN → FLOAT_LIABILITY}（这笔钱以 QRIS 现金进来，参照充值）。
 * 钱包服务<b>零改动</b>，只调用。
 *
 * <p>两个方法各自 {@code REQUIRES_NEW}，且都在行锁下只认 {@code UNDELIVERED_REFUND_PENDING}：
 * <ul>
 *   <li>{@link #attempt}：入账 + 置 {@code UNDELIVERED_REFUNDED} + 发 {@link GiftRefundedEvent} 同一事务；
 *       {@code credit} 抛任何异常 → 本事务整体回滚（异常原样抛给调用方）。</li>
 *   <li>{@link #recordFailure}：调用方在<b>另一个</b>新事务里记失败（不能与失败的那次同事务 —— 它已是 rollback-only）；
 *       第 {@value #MAX_ATTEMPTS} 次失败置 {@code REFUND_MANUAL}（后台异常单「需人工」）。</li>
 * </ul>
 * 退回幂等三重：行锁串行化 + 状态守卫 + {@code credit} 的幂等键重放短路 —— 同一订单无论触发多少次只入账一次。
 *
 * <p>无送礼成长值：全程不发 {@code GiftSentEvent}，也不发任何成长值事件。
 */
@Service
public class GiftRefundService {

    private static final Logger log = LoggerFactory.getLogger(GiftRefundService.class);

    /** 退回最多尝试次数（含首次触发那一次，P-2）。 */
    public static final int MAX_ATTEMPTS = 5;

    /** 钱包流水 {@code ref_type}（App 按它显示「Hadiah dikembalikan」）。 */
    public static final String REFUND_REF_TYPE = "GIFT_REFUND";

    private final GiftOrderRepository orders;
    private final PawCoinWalletService wallet;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public GiftRefundService(GiftOrderRepository orders, PawCoinWalletService wallet,
            ApplicationEventPublisher events) {
        this(orders, wallet, events, Clock.systemUTC());
    }

    GiftRefundService(GiftOrderRepository orders, PawCoinWalletService wallet, ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.wallet = wallet;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 尝试退回一笔订单。返回 true = 本次退回成功；false = 订单不存在或已非待退回（幂等，什么都不做）。
     *
     * @throws RuntimeException {@code credit} 失败（本事务已回滚），调用方须调 {@link #recordFailure}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attempt(long giftOrderId) {
        GiftOrder order = orders.findByIdForUpdate(giftOrderId).orElse(null);
        if (order == null || !order.isRefundPending()) {
            return false;
        }
        wallet.credit(order.getSenderId(), order.getTotal(), PawCoinTxnType.TOPUP, REFUND_REF_TYPE, order.getId(),
                GiftIdemKeys.refund(order.getPublicToken()));
        order.markRefunded(Instant.now(clock));
        orders.save(order);
        events.publishEvent(new GiftRefundedEvent(order.getId()));
        log.info("gift order refunded orderId={} total={}", order.getId(), order.getTotal());
        return true;
    }

    /**
     * 记一次退回失败（只存异常简单类名，不存 message）。第 {@value #MAX_ATTEMPTS} 次置 {@code REFUND_MANUAL}。
     * 订单不存在或已非待退回 → 什么都不做。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long giftOrderId, String errorClass) {
        GiftOrder order = orders.findByIdForUpdate(giftOrderId).orElse(null);
        if (order == null || !order.recordRefundFailure(errorClass, MAX_ATTEMPTS)) {
            return;
        }
        orders.save(order);
        log.warn("gift refund failed orderId={} attempts={} status={} cause={}", order.getId(),
                order.getRefundAttempts(), order.getStatus(), order.getRefundError());
    }
}
