package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.event.GiftRefundedEvent;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftRefundRetryJob;
import com.tailtopia.gift.service.GiftRefundService;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * V1.4.0 gift Story 1.2 · AC1 ~ AC4 · L1（需 postgres + redis）：QRIS 付款前宠物删除 / 主人注销 / 送礼人拉黑主人 →
 * 到账后自动退回 PawCoin（流水一条 TOPUP / GIFT_REFUND、总账 CASH_IN → FLOAT_LIABILITY、对账一致）+ 退回通知一条；
 * 钱包写入失败 → 重试任务计次、5 次置 REFUND_MANUAL；中途成功只入账一次；重复触发不重复入账。
 */
@RecordApplicationEvents
class GiftRefundIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftSendService sendService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private GiftRefundService refunds;
    @Autowired
    private GiftRefundRetryJob retryJob;
    @Autowired
    private UserHideRelationService hideService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;

    @MockitoSpyBean
    private PawCoinWalletService wallet;

    @MockitoBean
    private NotificationPusher pusher;

    private GiftCatalogItem tenK;

    @BeforeEach
    void seedCatalog() {
        long n = SEQ.incrementAndGet();
        tenK = catalog.save(GiftCatalogItem.create("gr" + n, "Gift" + n, "Hadiah" + n, "https://cdn/g.png", 10_000,
                false, false, false, GiftKind.NORMAL, null, 0));
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "GR-" + SEQ.incrementAndGet()));
    }

    private GiftSendResponse startQris(User sender, PetProfile pet, int qty) {
        return sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(tenK.getPublicToken(), qty,
                PayChannel.QRIS, "r-" + SEQ.incrementAndGet(), "gift_page", null));
    }

    private void pay(GiftSendResponse r) {
        paymentIntents.applyCallback(new PaymentCallback(r.payment().token(), "stub-" + r.payment().token(),
                GatewayStatus.PAID, Map.of()));
    }

    private long orderId(String token) {
        return jdbc.queryForObject("SELECT id FROM gift_orders WHERE public_token = ?", Long.class, token);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM gift_orders WHERE id = ?", String.class, id);
    }

    private long refundTxns(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM pawcoin_transactions WHERE ref_type = 'GIFT_REFUND' "
                + "AND ref_id = ? AND type = 'TOPUP'", Long.class, id);
    }

    private long refundNotices(long senderId, String orderToken) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'GIFT_UNDELIVERED_REFUND' AND target_ref = ?", Long.class, senderId, orderToken);
    }

    private void assertRefunded(User sender, GiftSendResponse r, long before) {
        long id = orderId(r.orderToken());
        assertThat(status(id)).isEqualTo("UNDELIVERED_REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refunded_at IS NOT NULL FROM gift_orders WHERE id = ?", Boolean.class,
                id)).isTrue();
        assertThat(wallet.balanceOf(sender.getId())).isEqualTo(before + 20_000);
        assertThat(refundTxns(id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE ref_type = 'GIFT_REFUND' "
                + "AND ref_id = ? AND account = 'CASH_IN' AND direction = 'DEBIT'", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE ref_type = 'GIFT_REFUND' "
                + "AND ref_id = ? AND account = 'FLOAT_LIABILITY' AND direction = 'CREDIT'", Long.class, id))
                .isEqualTo(1L);
        assertThat(wallet.reconcile(sender.getId()).consistent()).isTrue();
        assertThat(refundNotices(sender.getId(), r.orderToken())).isEqualTo(1L);
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == id)).as("无送礼成长值").isEmpty();
        assertThat(events.stream(GiftRefundedEvent.class).filter(e -> e.giftOrderId() == id)).hasSize(1);
    }

    @Test
    void petDeletedBeforePaymentIsRefundedAndNotified() {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 2);
        long before = wallet.balanceOf(sender.getId());
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        pay(r);

        assertRefunded(sender, r, before);
        String body = jdbc.queryForObject("SELECT body FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'GIFT_UNDELIVERED_REFUND'", String.class, sender.getId());
        assertThat(body).contains("Mochi").contains("20.000");
    }

    @Test
    void ownerDeletedBeforePaymentIsRefunded() {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftSendResponse r = startQris(sender, pet, 2);
        long before = wallet.balanceOf(sender.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", owner.getId());

        pay(r);

        assertRefunded(sender, r, before);
        assertThat(jdbc.queryForObject("SELECT undelivered_reason FROM gift_orders WHERE public_token = ?",
                String.class, r.orderToken())).isEqualTo("OWNER_DELETED");
    }

    @Test
    void senderBlockedOwnerBeforePaymentIsRefunded() {
        User sender = newUser();
        User owner = newUser();
        PetProfile pet = newPet(owner);
        GiftSendResponse r = startQris(sender, pet, 2);
        long before = wallet.balanceOf(sender.getId());
        hideService.block(sender.getId(), owner.getId());

        pay(r);

        assertRefunded(sender, r, before);
        assertThat(jdbc.queryForObject("SELECT undelivered_reason FROM gift_orders WHERE public_token = ?",
                String.class, r.orderToken())).isEqualTo("SENDER_BLOCKED");
    }

    @Test
    void walletFailureFiveTimesGoesManualWithoutAnyTxn() {
        doThrow(new IllegalStateException("wallet down")).when(wallet)
                .credit(anyLong(), anyLong(), any(), eq("GIFT_REFUND"), anyLong(), anyString());
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 2);
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        pay(r); // 首次触发失败 → attempts = 1
        long id = orderId(r.orderToken());
        assertThat(status(id)).isEqualTo("UNDELIVERED_REFUND_PENDING");
        for (int i = 0; i < 4; i++) {
            retryJob.run();
        }

        assertThat(status(id)).isEqualTo("REFUND_MANUAL");
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM gift_orders WHERE id = ?", Integer.class, id))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT refund_error FROM gift_orders WHERE id = ?", String.class, id))
                .isEqualTo("IllegalStateException");
        assertThat(refundTxns(id)).isZero();
        assertThat(refundNotices(sender.getId(), r.orderToken())).isZero();

        retryJob.run(); // 之后不再被扫到
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM gift_orders WHERE id = ?", Integer.class, id))
                .isEqualTo(5);
    }

    @Test
    void thirdAttemptSucceedsOnceWithOneNotice() {
        IllegalStateException down = new IllegalStateException("wallet down");
        doThrow(down).doThrow(down).doCallRealMethod().when(wallet)
                .credit(anyLong(), anyLong(), any(), eq("GIFT_REFUND"), anyLong(), anyString());
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 2);
        long before = wallet.balanceOf(sender.getId());
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        pay(r);          // 第 1 次失败
        retryJob.run();  // 第 2 次失败
        retryJob.run();  // 第 3 次成功
        retryJob.run();  // 已退回，不再扫到

        assertRefunded(sender, r, before);
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM gift_orders WHERE public_token = ?",
                Integer.class, r.orderToken())).isEqualTo(2);
    }

    @Test
    void repeatedAndConcurrentTriggersCreditOnlyOnce() throws Exception {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 2);
        long before = wallet.balanceOf(sender.getId());
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());
        doThrow(new IllegalStateException()).doCallRealMethod().when(wallet)
                .credit(anyLong(), anyLong(), any(), eq("GIFT_REFUND"), anyLong(), anyString());
        pay(r); // 首次失败，留在待退回
        long id = orderId(r.orderToken());

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                go.await();
                try {
                    refunds.attempt(id);
                } catch (RuntimeException ignored) {
                    // 并发败方
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        retryJob.run();
        refunds.attempt(id);

        assertRefunded(sender, r, before);
    }
}
