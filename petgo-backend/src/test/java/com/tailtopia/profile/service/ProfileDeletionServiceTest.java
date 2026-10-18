package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.passport.service.PetPassportDeletionService;
import com.tailtopia.place.service.PlaceCheckinDeletionService;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.media.PersonalMedia;
import com.tailtopia.tailsonality.service.TailsonalityDeletionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.4.0 multi-pet Story 1.3 · AC1 / AC2 · L0：按只删除只动这一只、不给身份证打标；注销逐只删后按账号打标一次。
 */
class ProfileDeletionServiceTest {

    private static final long OWNER = 7L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;

    private final PetProfileRepository petProfiles = mock(PetProfileRepository.class);
    private final HealthEventRepository healthEvents = mock(HealthEventRepository.class);
    private final HealthRecordRepository healthRecords = mock(HealthRecordRepository.class);
    private final PetMilestoneRepository petMilestones = mock(PetMilestoneRepository.class);
    private final MilestoneCompletionRepository milestoneCompletions = mock(MilestoneCompletionRepository.class);
    private final MilestoneShareRepository milestoneShares = mock(MilestoneShareRepository.class);
    private final ContentPostRepository contentPosts = mock(ContentPostRepository.class);
    private final SerialAllocationService serialAllocation = mock(SerialAllocationService.class);
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final PlaceCheckinDeletionService placeCheckins = mock(PlaceCheckinDeletionService.class);
    private final PetPassportDeletionService petPassports = mock(PetPassportDeletionService.class);
    private final TailsonalityDeletionService tailsonality = mock(TailsonalityDeletionService.class);

    private ProfileDeletionService service;
    private PetProfile a;
    private PetProfile b;

