package com.tailtopia.triage.service;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 解锁现金到账处理器（Story 2.3，AC4）。监听 1.1 在意图 {@code PENDING→PAID} 后发布的
 * {@link PaymentIntentPaidEvent}，按 {@code purpose} 分派——仅处理 {@code AI_UNLOCK}（照 {@code TopupPaidHandler} 范式）。
 *
 * <p><b>同事务原子解锁（血泪护栏）</b>：用<b>同步 {@link EventListener}</b>（非 {@code @TransactionalEventListener(AFTER_COMMIT)}）：
 * {@code publishEvent} 在 {@code PaymentIntentService.applyCallback} 的 {@code @Transactional} 内同线程内联触发，本处理器
 * 以 {@code MANDATORY} <b>强制加入同一事务</b>——{@code markPaid} 与解锁（{@code task.unlock} + 订单 COMPLETED）
 * 要么一起提交、要么一起回滚。<b>绝不用 AFTER_COMMIT 异步</b>（记忆库血泪：notify AFTER_COMMIT+默认 REQUIRED 静默吞写）。
 *
 * <p>放 {@code triage} 模块（triage 已依赖 pay：{@link AiUnlockService} 用 {@code PaymentIntentService}），避免新增
 * pay→triage 依赖；仅消费 pay 的<b>事件</b>（解耦）。幂等（订单已 COMPLETED 短路 + applyCallback 已 PAID 不再发事件）
 * 全在 {@link AiUnlockService#completeCashUnlock}。其余 purpose（PAWCOIN_TOPUP/VET_CONSULT/ID_HD）忽略，不 crash。
 */
@Component
public class AiUnlockPaidHandler {

    private final AiUnlockService aiUnlockService;

    public AiUnlockPaidHandler(AiUnlockService aiUnlockService) {
        this.aiUnlockService = aiUnlockService;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.AI_UNLOCK) {
            return; // 非 AI 解锁意图，交各自 story 的监听器
        }
        aiUnlockService.completeCashUnlock(event.publicToken());
    }
}
