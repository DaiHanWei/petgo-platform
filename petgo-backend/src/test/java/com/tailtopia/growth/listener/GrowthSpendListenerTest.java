package com.tailtopia.growth.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.event.ConsultPaidByPawCoinEvent;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthPoints;
import com.tailtopia.growth.service.GrowthSpendTierResolver;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PawCoinSpentEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V1.4.0 ① Story 1.3 · AC3 / AC4（L0）：四个消费信号的过滤、金额换算、幂等键与 ref。 */
class GrowthSpendListenerTest {

    private final GrowthLedgerService ledger = mock(GrowthLedgerService.class);
    private final GrowthSpendTierResolver tiers = mock(GrowthSpendTierResolver.class);
    private final GrowthSpendListener listener = new GrowthSpendListener(ledger, tiers);

    @BeforeEach
    void setUp() {
        when(tiers.ruleFor(anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(0)) {
            case "SHOP_ORDER" -> RuleCode.SPEND_SHOP;
            case "PAWCOIN_TOPUP", "GIFT" -> null;
            default -> RuleCode.SPEND_DIGITAL;
        });
        // 照 seed：SPEND_DIGITAL 5/1000、SPEND_SHOP 5/10000。
        when(ledger.pointsForAmount(any(), anyLong())).thenAnswer(inv -> {
            RuleCode r = inv.getArgument(0);
            long amount = inv.getArgument(1);
            return GrowthPoints.ofAmount(amount, 5, r == RuleCode.SPEND_SHOP ? 10_000 : 1_000);
        });
    }

    private AccrualCommand accrued() {
        ArgumentCaptor<AccrualCommand> c = ArgumentCaptor.forClass(AccrualCommand.class);
        verify(ledger).accrue(c.capture());
        return c.getValue();
    }

    private static PaymentIntentPaidEvent paid(long id, PaymentPurpose purpose, long amount) {
        return new PaymentIntentPaidEvent(id, "tok", 7L, purpose, PayChannel.QRIS, amount, "IDR");
    }

    @Test
    void intentPaidForDigitalPurposeAccruesKeyedByIntent() {
        listener.onIntentPaid(paid(42L, PaymentPurpose.TAILSONALITY, 20_000));

        AccrualCommand c = accrued();
        assertThat(c.rule()).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(c.pointsOverride()).isEqualTo(100);
        assertThat(c.idemKey()).isEqualTo("SPEND:PI:42");
        assertThat(c.refType()).isEqualTo("TAILSONALITY");
        assertThat(c.refId()).isEqualTo("42");
        assertThat(c.userId()).isEqualTo(7L);
    }

    @Test
    void intentPaidForShopTopupOrGiftIsIgnored() {
        listener.onIntentPaid(paid(1L, PaymentPurpose.SHOP_ORDER, 200_000));
        listener.onIntentPaid(paid(2L, PaymentPurpose.PAWCOIN_TOPUP, 50_000));
        listener.onIntentPaid(paid(3L, PaymentPurpose.GIFT, 12_500));

        verify(ledger, never()).accrue(any());
    }

    @Test
    void pureCoinKtpAccruesKeyedByDebitKey() {
        listener.onCoinSpent(new PawCoinSpentEvent(7L, 20_000, "ID_HD", 5L, "id-hd-card:5"));

        AccrualCommand c = accrued();
        assertThat(c.rule()).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(c.pointsOverride()).isEqualTo(100);
        assertThat(c.idemKey()).isEqualTo("SPEND:COIN:id-hd-card:5");
        assertThat(c.refType()).isEqualTo("ID_HD");
        assertThat(c.refId()).isEqualTo("5");
    }

    @Test
    void coinSpentForShopGiftOrConsultIsIgnored() {
        listener.onCoinSpent(new PawCoinSpentEvent(7L, 100_000, "SHOP_ORDER", 1L, "shop:1"));
        listener.onCoinSpent(new PawCoinSpentEvent(7L, 12_500, "GIFT", 2L, "gift:2"));
        listener.onCoinSpent(new PawCoinSpentEvent(7L, 50_000, "VET_CONSULT", 3L, "vet-consult:r"));

        verify(ledger, never()).accrue(any());
    }

    @Test
    void consultPaidByCoinAccruesByOrderId() {
        listener.onConsultPaidByCoin(new ConsultPaidByPawCoinEvent(7L, 9L, 50_000));

        AccrualCommand c = accrued();
        assertThat(c.rule()).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(c.pointsOverride()).isEqualTo(250);
        assertThat(c.idemKey()).isEqualTo("SPEND:COIN:vet-consult-order:9");
        assertThat(c.refType()).isEqualTo("VET_CONSULT");
        assertThat(c.refId()).isEqualTo("9");
    }

    @Test
    void shopOrderAccruesOncePerOrderOnPaidTotal() {
        listener.onShopOrderPaid(new ShopOrderPaidEvent(11L, "ord", 7L, 200_000));

        AccrualCommand c = accrued();
        assertThat(c.rule()).isEqualTo(RuleCode.SPEND_SHOP);
        assertThat(c.pointsOverride()).isEqualTo(100);
        assertThat(c.idemKey()).isEqualTo("SPEND:SHOP:11");
        assertThat(c.refType()).isEqualTo("SHOP_ORDER");
        assertThat(c.refId()).isEqualTo("11");
    }

    @Test
    void zeroPointsIsNotAccrued() {
        listener.onCoinSpent(new PawCoinSpentEvent(7L, 50, "AI_UNLOCK", 1L, "ai-unlock:1"));

        verify(ledger, never()).accrue(any());
    }
}
