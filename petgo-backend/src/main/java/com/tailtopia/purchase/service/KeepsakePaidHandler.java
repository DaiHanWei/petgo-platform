package com.tailtopia.purchase.service;

import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性解锁 QRIS 到账（V1.3.2 Story 3.1 · AC5）：三个新 purpose 的<b>唯一</b> {@link PaymentIntentPaidEvent} 消费者。
 *
 * <p><b>同事务</b>：同步 {@link EventListener} + {@code MANDATORY} 加入 {@code applyCallback} 的事务（照 {@code IdHdPaidHandler}，
 * <b>绝不 AFTER_COMMIT</b>）。<b>到账永不回滚</b>：本方法任何路径都不向上抛 —— 向上抛会把 {@code markPaid} 一起回滚，
 * 到账状态就丢了；宁可记 ORPHAN_PAID 交人工。
 *
 * <p>⚠️ 已知局限：若仓库读写本身在外层事务里失败（DB 故障），JPA 会先把事务标成 rollback-only，这里的兜底 catch
 * 只能保证不再向上抛，救不回提交。那属于 DB 故障，由网关回调重试兜底。
 */
@Component
public class KeepsakePaidHandler {

    private static final Logger log = LoggerFactory.getLogger(KeepsakePaidHandler.class);

    private final KeepsakePurchaseRepository purchases;
    private final KeepsakeGrantRunner grantRunner;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public KeepsakePaidHandler(KeepsakePurchaseRepository purchases, KeepsakeGrantRunner grantRunner,
            ApplicationEventPublisher events) {
        this(purchases, grantRunner, events, Clock.systemUTC());
    }

    KeepsakePaidHandler(KeepsakePurchaseRepository purchases, KeepsakeGrantRunner grantRunner,
            ApplicationEventPublisher events, Clock clock) {
        this.purchases = purchases;
        this.grantRunner = grantRunner;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (KeepsakeSku.fromPurpose(event.purpose()).isEmpty()) {
            return; // 非一次性解锁，交各自的监听器
        }
        try {
            handle(event);
        } catch (RuntimeException e) {
            // 兜底：仓库读写本身出错也不向上抛（否则 markPaid 回滚）。
            log.error("keepsake paid handling failed intentId={}", event.intentId(), e);
        }
    }

    private void handle(PaymentIntentPaidEvent event) {
        KeepsakePurchase row = purchases.findByPaymentIntentId(event.intentId()).orElse(null);
        if (row == null) {
            log.warn("keepsake paid without purchase row intentId={}", event.intentId());
            return;
        }
        if (row.getStatus().isPaid()) {
            return; // 重放幂等
        }
        row.markPaid(event.amount(), Instant.now(clock));
        purchases.saveAndFlush(row);
        GrantOutcome outcome = grantRunner.grantOrNull(row);
        if (outcome == null) {
            row.markStatus(KeepsakePurchaseStatus.ORPHAN_PAID);
        } else {
            row.applyGrantOutcome(outcome);
        }
        purchases.save(row);
        if (outcome == GrantOutcome.GRANTED) {
            events.publishEvent(new KeepsakeUnlockedEvent(row.getUserId(), row.getSku(), row.getRefId(),
                    row.getPriceIdr(), row.getPayChannel()));
        }
    }
}
