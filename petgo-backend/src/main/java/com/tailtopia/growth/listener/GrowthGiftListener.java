package com.tailtopia.growth.listener;

import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 送礼 → 成长值（V1.4.0 ① Story 1.1 · 架构 AD-6）。{@link GiftSentEvent} 由 ③ 发布、只由本线监听。
 *
 * <p>送礼人 {@code GIFT_SENT}、收礼宠物主人 {@code GIFT_RECEIVED}，按 {@code totalAmount} 比例换算（HALF_UP），不设上限；
 * 换算为 0 不入账。<b>不走消费返</b>（Story 1.3 排除 {@code GIFT}）。③ 合并前无事件，规则自然不触发。
 */
@Component
public class GrowthGiftListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthGiftListener.class);

    static final String REF_GIFT_ORDER = "GIFT_ORDER";

    private final GrowthLedgerService ledger;
    private final Clock clock;

    @Autowired
    public GrowthGiftListener(GrowthLedgerService ledger) {
        this(ledger, Clock.systemUTC());
    }

    GrowthGiftListener(GrowthLedgerService ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    public void onGiftSent(GiftSentEvent e) {
        Instant now = clock.instant();
        String ref = String.valueOf(e.giftOrderId());
        List<Long> pets = List.of(e.petId());
        accrue(e.senderId(), RuleCode.GIFT_SENT, e.totalAmount(), GrowthIdemKeys.giftSent(e.giftOrderId()), ref,
                pets, now);
        accrue(e.receiverOwnerId(), RuleCode.GIFT_RECEIVED, e.totalAmount(),
                GrowthIdemKeys.giftReceived(e.giftOrderId()), ref, pets, now);
    }

    private void accrue(long userId, RuleCode rule, long amount, String idemKey, String ref, List<Long> pets,
            Instant at) {
        try {
            int points = ledger.pointsForAmount(rule, amount);
            if (points <= 0) {
                return;
            }
            ledger.accrue(new AccrualCommand(userId, rule, points, idemKey, REF_GIFT_ORDER, ref, pets, at));
        } catch (RuntimeException ex) {
            log.error("growth gift accrue failed: userId={} rule={} error={}", userId, rule,
                    ex.getClass().getSimpleName());
        }
    }
}
