package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.service.PetProfileQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X4 / X5）：兽医会话 / 进行中 / 历史按<b>会话订单上的宠物</b>取身份；
 * 无订单（V1.0 免费会话）回落机主默认宠物；单上宠物已删 → 空身份兜底不抛；批量无 N+1。
 */
class VetConsultServiceTest {

    private static final long VET = 3L;
    private static final long USER = 7L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;

    private ConsultSessionRepository repo;
    private ConsultRatingRepository ratings;
    private PetProfileQueryService pets;
    private AccountQueryService accounts;
    private ConsultOrderRepository orders;
    private VetConsultService service;

    @BeforeEach
    void setUp() {
        repo = mock(ConsultSessionRepository.class);
        ratings = mock(ConsultRatingRepository.class);
        pets = mock(PetProfileQueryService.class);
        accounts = mock(AccountQueryService.class);
        orders = mock(ConsultOrderRepository.class);
        service = new VetConsultService(repo, ratings, mock(ConsultQueueService.class), pets, accounts,
                mock(ApplicationEventPublisher.class), orders);
        when(accounts.findAuthorViews(anyCollection())).thenReturn(Map.of());
        when(ratings.findByVetIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(pets.findIdentitiesByPetIds(anyCollection())).thenReturn(Map.of(
                PET_A, new PetIdentityView("Momo", "CAT", 3), PET_B, new PetIdentityView("Bobo", "DOG", 5)));
        when(pets.findDefaultIdentitiesByOwners(anyCollection()))
                .thenReturn(Map.of(USER, new PetIdentityView("Momo", "CAT", 3)));
    }

    private static ConsultSession session(long id, SessionStatus status) {
        ConsultSession s = ConsultSession.startWaiting(USER, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "vetId", VET);
        ReflectionTestUtils.setField(s, "status", status);
        ReflectionTestUtils.setField(s, "createdAt", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(id));
        return s;
    }

    private static ConsultOrder order(long sessionId, long petId) {
        ConsultOrder o = ConsultOrder.inProgress("o" + sessionId, USER, VET, petId, 1000L, PayChannel.PAWCOIN,
                null, 700L, 70, 1000L, Instant.now());
        ReflectionTestUtils.setField(o, "consultSessionId", sessionId);
        return o;
    }

    @Test
    void inProgressShowsThePetOnEachSessionsOrder() {
        when(repo.findByVetIdAndStatusIn(eq(VET), anyCollection()))
                .thenReturn(List.of(session(100, SessionStatus.IN_PROGRESS), session(101, SessionStatus.IN_PROGRESS)));
        when(orders.findByVetIdAndConsultSessionIdIn(eq(VET), anyCollection()))
                .thenReturn(List.of(order(100, PET_A), order(101, PET_B)));

        var items = service.inProgressList(VET);

        assertThat(items).extracting(i -> i.sessionId() + ":" + i.petName()).containsExactly("100:Momo", "101:Bobo");
        verify(pets, times(1)).findIdentitiesByPetIds(anyCollection());
        verify(pets, never()).findDefaultIdentitiesByOwners(anyCollection());
    }

    @Test
    void historyFallsBackToDefaultPetForSessionsWithoutOrderAndEmptyForDeletedPet() {
        when(repo.findByVetIdAndStatusInOrderByCreatedAtDesc(eq(VET), anyCollection()))
                .thenReturn(List.of(session(200, SessionStatus.CLOSED), session(201, SessionStatus.CLOSED),
                        session(202, SessionStatus.CLOSED)));
        // 200 → B；201 无订单（V1.0 免费会话）→ 默认宠物 A；202 单上宠物 99 已删 → 空身份。
        when(orders.findByVetIdAndConsultSessionIdIn(eq(VET), anyCollection()))
                .thenReturn(List.of(order(200, PET_B), order(202, 99L)));

        var items = service.historyList(VET);

        assertThat(items).extracting(i -> i.sessionId() + ":" + i.petName())
                .containsExactlyInAnyOrder("200:Bobo", "201:Momo", "202:null");
        verify(pets, times(1)).findIdentitiesByPetIds(anyCollection());
        verify(pets, times(1)).findDefaultIdentitiesByOwners(anyCollection());
        verify(orders, times(1)).findByVetIdAndConsultSessionIdIn(eq(VET), anyCollection());
    }

    @Test
    void sessionViewUsesTheOrdersPetThenDefaultThenEmpty() {
        ConsultSession withOrder = session(300, SessionStatus.IN_PROGRESS);
        when(orders.findFirstByConsultSessionIdOrderByIdDesc(300L)).thenReturn(Optional.of(order(300, PET_B)));
        when(pets.findIdentityByPetId(PET_B)).thenReturn(Optional.of(new PetIdentityView("Bobo", "DOG", 5)));
        assertThat(service.toSessionView(withOrder).petName()).isEqualTo("Bobo");

        ConsultSession noOrder = session(301, SessionStatus.IN_PROGRESS);
        when(orders.findFirstByConsultSessionIdOrderByIdDesc(301L)).thenReturn(Optional.empty());
        when(pets.findDefaultIdentityByOwner(USER)).thenReturn(Optional.of(new PetIdentityView("Momo", "CAT", 3)));
        assertThat(service.toSessionView(noOrder).petName()).isEqualTo("Momo");

        ConsultSession deletedPet = session(302, SessionStatus.IN_PROGRESS);
        when(orders.findFirstByConsultSessionIdOrderByIdDesc(302L)).thenReturn(Optional.of(order(302, 99L)));
        when(pets.findIdentityByPetId(99L)).thenReturn(Optional.empty());
        assertThat(service.toSessionView(deletedPet).petName()).isNull();
    }
}
