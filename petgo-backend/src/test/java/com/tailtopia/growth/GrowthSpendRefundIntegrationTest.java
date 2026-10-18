package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.event.ConsultPaidByPawCoinEvent;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.event.RefundSucceededEvent;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.4.0 ① Story 1.3 · AC3–AC6（L1，需 Docker postgres + redis）：消费信号入账 + 退款按比例扣回，事件级端到端。
 * 五处真实退钱点各自的发布由既有支付 / 退款集成测试回归 + 本类的事件级断言共同覆盖。
 */
class GrowthSpendRefundIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PawCoinWalletService wallet;

    private long sum(long userId, String rule) {
        return jdbc.queryForObject("SELECT COALESCE(sum(delta), 0) FROM growth_ledger WHERE user_id = ? AND rule_code = ?",
                Long.class, userId, rule);
    }

    private int count(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Integer.class, userId);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        Thread.sleep(150);
    }

    private void publish(Object e) {
        tx.executeWithoutResult(s -> events.publishEvent(e));
    }

    @Test
    void topupGivesNothingAndPiReplayCountsOnce() throws Exception {
        long u = newUser().getId();
        long intent = 6_000_000L + u;
        publish(new PaymentIntentPaidEvent(intent, "t", u, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 50_000, "IDR"));
        publish(new PaymentIntentPaidEvent(intent + 1, "t", u, PaymentPurpose.TAILSONALITY, PayChannel.QRIS, 20_000, "IDR"));
        publish(new PaymentIntentPaidEvent(intent + 1, "t", u, PaymentPurpose.TAILSONALITY, PayChannel.QRIS, 20_000, "IDR"));
        await(() -> count(u) >= 1);

        assertThat(count(u)).isEqualTo(1);
        assertThat(sum(u, "SPEND_DIGITAL")).isEqualTo(100);
    }

    @Test
    void pureCoinKtpViaWalletDebitAccruesOnce() throws Exception {
        long u = newUser().getId();
        tx.executeWithoutResult(s -> wallet.credit(u, 50_000, PawCoinTxnType.BONUS, "TEST", 1L, "it-grant-" + u));
        tx.executeWithoutResult(s -> wallet.debit(u, 20_000, PawCoinTxnType.SPEND, "ID_HD", 5L, "id-hd-card:it" + u));
        await(() -> count(u) >= 1);

        assertThat(sum(u, "SPEND_DIGITAL")).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT idem_key FROM growth_ledger WHERE user_id = ?", String.class, u))
                .isEqualTo("SPEND:COIN:id-hd-card:it" + u);
    }

    @Test
    void shopPartialRefundsNeverOverDeduct() throws Exception {
        long u = newUser().getId();
        long order = 7_000_000L + u;
        publish(new ShopOrderPaidEvent(order, "ord", u, 200_000));
        await(() -> sum(u, "SPEND_SHOP") == 100);

        publish(new RefundSucceededEvent("ret-a-" + u, u, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", String.valueOf(order),
                null, 60_000, 200_000));
        await(() -> sum(u, "SPEND_SHOP") == 70);
        publish(new RefundSucceededEvent("ret-b-" + u, u, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", String.valueOf(order),
                null, 140_000, 200_000));
        await(() -> sum(u, "SPEND_SHOP") == 0);
        // 重放与「再多退一笔」都不再扣。
        publish(new RefundSucceededEvent("ret-b-" + u, u, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", String.valueOf(order),
                null, 140_000, 200_000));
        publish(new RefundSucceededEvent("ret-c-" + u, u, PaymentPurpose.SHOP_ORDER, "SHOP_ORDER", String.valueOf(order),
                null, 10_000, 200_000));
        await(() -> false);

        assertThat(sum(u, "SPEND_SHOP")).isZero();
        assertThat(count(u)).isEqualTo(3);
    }

    @Test
    void consultCoinRefundLocatesByOrderAndMissingOriginalIsIgnored() throws Exception {
        long u = newUser().getId();
        long consultOrder = 8_000_000L + u;
        publish(new ConsultPaidByPawCoinEvent(u, consultOrder, 50_000));
        await(() -> sum(u, "SPEND_DIGITAL") == 250);

        publish(new RefundSucceededEvent("consult-refund-" + consultOrder, u, PaymentPurpose.VET_CONSULT,
                "CONSULT_ORDER", String.valueOf(consultOrder), null, 50_000, 50_000));
        publish(new RefundSucceededEvent("never-paid-" + u, u, PaymentPurpose.VET_CONSULT, "CONSULT_ORDER",
                "999999999", null, 50_000, 50_000));
        await(() -> sum(u, "SPEND_DIGITAL") == 0);

        assertThat(sum(u, "SPEND_DIGITAL")).isZero();
        assertThat(count(u)).isEqualTo(2);
    }
}
