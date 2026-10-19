package com.tailtopia.gift.service;

import static com.tailtopia.gift.service.GiftTestFixtures.OWNER;
import static com.tailtopia.gift.service.GiftTestFixtures.PET;
import static com.tailtopia.gift.service.GiftTestFixtures.SENDER;
import static com.tailtopia.gift.service.GiftTestFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.repository.GiftPetSummaryRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** V1.4.0 gift Story 1.1 · AC8.1 ~ AC8.3（L0）：交付、只交付一次、三种不可送达。 */
class GiftDeliveryServiceTest {

    private GiftOrderRepository orders;
    private GiftPetSummaryRepository summaries;
    private GiftPetDirectory pets;
    private AccountQueryService accounts;
    private UserHideRelationReader hides;
    private ApplicationEventPublisher events;
    private GiftDeliveryService service;
    private GiftOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        summaries = mock(GiftPetSummaryRepository.class);
        pets = mock(GiftPetDirectory.class);
        accounts = mock(AccountQueryService.class);
        hides = mock(UserHideRelationReader.class);
        events = mock(ApplicationEventPublisher.class);
        service = new GiftDeliveryService(orders, summaries, pets, accounts, hides, events);

        order = GiftTestFixtures.order(31L, PayChannel.QRIS, 5, 2_000);
        owner(false);
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        when(orders.markDeliveredIfPending(31L)).thenReturn(1);
        when(orders.markUndeliveredIfPending(anyLong(), anyString())).thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void owner(boolean deleted) {
        User u = mock(User.class);
        when(u.getDeletedAt()).thenReturn(deleted ? Instant.EPOCH : null);
        when(accounts.findAccountById(OWNER)).thenReturn(Optional.of(u));
    }

    @Test
    void deliverableUpdatesOnceUpsertsSummariesAndPublishesSentEvent() {
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.DELIVERED);

        InOrder seq = inOrder(orders, summaries, events);
        seq.verify(orders).markDeliveredIfPending(31L);
        seq.verify(summaries).addToWall(PET, 5L, 2_000L, "Gift5", "Hadiah5", "https://cdn/g5.png", 5);
        seq.verify(summaries).addToSupporters(PET, SENDER, 10_000L, 5);
        seq.verify(events).publishEvent(new GiftSentEvent(31L, SENDER, OWNER, PET, 10_000L));
        verify(events, never()).publishEvent(any(GiftUndeliveredEvent.class));
    }

    @Test
    void concurrentlyDeliveredDoesNothingMore() {
        when(orders.markDeliveredIfPending(31L)).thenReturn(0);
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.NOT_PENDING);
        verifyNoInteractions(summaries, events);
    }

    @Test
    void nonPendingOrderIsSkipped() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.DELIVERED);
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.NOT_PENDING);
        verify(orders, never()).markDeliveredIfPending(anyLong());
        verifyNoInteractions(summaries, events);
    }

    @Test
    void ownerDeletedComesFirst() {
        owner(true);
        when(pets.pet(PET)).thenReturn(Optional.empty()); // 注销连带删宠物：原因仍记 OWNER_DELETED
        assertUndelivered("OWNER_DELETED");
    }

    @Test
    void ownerDeletedFlagOnPetAlsoCounts() {
        when(pets.pet(PET)).thenReturn(Optional.of(new GiftPet(PET, OWNER, "Mochi", null, "CAT", null, false, true)));
        assertUndelivered("OWNER_DELETED");
    }

    @Test
    void petDeletedWhenPetIdNullOrMissing() {
        when(pets.pet(PET)).thenReturn(Optional.empty());
        assertUndelivered("PET_DELETED");

        GiftOrder nullPet = GiftTestFixtures.order(32L, PayChannel.QRIS, 1, 10_000);
        ReflectionTestUtils.setField(nullPet, "petId", null);
        assertThat(service.deliver(nullPet)).isEqualTo(GiftDeliveryService.Result.UNDELIVERED);
        verify(orders).markUndeliveredIfPending(32L, "PET_DELETED");
    }

    @Test
    void senderBlockedOwnerIsLastAndOnlyOneDirection() {
        when(hides.isBlocked(OWNER, SENDER)).thenReturn(true); // 主人拉黑送礼人：照常送达
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.DELIVERED);

        GiftOrder second = GiftTestFixtures.order(33L, PayChannel.QRIS, 1, 10_000);
        when(hides.isBlocked(SENDER, OWNER)).thenReturn(true);
        assertThat(service.deliver(second)).isEqualTo(GiftDeliveryService.Result.UNDELIVERED);
        verify(orders).markUndeliveredIfPending(33L, "SENDER_BLOCKED");
        verify(events).publishEvent(new GiftUndeliveredEvent(33L));
    }

    @Test
    void undeliveredAlreadyHandledConcurrentlyPublishesNothing() {
        when(pets.pet(PET)).thenReturn(Optional.empty());
        when(orders.markUndeliveredIfPending(anyLong(), anyString())).thenReturn(0);
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.NOT_PENDING);
        verifyNoInteractions(events, summaries);
    }

    @Test
    void mustRunInsideCallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.deliver(order)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orders, summaries, events);
    }

    private void assertUndelivered(String reason) {
        assertThat(service.deliver(order)).isEqualTo(GiftDeliveryService.Result.UNDELIVERED);
        verify(orders).markUndeliveredIfPending(31L, reason);
        verify(orders, never()).markDeliveredIfPending(anyLong());
        verify(summaries, never()).addToWall(anyLong(), anyLong(), anyLong(), any(), any(), any(), anyInt());
        verify(summaries, never()).addToSupporters(anyLong(), anyLong(), anyLong(), anyInt());
        verify(events).publishEvent(new GiftUndeliveredEvent(31L));
        verify(events, never()).publishEvent(any(GiftSentEvent.class));
    }
}
