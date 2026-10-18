package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.consult.ConsultProperties;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.triage.service.TriageService;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X1 ~ X3）：兽医待接单 / 待付款按<b>单上的宠物</b>取身份，
 * 同一用户两张单分别挂 A、B 时各显示单上宠物；单上宠物已删 → 兜底 null 不抛。
 */
class ConsultRequestServiceTest {

    private static final long USER = 7L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;

    private ConsultRequestRepository requests;
    private PetProfileRepository petProfiles;
    private PetProfileQueryService petQuery;
    private AccountQueryService accounts;
    private ConsultRequestService service;

    @BeforeEach
    void setUp() {
        requests = mock(ConsultRequestRepository.class);
        petProfiles = mock(PetProfileRepository.class);
        petQuery = mock(PetProfileQueryService.class);
        accounts = mock(AccountQueryService.class);
        service = new ConsultRequestService(requests, petProfiles, mock(CardTokenGenerator.class),
                mock(ApplicationEventPublisher.class), mock(VetPresenceService.class), mock(ConsultProperties.class),
                petQuery, accounts, mock(TriageService.class), mock(SignedUrlService.class),
                mock(PaymentIntentService.class));
        when(accounts.findAuthorViews(anyCollection())).thenReturn(Map.of());
        when(requests.findByVetIdAndStateOrderByCreatedAtAsc(anyLong(), any())).thenReturn(List.of());
        when(requests.findByStateOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private static ConsultRequest request(String token, long petId) {
        return ConsultRequest.queue(USER, petId, token, Instant.now().plusSeconds(60));
    }

    private static PetIdentityView identity(String name, String species) {
        return new PetIdentityView(name, species, 3);
    }

    @Test
    void queuePoolShowsThePetOnEachRequest() {
        when(requests.findByStateOrderByCreatedAtAsc(ConsultRequestState.QUEUEING))
                .thenReturn(List.of(request("ra", PET_A), request("rb", PET_B)));
        when(petQuery.findIdentitiesByPetIds(anyCollection()))
                .thenReturn(Map.of(PET_A, identity("Momo", "CAT"), PET_B, identity("Bobo", "DOG")));

        var resp = service.vetQueue(1L);

        assertThat(resp.available()).extracting(i -> i.requestToken() + ":" + i.petName() + ":" + i.petSpecies())
                .containsExactly("ra:Momo:CAT", "rb:Bobo:DOG");
        verify(petQuery, never()).findDefaultIdentitiesByOwners(anyCollection());
    }

    @Test
    void awaitingPayShowsThePetOnTheRequest() {
        when(requests.findByVetIdAndStateOrderByCreatedAtAsc(1L, ConsultRequestState.ACCEPTED_AWAIT_PAY))
                .thenReturn(List.of(request("ra", PET_A), request("rb", PET_B)));
        when(petQuery.findIdentityByPetId(PET_A)).thenReturn(Optional.of(identity("Momo", "CAT")));
        when(petQuery.findIdentityByPetId(PET_B)).thenReturn(Optional.of(identity("Bobo", "DOG")));

        var resp = service.vetQueue(1L);

        assertThat(resp.awaitingPays()).extracting(i -> i.petName()).containsExactly("Momo", "Bobo");
    }

    @Test
    void deletedPetOnRequestFallsBackToNullWithoutThrowing() {
        when(requests.findByVetIdAndStateOrderByCreatedAtAsc(1L, ConsultRequestState.ACCEPTED_AWAIT_PAY))
                .thenReturn(List.of(request("ra", PET_A)));
        when(requests.findByStateOrderByCreatedAtAsc(ConsultRequestState.QUEUEING))
                .thenReturn(List.of(request("rb", PET_B)));
        when(petQuery.findIdentityByPetId(PET_A)).thenReturn(Optional.empty());
        when(petQuery.findIdentitiesByPetIds(anyCollection())).thenReturn(Map.of());

        var resp = service.vetQueue(1L);

        assertThat(resp.awaitingPays().get(0).petName()).isNull();
        assertThat(resp.available().get(0).petName()).isNull();
    }

    @Test
    void createRequestWithoutAnyPetIsStill409() {
        when(requests.existsByUserId(USER)).thenReturn(false);
        when(petProfiles.findDefault(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRequest(USER, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }
}
