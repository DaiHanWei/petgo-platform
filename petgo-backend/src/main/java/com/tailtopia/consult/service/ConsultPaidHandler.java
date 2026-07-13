package com.tailtopia.consult.service;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医咨询现金到账处理器（Story 3.4，AC4）。监听 1.1 在意图 {@code PENDING→PAID} 后发布的
 * {@link PaymentIntentPaidEvent}，按 {@code purpose} 分派——仅处理 {@code VET_CONSULT}（照 2-3
 * {@code AiUnlockPaidHandler} / {@code TopupPaidHandler} 范式）。
 *
 * <p><b>同事务原子转单（血泪护栏）</b>：用<b>同步 {@link EventListener}</b>（非 {@code @TransactionalEventListener(AFTER_COMMIT)}）：
 * {@code publishEvent} 在 {@code PaymentIntentService.applyCallback} 的 {@code @Transactional} 内同线程内联触发，本处理器
 * 以 {@code MANDATORY} <b>强制加入同一事务</b>——{@code markPaid} 与转单（建单+建会话+删 request）要么一起提交、要么一起回滚。
 * <b>绝不用 AFTER_COMMIT 异步</b>（记忆库血泪：notify AFTER_COMMIT+默认 REQUIRED 静默吞写）。
 *
 * <p>放 {@code consult} 模块（consult 已依赖 pay：{@link ConsultPayService} 用 {@code PaymentIntentService}），仅消费
 * pay 的<b>事件</b>（解耦）。幂等（applyCallback 已 PAID 不再发事件 + 转单 CAS deleteIfState）在 {@link ConsultPayService}。
 * 其余 purpose（AI_UNLOCK/PAWCOIN_TOPUP/ID_HD）忽略，不 crash。
 */
@Component
public class ConsultPaidHandler {

    private final ConsultPayService payService;

    public ConsultPaidHandler(ConsultPayService payService) {
        this.payService = payService;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.VET_CONSULT) {
            return; // 非兽医咨询意图，交各自 story 的监听器
        }
        payService.completePaidConsult(event.userId(), event.channel(), event.intentId());
    }
}
