package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * V1.4.0 gift Story 1.1 · AC7 / AC8 · L1（需 postgres + redis）：QRIS 到账交付只一次、付款前删宠物 → 待退回、
 * 未付过期 → EXPIRED 且无事件。退回本身由 Story 1.2 验。
 */
@RecordApplicationEvents
class GiftQrisDeliveryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GiftSendService sendService;
    @Autowired
    private GiftCatalogRepository catalog;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PaymentIntentService paymentIntents;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;

    private GiftCatalogItem tenK;

    @BeforeEach
    void seedCatalog() {
        long n = SEQ.incrementAndGet();
        tenK = catalog.save(GiftCatalogItem.create("gq" + n, "Gift" + n, "Hadiah" + n, "https://cdn/g.png", 10_000,
                false, false, false, GiftKind.NORMAL, null, 0));
    }

    private PetProfile newPet(User owner) {
        return petProfiles.save(PetProfile.create(owner.getId(), PetType.CAT, "Mochi", null, null, null, null,
                "GQ-" + SEQ.incrementAndGet()));
    }

    private GiftSendResponse startQris(User sender, PetProfile pet, int qty) {
        return sendService.send(sender.getId(), pet.getId(), new GiftSendRequest(tenK.getPublicToken(), qty,
                PayChannel.QRIS, "q-" + SEQ.incrementAndGet(), "gift_page", null));
    }

    private long orderId(String orderToken) {
        return jdbc.queryForObject("SELECT id FROM gift_orders WHERE public_token = ?", Long.class, orderToken);
    }

    private String orderStatus(String orderToken) {
        return jdbc.queryForObject("SELECT status FROM gift_orders WHERE public_token = ?", String.class, orderToken);
    }

    private PaymentCallback paid(GiftSendResponse r) {
        return new PaymentCallback(r.payment().token(), "stub-" + r.payment().token(), GatewayStatus.PAID, Map.of());
    }

    @Test
    void concurrentCallbackAndPollDeliverOnce() throws Exception {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 2);
        assertThat(r.delivered()).isFalse();
        assertThat(r.payload()).isNotBlank();
        assertThat(r.payment().purpose()).isEqualTo("GIFT");
        assertThat(r.payment().amount()).isEqualTo(20_000L);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                go.await();
                try {
                    paymentIntents.applyCallback(paid(r));
                } catch (RuntimeException ignored) {
                    // 并发败方（乐观锁 / 唯一约束）整笔回滚，属预期
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long id = orderId(r.orderToken());
        assertThat(orderStatus(r.orderToken())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT count FROM gift_pet_wall WHERE pet_id = ? AND gift_id = ?",
                Long.class, pet.getId(), tenK.getId())).isEqualTo(2L);
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == id)).hasSize(1);

        // 再来一次回调（重放）：仍只一次
        paymentIntents.applyCallback(paid(r));
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == id)).hasSize(1);
    }

    @Test
    void petDeletedBeforePaymentBecomesUndeliveredRefundPending() {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 1);
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet.getId());

        paymentIntents.applyCallback(paid(r));

        long id = orderId(r.orderToken());
        // Story 1.2 起：交付事务提交后 GiftUndeliveredListener 同步（AFTER_COMMIT）退回 → applyCallback 返回时已是 UNDELIVERED_REFUNDED。
        // 退回细节（流水 / 总账 / 通知）由 GiftRefundIntegrationTest 验。
        assertThat(orderStatus(r.orderToken())).isEqualTo("UNDELIVERED_REFUNDED");
        assertThat(jdbc.queryForObject("SELECT undelivered_reason FROM gift_orders WHERE id = ?", String.class, id))
                .isEqualTo("PET_DELETED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_token = ?", String.class,
                r.payment().token())).as("到账不回滚").isEqualTo("PAID");
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == id)).isEmpty();
        assertThat(events.stream(GiftUndeliveredEvent.class).filter(e -> e.giftOrderId() == id)).hasSize(1);
    }

    @Test
    void unpaidExpiryMarksOrderExpiredWithoutEvents() {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendResponse r = startQris(sender, pet, 1);
        jdbc.update("UPDATE payment_intents SET expires_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), r.payment().token());

        paymentIntents.expireOverduePending(50);

        long id = orderId(r.orderToken());
        assertThat(orderStatus(r.orderToken())).isEqualTo("EXPIRED");
        assertThat(events.stream(GiftSentEvent.class).filter(e -> e.giftOrderId() == id)).isEmpty();
        assertThat(events.stream(GiftUndeliveredEvent.class).filter(e -> e.giftOrderId() == id)).isEmpty();
    }

    @Test
    void sameRequestReplayReturnsSameIntentAndPayload() {
        User sender = newUser();
        PetProfile pet = newPet(newUser());
        GiftSendRequest req = new GiftSendRequest(tenK.getPublicToken(), 1, PayChannel.QRIS,
                "q-" + SEQ.incrementAndGet(), "gift_page", null);
        GiftSendResponse a = sendService.send(sender.getId(), pet.getId(), req);
        GiftSendResponse b = sendService.send(sender.getId(), pet.getId(), req);
        assertThat(b.orderToken()).isEqualTo(a.orderToken());
        assertThat(b.payment().token()).isEqualTo(a.payment().token());
        assertThat(b.payload()).isEqualTo(a.payload());
    }
}
