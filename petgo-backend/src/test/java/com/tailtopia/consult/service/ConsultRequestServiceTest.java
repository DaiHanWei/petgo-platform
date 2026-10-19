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
    private TriageService triage;
    private ConsultRequestService service;

    @BeforeEach
    void setUp() {
        requests = mock(ConsultRequestRepository.class);
        petProfiles = mock(PetProfileRepository.class);
        petQuery = mock(PetProfileQueryService.class);
        accounts = mock(AccountQueryService.class);
        triage = mock(TriageService.class);
        CardTokenGenerator tokens = mock(CardTokenGenerator.class);
        when(tokens.generate()).thenReturn("tok-new");
        service = new ConsultRequestService(requests, petProfiles, tokens,
                mock(ApplicationEventPublisher.class), mock(VetPresenceService.class), mock(ConsultProperties.class),
                petQuery, accounts, triage, mock(SignedUrlService.class),
                mock(PaymentIntentService.class), new com.tailtopia.profile.service.PetResolver(petProfiles));
        when(requests.save(any(ConsultRequest.class))).thenAnswer(inv -> {
            ConsultRequest r = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 500L);
            return r;
        });
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

    // ---- V1.4.0 multi-pet Story 4.1 · AC4：入队按所选宠物 ----

    private static final long PET_FOREIGN = 99L;

    private void twoPets() {
        when(requests.existsByUserId(USER)).thenReturn(false);
        var a = com.tailtopia.profile.support.PetFixtures.pet(USER, PET_A, Instant.EPOCH);
        var b = com.tailtopia.profile.support.PetFixtures.pet(USER, PET_B, Instant.EPOCH.plusSeconds(1));
        when(petProfiles.findDefault(USER)).thenReturn(Optional.of(a));
        when(petProfiles.findByIdAndOwnerId(PET_A, USER)).thenReturn(Optional.of(a));
        when(petProfiles.findByIdAndOwnerId(PET_B, USER)).thenReturn(Optional.of(b));
        when(petProfiles.findByIdAndOwnerId(PET_FOREIGN, USER)).thenReturn(Optional.empty());
        when(petQuery.ownsPet(USER, PET_A)).thenReturn(true);
        when(petQuery.ownsPet(USER, PET_B)).thenReturn(true);
    }

    private static com.tailtopia.consult.dto.CreateConsultationRequest direct(Long petId) {
        return new com.tailtopia.consult.dto.CreateConsultationRequest(null, null, "muntah", null, petId);
    }

    private static com.tailtopia.consult.dto.CreateConsultationRequest upgrade(Long petId) {
        return new com.tailtopia.consult.dto.CreateConsultationRequest("AI_UPGRADE", 5L, null, null, petId);
    }

    private void triageTaskPet(Long petId) {
        when(triage.getResultForUpgrade(USER, 5L)).thenReturn(new com.tailtopia.triage.dto.TriageUpgradeContext(
                5L, com.tailtopia.triage.domain.DangerLevel.YELLOW, "muntah", List.of(), petId));
    }

    @Test
    void directWithOwnPetQueuesThatPet() {
        twoPets();
        var r = service.createRequest(USER, direct(PET_B));
        assertThat(r.alreadyActive()).isFalse();
        assertThat(r.request().getPetProfileId()).isEqualTo(PET_B);
    }

    @Test
    void directWithForeignPetIs404AndNotQueued() {
        twoPets();
        assertThatThrownBy(() -> service.createRequest(USER, direct(PET_FOREIGN)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        verify(requests, never()).save(any());
    }

    @Test
    void directWithoutPetIdUsesDefaultPet() {
        twoPets();
        assertThat(service.createRequest(USER, direct(null)).request().getPetProfileId()).isEqualTo(PET_A);
        assertThat(service.createRequest(USER, null).request().getPetProfileId()).isEqualTo(PET_A);
    }

    @Test
    void readOnlyPetCanStillQueue() {
        twoPets();
        var b = com.tailtopia.profile.support.PetFixtures.pet(USER, PET_B, Instant.EPOCH.plusSeconds(1));
        b.markReadOnly();
        when(petProfiles.findByIdAndOwnerId(PET_B, USER)).thenReturn(Optional.of(b));
        assertThat(service.createRequest(USER, direct(PET_B)).request().getPetProfileId()).isEqualTo(PET_B);
    }

    @Test
    void upgradeUsesTriageTaskPetIgnoringBody() {
        twoPets();
        triageTaskPet(PET_B);
        assertThat(service.createRequest(USER, upgrade(PET_A)).request().getPetProfileId()).isEqualTo(PET_B);
    }

    @Test
    void upgradeWithoutTaskPetFallsBackToBodyThenDefault() {
        twoPets();
        triageTaskPet(null);
        assertThat(service.createRequest(USER, upgrade(PET_B)).request().getPetProfileId()).isEqualTo(PET_B);
        assertThat(service.createRequest(USER, upgrade(null)).request().getPetProfileId()).isEqualTo(PET_A);
    }

    @Test
    void upgradeWhoseTaskPetIsNoLongerOwnedFallsBackToDefault() {
        twoPets();
        triageTaskPet(PET_FOREIGN); // 任务上的宠物已删 / 不属本人
        assertThat(service.createRequest(USER, upgrade(null)).request().getPetProfileId()).isEqualTo(PET_A);
    }

    @Test
    void occupiedReturnsExistingWithoutCheckingBodyPet() {
        when(requests.existsByUserId(USER)).thenReturn(true);
        ConsultRequest live = request("live", PET_A);
        when(requests.findFirstByUserIdOrderByCreatedAtAsc(USER)).thenReturn(Optional.of(live));

        var r = service.createRequest(USER, direct(PET_FOREIGN));

        assertThat(r.alreadyActive()).isTrue();
        assertThat(r.request()).isSameAs(live);
        verify(petProfiles, never()).findByIdAndOwnerId(anyLong(), anyLong());
    }
}
