package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.tailsonality.service.TailsonalityBadgeQuery;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.3 · AC4.2 / AC4.3 / AC4.4（L0）：公开宠物卡带 4 字母小标；三个 204 分支不变；访客视图不带。 */
class PublicProfilePetBadgeTest {

    private static final long OWNER = 9L;

    private final ProfileService profiles = mock(ProfileService.class);
    private final VisitorProjectionService visitors = mock(VisitorProjectionService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final UserHideRelationReader hide = mock(UserHideRelationReader.class);
    private final TailsonalityBadgeQuery badges = mock(TailsonalityBadgeQuery.class);
    private PublicProfilePetController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicProfilePetController(profiles, visitors, accounts, hide, badges);
        PetProfile p = PetProfile.create(OWNER, PetType.CAT, "Miu", null, null, null, null, "tok-secret");
        ReflectionTestUtils.setField(p, "id", 42L);
        when(profiles.findByOwnerId(OWNER)).thenReturn(Optional.of(p));
        when(accounts.isActive(OWNER)).thenReturn(true);
    }

    @Test
    void equippedUnlockedBadgeIsFourLetters() {
        when(badges.badgeOf(42L)).thenReturn(Optional.of("ENTJ"));
        assertThat(controller.pet(null, OWNER).getBody().tailsonalityBadge()).isEqualTo("ENTJ");
    }

    @Test
    void noBadgeIsNull() {
        when(badges.badgeOf(42L)).thenReturn(Optional.empty());
        assertThat(controller.pet(null, OWNER).getBody().tailsonalityBadge()).isNull();
    }

    @Test
    void inactiveOwnerStill204AndBadgeNeverQueried() {
        when(accounts.isActive(OWNER)).thenReturn(false);
        assertThat(controller.pet(null, OWNER).getStatusCode().value()).isEqualTo(204);
        verify(badges, never()).badgeOf(org.mockito.ArgumentMatchers.anyLong());
        verify(profiles, never()).findByOwnerId(any(Long.class));
    }

    /** AC4.4：点进去的访客视图不加该字段（AD-3 只点名两处）。 */
    @Test
    void visitorProfileHasNoBadgeComponent() {
        assertThat(Arrays.stream(VisitorProfileResponse.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase(java.util.Locale.ROOT)))
                .noneMatch(n -> n.contains("tailsonality") || n.contains("badge"));
    }
}
