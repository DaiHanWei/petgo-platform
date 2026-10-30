package com.tailtopia.growth.listener;

import com.tailtopia.consult.event.ConsultPaidByPawCoinEvent;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthSpendTierResolver;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PawCoinSpentEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 消费 → 成长值（V1.4.0 ① Story 1.3 · 架构 AD-8）。<b>每笔消费只经下面其中一个信号</b>：
 * <ul>
 *   <li>{@link PaymentIntentPaidEvent}（现金 / QRIS）：排除 {@code SHOP_ORDER}（走电商信号）、{@code PAWCOIN_TOPUP}（充值不返）、
 *       {@code GIFT}（走送礼规则）；</li>
 *   <li>{@link PawCoinSpentEvent}（纯币）：排除 {@code SHOP_ORDER}（MIXED 币段）、{@code GIFT}、{@code VET_CONSULT}
 *       （问诊纯币的 refId 是随即被删的请求 id，改走 {@link ConsultPaidByPawCoinEvent}）；</li>
 *   <li>{@link ConsultPaidByPawCoinEvent}（问诊纯币，按订单 id）；</li>
 *   <li>{@link ShopOrderPaidEvent}（电商：QRIS / MIXED / 纯币都只经此处，金额 = 实付总额）。</li>
 * </ul>
 * {@code KeepsakeUnlockedEvent} / {@code IdHdPawcoinUnlockedEvent} <b>不监听</b>（分别与 PI / 纯币信号重复）。
 * 档位读 {@code growth_spend_tiers}（未登记 = DIGITAL）；NONE / GIFT 不入账；换算 HALF_UP，0 不入账。
 *
 * <p>写法同 {@link GrowthActivityListener}：{@code @Async} + AFTER_COMMIT，每条命令由 {@link GrowthLedgerService} 自开事务；
 * 失败只记 ERROR、不重试，绝不冒回支付事务。
 */
@Component
public class GrowthSpendListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthSpendListener.class);

    private static final Set<PaymentPurpose> PI_EXCLUDED =
            Set.of(PaymentPurpose.SHOP_ORDER, PaymentPurpose.PAWCOIN_TOPUP, PaymentPurpose.GIFT,
                    // V1.4.1 门店进货单：付款人是门店，意图 user_id 不是 App 用户（见 PurchaseOrderPayer），绝不加成长值。
                    PaymentPurpose.MERCHANT_PO);
    private static final Set<String> COIN_EXCLUDED = Set.of(PaymentPurpose.SHOP_ORDER.name(),
            PaymentPurpose.GIFT.name(), PaymentPurpose.VET_CONSULT.name());

    private final GrowthLedgerService ledger;
    private final GrowthSpendTierResolver tiers;
    private final Clock clock;

    @Autowired
    public GrowthSpendListener(GrowthLedgerService ledger, GrowthSpendTierResolver tiers) {
        this(ledger, tiers, Clock.systemUTC());
    }

    GrowthSpendListener(GrowthLedgerService ledger, GrowthSpendTierResolver tiers, Clock clock) {
        this.ledger = ledger;
        this.tiers = tiers;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    public void onIntentPaid(PaymentIntentPaidEvent e) {
        if (e.purpose() == null || PI_EXCLUDED.contains(e.purpose())) {
            return;
        }
        spend(e.userId(), e.purpose().name(), e.amount(), GrowthIdemKeys.spendIntent(e.intentId()),
                e.purpose().name(), String.valueOf(e.intentId()));
    }

    @Async
    @TransactionalEventListener
    public void onCoinSpent(PawCoinSpentEvent e) {
        if (e.refType() == null || COIN_EXCLUDED.contains(e.refType())) {
            return;
        }
        spend(e.userId(), e.refType(), e.coins(), GrowthIdemKeys.spendCoin(e.idempotencyKey()), e.refType(),
                e.refId() == null ? null : String.valueOf(e.refId()));
    }

    @Async
    @TransactionalEventListener
    public void onConsultPaidByCoin(ConsultPaidByPawCoinEvent e) {
        String purpose = PaymentPurpose.VET_CONSULT.name();
        spend(e.userId(), purpose, e.amount(), GrowthIdemKeys.spendConsultCoin(e.consultOrderId()), purpose,
                String.valueOf(e.consultOrderId()));
    }

    @Async
    @TransactionalEventListener
    public void onShopOrderPaid(ShopOrderPaidEvent e) {
        String purpose = PaymentPurpose.SHOP_ORDER.name();
        spend(e.userId(), purpose, e.paidTotal(), GrowthIdemKeys.spendShop(e.shopOrderId()), purpose,
                String.valueOf(e.shopOrderId()));
    }

    private void spend(long userId, String purpose, long amount, String idemKey, String refType, String refId) {
        RuleCode rule = null;
        try {
            rule = tiers.ruleFor(purpose);
            if (rule == null) {
                return; // NONE / GIFT 档
            }
            int points = ledger.pointsForAmount(rule, amount);
            if (points <= 0) {
                return;
            }
            Instant now = clock.instant();
            ledger.accrue(new AccrualCommand(userId, rule, points, idemKey, refType, refId, null, now));
        } catch (RuntimeException ex) {
            log.error("growth spend accrue failed: userId={} rule={} error={}", userId, rule,
                    ex.getClass().getSimpleName());
        }
    }
}