    @BeforeEach
    void setUp() {
        service = new ProfileDeletionService(petProfiles, healthEvents, healthRecords, petMilestones,
                milestoneCompletions, milestoneShares, contentPosts, serialAllocation, idCards,
                placeCheckins, petPassports, tailsonality);
        a = PetFixtures.pet(OWNER, PET_A, PetType.CAT, Instant.parse("2026-01-01T00:00:00Z"));
        b = PetFixtures.pet(OWNER, PET_B, PetType.DOG, Instant.parse("2026-02-01T00:00:00Z"));
        ReflectionTestUtils.setField(a, "serialId", 128L);
        a.setAvatarUrl("https://cdn/a.jpg");
        when(petProfiles.findByIdAndOwnerId(PET_A, OWNER)).thenReturn(Optional.of(a));
        when(petProfiles.findByIdAndOwnerId(PET_B, OWNER)).thenReturn(Optional.of(b));
        when(petProfiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b));
        // 删 A 后账号仍有 B（只有删最后一只才打标，见 deletingLastPetStillMarksIdCards）。
        when(petProfiles.existsByOwnerId(OWNER)).thenReturn(true);
    }

    @Test
    void deleteByPetIdTouchesOnlyThatPetAndDoesNotMarkIdCards() {
        HealthEvent e = mock(HealthEvent.class);
        when(e.getImageKeys()).thenReturn(List.of("health/a1.jpg"));
        when(healthEvents.findByPetId(PET_A)).thenReturn(List.of(e));
        PetMilestone m = PetMilestone.of(PET_A, MilestoneCatalog.byCode("C-S1"));
        ReflectionTestUtils.setField(m, "id", 501L);
        when(petMilestones.findByPetProfileIdOrderBySortOrderAsc(PET_A)).thenReturn(List.of(m));

        PersonalMedia media = service.deleteByPetId(OWNER, PET_A);

        verify(healthEvents).deleteByPetId(PET_A);
        verify(healthRecords).deleteByPetProfileId(PET_A);
        verify(milestoneCompletions).deleteByPetMilestoneIdIn(List.of(501L));
        verify(petMilestones).deleteByPetProfileId(PET_A);
        verify(milestoneShares).deleteByPetProfileId(PET_A);
        verify(contentPosts).detachPet(PET_A);
        verify(placeCheckins).deleteForPet(PET_A, OWNER);
        verify(petPassports).deleteForPet(PET_A);
        verify(tailsonality).deleteForPet(PET_A);
        verify(serialAllocation).release(128L);
        verify(petProfiles).delete(a);
        // 🔴 AC1.2：还剩其他宠物时，按只删除不给账号全部身份证打标。
        verify(idCards, never()).markProfileDeleted(anyLong(), any());
        // B 的任何一张表都不碰。
        verify(healthEvents, never()).deleteByPetId(PET_B);
        verify(contentPosts, never()).detachPet(PET_B);
        verify(placeCheckins, never()).deleteForPet(eq(PET_B), anyLong());
        verify(petProfiles, never()).delete(b);

        assertThat(media.privateKeys()).containsExactly("health/a1.jpg");
        assertThat(media.publicUrls()).containsExactly("https://cdn/a.jpg");
    }

    /** FK 无 ON DELETE 的子表必须先于宠物行删除。 */
    @Test
    void childTablesAreClearedBeforeThePetRow() {
        service.deleteByPetId(OWNER, PET_A);

        InOrder o = inOrder(contentPosts, placeCheckins, petPassports, tailsonality, healthEvents,
                milestoneShares, petProfiles);
        o.verify(healthEvents).deleteByPetId(PET_A);
        o.verify(milestoneShares).deleteByPetProfileId(PET_A);
        o.verify(contentPosts).detachPet(PET_A);
        o.verify(placeCheckins).deleteForPet(PET_A, OWNER);
        o.verify(petPassports).deleteForPet(PET_A);
        o.verify(tailsonality).deleteForPet(PET_A);
        o.verify(petProfiles).delete(a);
    }

    @Test
    void deleteByPetIdOfForeignPetIsNoop() {
        when(petProfiles.findByIdAndOwnerId(99L, OWNER)).thenReturn(Optional.empty());

        PersonalMedia media = service.deleteByPetId(OWNER, 99L);

        assertThat(media.privateKeys()).isEmpty();
        assertThat(media.publicUrls()).isEmpty();
        verify(petProfiles, never()).delete(any());
        verify(contentPosts, never()).detachPet(anyLong());
    }

    /** AC2：注销逐只删两只，最后按账号打标一次。 */
    @Test
    void deleteAllForAccountDeletesEachPetThenMarksIdCardsOnce() {
        b.setAvatarUrl("https://cdn/b.jpg");

        PersonalMedia media = service.deleteAllForAccount(OWNER);

        verify(petProfiles).delete(a);
        verify(petProfiles).delete(b);
        verify(contentPosts).detachPet(PET_A);
        verify(contentPosts).detachPet(PET_B);
        verify(idCards, times(1)).markProfileDeleted(eq(OWNER), any(Instant.class));
        InOrder o = inOrder(petProfiles, idCards);
        o.verify(petProfiles).delete(a);
        o.verify(petProfiles).delete(b);
        o.verify(idCards).markProfileDeleted(eq(OWNER), any(Instant.class));
        assertThat(media.publicUrls()).containsExactlyInAnyOrder("https://cdn/a.jpg", "https://cdn/b.jpg");
    }

    @Test
    void deleteAllForAccountWithoutPetsStillMarksAndReturnsEmpty() {
        when(petProfiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of());

        PersonalMedia media = service.deleteAllForAccount(OWNER);

        assertThat(media.privateKeys()).isEmpty();
        assertThat(media.publicUrls()).isEmpty();
        verify(petProfiles, never()).delete(any());
    }

    /** 1.3 复审：删的是账号最后一只（单宠物删档）→ 照旧按账号打标，防 bug 20260828 复现。 */
    @Test
    void deletingLastPetStillMarksIdCards() {
        when(petProfiles.existsByOwnerId(OWNER)).thenReturn(false);

        service.deleteByPetId(OWNER, PET_A);

        InOrder o = inOrder(petProfiles, idCards);
        o.verify(petProfiles).delete(a);
        o.verify(idCards).markProfileDeleted(eq(OWNER), any(Instant.class));
    }
}
