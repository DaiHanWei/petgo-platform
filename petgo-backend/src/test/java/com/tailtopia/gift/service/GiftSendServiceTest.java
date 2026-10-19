package com.tailtopia.gift.service;

import static com.tailtopia.gift.service.GiftTestFixtures.OWNER;
import static com.tailtopia.gift.service.GiftTestFixtures.PET;
import static com.tailtopia.gift.service.GiftTestFixtures.SENDER;
import static com.tailtopia.gift.service.GiftTestFixtures.exclusive;
import static com.tailtopia.gift.service.GiftTestFixtures.gift;
import static com.tailtopia.gift.service.GiftTestFixtures.offline;
import static com.tailtopia.gift.service.GiftTestFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.domain.GiftSource;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 gift Story 1.1 · AC5 ~ AC7（L0，Mockito）：九条校验、幂等、PawCoin 与 QRIS 两条路径。 */
class GiftSendServiceTest {

    private GiftOrderRepository orders;
    private GiftCatalogRepository catalog;
    private GiftPetDirectory pets;
    private UserHideRelationReader hides;
    private GrowthQueryService growth;
    private PawCoinWalletService wallet;
    private PaymentIntentService paymentIntents;
    private PaymentGateway gateway;
    private GiftDeliveryService delivery;
    private GiftTokenGenerator tokens;
    private GiftSendService service;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        catalog = mock(GiftCatalogRepository.class);
        pets = mock(GiftPetDirectory.class);
        hides = mock(UserHideRelationReader.class);
        growth = mock(GrowthQueryService.class);
        wallet = mock(PawCoinWalletService.class);
        paymentIntents = mock(PaymentIntentService.class);
        gateway = mock(PaymentGateway.class);
        delivery = mock(GiftDeliveryService.class);
        tokens = mock(GiftTokenGenerator.class);
        PlatformConfigService config = mock(PlatformConfigService.class);
        PricingConfig pricing = mock(PricingConfig.class);
        when(pricing.getGiftQrisMinAmount()).thenReturn(10_000L);
        when(config.pricing()).thenReturn(pricing);

