package com.petgo.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.petgo.profile.domain.PetProfile;
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
    private ProfileService service;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        tokenGenerator = Mockito.mock(CardTokenGenerator.class);
        when(tokenGenerator.generate()).thenReturn("TOKEN_ABC");
        service = new ProfileService(profiles, tokenGenerator);
    }

    private PetProfileCreateRequest req() {
        return new PetProfileCreateRequest("https://cdn/x.jpg", "Momo", "Shiba", LocalDate.of(2022, 1, 1), "好奇宝宝");
    }

    @Test
    void createPersistsWithGeneratedToken() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        PetProfileResponse resp = service.create(1L, req());

        assertThat(resp.name()).isEqualTo("Momo");
        assertThat(resp.cardToken()).isEqualTo("TOKEN_ABC");
        assertThat(resp.avatarUrl()).isEqualTo("https://cdn/x.jpg");
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
                new PetProfileCreateRequest(null, "   ", null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getMyProfileNotFoundThrows404() {
        when(profiles.findByOwnerId(9L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.getMyProfile(9L)).isInstanceOf(AppException.class);
    }

    @Test
    void blankOptionalFieldsNormalizedToNull() {
        when(profiles.existsByOwnerId(1L)).thenReturn(false);
        when(profiles.save(any(PetProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        PetProfileResponse resp = service.create(1L,
                new PetProfileCreateRequest("  ", "Momo", "  ", null, "  "));
        assertThat(resp.breed()).isNull();
        assertThat(resp.intro()).isNull();
        assertThat(resp.avatarUrl()).isNull();
    }
}
