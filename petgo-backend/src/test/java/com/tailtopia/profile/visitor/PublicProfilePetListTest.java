package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.tailsonality.service.TailsonalityBadgeQuery;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.4.0 multi-pet Story 1.4 · AC5 他人主页宠物列表（四种守卫出口 / 顺序 / 不含状态）。 */
class PublicProfilePetListTest {

    private static final long OWNER = 9L;
    private static final long VIEWER = 3L;

    private final ProfileService profiles = mock(ProfileService.class);
    private final VisitorProjectionService visitors = mock(VisitorProjectionService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final UserHideRelationReader hide = mock(UserHideRelationReader.class);
    private final TailsonalityBadgeQuery badges = mock(TailsonalityBadgeQuery.class);
    private PublicProfilePetController controller;
    private PetProfile a;
    private PetProfile b;

    private static PetProfile pet(long id, String name) {
        PetProfile p = PetProfile.create(OWNER, PetType.CAT, name, null, null, null, null, "tok-" + id);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static Jwt userJwt(long id) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(id)).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @BeforeEach
    void setUp() {
        controller = new PublicProfilePetController(profiles, visitors, accounts, hide, badges);
        a = pet(42L, "Miu");
        b = pet(43L, "Bobo");
        when(profiles.listOwned(OWNER)).thenReturn(List.of(a, b)); // 已按 created_at, id 升序
        when(accounts.isActive(OWNER)).thenReturn(true);
        when(visitors.diaryCount(a)).thenReturn(3L);
        when(visitors.diaryCount(b)).thenReturn(0L);
        when(badges.badgeOf(42L)).thenReturn(Optional.of("ENTJ"));
        when(badges.badgeOf(43L)).thenReturn(Optional.empty());
    }

    @Test
    void guestSeesAllPetsInCreationOrderWithSameProjection() {
        List<PublicProfilePetResponse> list = controller.pets(null, OWNER);
        assertThat(list).extracting(PublicProfilePetResponse::petId).containsExactly(42L, 43L);
        assertThat(list.get(0).diaryCount()).isEqualTo(3L);
        assertThat(list.get(0).tailsonalityBadge()).isEqualTo("ENTJ");
        assertThat(list.get(1).tailsonalityBadge()).isNull();
    }

    @Test
    void iBlockedThemIsBlockedUser() {
        when(hide.isBlocked(VIEWER, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> controller.pets(userJwt(VIEWER), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.BLOCKED_USER));
    }

    @Test
    void theyBlockedMeIsEmptyList() {
        when(hide.isBlocked(OWNER, VIEWER)).thenReturn(true);
        assertThat(controller.pets(userJwt(VIEWER), OWNER)).isEmpty();
        verify(profiles, never()).listOwned(anyLong());
    }

    @Test
    void inactiveOwnerIsEmptyList() {
        when(accounts.isActive(OWNER)).thenReturn(false);
        assertThat(controller.pets(null, OWNER)).isEmpty();
        verify(profiles, never()).listOwned(anyLong());
        verify(badges, never()).badgeOf(anyLong());
    }

    @Test
    void noPetsIsEmptyList() {
        when(profiles.listOwned(OWNER)).thenReturn(List.of());
        assertThat(controller.pets(null, OWNER)).isEmpty();
    }

    @Test
    void vetTokenTreatedAsGuest() {
        Jwt vet = Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(VIEWER)).claim("role", "VET")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(controller.pets(vet, OWNER)).hasSize(2);
        verify(hide, never()).isBlocked(anyLong(), anyLong());
    }

    /** UI 稿 J1：只读是主人自己的状态，访客列表不下发宠物状态。 */
    @Test
    void responseHasNoStatusComponent() {
        assertThat(Arrays.stream(PublicProfilePetResponse.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase(Locale.ROOT)))
                .noneMatch(n -> n.contains("status") || n.contains("readonly"));
    }
}
