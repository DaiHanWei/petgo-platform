package com.tailtopia.profile.slot;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
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
 * 宠物名额月租 QRIS 到账（V1.4.0 multi-pet Story 2.2 · AC6 · AD-6，照 {@code KeepsakePaidHandler}）：
 * {@code PET_SLOT_RENT} 的<b>唯一</b>发放型 {@link PaymentIntentPaidEvent} 消费者（① 的成长值监听除外，守卫测试
 * {@code PetSlotSingleConsumerSourceGuardTest}）。
 *
 * <p><b>同事务</b>：同步 {@link EventListener} + {@code MANDATORY} 加入 {@code PaymentIntentService.applyCallback} 的事务
 * （<b>绝不 AFTER_COMMIT</b>）。回调与对账轮询同走 {@code applyCallback}：Redis 前置 + 终态守卫 + {@code gateway_ref} 唯一，
 * 再加这里的「已结算」守卫与 {@code payment_intent_id} 唯一 —— 只生效一次（AC12）。
 *
 * <p><b>到账永不回滚</b>（code review #1）：
 * <ul>
 *   <li>发放经 {@link PetSlotGranter}：纯 JDBC + 保存点，失败只回滚到保存点 → 本行记 {@code ORPHAN_PAID} 交人工；</li>
 *   <li>发放会让名额超 5 → 不发、记 {@code OVERPAID} 交人工退款；</li>
 *   <li>reconcile 不在本事务里做：发 {@link PetSlotGrantedEvent}，由 {@link PetSlotGrantedReconcileListener}
 *       提交后另开事务执行。</li>
 * </ul>
 * 未结算的行（PENDING，或同键重开时被置 EXPIRED / 失败回调置 FAILED 但其意图仍到账）一律照常发放。
 * ⚠️ 已知局限（同 {@code KeepsakePaidHandler}）：购买行的 JPA 读 / 提交时的 flush 本身失败（DB 故障）仍会让整个到账事务回滚，
 * 由网关回调重试 / 对账轮询兜底。日志只记 intentId / purchaseToken。
 */
@Component
public class PetSlotPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(PetSlotPaidHandler.class);

    private final PetSlotPurchaseRepository purchases;
    private final PetSlotGranter granter;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public PetSlotPaidHandler(PetSlotPurchaseRepository purchases, PetSlotGranter granter,
            ApplicationEventPublisher events) {
        this(purchases, granter, events, Clock.systemUTC());
    }

    PetSlotPaidHandler(PetSlotPurchaseRepository purchases, PetSlotGranter granter, ApplicationEventPublisher events,
            Clock clock) {
        this.purchases = purchases;
        this.granter = granter;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.PET_SLOT_RENT) {
            return; // 非月租，交各自的监听器
        }
        String purchaseToken = null;
        try {
            PetSlotPurchase row = purchases.findByPaymentIntentId(event.intentId()).orElse(null);
            if (row == null) {
                log.warn("pet slot paid without purchase row intentId={}", event.intentId());
                return;
            }
            purchaseToken = row.getPublicToken();
            if (row.getStatus().isSettled()) {
                return; // 重放幂等
            }
            Instant now = Instant.now(clock);
            PetSlotGranter.Result result = grantOrNull(row, now, event.intentId());
            if (result == null) {
                row.settle(PetSlotPurchaseStatus.ORPHAN_PAID, now);
            } else if (!result.granted()) {
                row.settle(PetSlotPurchaseStatus.OVERPAID, now);
                log.warn("pet slot paid but over cap, no slots granted intentId={} purchaseToken={}",
                        event.intentId(), purchaseToken);
            } else {
                row.settle(PetSlotPurchaseStatus.PAID, now);
                row.setRentalIds(result.rentalIds());
            }
            purchases.save(row);
            if (result != null && result.granted()) {
                events.publishEvent(new PetSlotGrantedEvent(row.getUserId(), result.trigger()));
            }
        } catch (RuntimeException e) {
            // 兜底：不向上抛（否则 markPaid 回滚）。只记 id / token，不记 PII。
            log.error("pet slot paid handling failed intentId={} purchaseToken={}", event.intentId(), purchaseToken, e);
        }
    }

    /** 发放；失败（保存点已回滚）→ 记 error 返回 null，<b>不向上抛</b>（照 {@code KeepsakeGrantRunner.grantOrNull}）。 */
    private PetSlotGranter.Result grantOrNull(PetSlotPurchase row, Instant now, long intentId) {
        try {
            return granter.grant(row, now);
        } catch (RuntimeException e) {
            log.error("pet slot grant failed intentId={} purchaseToken={}", intentId, row.getPublicToken(), e);
            return null;
        }
    }
}
