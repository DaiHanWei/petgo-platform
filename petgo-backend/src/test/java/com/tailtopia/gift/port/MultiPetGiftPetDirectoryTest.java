package com.tailtopia.gift.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.PetView;
import com.tailtopia.profile.service.PetQueryService;
import com.tailtopia.profile.service.ProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 gift Story 4.1 · AC1.1 · L0：多宠实现的映射、顺序与过滤（mock ② 读口与账号读口）。 */
class MultiPetGiftPetDirectoryTest {

    private PetQueryService petQuery;
    private ProfileService profiles;
    private ContentPetQuery contentPets;
    private AccountQueryService accounts;
    private NamedParameterJdbcTemplate jdbc;
    private MultiPetGiftPetDirectory dir;

    @BeforeEach
    void setUp() {
        petQuery = mock(PetQueryService.class);
        profiles = mock(ProfileService.class);
        contentPets = mock(ContentPetQuery.class);
        accounts = mock(AccountQueryService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        dir = new MultiPetGiftPetDirectory(petQuery, profiles, contentPets, accounts, jdbc);
    }

    private static PetView view(long id, long owner, PetType type, PetStatus status) {
        return new PetView(id, owner, "pet" + id, "a" + id, type, status, Instant.EPOCH);
    }

    private static User user(UserStatus status, Instant deletedAt) {
        User u = mock(User.class);
        when(u.getStatus()).thenReturn(status);
        when(u.getDeletedAt()).thenReturn(deletedAt);
        return u;
    }

    private void breed(long petId, String breed) {
        PetProfile p = PetProfile.create(1L, PetType.CAT, "x", null, breed, null, null, "t" + petId);
        when(profiles.findById(petId)).thenReturn(Optional.of(p));
    }

    private void giftable(long postId, boolean ok) {
        when(jdbc.queryForObject(anyString(), eq(Map.of("postId", postId)), eq(Boolean.class))).thenReturn(ok);
    }

    @Test
    void petMapsAllEightFieldsAndOwnerFlags() {
        when(petQuery.get(10L)).thenReturn(Optional.of(view(10L, 7L, PetType.CAT, PetStatus.ACTIVE)));
        User owner = user(UserStatus.ACTIVE, null);
        when(accounts.findAccountById(7L)).thenReturn(Optional.of(owner));
        breed(10L, "Persia");

        assertThat(dir.pet(10L)).contains(new GiftPet(10L, 7L, "pet10", "a10", "CAT", "Persia", true, false));
    }

    @Test
    void bannedOwnerIsInactiveButNotDeleted_deletedOwnerIsBoth() {
        when(petQuery.get(10L)).thenReturn(Optional.of(view(10L, 7L, PetType.DOG, PetStatus.ACTIVE)));
        User banned = user(UserStatus.DEACTIVATED, null);
        when(accounts.findAccountById(7L)).thenReturn(Optional.of(banned));
        assertThat(dir.pet(10L)).get().satisfies(p -> {
            assertThat(p.ownerActive()).isFalse();
            assertThat(p.ownerDeleted()).isFalse();
            assertThat(p.breed()).isNull();
        });

        User gone = user(UserStatus.ACTIVE, Instant.now());
        when(accounts.findAccountById(7L)).thenReturn(Optional.of(gone));
        assertThat(dir.pet(10L)).get().satisfies(p -> {
            assertThat(p.ownerActive()).isFalse();
            assertThat(p.ownerDeleted()).isTrue();
        });
    }

    @Test
    void readOnlyPetIsStillReturned_missingPetOrOwnerIsEmpty() {
        when(petQuery.get(10L)).thenReturn(Optional.of(view(10L, 7L, PetType.CAT, PetStatus.READ_ONLY)));
        User owner = user(UserStatus.ACTIVE, null);
        when(accounts.findAccountById(7L)).thenReturn(Optional.of(owner));
        assertThat(dir.pet(10L)).isPresent();

        when(petQuery.get(11L)).thenReturn(Optional.empty());
        assertThat(dir.pet(11L)).isEmpty();

        when(petQuery.get(12L)).thenReturn(Optional.of(view(12L, 8L, PetType.CAT, PetStatus.ACTIVE)));
        when(accounts.findAccountById(8L)).thenReturn(Optional.empty());
        assertThat(dir.pet(12L)).isEmpty();
    }

    @Test
    void petsOfPostKeepsPetsOfOrderAndDropsMissing() {
        giftable(5L, true);
        when(contentPets.petsOf(5L)).thenReturn(List.of(30L, 20L, 99L));
        when(petQuery.getAll(List.of(30L, 20L, 99L))).thenReturn(Map.of(
                20L, view(20L, 7L, PetType.DOG, PetStatus.ACTIVE),
                30L, view(30L, 7L, PetType.CAT, PetStatus.READ_ONLY)));
        User owner = user(UserStatus.ACTIVE, null);
        when(accounts.findAccountById(7L)).thenReturn(Optional.of(owner));

        assertThat(dir.petsOfPost(5L)).extracting(GiftPet::petId).containsExactly(30L, 20L);
        verify(accounts).findAccountById(7L); // 同一主人只取一次
    }

    @Test
    void notGiftablePostHasNoPetsAndDoesNotTouchPetReadPorts() {
        giftable(5L, false);
        assertThat(dir.petsOfPost(5L)).isEmpty();
        verify(contentPets, never()).petsOf(5L);
        verify(petQuery, never()).getAll(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void giftablePostWithoutLinkedPetsIsEmpty() {
        giftable(6L, true);
        when(contentPets.petsOf(6L)).thenReturn(List.of());
        assertThat(dir.petsOfPost(6L)).isEmpty();
    }

    @Test
    void giftablePostSqlKeepsTheOneOneVisibilityFilterAndReadsNoPetColumn() {
        assertThat(MultiPetGiftPetDirectory.GIFTABLE_POST_SQL)
                .contains("type = 'GROWTH_MOMENT'", "deleted_at IS NULL", "status = 'PUBLISHED'",
                        "visibility = 'PUBLIC'", "report_hidden_at IS NULL")
                .doesNotContain("pet_id");
    }
}
