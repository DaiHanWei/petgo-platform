package com.tailtopia.starterpack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.GrantResult;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.slot.PetSlotGranter;
import com.tailtopia.profile.slot.PetSlotLock;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.PetSlotStateService;
import com.tailtopia.profile.slot.PetSlotTokenGenerator;
import com.tailtopia.profile.slot.PetSlotTrigger;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackSlotOutcome;
import com.tailtopia.starterpack.domain.StarterPackTier;
import com.tailtopia.starterpack.event.StarterPackFulfilledEvent;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 gift-pack Story 4-2 AC6.6 —— 三档发放的类型与数量、名额三分支、重放不重复（Mockito）。 */
class StarterPackFulfillmentTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2027-02-14T03:00:00Z");
    private static final long USER = 9L;
    private static final long PID = 7L;

    private StarterPackPurchaseRepository purchases;
    private EntitlementCreditService credits;
    private PetSlotLock lock;
    private PetSlotService slots;
    private PetSlotPurchaseRepository slotPurchases;
    private PetSlotRentalRepository rentals;
    private PetSlotGranter granter;
    private PetSlotStateService state;
    private ApplicationEventPublisher events;
    private StarterPackFulfillmentService service;

    @BeforeEach
    void setUp() {
        purchases = mock(StarterPackPurchaseRepository.class);
        credits = mock(EntitlementCreditService.class);
        lock = mock(PetSlotLock.class);
        slots = mock(PetSlotService.class);
        slotPurchases = mock(PetSlotPurchaseRepository.class);
        rentals = mock(PetSlotRentalRepository.class);
        granter = mock(PetSlotGranter.class);
        state = mock(PetSlotStateService.class);
        events = mock(ApplicationEventPublisher.class);
        PetSlotTokenGenerator tokens = mock(PetSlotTokenGenerator.class);
        when(tokens.generate()).thenReturn("slot-token");
        when(credits.grant(any())).thenReturn(new GrantResult(1L, EXPIRES, false));
        when(slotPurchases.findByIdemKey(any())).thenReturn(Optional.empty());
        when(slotPurchases.saveAndFlush(any())).thenAnswer(i -> {
            PetSlotPurchase row = i.getArgument(0);
            ReflectionTestUtils.setField(row, "id", 300L);
            return row;
        });
        service = new StarterPackFulfillmentService(purchases, credits, lock, slots, slotPurchases, rentals, granter,
                state, tokens, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StarterPackPurchase paid(StarterPackTier tier) {
        StarterPackPurchase p = StarterPackPurchase.pending("tok", USER, tier, 13000, 23000, PayChannel.PAWCOIN,
                "STARTER_PACK:9:r1", NOW);
        ReflectionTestUtils.setField(p, "id", PID);
        p.markPaid(NOW, 2);
        when(purchases.findByIdForUpdate(PID)).thenReturn(Optional.of(p));
        return p;
    }

    private static PetSlotQuota quota(int total, boolean rental) {
        return new PetSlotQuota(total, 1, 0, total - 1, total, 5 - total, total < 5, null, rental);
    }

    private List<GrantCommand> grants() {
        ArgumentCaptor<GrantCommand> c = ArgumentCaptor.forClass(GrantCommand.class);
        verify(credits, org.mockito.Mockito.atLeastOnce()).grant(c.capture());
        return c.getAllValues();
    }

    @Test
    void tier1GrantsEightKtpAndOneTailsonalityNoSlot() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_1);

        service.fulfill(PID);

        List<GrantCommand> g = grants();
        assertThat(g).hasSize(9);
        assertThat(g.stream().map(GrantCommand::type).toList())
                .containsExactlyElementsOf(concat(CreditType.ktpTemplates(), CreditType.TAILSONALITY));
        assertThat(g).allSatisfy(c -> {
            assertThat(c.quantity()).isEqualTo(1);
            assertThat(c.source()).isEqualTo(CreditSource.PACK);
            assertThat(c.idemKey()).isEqualTo("PACK:7");
            assertThat(c.sourceRef()).isEqualTo("7");
            assertThat(c.userId()).isEqualTo(USER);
        });
        assertThat(g).noneMatch(c -> c.type() == CreditType.KTP_HD_ANY);
        verify(lock, never()).lockUser(anyLong());
        assertThat(p.getSlotOutcome()).isEqualTo(StarterPackSlotOutcome.NONE);
        assertThat(p.getFulfilledAt()).isEqualTo(NOW);
        assertThat(p.getCreditsExpireAt()).isEqualTo(EXPIRES);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new StarterPackFulfilledEvent(USER, 1, "PAWCOIN", 2));
    }

    @Test
    void tier3GrantsGiftCreditsAndNewSlotWhenUnderFive() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_3);
        when(slots.quotaOf(USER)).thenReturn(quota(3, false));
        when(granter.grant(any(), eq(NOW))).thenReturn(
                new PetSlotGranter.Result(PetSlotGranter.Outcome.GRANTED, List.of(55L), PetSlotTrigger.RENTAL_ADDED));

        service.fulfill(PID);

        List<GrantCommand> g = grants();
        assertThat(g).hasSize(10);
        assertThat(g.get(9).type()).isEqualTo(CreditType.GIFT_1K);
        assertThat(g.get(9).quantity()).isEqualTo(5);
        ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
        verify(slotPurchases).saveAndFlush(row.capture());
        assertThat(row.getValue().getKind()).isEqualTo(PetSlotPurchaseKind.PACK_GRANT);
        assertThat(row.getValue().getIdemKey()).isEqualTo("PACK_SLOT:7");
        assertThat(row.getValue().getStarterPackPurchaseId()).isEqualTo(PID);
        assertThat(row.getValue().getTotal()).isZero();
        assertThat(row.getValue().getChannel()).isNull();
        verify(lock).lockUser(USER);
        verify(state).reconcile(USER, PetSlotTrigger.RENTAL_ADDED);
        assertThat(p.getSlotOutcome()).isEqualTo(StarterPackSlotOutcome.GRANTED);
        assertThat(p.getSlotRentalId()).isEqualTo(55L);
        assertThat(p.getSlotExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void fullSlotsExtendEarliestRental() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_2);
        when(slots.quotaOf(USER)).thenReturn(quota(5, true));
        PetSlotRental r = mock(PetSlotRental.class);
        Instant rentalExp = NOW.plus(Duration.ofDays(3));
        when(r.getId()).thenReturn(41L);
        when(r.getExpiresAt()).thenReturn(rentalExp);
        when(rentals.findFirstByUserIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(USER,
                PetSlotRentalStatus.ACTIVE, NOW)).thenReturn(Optional.of(r));
        when(granter.extend(USER, 41L, 30)).thenReturn(1);

        service.fulfill(PID);

        ArgumentCaptor<PetSlotPurchase> row = ArgumentCaptor.forClass(PetSlotPurchase.class);
        verify(slotPurchases).saveAndFlush(row.capture());
        assertThat(row.getValue().getKind()).isEqualTo(PetSlotPurchaseKind.PACK_EXTEND);
        assertThat(row.getValue().rentalIdList()).containsExactly(41L);
        verify(granter, never()).grant(any(), any());
        assertThat(p.getSlotOutcome()).isEqualTo(StarterPackSlotOutcome.EXTENDED);
        assertThat(p.getSlotRentalId()).isEqualTo(41L);
        assertThat(p.getSlotExpiresAt()).isEqualTo(rentalExp.plus(Duration.ofDays(30)));
    }

    @Test
    void fullSlotsWithoutRentalAreSkippedButCreditsStillGranted() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_2);
        when(slots.quotaOf(USER)).thenReturn(quota(5, false));
        when(rentals.findFirstByUserIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(anyLong(), any(), any()))
                .thenReturn(Optional.empty());

        service.fulfill(PID);

        assertThat(grants()).hasSize(9);
        verify(slotPurchases, never()).saveAndFlush(any());
        verify(granter, never()).extend(anyLong(), anyLong(), anyInt());
        assertThat(p.getSlotOutcome()).isEqualTo(StarterPackSlotOutcome.SKIPPED);
        assertThat(p.getFulfilledAt()).isEqualTo(NOW);
    }

    @Test
    void alreadyFulfilledIsNoOp() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_3);
        p.markFulfilled(NOW, EXPIRES, StarterPackSlotOutcome.GRANTED, 55L, NOW);

        service.fulfill(PID);

        verify(credits, never()).grant(any());
        verify(lock, never()).lockUser(anyLong());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void slotReplayReusesExistingRowWithoutGrantingAgain() {
        StarterPackPurchase p = paid(StarterPackTier.TIER_2);
        PetSlotPurchase prior = PetSlotPurchase.pack("t", USER, PetSlotPurchaseKind.PACK_GRANT, "PACK_SLOT:7", PID,
                null, NOW);
        prior.setRentalIds(List.of(55L));
        when(slotPurchases.findByIdemKey("PACK_SLOT:7")).thenReturn(Optional.of(prior));
        PetSlotRental r = mock(PetSlotRental.class);
        when(r.getExpiresAt()).thenReturn(EXPIRES);
        when(rentals.findById(55L)).thenReturn(Optional.of(r));

        service.fulfill(PID);

        verify(slotPurchases, never()).saveAndFlush(any());
        verify(granter, never()).grant(any(), any());
        verify(slots, never()).quotaOf(anyLong());
        assertThat(p.getSlotOutcome()).isEqualTo(StarterPackSlotOutcome.GRANTED);
        assertThat(p.getSlotRentalId()).isEqualTo(55L);
        verify(credits, times(9)).grant(any());
    }

    private static List<CreditType> concat(List<CreditType> a, CreditType b) {
        java.util.ArrayList<CreditType> out = new java.util.ArrayList<>(a);
        out.add(b);
        return out;
    }
}
