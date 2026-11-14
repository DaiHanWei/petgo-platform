package com.tailtopia.starterpack.listener;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackPurchaseStatus;
import com.tailtopia.starterpack.event.StarterPackPaidEvent;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 礼包 QRIS 到账（V1.5.0 gift-pack Story 4-2 · AC5.1 · D-24，照 {@code GiftPaidHandler}）：在回调事务里（MANDATORY）
 * <b>只</b>把购买置 PAID、写 {@code paid_at} / {@code purchase_index}，发布 {@link StarterPackPaidEvent}；
 * 发放由 {@code StarterPackFulfillmentListener} 提交后执行。
 *
 * <p>{@code PENDING / EXPIRED / FAILED} 都置 PAID（过窗后才到账照样发货，记 INFO）；已 PAID → 什么都不做（重放幂等）。
 * 异常只记 ERROR、不向上抛（否则意图 markPaid 回滚）。
 */
@Component
public class StarterPackPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(StarterPackPaidHandler.class);

    private final StarterPackPurchaseRepository purchases;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public StarterPackPaidHandler(StarterPackPurchaseRepository purchases, ApplicationEventPublisher events) {
        this(purchases, events, Clock.systemUTC());
    }

    StarterPackPaidHandler(StarterPackPurchaseRepository purchases, ApplicationEventPublisher events, Clock clock) {
        this.purchases = purchases;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.STARTER_PACK) {
            return; // 非礼包，交各自的监听器
        }
        try {
            handle(event);
        } catch (RuntimeException e) {
            log.error("starter pack paid handling failed intentId={}", event.intentId(), e);
        }
    }

    private void handle(PaymentIntentPaidEvent event) {
        StarterPackPurchase p = purchases.findByPaymentIntentId(event.intentId()).orElse(null);
        if (p == null) {
            log.warn("starter pack paid without purchase intentId={}", event.intentId());
            return;
        }
        // 与下单同一把用户锁（purchase_index 不重号，4-2 复审 #2），再锁行重读（与过期关单串行，4-2 复审 #1）。
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", "starter_pack:" + p.getUserId()).getSingleResult();
        entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        StarterPackPurchaseStatus was = p.getStatus();
        if (was == StarterPackPurchaseStatus.PAID) {
            return; // 重放幂等
        }
        int index = (int) purchases.countByUserIdAndStatus(p.getUserId(), StarterPackPurchaseStatus.PAID) + 1;
        p.markPaid(clock.instant(), index);
        purchases.saveAndFlush(p);
        if (was != StarterPackPurchaseStatus.PENDING) {
            log.info("starter pack paid after close, fulfilling anyway userId={} purchaseId={} was={}",
                    p.getUserId(), p.getId(), was);
        } else {
            log.info("starter pack paid userId={} purchaseId={} tier={}", p.getUserId(), p.getId(), p.getTier());
        }
        events.publishEvent(new StarterPackPaidEvent(p.getId()));
    }
}
