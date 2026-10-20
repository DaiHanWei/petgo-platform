package com.tailtopia.admin.petslot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.profile.support.PetFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.4.0 multi-pet Story 4.3 · AC3 —— 「宠物与名额」页签读模型：构成四格取自 {@code quotaOf}（月租格取实际持有行数）、
 * 宠物状态、月租表排序与序号、购买记录（已结算三态、倒序、截 20 + truncated、ADMIN_GRANT 金额 / 方式为空带操作人与天数、
 * intent 只给 token 缩写）、空态、注销态；20 行购买时操作人与 intent token 各批量查 1 次（禁 N+1）。纯 Mockito。
 */
class AdminPetSlotTabServiceTest {

    private static final long USER = 42L;
    /** 2026-10-13 15:40 WIB。 */
    private static final Instant NOW = Instant.parse("2026-10-13T08:40:00Z");

    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final PetSlotService slots = mock(PetSlotService.class);
    private final PetResolver petResolver = mock(PetResolver.class);
    private final PetSlotRentalRepository rentals = mock(PetSlotRentalRepository.class);
    private final PetSlotPurchaseRepository purchases = mock(PetSlotPurchaseRepository.class);
    private final AdminAccountRepository adminAccounts = mock(AdminAccountRepository.class);
    private final PaymentIntentRepository intents = mock(PaymentIntentRepository.class);
    private final com.tailtopia.account.repository.AccountDeletionRepository deletions =
            mock(com.tailtopia.account.repository.AccountDeletionRepository.class);
    private AdminPetSlotTabService service;
    private long nextId = 1000;

    @BeforeEach
    void setUp() {
        service = new AdminPetSlotTabService(accounts, slots, petResolver, rentals, purchases, adminAccounts, intents,
                new AdminTime(), deletions);
        when(deletions.findByUserId(USER)).thenReturn(Optional.empty());
        User u = mock(User.class);
        when(accounts.findUserById(USER)).thenReturn(Optional.of(u));
        when(slots.now()).thenReturn(NOW);
        quota(3, 1, 3);
        when(petResolver.listOwned(USER)).thenReturn(List.of());
        when(rentals.findByUserIdOrderByExpiresAtAscIdAsc(USER)).thenReturn(List.of());
        when(purchases.findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(eq(USER), any(), any(Pageable.class)))
                .thenReturn(List.of());
    }

    private void quota(int total, int levelSlots, int petCount) {
        when(slots.quotaOf(USER)).thenReturn(new PetSlotQuota(total, 1, levelSlots, total - 1 - levelSlots, petCount,
                5 - total, total < 5, null, total - 1 - levelSlots > 0));
    }

    private PetSlotRental rental(Instant startsAt, int days, PetSlotRentalStatus status) {
        PetSlotRental r = PetSlotRental.start("rt" + nextId, USER, PetSlotRentalSource.RENT, startsAt, days, 1L);
        ReflectionTestUtils.setField(r, "id", nextId++);
        ReflectionTestUtils.setField(r, "status", status);
        return r;
    }

    private PetSlotPurchase paid(PetSlotPurchaseKind kind, PayChannel channel, Long intentId, long total,
            Instant createdAt, PetSlotPurchaseStatus status) {
        PetSlotPurchase p = PetSlotPurchase.pending("pt" + nextId, USER, kind, 1, total, total, channel, intentId,
                "k" + nextId, null, createdAt);
        ReflectionTestUtils.setField(p, "id", nextId++);
        p.settle(status, createdAt);
        return p;
    }

    private PetSlotPurchase adminGrant(long operator, int days, Instant createdAt) {
        PetSlotPurchase p = PetSlotPurchase.adminGrant("pg" + nextId, USER, days, "ADMIN_GRANT:t" + nextId, operator,
                "reason", createdAt);
        ReflectionTestUtils.setField(p, "id", nextId++);
        p.settle(PetSlotPurchaseStatus.PAID, createdAt);
        return p;
    }

