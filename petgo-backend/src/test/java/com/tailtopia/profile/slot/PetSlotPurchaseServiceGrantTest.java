package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PaymentGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.4.0 multi-pet Story 4.3 · AC5 —— 运营补发 {@link PetSlotPurchaseService#grantByAdmin}：校验 422 / 404、
 * 账号锁内按 {@code quotaOf} 判 5 封顶、ADMIN_GRANT 购买行 + 经 {@link PetSlotGranter} 发放、reconcile(RENTAL_ADDED)、
 * 同 token 重放不重复建行；不碰任何 PawCoin / 支付服务。纯 Mockito。
 */
class PetSlotPurchaseServiceGrantTest {

    private static final long USER = 7L;
    private static final long ADMIN = 900L;
    private static final Instant NOW = Instant.parse("2026-10-13T08:40:00Z");
    private static final String TOKEN = "0b6f5c1e-token";

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
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    private final AtomicLong ids = new AtomicLong(100);
    private PetSlotPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PetSlotPurchaseService(purchases, rentals, slots, state, lock, paymentIntents, intentRepo,
                wallet, gateway, granter, tokens, accounts, deletions, Clock.fixed(NOW, ZoneOffset.UTC));
        when(deletions.findByUserId(USER)).thenReturn(Optional.empty());
        when(tokens.generate()).thenAnswer(inv -> "tok" + ids.incrementAndGet());
        when(purchases.saveAndFlush(any(PetSlotPurchase.class))).thenAnswer(inv -> {
            PetSlotPurchase p = inv.getArgument(0);
            if (ReflectionTestUtils.getField(p, "id") == null) {
                ReflectionTestUtils.setField(p, "id", ids.incrementAndGet());
            }
            return p;
        });
        when(purchases.save(any(PetSlotPurchase.class))).thenAnswer(inv -> inv.getArgument(0));
        when(purchases.findByIdemKey(anyString())).thenReturn(Optional.empty());
        when(granter.grant(any(PetSlotPurchase.class), any(Instant.class))).thenAnswer(inv ->
                new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED, List.of(555L), PetSlotTrigger.RENTAL_ADDED));
        liveUser();
        quotaTotal(3);
    }

    private void liveUser() {
        User u = mock(User.class);
        when(u.getDeletedAt()).thenReturn(null);
        when(accounts.findUserById(USER)).thenReturn(Optional.of(u));
    }

    private void quotaTotal(int total) {
        when(slots.quotaOf(USER)).thenReturn(new PetSlotQuota(total, 1, 1, total - 2, 3, 5 - total, total < 5, 10,
                total > 2));
    }

    private AdminSlotGrantResult grant(int days, String reason, String token) {
        return service.grantByAdmin(new AdminSlotGrantCommand(USER, days, reason, token, ADMIN));
    }

    @Test
    void grantsOneAdminSlotThroughGranterAndReconciles() {
        AdminSlotGrantResult r = grant(7, "  Klaim: QRIS sukses tapi slot belum masuk  ", TOKEN);

        assertThat(r.replayed()).isFalse();
        assertThat(r.days()).isEqualTo(7);
        assertThat(r.rentalExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
        verify(purchases).saveAndFlush(row.capture());
        PetSlotPurchase p = row.getValue();
        assertThat(p.getKind()).isEqualTo(PetSlotPurchaseKind.ADMIN_GRANT);
        assertThat(p.getQuantity()).isEqualTo(1);
        assertThat(p.getUnitPrice()).isZero();
        assertThat(p.getTotal()).isZero();
        assertThat(p.getChannel()).isNull();
        assertThat(p.getPaymentIntentId()).isNull();
        assertThat(p.getIdemKey()).isEqualTo("ADMIN_GRANT:" + TOKEN);
        assertThat(p.getDays()).isEqualTo(7);
        assertThat(p.getOperatorAdminId()).isEqualTo(ADMIN);
        assertThat(p.getReason()).isEqualTo("Klaim: QRIS sukses tapi slot belum masuk");
        // 发放后同一行置 PAID、回填 rental_ids、paid_at = now
        assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
        assertThat(p.getPaidAt()).isEqualTo(NOW);
        assertThat(p.rentalIdList()).containsExactly(555L);

        // 锁 → 锁内判封顶 → 发放 → reconcile（RENTAL_ADDED 只恢复不降级）
        InOrder o = inOrder(lock, slots, granter, state);
        o.verify(lock).lockUser(USER);
        o.verify(slots).quotaOf(USER);
        o.verify(granter).grant(p, NOW);
        o.verify(state, times(1)).reconcile(USER, PetSlotTrigger.RENTAL_ADDED);
        // 🔴 不收费：不碰钱包 / 支付意图 / 网关
        verifyNoInteractions(wallet, paymentIntents, intentRepo, gateway);
    }

    @Test
    void sameTokenReplaysWithoutNewRowsOrReconcile() {
        PetSlotPurchase prior = PetSlotPurchase.adminGrant("tokP", USER, 7, "ADMIN_GRANT:" + TOKEN, ADMIN, "r",
                NOW.minusSeconds(5));
        prior.settle(PetSlotPurchaseStatus.PAID, NOW.minusSeconds(5));
        prior.setRentalIds(List.of(555L));
        when(purchases.findByIdemKey("ADMIN_GRANT:" + TOKEN)).thenReturn(Optional.of(prior));
        PetSlotRental rental = PetSlotRental.start("rt", USER, PetSlotRentalSource.ADMIN_GRANT, NOW.minusSeconds(5),
                7, 1L);
        when(rentals.findAllById(List.of(555L))).thenReturn(List.of(rental));

        // code review #3：重放时这次填了 30 天，结果仍报首次补发的 7 天（toast 用它）
        AdminSlotGrantResult r = grant(30, "r", TOKEN);

        assertThat(r.replayed()).isTrue();
        assertThat(r.days()).isEqualTo(7);
        assertThat(r.rentalExpiresAt()).isEqualTo(rental.getExpiresAt());
        verify(purchases, never()).saveAndFlush(any());
        verify(granter, never()).grant(any(), any());
        verify(state, never()).reconcile(any(Long.class), any());
        verifyNoInteractions(wallet, paymentIntents, gateway);
    }

    @Test
    void tokenReusedForAnotherUserIsRejectedNotReplayed() {
        PetSlotPurchase other = PetSlotPurchase.adminGrant("tokO", 8L, 7, "ADMIN_GRANT:" + TOKEN, ADMIN, "r", NOW);
        when(purchases.findByIdemKey("ADMIN_GRANT:" + TOKEN)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                .extracting("messageCode").isEqualTo("admin.err.petSlot.missingToken");
        verify(granter, never()).grant(any(), any());
    }

    @Test
    void fullQuotaIsRejectedInsideTheLock() {
        quotaTotal(5);

        assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422))
                .extracting("messageCode").isEqualTo("admin.err.petSlot.limitReached");
        InOrder o = inOrder(lock, slots);
        o.verify(lock).lockUser(USER);
        o.verify(slots).quotaOf(USER);
        verify(purchases, never()).saveAndFlush(any());
        verify(state, never()).reconcile(any(Long.class), any());
    }

    @Test
    void concurrentSameTokenForAnotherUserUniqueViolationIs409() {
        // code review #4：两账号两把锁互不串行，后到者撞 idem_key 唯一键 → 409（不冒成 500），不发放
        when(purchases.saveAndFlush(any(PetSlotPurchase.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_pet_slot_purchases_idem_key"));

        assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(409))
                .extracting("messageCode").isEqualTo("admin.err.petSlot.missingToken");
        verify(granter, never()).grant(any(), any());
        verify(state, never()).reconcile(any(Long.class), any());
    }

    @Test
    void granterOverCapRollsBackWith422() {
        when(granter.grant(any(PetSlotPurchase.class), any(Instant.class))).thenReturn(
                new PetSlotGranter.Result(PetSlotGranter.Outcome.OVER_CAP, List.of(), PetSlotTrigger.RENTAL_ADDED));

        assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                .extracting("messageCode").isEqualTo("admin.err.petSlot.limitReached");
        verify(state, never()).reconcile(any(Long.class), any());
    }

    @Nested
    class 校验422 {

        @Test
        void daysOutOfRange() {
            for (int days : new int[] {0, -1, 366}) {
                assertThatThrownBy(() -> grant(days, "r", TOKEN)).as("days=" + days).isInstanceOf(AppException.class)
                        .extracting("messageCode").isEqualTo("admin.err.petSlot.daysInvalid");
            }
            assertThat(grant(365, "r", TOKEN).replayed()).isFalse();
        }

        @Test
        void reasonBlankOrTooLong() {
            assertThatThrownBy(() -> grant(7, "  ", TOKEN)).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.reasonRequired");
            assertThatThrownBy(() -> grant(7, null, TOKEN)).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.reasonRequired");
            assertThatThrownBy(() -> grant(7, "x".repeat(201), TOKEN)).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.reasonTooLong");
            assertThat(grant(7, "x".repeat(200), TOKEN).replayed()).isFalse();
        }

        @Test
        void tokenBlank() {
            assertThatThrownBy(() -> grant(7, "r", " ")).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.missingToken");
            assertThatThrownBy(() -> grant(7, "r", null)).extracting("messageCode")
                    .isEqualTo("admin.err.petSlot.missingToken");
        }

        @Test
        void tokenTooLongOrIllegalCharsIs422() {
            // code review #4："ADMIN_GRANT:" + 149 位会撑爆 idem_key VARCHAR(160) → 原来 500
            for (String bad : new String[] {"a".repeat(149), "a".repeat(65), "tok en", "tok;drop", "令牌"}) {
                assertThatThrownBy(() -> grant(7, "r", bad)).as(bad).isInstanceOf(AppException.class)
                        .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422))
                        .extracting("messageCode").isEqualTo("admin.err.petSlot.missingToken");
            }
            assertThat(grant(7, "r", "a".repeat(64)).replayed()).isFalse();
            assertThat(grant(7, "r", "0b6f5c1e-2a3b-4c5d-8e9f-0a1b2c3d4e5f").replayed()).isFalse();
            verify(lock, times(2)).lockUser(USER);
        }

        @Test
        void deletionInProgressIs422() {
            // code review #2：注销已受理（account_deletions 有行）但 @Async 级联未跑完、deletedAt 仍为 null
            when(deletions.findByUserId(USER)).thenReturn(Optional.of(AccountDeletion.request(USER)));
            assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422))
                    .extracting("messageCode").isEqualTo("admin.err.petSlot.userDeleted");
            verify(lock, never()).lockUser(any(Long.class));
            verify(purchases, never()).saveAndFlush(any());
        }

        @Test
        void unknownUserIs404AndDeletedUserIs422() {
            when(accounts.findUserById(USER)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));

            User deleted = mock(User.class);
            when(deleted.getDeletedAt()).thenReturn(NOW.minusSeconds(60));
            when(accounts.findUserById(USER)).thenReturn(Optional.of(deleted));
            assertThatThrownBy(() -> grant(7, "r", TOKEN)).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422))
                    .extracting("messageCode").isEqualTo("admin.err.petSlot.userDeleted");
            verify(lock, never()).lockUser(USER);
        }

        @Test
        void invalidInputNeverTakesTheLockOrWrites() {
            assertThatThrownBy(() -> grant(0, "r", TOKEN)).isInstanceOf(AppException.class);
            verify(lock, never()).lockUser(any(Long.class));
            verify(purchases, never()).saveAndFlush(any());
            verifyNoInteractions(wallet, paymentIntents, gateway);
        }
    }

    @Test
    void granterUsesAdminGrantSourceForAdminRows() {
        PetSlotPurchase admin = PetSlotPurchase.adminGrant("t", USER, 7, "k", ADMIN, "r", NOW);
        PetSlotPurchase rent = PetSlotPurchase.pending("t2", USER, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                com.tailtopia.pay.domain.PayChannel.PAWCOIN, null, "k2", null, NOW);
        assertThat(PetSlotGranter.sourceOf(admin)).isEqualTo(PetSlotRentalSource.ADMIN_GRANT);
        assertThat(PetSlotGranter.sourceOf(rent)).isEqualTo(PetSlotRentalSource.RENT);
    }
}
