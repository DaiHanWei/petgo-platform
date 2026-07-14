package com.tailtopia.profile.service;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 身份证高清图现金到账处理器（Story 6.3 · AC2）。监听 1.1 在意图 {@code PENDING→PAID} 后发布的
 * {@link PaymentIntentPaidEvent}，仅处理 {@code ID_HD}（照 {@code TopupPaidHandler}/{@code AiUnlockPaidHandler} 范式）。
 *
 * <p><b>同事务原子建购买（血泪护栏）</b>：同步 {@link EventListener} + {@code MANDATORY} 强制加入
 * {@code applyCallback} 的事务——{@code markPaid} 与建购买行要么一起提交、要么一起回滚。<b>绝不 AFTER_COMMIT</b>
 * （记忆库血泪：AFTER_COMMIT + 默认 REQUIRED 静默吞写会丢账/丢解锁）。幂等在 {@link IdCardHdService#completePurchase}。
 * 其余 purpose 忽略、不 crash。
 */
@Component
public class IdHdPaidHandler {

    private final IdCardHdService idCardHdService;

    public IdHdPaidHandler(IdCardHdService idCardHdService) {
        this.idCardHdService = idCardHdService;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.ID_HD) {
            return; // 非身份证高清意图，交各自 story 的监听器
        }
        idCardHdService.completePurchase(event.userId(), event.channel(), event.intentId());
    }
}
