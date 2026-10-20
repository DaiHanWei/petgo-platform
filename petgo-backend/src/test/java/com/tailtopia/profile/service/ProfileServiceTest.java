package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.dto.PetProfileResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.PetSlotFullException;
import com.tailtopia.profile.slot.PetSlotLock;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：创建 + 单宠物 409 + token 注入 + getMe 404（AC1/AC2 逻辑面）。 */
class ProfileServiceTest {

    private PetProfileRepository profiles;
    private CardTokenGenerator tokenGenerator;
    private MilestoneService milestoneService;
    private ProfileDeletionService profileDeletion;
    private com.tailtopia.shared.media.MediaDeletionService mediaDeletion;
    private ProfileService service;

    private com.tailtopia.tailsonality.service.TailsonalityBadgeQuery tailsonalityBadges;
    private PetSlotService petSlots;
    private PetSlotLock petSlotLock;
    private org.springframework.context.ApplicationEventPublisher events;

    /** 名额（Lv1 占位：total 1、可租 4、下一档 Lv5）。 */
    private static PetSlotQuota quota(int total, int petCount, Integer nextLevelSlot) {
        int rentable = PetSlotService.MAX_PETS - total;
        return new PetSlotQuota(total, 1, total - 1, 0, petCount, rentable, rentable > 0, nextLevelSlot, false);
    }

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        tokenGenerator = Mockito.mock(CardTokenGenerator.class);
        milestoneService = Mockito.mock(MilestoneService.class);
        profileDeletion = Mockito.mock(ProfileDeletionService.class);
        mediaDeletion = Mockito.mock(com.tailtopia.shared.media.MediaDeletionService.class);
        when(tokenGenerator.generate()).thenReturn("TOKEN_ABC");
        tailsonalityBadges = Mockito.mock(com.tailtopia.tailsonality.service.TailsonalityBadgeQuery.class);
        when(tailsonalityBadges.badgeOf(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.Optional.empty());
        petSlots = Mockito.mock(PetSlotService.class);
        petSlotLock = Mockito.mock(PetSlotLock.class);
        events = Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        when(petSlots.unitPrice()).thenReturn(PetSlotService.DEFAULT_UNIT_PRICE);
        when(petSlots.quotaOf(org.mockito.ArgumentMatchers.anyLong())).thenReturn(quota(1, 0, 5));
        service = new ProfileService(profiles, tokenGenerator, milestoneService, profileDeletion,
                mediaDeletion, events, tailsonalityBadges, new PetResolver(profiles), petSlots, petSlotLock,
                new PetWriteGuard(profiles));
    }

    private PetProfileCreateRequest req() {
        return new PetProfileCreateRequest(
                "https://cdn/x.jpg", "CAT", "Momo", "Shiba", LocalDate.of(2022, 1, 1), "好奇宝宝");
    }

    /** 模拟 JPA 落库回填自增 id（mock repository 无法生成）；供 create() 后 assignRoster(id) 用。 */
    private static PetProfile withId(PetProfile p, long id) {
        try {
            java.lang.reflect.Field f = PetProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    @Test
    void createPersistsWithGeneratedToken() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));

        PetProfileResponse resp = service.create(1L, req());

