package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PawCoin 充值到账处理器（Story 1.3，AC3 最高风险点）。监听 1.1 在意图 {@code PENDING→PAID} 后发布的
 * {@link PaymentIntentPaidEvent}，按 {@code purpose} 分派——仅处理 {@code PAWCOIN_TOPUP}。
 *
 * <p><b>同事务原子入账（血泪护栏）</b>：用 <b>同步 {@link EventListener}</b>（非
 * {@code @TransactionalEventListener(AFTER_COMMIT)}）——{@code ApplicationEventPublisher.publishEvent}
 * 在 {@code PaymentIntentService.applyCallback} 的 {@code @Transactional} 内<b>同线程内联触发</b>，本处理器
 * 以 {@code REQUIRES}（默认 REQUIRED）<b>加入同一事务</b>：{@code markPaid} 与 {@code credit}（原子改钱包 +
 * 平衡分录 + 写流水）<b>要么一起提交、要么一起回滚</b>。
 *
 * <p><b>绝不用 AFTER_COMMIT 异步入账</b>——记忆库血泪：notify 曾用 {@code AFTER_COMMIT + 默认 REQUIRED}
 * 致 INSERT 静默不提交（只涨角标不进中心）；资金入账重蹈将丢账。
 *
 * <p><b>幂等</b>：credit 幂等键 = {@code intent.publicToken}（1.2 IdempotencyService + 总账唯一约束去重）；
 * 且 1.1 {@code applyCallback} 对已 PAID 意图直接返回、不再发事件——回调重放/双通道<b>绝不重复入账</b>。
 * {@code FAILED/EXPIRED} 不发本事件（1.1 只在 PAID 发），故失败态<b>不 credit、余额不变</b>（AC4）。
 *
 * <p>其余 purpose（VET_CONSULT/AI_UNLOCK/ID_HD）由各自 story 的监听器接（3.4/2.3/6.3）；本处理器忽略，
 * 未接分支不 crash。
 */
@Component
public class TopupPaidHandler {

    private static final Logger log = LoggerFactory.getLogger(TopupPaidHandler.class);
    private static final String REF_TYPE = "PAYMENT_INTENT";

    private final PawCoinWalletService walletService;

    public TopupPaidHandler(PawCoinWalletService walletService) {
        this.walletService = walletService;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.PAWCOIN_TOPUP) {
            return; // 非充值意图，交各自 story 的监听器
        }
        // 同一事务内入账（credit 内部：原子改钱包 + LedgerService.post 平衡分录 + 写 pawcoin_transactions）。
        // coins == amount（1 koin=Rp1）；幂等键=publicToken，重放不双入账。
        walletService.credit(event.userId(), event.amount(), PawCoinTxnType.TOPUP,
                REF_TYPE, event.intentId(), event.publicToken());
        log.info("PawCoin 充值到账: intent={} coins={}", event.publicToken(), event.amount());
    }
}
