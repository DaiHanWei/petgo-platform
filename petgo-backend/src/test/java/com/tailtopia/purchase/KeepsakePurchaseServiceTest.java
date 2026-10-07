package com.tailtopia.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.1 · AC4（L0，mock 下游）：渠道分流、渠道后缀幂等键、价格口径、QRIS 行复用 / 过期、已解锁 409、MIXED 422。 */
class KeepsakePurchaseServiceTest {

    private static final long USER = 7L;

    private KeepsakePurchaseRepository repo;
    private PaymentIntentService intents;
    private PawCoinWalletService wallet;
    private PlatformConfigService config;
    private PaymentGateway gateway;
    private KeepsakeGrantRunner grants;
    private ApplicationEventPublisher events;
    private KeepsakePurchaseService service;
    private PricingConfig pricing;

    private final List<KeepsakePurchase> rows = new ArrayList<>();
    private final Map<String, PaymentIntent> intentByKey = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        repo = mock(KeepsakePurchaseRepository.class);
        intents = mock(PaymentIntentService.class);
        wallet = mock(PawCoinWalletService.class);
        config = mock(PlatformConfigService.class);
        gateway = mock(PaymentGateway.class);
        grants = mock(KeepsakeGrantRunner.class);
        events = mock(ApplicationEventPublisher.class);
        KeepsakeTokenGenerator tokens = mock(KeepsakeTokenGenerator.class);
        when(tokens.generate()).thenAnswer(inv -> "t" + (nextId++));
        pricing = org.springframework.beans.BeanUtils.instantiateClass(PricingConfig.class);
        pricing.setTailsonalityUnlockPrice(5000);
        pricing.setPassportPageUnlockPrice(2000);
        pricing.setPassportBoardingUnlockPrice(1000);
        when(config.pricing()).thenReturn(pricing);
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.GRANTED);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            KeepsakePurchase p = inv.getArgument(0);
            if (p.getId() == null) {
                ReflectionTestUtils.setField(p, "id", nextId++);
                rows.add(p);
            }
            return p;
        });
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findFirstBySkuAndRefIdAndStatus(any(), anyLong(), any())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getSku() == inv.getArgument(0) && r.getRefId() == (long) inv.getArgument(1)
                        && r.getStatus() == inv.getArgument(2))
                .findFirst());
        when(repo.findByPaymentIntentId(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(r -> Long.valueOf((long) inv.getArgument(0)).equals(r.getPaymentIntentId())).findFirst());
        when(intents.createIntent(eq(USER), any(), eq(PayChannel.QRIS), anyLong(), eq("IDR"), anyString(), any()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(5);
                    PaymentIntent pi = intentByKey.computeIfAbsent(key, k -> {
                        PaymentIntent n = PaymentIntent.create(USER, inv.getArgument(1), PayChannel.QRIS,
                                inv.getArgument(3), "IDR", "pi" + (nextId++));
                        ReflectionTestUtils.setField(n, "id", nextId++);
                        ReflectionTestUtils.setField(n, "createdAt", Instant.EPOCH);
                        return n;
                    });
                    return PaymentIntentResponse.of(pi);
                });
        when(intents.findByToken(anyString())).thenAnswer(inv -> intentByKey.values().stream()
                .filter(p -> p.getPublicToken().equals(inv.getArgument(0))).findFirst());
        when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw", "QR-PAYLOAD", Map.of()));
        service = new KeepsakePurchaseService(repo, intents, wallet, config, gateway, grants, tokens, events,
                Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"), ZoneOffset.UTC));
    }

    private static KeepsakeRef ref(KeepsakeSku sku, long refId, boolean unlocked) {
        return new KeepsakeRef(sku, refId, "tok" + refId, 70L, unlocked);
    }

    @Test
    void pawcoinTwoDifferentRefsDebitTwiceWithTheirOwnKeys() {
        service.start(USER, ref(KeepsakeSku.PASSPORT_SNAP, 1, false), PayChannel.PAWCOIN);
        service.start(USER, ref(KeepsakeSku.PASSPORT_SNAP, 2, false), PayChannel.PAWCOIN);
        verify(wallet).debit(USER, 2000, PawCoinTxnType.SPEND, "PASSPORT_SNAP", 1L, "PASSPORT_SNAP:tok1:PAWCOIN");
        verify(wallet).debit(USER, 2000, PawCoinTxnType.SPEND, "PASSPORT_SNAP", 2L, "PASSPORT_SNAP:tok2:PAWCOIN");
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(KeepsakePurchaseStatus.PAID);
            assertThat(r.getPriceIdr()).isEqualTo(2000);
            assertThat(r.getPayChannel()).isEqualTo(PayChannel.PAWCOIN);
            assertThat(r.getPaymentIntentId()).isNull();
            assertThat(r.getPaidAt()).isNotNull();
        });
        verify(events, times(2)).publishEvent(any(KeepsakeUnlockedEvent.class));
    }

    @Test
    void alreadyUnlockedIs409AndNothingIsCharged() {
        assertThatThrownBy(() -> service.start(USER, ref(KeepsakeSku.TAILSONALITY, 1, true), PayChannel.PAWCOIN))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType().toString()).endsWith("keepsake-already-unlocked");
                });
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    void mixedIs422() {
        assertThatThrownBy(() -> service.start(USER, ref(KeepsakeSku.TAILSONALITY, 1, false), PayChannel.MIXED))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessage("该商品不支持混合支付");
    }

    @Test
    void qrisThenPawcoinUsesDifferentKeysSoPawcoinReallyDebits() {
        service.start(USER, ref(KeepsakeSku.TAILSONALITY, 5, false), PayChannel.QRIS);
        service.start(USER, ref(KeepsakeSku.TAILSONALITY, 5, false), PayChannel.PAWCOIN);
        ArgumentCaptor<String> qrisKey = ArgumentCaptor.forClass(String.class);
        verify(intents).createIntent(eq(USER), eq(PaymentPurpose.TAILSONALITY), eq(PayChannel.QRIS), eq(5000L), eq("IDR"),
                qrisKey.capture(), any());
        assertThat(qrisKey.getValue()).isEqualTo("TAILSONALITY:tok5:QRIS");
        verify(wallet).debit(USER, 5000, PawCoinTxnType.SPEND, "TAILSONALITY", 5L, "TAILSONALITY:tok5:PAWCOIN");
    }

    @Test
    void qrisTwiceReusesTheSameRowAndIntent() {
        KeepsakePurchaseResponse a = service.start(USER, ref(KeepsakeSku.BOARDING_PASS, 9, false), PayChannel.QRIS);
        KeepsakePurchaseResponse b = service.start(USER, ref(KeepsakeSku.BOARDING_PASS, 9, false), PayChannel.QRIS);
        assertThat(rows).hasSize(1);
        assertThat(a.purchaseToken()).isEqualTo(b.purchaseToken());
        assertThat(a.payment().token()).isEqualTo(b.payment().token());
        assertThat(a.unlocked()).isFalse();
        assertThat(a.payload()).isEqualTo("QR-PAYLOAD");
        assertThat(a.payment().displayNo()).startsWith("PAYBP-");
        KeepsakePurchase row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(KeepsakePurchaseStatus.PENDING);
        assertThat(row.getPayChannel()).isEqualTo(PayChannel.QRIS);
        assertThat(row.getPriceIdr()).isEqualTo(1000);
    }

    @Test
    void qrisRowPriceIsTheIntentAmountNotTheLocalVariable() {
        // 意图已按旧价 1000 建好；随后后台改价到 3000 —— createIntent 复用未过窗旧意图，行价跟意图走。
        service.start(USER, ref(KeepsakeSku.BOARDING_PASS, 3, false), PayChannel.QRIS);
        pricing.setPassportBoardingUnlockPrice(3000);
        when(intents.createIntent(eq(USER), any(), eq(PayChannel.QRIS), eq(3000L), eq("IDR"),
                eq("BOARDING_PASS:tok3:QRIS"), any()))
                .thenAnswer(inv -> PaymentIntentResponse.of(intentByKey.get("BOARDING_PASS:tok3:QRIS")));
        service.start(USER, ref(KeepsakeSku.BOARDING_PASS, 3, false), PayChannel.QRIS);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPriceIdr()).isEqualTo(1000);
        // 新发起的另一业务行用新价。
        service.start(USER, ref(KeepsakeSku.BOARDING_PASS, 4, false), PayChannel.QRIS);
        assertThat(rows.get(1).getPriceIdr()).isEqualTo(3000);
    }

    @Test
    void newIntentExpiresTheOldPendingRowAndCreatesANewOne() {
        service.start(USER, ref(KeepsakeSku.TAILSONALITY, 8, false), PayChannel.QRIS);
        // 旧意图过窗：下一次 createIntent 返回一个新意图（映射覆盖）。
        intentByKey.remove("TAILSONALITY:tok8:QRIS");
        service.start(USER, ref(KeepsakeSku.TAILSONALITY, 8, false), PayChannel.QRIS);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(KeepsakePurchaseStatus.EXPIRED);
        assertThat(rows.get(1).getStatus()).isEqualTo(KeepsakePurchaseStatus.PENDING);
        assertThat(rows.get(1).getPaymentIntentId()).isNotEqualTo(rows.get(0).getPaymentIntentId());
    }

    @Test
    void paidIntentReturnsUnlockedWhenItsRowIsPaidOtherwise409() {
        service.start(USER, ref(KeepsakeSku.TAILSONALITY, 6, false), PayChannel.QRIS);
        PaymentIntent pi = intentByKey.get("TAILSONALITY:tok6:QRIS");
        ReflectionTestUtils.setField(pi, "status", PaymentStatus.PAID);
        rows.get(0).markPaid(5000, Instant.EPOCH);
        assertThat(service.start(USER, ref(KeepsakeSku.TAILSONALITY, 6, false), PayChannel.QRIS).unlocked()).isTrue();
        rows.get(0).markStatus(KeepsakePurchaseStatus.ORPHAN_PAID);
        assertThatThrownBy(() -> service.start(USER, ref(KeepsakeSku.TAILSONALITY, 6, false), PayChannel.QRIS))
                .isInstanceOf(AppException.class);
        assertThat(rows).hasSize(1);
    }

    @Test
    void pawcoinAlreadyUnlockedIsDuplicatePaidAndDoesNotPublish() {
        when(grants.grantOrNull(any())).thenReturn(GrantOutcome.ALREADY_UNLOCKED);
        assertThat(service.start(USER, ref(KeepsakeSku.PASSPORT_SNAP, 11, false), PayChannel.PAWCOIN).unlocked()).isTrue();
        assertThat(rows).extracting(KeepsakePurchase::getStatus).containsExactly(KeepsakePurchaseStatus.DUPLICATE_PAID);
        verify(events, never()).publishEvent(any(KeepsakeUnlockedEvent.class));
    }

    /** 复审：PawCoin 发放失败 / 业务行不存在 → 抛出让整笔回滚（不扣币），绝不回复「已解锁」。 */
    @Test
    void pawcoinGrantFailureOrMissingRefThrowsSoTheWholeChargeRollsBack() {
        for (GrantOutcome o : new GrantOutcome[] {null, GrantOutcome.REF_MISSING}) {
            when(grants.grantOrNull(any())).thenReturn(o);
            assertThatThrownBy(() -> service.start(USER, ref(KeepsakeSku.PASSPORT_SNAP, 12, false), PayChannel.PAWCOIN))
                    .isInstanceOfSatisfying(AppException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        verify(events, never()).publishEvent(any(KeepsakeUnlockedEvent.class));
    }

    @Test
    void pricesAreReadPerSku() {
        assertThat(KeepsakePurchaseService.priceOf(pricing, KeepsakeSku.TAILSONALITY)).isEqualTo(5000);
        assertThat(KeepsakePurchaseService.priceOf(pricing, KeepsakeSku.PASSPORT_SNAP)).isEqualTo(2000);
        assertThat(KeepsakePurchaseService.priceOf(pricing, KeepsakeSku.BOARDING_PASS)).isEqualTo(1000);
        assertThat(Optional.of(KeepsakeSku.TAILSONALITY.toPurpose())).contains(PaymentPurpose.TAILSONALITY);
    }
}