    private static PaymentIntent intent(long id, String token) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn(id);
        when(pi.getPublicToken()).thenReturn(token);
        return pi;
    }

    private static AdminAccount admin(long id, String name) {
        AdminAccount a = mock(AdminAccount.class);
        when(a.getId()).thenReturn(id);
        when(a.getDisplayName()).thenReturn(name);
        return a;
    }

    @Test
    void quotaCellsComeFromQuotaOfAndRentCellCountsLiveRows() {
        quota(5, 2, 4);
        // 生效中实际持有 3 行（封顶后 quotaOf.rentalSlots 只有 2）+ 1 行 ACTIVE 但已过 expires_at + 1 行 EXPIRED
        when(rentals.findByUserIdOrderByExpiresAtAscIdAsc(USER)).thenReturn(List.of(
                rental(NOW.minus(Duration.ofDays(31)), 30, PetSlotRentalStatus.ACTIVE),
                rental(NOW.minus(Duration.ofDays(10)), 30, PetSlotRentalStatus.ACTIVE),
                rental(NOW.minus(Duration.ofDays(5)), 30, PetSlotRentalStatus.ACTIVE),
                rental(NOW.minus(Duration.ofDays(1)), 30, PetSlotRentalStatus.ACTIVE),
                rental(NOW.minus(Duration.ofDays(60)), 30, PetSlotRentalStatus.EXPIRED)));

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.deleted()).isFalse();
        assertThat(t.userId()).isEqualTo(USER);
        assertThat(t.quotaTotal()).isEqualTo(5);
        assertThat(t.defaultSlots()).isEqualTo(1);
        assertThat(t.levelSlots()).isEqualTo(2);
        assertThat(t.petCount()).isEqualTo(4);
        assertThat(t.rentedActive()).isEqualTo(3);
        assertThat(t.full()).isTrue();
    }

    @Test
    void notFullBelowFive() {
        assertThat(service.tab(USER).full()).isFalse();
        assertThat(service.tab(USER).deletionPending()).isFalse();
    }

    @Test
    void deletionInProgressIsFlaggedButDataStillShown() {
        when(deletions.findByUserId(USER))
                .thenReturn(Optional.of(com.tailtopia.account.domain.AccountDeletion.request(USER)));
        AdminPetSlotTabService.Tab t = service.tab(USER);
        assertThat(t.deleted()).isFalse();
        assertThat(t.deletionPending()).isTrue();
        assertThat(t.quotaTotal()).isEqualTo(3);
    }

    @Test
    void petsInCreationOrderWithStatus() {
        Instant t0 = Instant.parse("2026-06-11T20:00:00Z"); // = 2026-06-12 03:00 WIB
        PetProfile a = PetFixtures.pet(USER, 5521L, t0);
        PetProfile b = PetFixtures.pet(USER, 6120L, t0.plus(Duration.ofDays(100)));
        ReflectionTestUtils.setField(b, "status", PetStatus.READ_ONLY);
        when(petResolver.listOwned(USER)).thenReturn(List.of(a, b));

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.pets()).extracting(AdminPetSlotTabService.PetRow::id).containsExactly(5521L, 6120L);
        assertThat(t.pets()).extracting(AdminPetSlotTabService.PetRow::status).containsExactly("ACTIVE", "READ_ONLY");
        assertThat(t.pets().get(0).createdAtWibDate()).isEqualTo("2026-06-12");
    }

    @Test
    void rentalsLiveFirstByExpiryAscThenEndedByExpiryDescWithDisplaySeq() {
        PetSlotRental liveLate = rental(NOW.minus(Duration.ofDays(1)), 30, PetSlotRentalStatus.ACTIVE);
        PetSlotRental liveSoon = rental(NOW.minus(Duration.ofDays(28)), 30, PetSlotRentalStatus.ACTIVE);
        PetSlotRental endedOld = rental(NOW.minus(Duration.ofDays(90)), 30, PetSlotRentalStatus.EXPIRED);
        PetSlotRental endedRecent = rental(NOW.minus(Duration.ofDays(40)), 30, PetSlotRentalStatus.EXPIRED);
        when(rentals.findByUserIdOrderByExpiresAtAscIdAsc(USER))
                .thenReturn(List.of(endedOld, endedRecent, liveSoon, liveLate));

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.rentals()).extracting(AdminPetSlotTabService.RentalRow::seq).containsExactly(1, 2, 3, 4);
        assertThat(t.rentals()).extracting(AdminPetSlotTabService.RentalRow::active)
                .containsExactly(true, true, false, false);
        AdminTime fmt = new AdminTime();
        assertThat(t.rentals()).extracting(AdminPetSlotTabService.RentalRow::expiresAtWib).containsExactly(
                fmt.fmt(liveSoon.getExpiresAt()), fmt.fmt(liveLate.getExpiresAt()),
                fmt.fmt(endedRecent.getExpiresAt()), fmt.fmt(endedOld.getExpiresAt()));
        assertThat(t.rentedActive()).isEqualTo(2);
    }

    @Test
    void noRentalsAndNoPurchasesGiveEmptyStates() {
        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.rentals()).isEmpty();
        assertThat(t.rentedActive()).isZero();
        assertThat(t.purchases()).isEmpty();
        assertThat(t.purchasesTruncated()).isFalse();
        assertThat(t.hasPurchaseExceptions()).isFalse();
        verifyNoInteractions(adminAccounts, intents);
    }

    @Test
    void purchasesListSettledStatusesNewestFirstAndAsksForOneExtraRow() {
        service.tab(USER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PetSlotPurchaseStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(purchases).findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(eq(USER), statuses.capture(),
                page.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(PetSlotPurchaseStatus.PAID,
                PetSlotPurchaseStatus.ORPHAN_PAID, PetSlotPurchaseStatus.OVERPAID);
        assertThat(page.getValue().getPageSize()).isEqualTo(AdminPetSlotTabService.PURCHASE_LIMIT + 1);
    }

    @Test
    void purchaseRowsShapeAdminGrantPawcoinAndQris() {
        PetSlotPurchase grant = adminGrant(900L, 7, NOW);
        PetSlotPurchase qris = paid(PetSlotPurchaseKind.RENEW, PayChannel.QRIS, 77L, 10000, NOW.minusSeconds(3600),
                PetSlotPurchaseStatus.PAID);
        PetSlotPurchase coin = paid(PetSlotPurchaseKind.RENT, PayChannel.PAWCOIN, null, 5000,
                NOW.minusSeconds(7200), PetSlotPurchaseStatus.PAID);
        when(purchases.findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(eq(USER), any(), any(Pageable.class)))
                .thenReturn(List.of(grant, qris, coin));
        List<AdminAccount> ops = List.of(admin(900L, "ops_lina"));
        List<PaymentIntent> pis = List.of(intent(77L, "pi_8f2c0d9e7b6a91"));
        when(adminAccounts.findAllById(anyIterable())).thenReturn(ops);
        when(intents.findAllById(anyIterable())).thenReturn(pis);

        List<AdminPetSlotTabService.PurchaseRow> rows = service.tab(USER).purchases();

        AdminPetSlotTabService.PurchaseRow g = rows.get(0);
        assertThat(g.kind()).isEqualTo("ADMIN_GRANT");
        assertThat(g.operatorName()).isEqualTo("ops_lina");
        assertThat(g.days()).isEqualTo(7);
        assertThat(g.quantity()).isEqualTo(1);
        assertThat(g.totalOrNull()).isNull();
        assertThat(g.channelOrNull()).isNull();
        assertThat(g.intentTokenOrNull()).isNull();
        assertThat(g.createdAtWib()).isEqualTo("2026-10-13 15:40");

        AdminPetSlotTabService.PurchaseRow q = rows.get(1);
        assertThat(q.kind()).isEqualTo("RENEW");
        assertThat(q.operatorName()).isNull();
        assertThat(q.totalOrNull()).isEqualTo(10000L);
        assertThat(q.channelOrNull()).isEqualTo("QRIS");
        assertThat(q.intentTokenOrNull()).isEqualTo("pi_8f2c0d9e7b6a91");
        assertThat(q.intentTokenShort()).isEqualTo("pi_8f2…a91");

        AdminPetSlotTabService.PurchaseRow c = rows.get(2);
        assertThat(c.channelOrNull()).isEqualTo("PAWCOIN");
        assertThat(c.intentTokenOrNull()).isNull();
        // 🔴 不外露数字 payment_intent_id / purchase id
        assertThat(rows.toString()).doesNotContain("=77,").doesNotContain("=77)");
    }

    @Test
    void orphanAndOverpaidRowsAreFlagged() {
        when(purchases.findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(eq(USER), any(), any(Pageable.class)))
                .thenReturn(List.of(
                        paid(PetSlotPurchaseKind.RENT, PayChannel.QRIS, null, 5000, NOW, PetSlotPurchaseStatus.OVERPAID),
                        paid(PetSlotPurchaseKind.RENT, PayChannel.QRIS, null, 5000, NOW.minusSeconds(1),
                                PetSlotPurchaseStatus.ORPHAN_PAID),
                        paid(PetSlotPurchaseKind.RENT, PayChannel.PAWCOIN, null, 5000, NOW.minusSeconds(2),
                                PetSlotPurchaseStatus.PAID)));

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.purchases()).extracting(AdminPetSlotTabService.PurchaseRow::exception)
                .containsExactly(true, true, false);
        assertThat(t.purchases()).extracting(AdminPetSlotTabService.PurchaseRow::status)
                .containsExactly("OVERPAID", "ORPHAN_PAID", "PAID");
        assertThat(t.hasPurchaseExceptions()).isTrue();
    }

    @Test
    void truncatesAtTwentyAndBatchesOperatorAndIntentLookupsOnce() {
        List<PetSlotPurchase> page = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            Instant at = NOW.minusSeconds(60L * i);
            page.add(i % 2 == 0 ? adminGrant(900L + (i % 3), 7, at)
                    : paid(PetSlotPurchaseKind.RENT, PayChannel.QRIS, 500L + i, 5000, at, PetSlotPurchaseStatus.PAID));
        }
        when(purchases.findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(eq(USER), any(), any(Pageable.class)))
                .thenReturn(page);
        List<AdminAccount> ops = List.of(admin(900L, "a"), admin(901L, "b"));
        when(adminAccounts.findAllById(anyIterable())).thenReturn(ops);
        when(intents.findAllById(anyIterable())).thenReturn(List.of());

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.purchases()).hasSize(20);
        assertThat(t.purchasesTruncated()).isTrue();
        verify(adminAccounts, times(1)).findAllById(anyIterable());
        verify(intents, times(1)).findAllById(anyIterable());
        verify(adminAccounts, never()).findById(anyLong());
        verify(intents, never()).findById(anyLong());
        // 查不到的操作人 → #id（不报错）
        assertThat(t.purchases()).extracting(AdminPetSlotTabService.PurchaseRow::operatorName).contains("#902");
    }

    @Test
    void deletedAccountShowsNothingElse() {
        User gone = mock(User.class);
        when(gone.getDeletedAt()).thenReturn(NOW);
        when(accounts.findUserById(USER)).thenReturn(Optional.of(gone));

        AdminPetSlotTabService.Tab t = service.tab(USER);

        assertThat(t.deleted()).isTrue();
        assertThat(t.full()).isFalse();
        assertThat(t.pets()).isEmpty();
        assertThat(t.rentals()).isEmpty();
        assertThat(t.purchases()).isEmpty();
        verify(slots, never()).quotaOf(anyLong());
        verifyNoInteractions(rentals, purchases, adminAccounts, intents, petResolver);
    }

    @Test
    void shortTokenKeepsSixAndThree() {
        assertThat(AdminPetSlotTabService.shortToken(null)).isNull();
        assertThat(AdminPetSlotTabService.shortToken("abc")).isEqualTo("abc");
        assertThat(AdminPetSlotTabService.shortToken("0123456789abcdef")).isEqualTo("012345…def");
    }
}
