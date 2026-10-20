package com.tailtopia.admin.virtual.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.repository.SeedRealAccountGrantRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X31）：发布身份「物种」列按账号全部宠物物种去重——
 * 0 只 → 推不出；1 种 → 该物种；≥ 2 种 → GENERAL「宠物档案（多物种）」。
 */
class AdminPublishIdentityServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER = 42L;

    private PetProfileRepository pets;
    private AdminPublishIdentityService service;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        SeedRealAccountGrantRepository grants = mock(SeedRealAccountGrantRepository.class);
        pets = mock(PetProfileRepository.class);
        service = new AdminPublishIdentityService(users, grants, mock(SeedContentHashRepository.class), pets,
                mock(PendingPublishScheduleCounter.class), mock(AdminAuditService.class));
        SeedRealAccountGrant g = mock(SeedRealAccountGrant.class);
        when(g.getUserId()).thenReturn(USER);
        when(grants.findByStatusOrderByIdDesc(SeedRealAccountGrant.Status.ACTIVE)).thenReturn(List.of(g));
        User u = mock(User.class);
        when(u.getId()).thenReturn(USER);
        when(users.findById(USER)).thenReturn(Optional.of(u));
    }

    private void petsAre(PetProfile... list) {
        when(pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(USER)).thenReturn(List.of(list));
    }

    @Test
    void noPetCannotDerive() {
        petsAre();
        var row = service.listRealAccounts().get(0);
        assertThat(row.speciesGuess()).isEqualTo("—");
        assertThat(row.speciesSource()).isEqualTo("NONE");
    }

    @Test
    void sameSpeciesPetsYieldThatSpecies() {
        petsAre(pet(USER, 1L, PetType.CAT, T0), pet(USER, 2L, PetType.CAT, T0.plusSeconds(1)));
        var row = service.listRealAccounts().get(0);
        assertThat(row.speciesGuess()).isEqualTo("CAT");
        assertThat(row.speciesSource()).isEqualTo("PROFILE");
    }

    @Test
    void multipleSpeciesYieldGeneral() {
        petsAre(pet(USER, 1L, PetType.CAT, T0), pet(USER, 2L, PetType.DOG, T0.plusSeconds(1)));
        var row = service.listRealAccounts().get(0);
        assertThat(row.speciesGuess()).isEqualTo("GENERAL");
        assertThat(row.speciesSource()).isEqualTo("PROFILE_MULTI");
    }
}
