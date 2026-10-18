package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · Q1 ~ Q8）：跨模块读口在「同一账号两只宠物」下不串、不抛。
 * 夹具：owner 1 有 A（10，先建，猫）、B（11，后建，狗）；owner 2 有 C（20）。
 */
class PetProfileQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private PetProfileRepository repo;
    private PetProfileQueryService service;
    private PetProfile a;
    private PetProfile b;
    private PetProfile c;

    @BeforeEach
    void setUp() {
        repo = mock(PetProfileRepository.class);
        service = new PetProfileQueryService(repo, mock(IdCardRepository.class));
        a = pet(1L, 10L, PetType.CAT, T0);
        b = pet(1L, 11L, PetType.DOG, T0.plusSeconds(60));
        c = pet(2L, 20L, PetType.CAT, T0.plusSeconds(30));
        when(repo.findAllByOwnerIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of(a, b));
        when(repo.findAllByOwnerIdOrderByCreatedAtAscIdAsc(2L)).thenReturn(List.of(c));
        when(repo.findDefault(org.mockito.ArgumentMatchers.anyLong())).thenCallRealMethod();
        when(repo.findById(10L)).thenReturn(Optional.of(a));
        when(repo.findById(11L)).thenReturn(Optional.of(b));
        when(repo.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(a));
        when(repo.findByIdAndOwnerId(11L, 1L)).thenReturn(Optional.of(b));
        when(repo.existsByIdAndOwnerId(11L, 1L)).thenReturn(true);
    }

    @Test
    void allSnapshotsAreReadInCreationOrder() {
        when(repo.findAll(any(Sort.class))).thenReturn(List.of(a, c, b));

        var snaps = service.allSnapshots();

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(repo).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by("createdAt", "id"));
        assertThat(snaps).extracting(s -> s.id()).containsExactly(10L, 20L, 11L);
        verify(repo, never()).findAll();
    }

    @Test
    void defaultPetNameByOwnerIsAlwaysTheFirstCreatedPet() {
        when(repo.findAll(any(Sort.class))).thenReturn(List.of(a, c, b));

        var names = service.defaultPetNameByOwner();

        assertThat(names).containsEntry(1L, "Pet10").containsEntry(2L, "Pet20").hasSize(2);
    }

    @Test
    void identityByPetIdTargetsThatPetAndNullOrMissingIsEmpty() {
        assertThat(service.findIdentityByPetId(11L)).get().extracting(v -> v.species()).isEqualTo("DOG");
        assertThat(service.findIdentityByPetId(10L)).get().extracting(v -> v.species()).isEqualTo("CAT");
        assertThat(service.findIdentityByPetId(null)).isEmpty();
        assertThat(service.findIdentityByPetId(999L)).isEmpty(); // 单上宠物已删
    }

    @Test
    void defaultIdentityByOwnerIsFirstCreated() {
        assertThat(service.findDefaultIdentityByOwner(1L)).get().extracting(v -> v.name()).isEqualTo("Pet10");
        assertThat(service.findDefaultIdentityByOwner(null)).isEmpty();
        assertThat(service.findDefaultIdentityByOwner(3L)).isEmpty();
    }

    @Test
    void identitiesByPetIdsAreKeyedByPetIdAndSkipNulls() {
        when(repo.findAllById(anyCollection())).thenReturn(List.of(a, b));

        var map = service.findIdentitiesByPetIds(java.util.Arrays.asList(10L, 11L, null, 11L));

        assertThat(map).containsOnlyKeys(10L, 11L);
        assertThat(map.get(10L).species()).isEqualTo("CAT");
        assertThat(map.get(11L).species()).isEqualTo("DOG");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(repo).findAllById(ids.capture());
        assertThat(Set.copyOf(ids.getValue())).containsExactlyInAnyOrder(10L, 11L);
        assertThat(service.findIdentitiesByPetIds(List.of())).isEmpty();
    }

    @Test
    void defaultIdentitiesByOwnersDoNotThrowOnTwoPetsAndPickTheDefault() {
        // 旧实现 toMap 无合并函数：一个账号两只宠物即 Duplicate key → 兽医工作台整页 500。
        when(repo.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(anyCollection())).thenReturn(List.of(a, c, b));

        var map = service.findDefaultIdentitiesByOwners(java.util.Arrays.asList(1L, 2L, null));

        assertThat(map).containsOnlyKeys(1L, 2L);
        assertThat(map.get(1L).name()).isEqualTo("Pet10");
        assertThat(map.get(2L).name()).isEqualTo("Pet20");
    }

    @Test
    void ownedPetAndPassportSubjectFollowPetIdOrDefault() {
        assertThat(service.findOwnedPet(1L, 11L)).get().extracting(r -> r.petId()).isEqualTo(11L);
        assertThat(service.findOwnedPet(1L, null)).get().extracting(r -> r.petId()).isEqualTo(10L);
        assertThat(service.findOwnedPet(1L, 20L)).as("他人 petId → empty（调用方 404）").isEmpty();

        assertThat(service.findPassportSubject(1L, 11L)).get().extracting(s -> s.petId()).isEqualTo(11L);
        assertThat(service.findPassportSubject(1L, null)).get().extracting(s -> s.petId()).isEqualTo(10L);
        assertThat(service.findPassportSubject(1L, 20L)).isEmpty();
        assertThat(service.findPassportSubject(3L, null)).isEmpty();
        assertThat(service.findPassportSubjectByPetId(11L)).get().extracting(s -> s.petType()).isEqualTo("DOG");
    }

    @Test
    void passportSubjectCarriesBreed() {
        PetProfile withBreed = PetProfile.create(5L, PetType.CAT, "Momo", null, "Anggora", null, null, "TOKX");
        org.springframework.test.util.ReflectionTestUtils.setField(withBreed, "id", 50L);
        when(repo.findById(50L)).thenReturn(Optional.of(withBreed));
        assertThat(service.findPassportSubjectByPetId(50L)).get().extracting(s -> s.breed()).isEqualTo("Anggora");
    }

    @Test
    void petIdsByOwnerListsAllInOrderAndOwnsPetChecksOwnership() {
        assertThat(service.findPetIdsByOwner(1L)).containsExactly(10L, 11L);
        assertThat(service.findPetIdsByOwner(3L)).isEmpty();
        assertThat(service.ownsPet(1L, 11L)).isTrue();
        assertThat(service.ownsPet(1L, 20L)).isFalse();
    }
}