        when(tokens.generate()).thenReturn("ORDERTOKEN");
        when(orders.findByIdemKey(anyString())).thenReturn(Optional.empty());
        when(orders.saveAndFlush(any())).thenAnswer(inv -> {
            GiftOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                ReflectionTestUtils.setField(o, "id", ids.incrementAndGet());
            }
            return o;
        });
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        when(delivery.deliver(any())).thenReturn(GiftDeliveryService.Result.DELIVERED);
        catalogHas(gift(1, 1_000, true, false, false, 0));
        catalogHas(gift(2, 10_000, false, false, false, 0));
        catalogHas(exclusive(3, 20_000, 5, 0));

        service = new GiftSendService(orders, catalog, new GiftRecipientGuard(pets, hides), growth, config, wallet,
                paymentIntents, gateway, delivery, tokens, Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"),
                        ZoneOffset.UTC));
    }

    private void catalogHas(GiftCatalogItem g) {
        when(catalog.findByPublicToken(g.getPublicToken())).thenReturn(Optional.of(g));
    }

    private static GiftSendRequest req(String giftToken, int qty, PayChannel channel) {
        return new GiftSendRequest(giftToken, qty, channel, "req-1", "diary_tab", null);
    }

    private static GiftSendRequest req(String giftToken, int qty, PayChannel channel, String source) {
        return new GiftSendRequest(giftToken, qty, channel, "req-1", source, null);
    }

    private void assertRejected(GiftSendRequest r, HttpStatus status, String typeSuffix) {
        assertRejected(SENDER, r, status, typeSuffix);
    }

    private void assertRejected(long sender, GiftSendRequest r, HttpStatus status, String typeSuffix) {
        assertThatThrownBy(() -> service.send(sender, PET, r))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getType().toString()).endsWith("/" + typeSuffix);
                });
        // 校验全部在任何写库之前：不留行、不扣币、不出码
        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(wallet, gateway);
        verify(paymentIntents, never()).createIntent(anyLong(), any(), any(), anyLong(), any(), any(), any());
    }

    /** QRIS 用的意图桩：返回指定 id / 状态的真实意图。 */
    private PaymentIntent intent(long id, PaymentStatus status, String gatewayRef, Map<String, Object> meta) {
        PaymentIntent p = PaymentIntent.create(SENDER, PaymentPurpose.GIFT, PayChannel.QRIS, 10_000, "IDR",
                "pi-" + id, Instant.parse("2026-10-18T09:00:00Z"));
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "status", status);
        ReflectionTestUtils.setField(p, "gatewayRef", gatewayRef);
        ReflectionTestUtils.setField(p, "gatewayMeta", meta);
        when(paymentIntents.findByToken("pi-" + id)).thenReturn(Optional.of(p));
        return p;
    }

    private void createIntentReturns(PaymentIntent p) {
        when(paymentIntents.createIntent(anyLong(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(PaymentIntentResponse.of(p));
    }

    // ---------- 九条校验 ----------

    @Test
    void v1_petMissingOrOwnerDeletedIs404() {
        when(pets.pet(PET)).thenReturn(Optional.empty());
        assertRejected(req("g1", 1, PayChannel.PAWCOIN), HttpStatus.NOT_FOUND, "gift-pet-gone");
        when(pets.pet(PET)).thenReturn(Optional.of(new GiftPet(PET, OWNER, "Mochi", null, "CAT", null, false, true)));
        assertRejected(req("g1", 1, PayChannel.PAWCOIN), HttpStatus.NOT_FOUND, "gift-pet-gone");
    }

    @Test
    void v1_bannedOwnerCanStillReceive() {
        when(pets.pet(PET)).thenReturn(Optional.of(new GiftPet(PET, OWNER, "Mochi", null, "CAT", null, false, false)));
        GiftSendResponse r = service.send(SENDER, PET, req("g1", 1, PayChannel.PAWCOIN));
        assertThat(r.delivered()).isTrue();
    }

    @Test
    void v2_ownPetIs422() {
        assertRejected(OWNER, req("g1", 1, PayChannel.PAWCOIN), HttpStatus.UNPROCESSABLE_ENTITY, "gift-own-pet");
    }

    @Test
    void v3_senderBlockedOwnerIs403ButReverseIsAllowed() {
        when(hides.isBlocked(SENDER, OWNER)).thenReturn(true);
        assertRejected(req("g1", 1, PayChannel.PAWCOIN), HttpStatus.FORBIDDEN, "gift-blocked");

        when(hides.isBlocked(SENDER, OWNER)).thenReturn(false);
        when(hides.isBlocked(OWNER, SENDER)).thenReturn(true); // 主人拉黑送礼人：不拦
        assertThat(service.send(SENDER, PET, req("g1", 1, PayChannel.PAWCOIN)).delivered()).isTrue();
    }

    @Test
    void v4_unknownOrOfflineGiftIs409() {
        assertRejected(req("nope", 1, PayChannel.PAWCOIN), HttpStatus.CONFLICT, "gift-unavailable");
        catalogHas(offline(gift(9, 1_000, false, false, false, 0)));
        assertRejected(req("g9", 1, PayChannel.PAWCOIN), HttpStatus.CONFLICT, "gift-unavailable");
    }

    @Test
    void v5_exclusiveNeedsParticipatingLevel() {
        assertRejected(req("g3", 1, PayChannel.PAWCOIN), HttpStatus.FORBIDDEN, "gift-locked");
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.NOT_PARTICIPATING);
        assertRejected(req("g3", 1, PayChannel.PAWCOIN), HttpStatus.FORBIDDEN, "gift-locked");

        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(5, GrowthTier.NEWBIE));
        assertThat(service.send(SENDER, PET, req("g3", 1, PayChannel.PAWCOIN)).delivered()).isTrue();
    }

    @Test
    void v6_quantityOutOfRangeIs422() {
        assertRejected(req("g1", 0, PayChannel.PAWCOIN), HttpStatus.UNPROCESSABLE_ENTITY, "validation");
        assertRejected(req("g1", 100, PayChannel.PAWCOIN), HttpStatus.UNPROCESSABLE_ENTITY, "validation");
    }

    @Test
    void v7_mixedIsRejected() {
        assertRejected(req("g1", 1, PayChannel.MIXED), HttpStatus.UNPROCESSABLE_ENTITY, "validation");
    }

    @Test
    void v8_qrisBelowMinIs422AndExactlyMinPasses() {
        assertRejected(req("g1", 9, PayChannel.QRIS), HttpStatus.UNPROCESSABLE_ENTITY, "gift-qris-below-min");

        createIntentReturns(intent(501, PaymentStatus.PENDING, null, null));
        when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-1", "QR-PAYLOAD", Map.of()));
        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS)); // Rp10,000 正好可用
        assertThat(r.delivered()).isFalse();
        assertThat(r.payload()).isEqualTo("QR-PAYLOAD");
    }

    @Test
    void v9_unknownSourceIs422() {
        assertRejected(req("g1", 1, PayChannel.PAWCOIN, "feed"), HttpStatus.UNPROCESSABLE_ENTITY, "validation");
    }

    // ---------- PawCoin ----------

    @Test
    void pawcoinDebitsSenderOnceThenDelivers() {
        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.PAWCOIN));

        ArgumentCaptor<GiftOrder> saved = ArgumentCaptor.forClass(GiftOrder.class);
        InOrder order = inOrder(orders, wallet, delivery);
        order.verify(orders).saveAndFlush(saved.capture());
        order.verify(wallet, times(1)).debit(SENDER, 10_000L, PawCoinTxnType.SPEND, "GIFT", saved.getValue().getId(),
                "GIFT_COIN:ORDERTOKEN");
        order.verify(delivery).deliver(saved.getValue());
        verifyNoInteractions(paymentIntents, gateway);
        assertThat(r.delivered()).isTrue();
        assertThat(r.payment()).isNull();
        assertThat(r.orderToken()).isEqualTo("ORDERTOKEN");
        assertThat(r.status()).isEqualTo("DELIVERED");
        assertThat(GiftSendService.COIN_REF_TYPE).isEqualTo(PaymentPurpose.GIFT.name());
    }

    @Test
    void pawcoinOrderSnapshotsGiftAndPet() {
        service.send(SENDER, PET, new GiftSendRequest("g1", 5, PayChannel.PAWCOIN, "req-1", "gift_page", 900L));
        ArgumentCaptor<GiftOrder> saved = ArgumentCaptor.forClass(GiftOrder.class);
        verify(orders).saveAndFlush(saved.capture());
        GiftOrder o = saved.getValue();
        assertThat(o.getStatus()).isEqualTo(GiftOrderStatus.PENDING);
        assertThat(o.getChannel()).isEqualTo(PayChannel.PAWCOIN);
        assertThat(o.getPetName()).isEqualTo("Mochi");
        assertThat(o.getOwnerId()).isEqualTo(OWNER);
        assertThat(o.getGiftNameEn()).isEqualTo("Gift1");
        assertThat(o.getGiftNameId()).isEqualTo("Hadiah1");
        assertThat(o.getUnitPrice()).isEqualTo(1_000L);
        assertThat(o.getQuantity()).isEqualTo(5);
        assertThat(o.getTotal()).isEqualTo(5_000L);
        assertThat(o.getSource()).isEqualTo(GiftSource.GIFT_PAGE);
        assertThat(o.getIdemKey()).isEqualTo("GIFT:" + SENDER + ":req-1");
    }

    @Test
    void pawcoinInsufficientPropagatesAndNothingIsDelivered() {
        doThrow(AppException.pawcoinInsufficient("PawCoin 余额不足")).when(wallet)
                .debit(anyLong(), anyLong(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.send(SENDER, PET, req("g2", 1, PayChannel.PAWCOIN)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType().toString()).endsWith("/pawcoin-insufficient"));
        verify(delivery, never()).deliver(any());
    }

    @Test
    void pawcoinNotDeliveredRollsBackWholeTransaction() {
        when(delivery.deliver(any())).thenReturn(GiftDeliveryService.Result.UNDELIVERED);
        assertThatThrownBy(() -> service.send(SENDER, PET, req("g2", 1, PayChannel.PAWCOIN)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---------- QRIS ----------

    @Test
    void qrisCreatesIntentWithPerOrderKeyAndWindow() {
        PaymentIntent p = intent(501, PaymentStatus.PENDING, null, null);
        createIntentReturns(p);
        when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-1", "QR-PAYLOAD", Map.of()));

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 2, PayChannel.QRIS));

        verify(paymentIntents).createIntent(SENDER, PaymentPurpose.GIFT, PayChannel.QRIS, 20_000L, "IDR",
                "GIFT_QRIS:ORDERTOKEN", Duration.ofMinutes(60));
        assertThat(GiftSendService.PAY_WINDOW).isEqualTo(Duration.ofMinutes(60));
        ArgumentCaptor<GiftOrder> saved = ArgumentCaptor.forClass(GiftOrder.class);
        verify(orders, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPaymentIntentId()).as("回填意图 id").isEqualTo(501L);
        verify(paymentIntents).attachCharge(eq("pi-501"), eq("gw-1"), any());
        verifyNoInteractions(wallet, delivery);
        assertThat(r.delivered()).isFalse();
        assertThat(r.payment().token()).isEqualTo("pi-501");
        assertThat(r.status()).isEqualTo("PENDING");
    }

    @Test
    void qrisGatewayFailureMarksIntentFailedAndRethrows() {
        createIntentReturns(intent(501, PaymentStatus.PENDING, null, null));
        when(gateway.createCharge(any())).thenThrow(new IllegalStateException("gateway down"));
        assertThatThrownBy(() -> service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS)))
                .isInstanceOf(IllegalStateException.class);
        verify(paymentIntents).failChargeAttempt("pi-501");
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReturnsSameOrderWithoutDebitOrNewIntent() {
        GiftOrder existing = GiftTestFixtures.order(77L, PayChannel.PAWCOIN, 1, 10_000);
        ReflectionTestUtils.setField(existing, "status", GiftOrderStatus.DELIVERED);
        when(orders.findByIdemKey("GIFT:" + SENDER + ":req-1")).thenReturn(Optional.of(existing));
        when(pets.pet(PET)).thenReturn(Optional.empty()); // 「不再校验」：即使宠物已不在也照返

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.PAWCOIN));

        assertThat(r.delivered()).isTrue();
        assertThat(r.orderToken()).isEqualTo(existing.getPublicToken());
        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(wallet, paymentIntents, gateway, delivery);
    }

    /** 重放认订单自己挂的那笔意图（OCR #7）：PENDING → 同一笔的已存载荷，不再同键 createIntent。 */
    private PaymentIntent ownIntent(long id, PaymentStatus status, String gatewayRef, Map<String, Object> meta) {
        PaymentIntent p = intent(id, status, gatewayRef, meta);
        when(paymentIntents.findById(id)).thenReturn(Optional.of(p));
        when(paymentIntents.statusOf(SENDER, "pi-" + id)).thenReturn(status);
        return p;
    }

    @Test
    void qrisReplaySameIntentReturnsSavedPayload() {
        GiftOrder existing = GiftTestFixtures.order(78L, PayChannel.QRIS, 1, 10_000);
        existing.attachPaymentIntent(501L);
        when(orders.findByIdemKey(anyString())).thenReturn(Optional.of(existing));
        ownIntent(501, PaymentStatus.PENDING, "gw-1", Map.of("payload", "SAVED-QR"));

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS));

        assertThat(r.payload()).isEqualTo("SAVED-QR");
        assertThat(r.payment().token()).isEqualTo("pi-501");
        assertThat(r.orderToken()).isEqualTo(existing.getPublicToken());
        verify(paymentIntents, never()).createIntent(anyLong(), any(), any(), anyLong(), any(), any(), any());
        verifyNoInteractions(gateway, wallet);
        verify(orders, never()).saveAndFlush(any());
    }

    /**
     * OCR #7：幂等键映射丢失（Redis 过期 / 清空）时旧意图仍 PENDING、旧码仍可付 —— 原先同键 createIntent 会建出第二笔意图、
     * 把订单置 EXPIRED，旧码付了却不送达。现在只认订单自己的意图：窗内照常返回旧码，订单仍 PENDING。
     */
    @Test
    void qrisReplayKeepsLiveOwnIntentEvenIfIdempotencyMappingWasLost() {
        GiftOrder existing = GiftTestFixtures.order(79L, PayChannel.QRIS, 1, 10_000);
        existing.attachPaymentIntent(501L);
        when(orders.findByIdemKey(anyString())).thenReturn(Optional.of(existing));
        ownIntent(501, PaymentStatus.PENDING, "gw-1", Map.of("payload", "OLD-QR"));
        createIntentReturns(intent(502, PaymentStatus.PENDING, null, null)); // 映射丢失时同键会拿到的新意图

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS));

        assertThat(r.payload()).isEqualTo("OLD-QR");
        assertThat(existing.getStatus()).isEqualTo(GiftOrderStatus.PENDING);
        assertThat(existing.getPaymentIntentId()).isEqualTo(501L);
        verify(paymentIntents, never()).createIntent(anyLong(), any(), any(), anyLong(), any(), any(), any());
    }

    /** OCR #7：自己的意图已过窗 → statusOf 经意图状态机懒过期为 EXPIRED，订单随之 EXPIRED；不建新意图、不出码。 */
    @Test
    void qrisReplayWithOwnIntentExpiredExpiresOrderWithoutNewIntent() {
        GiftOrder existing = GiftTestFixtures.order(80L, PayChannel.QRIS, 1, 10_000);
        existing.attachPaymentIntent(501L);
        when(orders.findByIdemKey(anyString())).thenReturn(Optional.of(existing));
        ownIntent(501, PaymentStatus.EXPIRED, "gw-1", Map.of("payload", "OLD-QR"));

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS));

        assertThat(r.status()).isEqualTo("EXPIRED");
        assertThat(r.delivered()).isFalse();
        assertThat(r.payment()).isNull();
        assertThat(r.payload()).isNull();
        assertThat(existing.getStatus()).isEqualTo(GiftOrderStatus.EXPIRED);
        assertThat(existing.getPaymentIntentId()).isEqualTo(501L);
        verify(paymentIntents).statusOf(SENDER, "pi-501"); // 意图先终态（支付模块状态机），订单后标
        verify(paymentIntents, never()).createIntent(anyLong(), any(), any(), anyLong(), any(), any(), any());
        verifyNoInteractions(gateway);
    }

    /** 意图已 FAILED、订单还 PENDING（漏了失败事件）→ 订单 FAILED。 */
    @Test
    void qrisReplayWithOwnIntentFailedFailsOrder() {
        GiftOrder existing = GiftTestFixtures.order(81L, PayChannel.QRIS, 1, 10_000);
        existing.attachPaymentIntent(501L);
        when(orders.findByIdemKey(anyString())).thenReturn(Optional.of(existing));
        ownIntent(501, PaymentStatus.FAILED, null, null);

        GiftSendResponse r = service.send(SENDER, PET, req("g2", 1, PayChannel.QRIS));

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(existing.getStatus()).isEqualTo(GiftOrderStatus.FAILED);
        verifyNoInteractions(gateway);
    }

    @Test
    void replayExistingAfterConcurrentInsertReturnsTheWinner() {
        GiftOrder existing = GiftTestFixtures.order(80L, PayChannel.PAWCOIN, 1, 10_000);
        ReflectionTestUtils.setField(existing, "status", GiftOrderStatus.DELIVERED);
        when(orders.findByIdemKey("GIFT:" + SENDER + ":req-9")).thenReturn(Optional.of(existing));
        assertThat(service.replayExisting(SENDER, "req-9")).get()
                .extracting(GiftSendResponse::orderToken).isEqualTo(existing.getPublicToken());
        assertThat(service.replayExisting(SENDER, "req-unknown")).isEmpty();
    }
}