        assertThat(resp.name()).isEqualTo("Momo");
        assertThat(resp.petType()).isEqualTo("CAT"); // F6：类型落库回显
        assertThat(resp.cardToken()).isEqualTo("TOKEN_ABC");
        assertThat(resp.avatarUrl()).isEqualTo("https://cdn/x.jpg");
    }

    @Test
    void invalidPetTypeRejected() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        assertThatThrownBy(() -> service.create(1L, new PetProfileCreateRequest(
                null, "BIRD", "Momo", null, LocalDate.of(2022, 1, 1), null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("CAT/DOG/OTHER");
    }

    @Test
    void blankPetTypeRejected() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        assertThatThrownBy(() -> service.create(1L, new PetProfileCreateRequest(
                null, "  ", "Momo", null, LocalDate.of(2022, 1, 1), null)))
                .isInstanceOf(AppException.class);
    }

    /**
     * V1.4.0 multi-pet Story 1.4 · AC7.1：规则按设计改变——原「已有档案 → 409 profile-exists」，
     * 现为「已建只数 ≥ 名额 → 409 pet-slot-full」（Lv1 占位下名额 1，所以已有 1 只仍 409，只是 type 变了）。
     */
    @Test
    void duplicateProfileRejectedWith409PetSlotFullUnderDefaultQuota() {
        when(profiles.countByOwnerId(1L)).thenReturn(1L);
        when(petSlots.quotaOf(1L)).thenReturn(quota(1, 1, 5));
        assertThatThrownBy(() -> service.create(1L, req()))
                .isInstanceOf(PetSlotFullException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.PET_SLOT_FULL);
                });
    }

    @Test
    void blankNameRejected() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        assertThatThrownBy(() -> service.create(1L,
                new PetProfileCreateRequest(null, "CAT", "   ", null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getMyProfileNotFoundThrows404() {
        when(profiles.findDefault(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getMyProfile(9L)).isInstanceOf(AppException.class);
    }

    @Test
    void updatePartialKeepsCardTokenAndChangesFields() {
        PetProfile existing = PetProfile.create(1L, PetType.CAT, "Old", "u", "Breed",
                java.time.LocalDate.of(2020, 1, 1), "old intro", "TOK_KEEP");
        when(profiles.findDefault(1L)).thenReturn(java.util.Optional.of(existing));
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));

        PetProfileResponse resp = service.update(1L,
                new com.tailtopia.profile.dto.PetProfileUpdateRequest(null, "New", null, null, null, "new intro", null, null));

        assertThat(resp.name()).isEqualTo("New");
        assertThat(resp.intro()).isEqualTo("new intro");
        assertThat(resp.cardToken()).isEqualTo("TOK_KEEP"); // token 不变
        assertThat(existing.getBreed()).isEqualTo("Breed"); // 未传字段不变
    }

    @Test
    void updateWithoutProfileThrows404() {
        when(profiles.findDefault(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.update(9L,
                new com.tailtopia.profile.dto.PetProfileUpdateRequest(null, "X", null, null, null, null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void updateBlankNameRejected() {
        PetProfile existing = PetProfile.create(1L, PetType.CAT, "Old", null, null, null, null, "TOK");
        when(profiles.findDefault(1L)).thenReturn(java.util.Optional.of(existing));
        assertThatThrownBy(() -> service.update(1L,
                new com.tailtopia.profile.dto.PetProfileUpdateRequest(null, "   ", null, null, null, null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void deleteMyProfileCascadesAndCleansMedia() {
        PetProfile only = withId(PetProfile.create(1L, PetType.CAT, "Momo", null, null, null, null, "TOK"), 5L);
        when(profiles.findDefault(1L)).thenReturn(java.util.Optional.of(only));
        when(profileDeletion.deleteByPetId(1L, 5L)).thenReturn(new com.tailtopia.shared.media.PersonalMedia(
                java.util.List.of("k1", "k2"), java.util.List.of("https://cdn/avatar.jpg")));

        service.deleteMyProfile(1L);

        Mockito.verify(profileDeletion).deleteByPetId(1L, 5L);
        Mockito.verify(mediaDeletion).deletePrivateKeys(java.util.List.of("k1", "k2"));
        Mockito.verify(mediaDeletion).deletePublicByUrls(java.util.List.of("https://cdn/avatar.jpg"));
    }

    @Test
    void deleteMyProfileWithoutProfileThrows404() {
        when(profiles.findDefault(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.deleteMyProfile(9L)).isInstanceOf(AppException.class);
        Mockito.verify(profileDeletion, Mockito.never()).deleteByPetId(Mockito.anyLong(), Mockito.anyLong());
    }

    /** V1.4.0 multi-pet Story 1.3 · AC1.3：带 petId 只删那一只，不碰默认宠物。 */
    @Test
    void deleteMyProfileWithPetIdDeletesOnlyThatPet() {
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        when(profiles.findByIdAndOwnerId(11L, 1L)).thenReturn(java.util.Optional.of(b));
        when(profileDeletion.deleteByPetId(1L, 11L)).thenReturn(com.tailtopia.shared.media.PersonalMedia.empty());

        service.deleteMyProfile(1L, 11L);

        Mockito.verify(profileDeletion).deleteByPetId(1L, 11L);
        Mockito.verify(profileDeletion, Mockito.never()).deleteAllForAccount(Mockito.anyLong());
        Mockito.verify(profiles, Mockito.never()).findDefault(Mockito.anyLong());
    }

    /** 他人 / 不存在的 petId → 404，不删任何东西。 */
    @Test
    void deleteMyProfileWithForeignPetId404() {
        when(profiles.findByIdAndOwnerId(99L, 1L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.deleteMyProfile(1L, 99L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
        Mockito.verify(profileDeletion, Mockito.never()).deleteByPetId(Mockito.anyLong(), Mockito.anyLong());
    }

    /** 不带 petId = 默认宠物（兼容旧版 App）。 */
    @Test
    void deleteMyProfileWithoutPetIdDeletesDefaultPet() {
        PetProfile a = withId(PetProfile.create(1L, PetType.CAT, "A", null, null, null, null, "TOK_A"), 10L);
        when(profiles.findDefault(1L)).thenReturn(java.util.Optional.of(a));
        when(profileDeletion.deleteByPetId(1L, 10L)).thenReturn(com.tailtopia.shared.media.PersonalMedia.empty());

        service.deleteMyProfile(1L, null);

        Mockito.verify(profileDeletion).deleteByPetId(1L, 10L);
    }

    /** 旧版兼容：多宠账号不带 petId 的编辑 / 删除一律 409 pet-id-required，且不落任何改动（不解析默认宠物、不删）。 */
    @Test
    void multiPetWithoutPetIdUpdateAndDeleteRejected() {
        when(profiles.countByOwnerId(1L)).thenReturn(2L);

        assertThatThrownBy(() -> service.update(1L, null, new com.tailtopia.profile.dto.PetProfileUpdateRequest(
                "X", null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.PET_ID_REQUIRED));
        assertThatThrownBy(() -> service.deleteMyProfile(1L, null))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.PET_ID_REQUIRED));

        Mockito.verify(profiles, Mockito.never()).findDefault(Mockito.anyLong());
        Mockito.verify(profiles, Mockito.never()).save(any(PetProfile.class));
        Mockito.verify(profileDeletion, Mockito.never()).deleteByPetId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void blankOptionalFieldsNormalizedToNull() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));
        PetProfileResponse resp = service.create(1L,
                new PetProfileCreateRequest("  ", "DOG", "Momo", "  ", null, "  "));
        assertThat(resp.breed()).isNull();
        assertThat(resp.intro()).isNull();
        assertThat(resp.avatarUrl()).isNull();
    }

    // ---------- V1.4.0 multi-pet：按 petId 寻址 ----------

    @Test
    void getMyProfileWithPetIdReturnsThatPet() {
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        when(profiles.findByIdAndOwnerId(11L, 1L)).thenReturn(java.util.Optional.of(b));
        assertThat(service.getMyProfile(1L, 11L).name()).isEqualTo("B");
    }

    @Test
    void getMyProfileWithForeignPetId404() {
        when(profiles.findByIdAndOwnerId(99L, 1L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getMyProfile(1L, 99L)).isInstanceOf(AppException.class);
    }

    @Test
    void updateWithPetIdChangesOnlyThatPet() {
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        when(profiles.findByIdAndOwnerId(11L, 1L)).thenReturn(java.util.Optional.of(b));
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        service.update(1L, 11L,
                new com.tailtopia.profile.dto.PetProfileUpdateRequest(null, "B2", null, null, null, null, null, null));
        assertThat(b.getName()).isEqualTo("B2");
        Mockito.verify(profiles, Mockito.never()).findDefault(Mockito.anyLong());
    }

    @Test
    void recordCardSharedPublishesForChosenPet() {
        var events = Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        ProfileService svc = new ProfileService(profiles, tokenGenerator, milestoneService, profileDeletion,
                mediaDeletion, events, tailsonalityBadges, new PetResolver(profiles), petSlots, petSlotLock,
                new PetWriteGuard(profiles));
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        when(profiles.findByIdAndOwnerId(11L, 1L)).thenReturn(java.util.Optional.of(b));
        svc.recordCardShared(1L, 11L);
        var cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(events).publishEvent(cap.capture());
        assertThat(((com.tailtopia.profile.event.CardSharedEvent) cap.getValue()).petProfileId()).isEqualTo(11L);
    }

    @Test
    void ownsPetUsesIdAndOwnerExistence() {
        when(profiles.existsByIdAndOwnerId(11L, 1L)).thenReturn(true);
        when(profiles.existsByIdAndOwnerId(99L, 1L)).thenReturn(false);
        assertThat(service.ownsPet(1L, 11L)).isTrue();
        assertThat(service.ownsPet(1L, 99L)).isFalse();
    }

    // ---------- V1.4.0 multi-pet Story 1.4：名额校验 ----------

    @Test
    void lockIsTakenBeforeCountingAndQuota() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));
        service.create(1L, req());
        org.mockito.InOrder order = Mockito.inOrder(petSlotLock, profiles, petSlots);
        order.verify(petSlotLock).lockUser(1L);
        order.verify(profiles).countByOwnerId(1L);
        order.verify(petSlots).quotaOf(1L);
        order.verify(profiles).save(any(PetProfile.class));
    }

    @Test
    void fivePetsRejectedWithPetLimitReachedBeforeSlotCheck() {
        when(profiles.countByOwnerId(1L)).thenReturn(5L);
        when(petSlots.quotaOf(1L)).thenReturn(quota(5, 5, null));
        assertThatThrownBy(() -> service.create(1L, req()))
                .isInstanceOf(AppException.class)
                .isNotInstanceOf(PetSlotFullException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.PET_LIMIT_REACHED);
                });
        Mockito.verify(petSlots, Mockito.never()).quotaOf(Mockito.anyLong());
        Mockito.verify(profiles, Mockito.never()).save(any(PetProfile.class));
    }

    @Test
    void slotFullCarriesExtensions() {
        when(profiles.countByOwnerId(1L)).thenReturn(2L);
        when(petSlots.quotaOf(1L)).thenReturn(quota(2, 2, 10)); // Lv5：名额 2
        assertThatThrownBy(() -> service.create(1L, req()))
                .isInstanceOf(PetSlotFullException.class)
                .satisfies(e -> {
                    var ext = ((PetSlotFullException) e).problemExtensions();
                    assertThat(ext).containsEntry("slotTotal", 2).containsEntry("petCount", 2)
                            .containsEntry("canRent", true).containsEntry("nextLevelSlot", 10)
                            .containsEntry("unitPrice", 5000L);
                });
        Mockito.verify(profiles, Mockito.never()).save(any(PetProfile.class));
    }

    @Test
    void secondPetCreatedWhenQuotaAllowsAndPublishesEvents() {
        when(profiles.countByOwnerId(1L)).thenReturn(1L);
        when(petSlots.quotaOf(1L)).thenReturn(quota(2, 1, 10)); // Lv5
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 101L));
        PetProfileResponse resp = service.create(1L, req());
        assertThat(resp.name()).isEqualTo("Momo");
        Mockito.verify(milestoneService).assignRoster(101L, PetType.CAT);
        var cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(events, Mockito.times(3)).publishEvent(cap.capture());
        assertThat(cap.getAllValues()).hasAtLeastOneElementOfType(com.tailtopia.profile.event.ProfileCreatedEvent.class)
                .hasAtLeastOneElementOfType(com.tailtopia.namemoderation.event.NameSubmittedEvent.class)
                .hasAtLeastOneElementOfType(com.tailtopia.avatarmoderation.event.AvatarReviewRequestedEvent.class);
    }

    /** PRD AC9：Lv10 建满 3 只后降到 Lv9（名额 2）→ 已建照常，只是不能再建第 4 只。 */
    @Test
    void downgradeKeepsExistingPetsButBlocksNewOne() {
        when(profiles.countByOwnerId(1L)).thenReturn(3L);
        when(petSlots.quotaOf(1L)).thenReturn(quota(2, 3, 10)); // Lv9
        assertThatThrownBy(() -> service.create(1L, req())).isInstanceOf(PetSlotFullException.class);
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 12L);
        when(profiles.findByIdAndOwnerId(12L, 1L)).thenReturn(java.util.Optional.of(b));
        assertThat(service.getMyProfile(1L, 12L).name()).isEqualTo("B");
    }

    @Test
    void uniqueViolationStillNormalizedToProfileExists() {
        when(profiles.countByOwnerId(1L)).thenReturn(0L);
        when(profiles.save(any(PetProfile.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_pet_profiles_card_token"));
        assertThatThrownBy(() -> service.create(1L, req()))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.PROFILE_EXISTS));
    }

    @Test
    void listOwnedDelegatesToResolverOrder() {
        PetProfile a = withId(PetProfile.create(1L, PetType.CAT, "A", null, null, null, null, "TOK_A"), 10L);
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(java.util.List.of(a, b));
        assertThat(service.listOwned(1L)).extracting(PetProfile::getName).containsExactly("A", "B");
    }

    /** V1.4.0 multi-pet Story 2.1（AC7 #5）：编辑只读宠物档案 → 409 pet-read-only，不改字段、不落库、不发送审事件。 */
    @Test
    void updateReadOnlyPetRejected409WithoutChanges() {
        PetProfile b = withId(PetProfile.create(1L, PetType.DOG, "B", null, null, null, null, "TOK_B"), 11L);
        b.markReadOnly();
        when(profiles.findByIdAndOwnerId(11L, 1L)).thenReturn(java.util.Optional.of(b));

        assertThatThrownBy(() -> service.update(1L, 11L,
                new com.tailtopia.profile.dto.PetProfileUpdateRequest(null, "B2", null, null, null, null, null, null)))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getType()).isEqualTo(com.tailtopia.shared.error.ErrorTypes.PET_READ_ONLY);
                });
        assertThat(b.getName()).isEqualTo("B");
        Mockito.verify(profiles, Mockito.never()).save(any(PetProfile.class));
        Mockito.verify(events, Mockito.never()).publishEvent(any(Object.class));
    }
}
