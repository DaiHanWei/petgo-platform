package com.petgo.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：创建 + 单宠物 409 + token 注入 + getMe 404（AC1/AC2 逻辑面）。 */
class ProfileServiceTest {

    private PetProfileRepository profiles;
    private CardTokenGenerator tokenGenerator;
    private MilestoneService milestoneService;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        tokenGenerator = Mockito.mock(CardTokenGenerator.class);
        milestoneService = Mockito.mock(MilestoneService.class);
        when(tokenGenerator.generate()).thenReturn("TOKEN_ABC");
        service = new ProfileService(profiles, tokenGenerator, milestoneService);
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
        when(profiles.findByOwnerId(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getMyProfile(9L)).isInstanceOf(AppException.class);
    }

    @Test
    void updatePartialKeepsCardTokenAndChangesFields() {
        PetProfile existing = PetProfile.create(1L, PetType.CAT, "Old", "u", "Breed",
                java.time.LocalDate.of(2020, 1, 1), "old intro", "TOK_KEEP");
        when(profiles.findByOwnerId(1L)).thenReturn(java.util.Optional.of(existing));
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));

        PetProfileResponse resp = service.update(1L,
                new com.petgo.profile.dto.PetProfileUpdateRequest(null, "New", null, null, "new intro"));

        assertThat(resp.name()).isEqualTo("New");
        assertThat(resp.intro()).isEqualTo("new intro");
        assertThat(resp.cardToken()).isEqualTo("TOK_KEEP"); // token 不变
        assertThat(existing.getBreed()).isEqualTo("Breed"); // 未传字段不变
    }

    @Test
    void updateWithoutProfileThrows404() {
        when(profiles.findByOwnerId(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.update(9L,
                new com.petgo.profile.dto.PetProfileUpdateRequest(null, "X", null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void updateBlankNameRejected() {
        PetProfile existing = PetProfile.create(1L, PetType.CAT, "Old", null, null, null, null, "TOK");
        when(profiles.findByOwnerId(1L)).thenReturn(java.util.Optional.of(existing));
        assertThatThrownBy(() -> service.update(1L,
                new com.petgo.profile.dto.PetProfileUpdateRequest(null, "   ", null, null, null)))
                .isInstanceOf(AppException.class);
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
}
