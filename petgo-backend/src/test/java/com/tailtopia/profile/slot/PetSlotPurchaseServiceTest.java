package com.tailtopia.profile.slot;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseResponse;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseStatusView;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.PetSlotRentUnavailableException;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.4.0 multi-pet Story 2.2 · AC4 / AC5 / AC7.2 / AC8.5 —— 新租 / 续租下单（PawCoin 同步成交、QRIS 建意图 + PENDING 行）、
 * 可租数与续租上限、幂等键按渠道加后缀、改价只影响新单、购买状态自愈。纯 Mockito。
 */
class PetSlotPurchaseServiceTest {

    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");
    private static final String CK = "client-key-1";

    private final PetSlotPurchaseRepository purchases = mock(PetSlotPurchaseRepository.class);
    private final PetSlotRentalRepository rentals = mock(PetSlotRentalRepository.class);
    private final PetSlotService slots = mock(PetSlotService.class);
    private final PetSlotStateService state = mock(PetSlotStateService.class);
    private final PetSlotLock lock = mock(PetSlotLock.class);
    private final PaymentIntentService paymentIntents = mock(PaymentIntentService.class);
    private final PaymentIntentRepository intentRepo = mock(PaymentIntentRepository.class);
    private final PawCoinWalletService wallet = mock(PawCoinWalletService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PetSlotTokenGenerator tokens = mock(PetSlotTokenGenerator.class);
    private final PetSlotGranter granter = mock(PetSlotGranter.class);
    private final AtomicLong ids = new AtomicLong(100);
    /** granter 桩每次发放的名额 id（RENT 新建 / RENEW 原样延长）。 */
    private final List<Long> grantedIds = new ArrayList<>();
    private PetSlotPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PetSlotPurchaseService(purchases, rentals, slots, state, lock, paymentIntents, intentRepo,
                wallet, gateway, granter, tokens, mock(com.tailtopia.auth.service.AccountQueryService.class),
                mock(com.tailtopia.account.repository.AccountDeletionRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
        when(tokens.generate()).thenAnswer(inv -> "tok" + ids.incrementAndGet());
        when(purchases.saveAndFlush(any(PetSlotPurchase.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(purchases.save(any(PetSlotPurchase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(purchases.findByIdemKey(anyString())).thenReturn(Optional.empty());
        when(granter.grant(any(PetSlotPurchase.class), any(Instant.class))).thenAnswer(inv -> {
            PetSlotPurchase row = inv.getArgument(0);
            List<Long> out = new ArrayList<>();
            if (row.getKind() == PetSlotPurchaseKind.RENEW) {
                out.addAll(row.rentalIdList());
            } else {
                for (int i = 0; i < row.getQuantity(); i++) {
                    out.add(ids.incrementAndGet());
                }
            }
            grantedIds.addAll(out);
            return new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED, out,
                    row.getKind() == PetSlotPurchaseKind.RENEW ? PetSlotTrigger.RENEWED : PetSlotTrigger.RENTAL_ADDED);
        });
        when(slots.unitPrice()).thenReturn(5000L);
        quotaTotal(1, 0); // Lv1、无月租
    }

    private <T> T withId(T entity) {
        if (ReflectionTestUtils.getField(entity, "id") == null) {
            ReflectionTestUtils.setField(entity, "id", ids.incrementAndGet());
        }
        return entity;
    }

    /** 名额构成：total、等级名额；rentalSlots = total − 1 − levelSlots。 */
    private void quotaTotal(int total, int levelSlots) {
        when(slots.quotaOf(USER)).thenReturn(new PetSlotQuota(total, 1, levelSlots, total - 1 - levelSlots, 1,
                5 - total, 5 - total > 0, null, total - 1 - levelSlots > 0));
    }

    private PaymentIntent intent(long id, long amount, PaymentStatus status) {
        PaymentIntent pi = PaymentIntent.create(USER, PaymentPurpose.PET_SLOT_RENT, PayChannel.QRIS, amount, "IDR",
                "pi" + id, NOW.plus(Duration.ofMinutes(60)));
        ReflectionTestUtils.setField(pi, "id", id);
        ReflectionTestUtils.setField(pi, "status", status);
        return pi;
    }

    private void stubIntent(PaymentIntent pi) {
        when(paymentIntents.createIntent(eq(USER), eq(PaymentPurpose.PET_SLOT_RENT), eq(PayChannel.QRIS), anyLong(),
                eq("IDR"), anyString(), any(Duration.class))).thenReturn(PaymentIntentResponse.of(pi));
        when(paymentIntents.findByToken(pi.getPublicToken())).thenReturn(Optional.of(pi));
    }

    private PetSlotRental liveRental(long id, Instant expiresAt) {
        PetSlotRental r = PetSlotRental.start("rt" + id, USER, PetSlotRentalSource.RENT,
                expiresAt.minus(Duration.ofDays(30)), 30, 1L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "remindedAt", NOW.minus(Duration.ofDays(1)));
        return r;
    }

    @Nested
    class Rent {

        @Test
        void pawcoinDebitsWithRefTypeAndPurchaseIdAndChannelSuffixedKeyThenGrantsAndReconciles() {
            PetSlotPurchaseResponse resp = service.rent(USER, 2, PayChannel.PAWCOIN, CK);

            ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
            verify(purchases).saveAndFlush(row.capture());
            PetSlotPurchase p = row.getValue();
            String key = "PET_SLOT_RENT:7:RENT:" + CK + ":PAWCOIN";
            verify(wallet).debit(USER, 10_000L, PawCoinTxnType.SPEND, "PET_SLOT_RENT", p.getId(), key);
            assertThat(p.getIdemKey()).isEqualTo(key);
            assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
            assertThat(p.getPaidAt()).isEqualTo(NOW);
            assertThat(p.getUnitPrice()).isEqualTo(5000L);
            assertThat(p.getTotal()).isEqualTo(10_000L);
            assertThat(p.getQuantity()).isEqualTo(2);
            assertThat(p.getKind()).isEqualTo(PetSlotPurchaseKind.RENT);
            verify(granter).grant(p, NOW); // 发放细节（+30 天、source、封顶、保存点）见 PetSlotGranterTest
            assertThat(grantedIds).hasSize(2);
            assertThat(p.rentalIdList()).containsExactlyElementsOf(grantedIds);
            verify(state).reconcile(USER, PetSlotTrigger.RENTAL_ADDED);
            assertThat(resp.unlocked()).isTrue();
            assertThat(resp.purchaseToken()).isEqualTo(p.getPublicToken());
            assertThat(resp.payment()).isNull();
        }

        @Test
        void locksAccountBeforeReadingQuota() {
            service.rent(USER, 1, PayChannel.PAWCOIN, CK);
            InOrder o = inOrder(lock, slots, wallet, state);
            o.verify(lock).lockUser(USER);
            o.verify(slots).quotaOf(USER);
            o.verify(wallet).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            o.verify(state).reconcile(USER, PetSlotTrigger.RENTAL_ADDED);
        }

        @Test
        void insufficientBalancePropagatesAndNothingIsGranted() {
            doThrow(AppException.pawcoinInsufficient("PawCoin 余额不足")).when(wallet)
                    .debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            assertThatThrownBy(() -> service.rent(USER, 2, PayChannel.PAWCOIN, CK))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.PAWCOIN_INSUFFICIENT);
            verify(granter, never()).grant(any(), any());
            verify(state, never()).reconcile(anyLong(), any());
        }

        @Test
        void quantityAboveRentableIs409WithRentable() {
            quotaTotal(3, 0); // 已租 2：rentable = 2
            assertThatThrownBy(() -> service.rent(USER, 3, PayChannel.PAWCOIN, CK))
                    .isInstanceOfSatisfying(PetSlotRentUnavailableException.class, e -> {
                        assertThat(e.getType()).isEqualTo(ErrorTypes.PET_SLOT_RENT_UNAVAILABLE);
                        assertThat(e.problemExtensions()).containsEntry("rentable", 2);
                    });
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            service.rent(USER, 2, PayChannel.PAWCOIN, CK); // 恰好 rentable 可租
        }

        @Test
        void fullQuotaRejectsAnyRentAndZeroIsRejected() {
            quotaTotal(5, 0);
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, CK))
                    .isInstanceOfSatisfying(PetSlotRentUnavailableException.class,
                            e -> assertThat(e.getRentable()).isZero());
            quotaTotal(1, 0);
            assertThatThrownBy(() -> service.rent(USER, 0, PayChannel.QRIS, CK))
                    .isInstanceOf(PetSlotRentUnavailableException.class);
            verify(paymentIntents, never()).createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(),
                    any(Duration.class));
        }

        @Test
        void mixedIs422AndMissingKeyIs422() {
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.MIXED, CK))
                    .isInstanceOf(AppException.class).hasMessageContaining("不支持混合支付")
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, " "))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, "k".repeat(101)))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
            verify(lock, never()).lockUser(anyLong());
        }

        @Test
        void paidReplayReturnsSameResultWithoutCheckingRentableOrCharging() {
            PetSlotPurchase paid = withId(PetSlotPurchase.pending("done", USER, PetSlotPurchaseKind.RENT, 4, 5000, 20_000,
                    PayChannel.PAWCOIN, null, "k", null, NOW));
            paid.markPaid(NOW);
            when(purchases.findByIdemKey("PET_SLOT_RENT:7:RENT:" + CK + ":PAWCOIN")).thenReturn(Optional.of(paid));
            quotaTotal(5, 0); // 已租满：重放仍返回原结果，不 409
            PetSlotPurchaseResponse resp = service.rent(USER, 4, PayChannel.PAWCOIN, CK);
            assertThat(resp.unlocked()).isTrue();
            assertThat(resp.purchaseToken()).isEqualTo("done");
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            verify(state, never()).reconcile(anyLong(), any());
        }

        @Test
        void qrisCreatesIntentForUnitPriceTimesQuantityAndPendingRow() {
            PaymentIntent pi = intent(55L, 15_000L, PaymentStatus.PENDING);
            stubIntent(pi);
            when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-1", "QRPAYLOAD", Map.of()));

            PetSlotPurchaseResponse resp = service.rent(USER, 3, PayChannel.QRIS, CK);

            verify(paymentIntents).createIntent(USER, PaymentPurpose.PET_SLOT_RENT, PayChannel.QRIS, 15_000L, "IDR",
                    "PET_SLOT_RENT:7:RENT:" + CK + ":QRIS", PetSlotPurchaseService.PAY_WINDOW);
            ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
            verify(purchases).saveAndFlush(row.capture());
            assertThat(row.getValue().getStatus()).isEqualTo(PetSlotPurchaseStatus.PENDING);
            assertThat(row.getValue().getPaymentIntentId()).isEqualTo(55L);
            assertThat(row.getValue().getTotal()).isEqualTo(15_000L);
            assertThat(row.getValue().getChannel()).isEqualTo(PayChannel.QRIS);
            verify(paymentIntents).attachCharge(eq("pi55"), eq("gw-1"), any());
            assertThat(resp.unlocked()).isFalse();
            assertThat(resp.payload()).isEqualTo("QRPAYLOAD");
            assertThat(resp.payment().token()).isEqualTo("pi55");
            assertThat(resp.purchaseToken()).isEqualTo(row.getValue().getPublicToken());
            verify(granter, never()).grant(any(), any()); // 到账才发放
            assertThat(row.getValue().getIdemKey()).isEqualTo("PET_SLOT_RENT:7:RENT:" + CK + ":QRIS:55");
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            verify(state, never()).reconcile(anyLong(), any());
        }

        @Test
        void qrisIntentAlreadyPaidReturnsCompletedPurchase() {
            PaymentIntent pi = intent(56L, 5000L, PaymentStatus.PAID);
            stubIntent(pi);
            PetSlotPurchase paid = withId(PetSlotPurchase.pending("paidTok", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 56L, "x", null, NOW));
            paid.markPaid(NOW);
            when(purchases.findByPaymentIntentId(56L)).thenReturn(Optional.of(paid));
            PetSlotPurchaseResponse resp = service.rent(USER, 1, PayChannel.QRIS, CK);
            assertThat(resp.unlocked()).isTrue();
            assertThat(resp.purchaseToken()).isEqualTo("paidTok");
            verify(gateway, never()).createCharge(any());
        }

        @Test
        void qrisReplayWithSameIntentReusesRowAndPayload() {
            PaymentIntent pi = intent(57L, 5000L, PaymentStatus.PENDING);
            ReflectionTestUtils.setField(pi, "gatewayRef", "gw-x");
            ReflectionTestUtils.setField(pi, "gatewayMeta", Map.of("payload", "SAVED"));
            stubIntent(pi);
            PetSlotPurchase existing = withId(PetSlotPurchase.pending("pend", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 57L, "PET_SLOT_RENT:7:RENT:" + CK + ":QRIS:57", null, NOW));
            when(purchases.findByPaymentIntentId(57L)).thenReturn(Optional.of(existing));
            quotaTotal(5, 0); // 本单已在待付里：重放不再判可租数
            PetSlotPurchaseResponse resp = service.rent(USER, 1, PayChannel.QRIS, CK);
            assertThat(resp.purchaseToken()).isEqualTo("pend");
            assertThat(resp.payload()).isEqualTo("SAVED");
            verify(purchases, never()).saveAndFlush(any());
            verify(gateway, never()).createCharge(any());
        }

        /** code review #3：同键重开（旧意图已过期）→ 旧行置 EXPIRED、另起新行（idem_key 带新意图 id），绝不改挂。 */
        @Test
        void qrisSameKeyAfterIntentExpiredExpiresOldRowAndInsertsNewRow() {
            PaymentIntent fresh = intent(60L, 7000L, PaymentStatus.PENDING);
            stubIntent(fresh);
            when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-2", "P2", Map.of()));
            when(slots.unitPrice()).thenReturn(7000L);
            String key = "PET_SLOT_RENT:7:RENT:" + CK + ":QRIS";
            PetSlotPurchase old = withId(PetSlotPurchase.pending("same", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 58L, key + ":58", null, NOW));
            PetSlotPurchase otherKey = withId(PetSlotPurchase.pending("other", USER, PetSlotPurchaseKind.RENT, 1, 5000,
                    5000, PayChannel.QRIS, 59L, key + ":QRIS:59", null, NOW)); // 另一客户端键恰以本键为前缀：不动
            when(purchases.findByUserIdAndStatus(USER, PetSlotPurchaseStatus.PENDING)).thenReturn(List.of(old, otherKey));

            PetSlotPurchaseResponse resp = service.rent(USER, 1, PayChannel.QRIS, CK);

            assertThat(old.getStatus()).isEqualTo(PetSlotPurchaseStatus.EXPIRED);
            assertThat(old.getPaymentIntentId()).isEqualTo(58L); // 不改挂：旧二维码仍付成功也能找到旧行
            assertThat(otherKey.getStatus()).isEqualTo(PetSlotPurchaseStatus.PENDING);
            assertThat(resp.purchaseToken()).isNotEqualTo("same");
            ArgumentCaptor<PetSlotPurchase> rows = ArgumentCaptor.forClass(PetSlotPurchase.class);
            verify(purchases, org.mockito.Mockito.times(2)).saveAndFlush(rows.capture());
            PetSlotPurchase created = rows.getAllValues().get(1);
            assertThat(created.getIdemKey()).isEqualTo(key + ":60");
            assertThat(created.getPaymentIntentId()).isEqualTo(60L);
            assertThat(created.getUnitPrice()).isEqualTo(7000L);
            assertThat(created.getTotal()).isEqualTo(7000L);
        }

        /** code review #4：QRIS 先建意图（可能改写旧意图行）再取账号锁，与 applyCallback（意图行 → 账号锁）同序。 */
        @Test
        void qrisCreatesIntentBeforeTakingAccountLock() {
            stubIntent(intent(61L, 5000L, PaymentStatus.PENDING));
            when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-4", "P4", Map.of()));
            service.rent(USER, 1, PayChannel.QRIS, CK);
            InOrder o = inOrder(paymentIntents, lock, purchases);
            o.verify(paymentIntents).createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(),
                    any(Duration.class));
            o.verify(lock).lockUser(USER);
            o.verify(purchases).sumOpenRentSlots(USER, NOW, 61L); // 锁内判可租数，排除本意图
        }

        /** code review #2：QRIS 租 4 未付时再用 PawCoin 租 → 待付名额计入，409 rentable 0。 */
        @Test
        void openPendingRentOrdersCountAgainstRentable() {
            when(purchases.sumOpenRentSlots(USER, NOW, -1L)).thenReturn(4L);
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, CK))
                    .isInstanceOfSatisfying(PetSlotRentUnavailableException.class,
                            e -> assertThat(e.getRentable()).isZero());
            when(purchases.sumOpenRentSlots(USER, NOW, -1L)).thenReturn(2L);
            when(purchases.countOpenRenewConversions(USER, NOW, -1L)).thenReturn(1L);
            assertThatThrownBy(() -> service.rent(USER, 2, PayChannel.PAWCOIN, CK))
                    .isInstanceOfSatisfying(PetSlotRentUnavailableException.class,
                            e -> assertThat(e.getRentable()).isEqualTo(1));
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
            service.rent(USER, 1, PayChannel.PAWCOIN, CK);
        }

        @Test
        void qrisSecondOrderWithOtherKeySeesFirstPendingOrder() {
            stubIntent(intent(62L, 15_000L, PaymentStatus.PENDING));
            when(purchases.sumOpenRentSlots(USER, NOW, 62L)).thenReturn(2L); // 另一键的待付新租 2 个
            assertThatThrownBy(() -> service.rent(USER, 3, PayChannel.QRIS, "other-key"))
                    .isInstanceOf(PetSlotRentUnavailableException.class);
            verify(gateway, never()).createCharge(any());
            verify(purchases, never()).saveAndFlush(any());
        }

        /** PawCoin 同步成交：发放会超 5（锁内已判，正常不可达）→ 409 整笔回滚，不 reconcile。 */
        @Test
        void pawcoinGrantOverCapIsRejected() {
            when(granter.grant(any(PetSlotPurchase.class), any(Instant.class))).thenReturn(new PetSlotGranter.Result(
                    PetSlotGranter.Outcome.OVER_CAP, List.of(), PetSlotTrigger.RENTAL_ADDED));
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, CK))
                    .isInstanceOf(PetSlotRentUnavailableException.class);
            verify(state, never()).reconcile(anyLong(), any());
        }

        @Test
        void pawcoinGrantFailurePropagatesSoWholeChargeRollsBack() {
            when(granter.grant(any(PetSlotPurchase.class), any(Instant.class)))
                    .thenThrow(new IllegalStateException("insert failed"));
            assertThatThrownBy(() -> service.rent(USER, 1, PayChannel.PAWCOIN, CK))
                    .isInstanceOf(IllegalStateException.class);
            verify(state, never()).reconcile(anyLong(), any());
        }

        @Test
        void priceChangeAffectsOnlyNewOrdersAndPendingRowKeepsSnapshot() {
            PetSlotPurchase pendingAt5000 = PetSlotPurchase.pending("p", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 1L, "k", null, NOW);
            when(slots.unitPrice()).thenReturn(7000L);
            service.rent(USER, 1, PayChannel.PAWCOIN, CK);
            verify(wallet).debit(eq(USER), eq(7000L), any(), anyString(), any(), anyString());
            assertThat(pendingAt5000.getUnitPrice()).isEqualTo(5000L); // 快照不随配置变
            assertThat(pendingAt5000.getTotal()).isEqualTo(5000L);
        }

        @Test
        void pawcoinAndQrisKeysDiffer() {
            assertThat(PetSlotPurchaseService.idempotencyKey(USER, PetSlotPurchaseKind.RENT, CK, PayChannel.QRIS))
                    .isNotEqualTo(PetSlotPurchaseService.idempotencyKey(USER, PetSlotPurchaseKind.RENT, CK,
                            PayChannel.PAWCOIN))
                    .isNotEqualTo(PetSlotPurchaseService.idempotencyKey(USER, PetSlotPurchaseKind.RENEW, CK,
                            PayChannel.QRIS));
        }
    }

    @Nested
    class Renew {

        private final Instant oct31 = Instant.parse("2026-10-31T03:00:00Z");

        @Test
        void pawcoinExtendsEachSelectedRentalBy30DaysAndClearsReminder() {
            quotaTotal(3, 0);
            PetSlotRental a = liveRental(11L, oct31);
            PetSlotRental b = liveRental(12L, oct31.plus(Duration.ofDays(2)));
            when(rentals.findByPublicTokenAndUserId("rt11", USER)).thenReturn(Optional.of(a));
            when(rentals.findByPublicTokenAndUserId("rt12", USER)).thenReturn(Optional.of(b));
            when(rentals.findById(11L)).thenReturn(Optional.of(a));
            when(rentals.findById(12L)).thenReturn(Optional.of(b));

            PetSlotPurchaseResponse resp = service.renew(USER, List.of("rt11", "rt12"), PayChannel.PAWCOIN, CK);

            ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
            verify(purchases).saveAndFlush(row.capture());
            PetSlotPurchase p = row.getValue();
            verify(wallet).debit(USER, 10_000L, PawCoinTxnType.SPEND, "PET_SLOT_RENT", p.getId(),
                    "PET_SLOT_RENT:7:RENEW:" + CK + ":PAWCOIN");
            assertThat(p.getKind()).isEqualTo(PetSlotPurchaseKind.RENEW);
            assertThat(p.getQuantity()).isEqualTo(2);
            assertThat(p.rentalIdList()).containsExactly(11L, 12L);
            verify(granter).grant(p, NOW); // 「+30 天、reminded_at 清空」（AC11：10/31 → 11/30）见 PetSlotGranterTest
            assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
            verify(state).reconcile(USER, PetSlotTrigger.RENEWED);
            assertThat(resp.unlocked()).isTrue();
        }

        @Test
        void expiredRentalCannotBeRenewed() {
            PetSlotRental gone = liveRental(13L, NOW.minus(Duration.ofHours(1))); // 已过到期时刻（扫描尚未置 EXPIRED）
            when(rentals.findByPublicTokenAndUserId("rt13", USER)).thenReturn(Optional.of(gone));
            assertThatThrownBy(() -> service.renew(USER, List.of("rt13"), PayChannel.PAWCOIN, CK))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.PET_SLOT_RENEW_INVALID);
            PetSlotRental marked = liveRental(14L, oct31);
            ReflectionTestUtils.setField(marked, "status", com.tailtopia.profile.slot.domain.PetSlotRentalStatus.EXPIRED);
            when(rentals.findByPublicTokenAndUserId("rt14", USER)).thenReturn(Optional.of(marked));
            assertThatThrownBy(() -> service.renew(USER, List.of("rt14"), PayChannel.QRIS, CK))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.PET_SLOT_RENEW_INVALID);
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
        }

        @Test
        void othersOrMissingRentalIs404() {
            when(rentals.findByPublicTokenAndUserId("nope", USER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.renew(USER, List.of("nope"), PayChannel.PAWCOIN, CK))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_FOUND);
        }

        @Test
        void renewCountAboveFiveMinusOneMinusLevelSlotsIs409() {
            quotaTotal(5, 2); // Lv10：只能续到 5 − 1 − 2 = 2 个
            List<String> tokens = new ArrayList<>();
            for (long id = 21; id <= 23; id++) {
                PetSlotRental r = liveRental(id, oct31);
                when(rentals.findByPublicTokenAndUserId("rt" + id, USER)).thenReturn(Optional.of(r));
                tokens.add("rt" + id);
            }
            assertThatThrownBy(() -> service.renew(USER, tokens, PayChannel.PAWCOIN, CK))
                    .isInstanceOfSatisfying(PetSlotRentUnavailableException.class, e -> {
                        assertThat(e.getType()).isEqualTo(ErrorTypes.PET_SLOT_RENT_UNAVAILABLE);
                        assertThat(e.getRenewableMax()).isEqualTo(2);
                    });
            verify(wallet, never()).debit(anyLong(), anyLong(), any(), anyString(), any(), anyString());
        }

        @Test
        void invalidTokenListsAre422BeforeLocking() {
            for (List<String> bad : List.of(List.<String>of(), List.of("a", "a"), List.of("a", "b", "c", "d", "e", "f"))) {
                assertThatThrownBy(() -> service.renew(USER, bad, PayChannel.PAWCOIN, CK))
                        .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
            }
            assertThatThrownBy(() -> service.renew(USER, null, PayChannel.PAWCOIN, CK))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
            verify(lock, never()).lockUser(anyLong());
        }

        @Test
        void qrisRenewPendingRowCarriesSelectedRentalIds() {
            quotaTotal(3, 0);
            PetSlotRental a = liveRental(31L, oct31);
            when(rentals.findByPublicTokenAndUserId("rt31", USER)).thenReturn(Optional.of(a));
            PaymentIntent pi = intent(70L, 5000L, PaymentStatus.PENDING);
            stubIntent(pi);
            when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-3", "P3", Map.of()));
            service.renew(USER, List.of("rt31"), PayChannel.QRIS, CK);
            ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
            verify(purchases).saveAndFlush(row.capture());
            assertThat(row.getValue().getKind()).isEqualTo(PetSlotPurchaseKind.RENEW);
            assertThat(row.getValue().rentalIdList()).containsExactly(31L);
            assertThat(a.getExpiresAt()).isEqualTo(oct31); // 到账才延长
        }
    }

    @Nested
    class Status {

        @Test
        void othersPurchaseIs404() {
            when(purchases.findByPublicTokenAndUserId("x", USER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.status(USER, "x"))
                    .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_FOUND);
        }

        @Test
        void pendingRowSelfHealsWhenIntentLazilyExpired() {
            PetSlotPurchase row = withId(PetSlotPurchase.pending("pt", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 80L, "k", null, NOW));
            when(purchases.findByPublicTokenAndUserId("pt", USER)).thenReturn(Optional.of(row));
            PaymentIntent pi = intent(80L, 5000L, PaymentStatus.PENDING);
            when(intentRepo.findById(80L)).thenReturn(Optional.of(pi));
            when(paymentIntents.statusOf(USER, "pi80")).thenAnswer(inv -> {
                ReflectionTestUtils.setField(pi, "status", PaymentStatus.EXPIRED);
                return PaymentStatus.EXPIRED;
            });
            PetSlotPurchaseStatusView v = service.status(USER, "pt");
            assertThat(v.status()).isEqualTo("EXPIRED");
            assertThat(v.expiresAt()).isNull();
            assertThat(row.getStatus()).isEqualTo(PetSlotPurchaseStatus.EXPIRED);
        }

        @Test
        void pendingRowBecomesFailedWhenIntentDeclined() {
            PetSlotPurchase row = withId(PetSlotPurchase.pending("pf", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 81L, "k", null, NOW));
            when(purchases.findByPublicTokenAndUserId("pf", USER)).thenReturn(Optional.of(row));
            PaymentIntent pi = intent(81L, 5000L, PaymentStatus.FAILED);
            when(intentRepo.findById(81L)).thenReturn(Optional.of(pi));
            when(paymentIntents.statusOf(USER, "pi81")).thenReturn(PaymentStatus.FAILED);
            assertThat(service.status(USER, "pf").status()).isEqualTo("FAILED");
        }

        @Test
        void pendingWithLiveIntentStaysPending() {
            PetSlotPurchase row = withId(PetSlotPurchase.pending("pp", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    PayChannel.QRIS, 82L, "k", null, NOW));
            when(purchases.findByPublicTokenAndUserId("pp", USER)).thenReturn(Optional.of(row));
            PaymentIntent pi = intent(82L, 5000L, PaymentStatus.PENDING);
            when(intentRepo.findById(82L)).thenReturn(Optional.of(pi));
            when(paymentIntents.statusOf(USER, "pi82")).thenReturn(PaymentStatus.PENDING);
            assertThat(service.status(USER, "pp").status()).isEqualTo("PENDING");
            verify(purchases, never()).save(any());
        }

        @Test
        void paidReturnsLatestExpiryOfGrantedRentals() {
            PetSlotPurchase row = withId(PetSlotPurchase.pending("pd", USER, PetSlotPurchaseKind.RENT, 2, 5000, 10_000,
                    PayChannel.PAWCOIN, null, "k", null, NOW));
            row.markPaid(NOW);
            row.setRentalIds(List.of(41L, 42L));
            when(purchases.findByPublicTokenAndUserId("pd", USER)).thenReturn(Optional.of(row));
            PetSlotRental r1 = liveRental(41L, Instant.parse("2026-11-27T03:00:00Z"));
            PetSlotRental r2 = liveRental(42L, Instant.parse("2026-11-30T03:00:00Z"));
            when(rentals.findAllById(List.of(41L, 42L))).thenReturn(List.of(r1, r2));
            PetSlotPurchaseStatusView v = service.status(USER, "pd");
            assertThat(v.status()).isEqualTo("PAID");
            assertThat(v.expiresAt()).isEqualTo(Instant.parse("2026-11-30T03:00:00Z"));
            verify(paymentIntents, never()).statusOf(anyLong(), anyString());
        }
    }
}
