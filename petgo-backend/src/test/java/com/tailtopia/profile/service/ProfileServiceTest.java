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
        service = new ProfileService(profiles, tokenGenerator, milestoneService, profileDeletion,
                mediaDeletion, Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                tailsonalityBadges, new PetResolver(profiles));
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
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));

        PetProfileResponse resp = service.create(1L, req());

        assertThat(resp.name()).isEqualTo("Momo");
        assertThat(resp.petType()).isEqualTo("CAT"); // F6：类型落库回显
        assertThat(resp.cardToken()).isEqualTo("TOKEN_ABC");
        assertThat(resp.avatarUrl()).isEqualTo("https://cdn/x.jpg");
    }

    @Test
    void invalidPetTypeRejected() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(1L, new PetProfileCreateRequest(
                null, "BIRD", "Momo", null, LocalDate.of(2022, 1, 1), null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("CAT/DOG/OTHER");
    }

    @Test
    void blankPetTypeRejected() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(1L, new PetProfileCreateRequest(
                null, "  ", "Momo", null, LocalDate.of(2022, 1, 1), null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void duplicateProfileRejectedWith409() {
        when(profiles.existsByOwnerId(1L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(1L, req()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("单只宠物");
    }

    @Test
    void blankNameRejected() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
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

    @Test
    void blankOptionalFieldsNormalizedToNull() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
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
                mediaDeletion, events, tailsonalityBadges, new PetResolver(profiles));
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
}
