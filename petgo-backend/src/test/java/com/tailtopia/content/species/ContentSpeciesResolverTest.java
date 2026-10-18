package com.tailtopia.content.species;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X27，V-1）：作者有两只不同物种的宠物 → 作者物种 = 默认（最早建）宠物物种，
 * 与仓储返回顺序无关、不抛异常；单宠作者结果不变。
 */
class ContentSpeciesResolverTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private UserRepository users;
    private PetProfileRepository pets;
    private ContentSpeciesResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        pets = mock(PetProfileRepository.class);
        resolver = new ContentSpeciesResolver(users, pets);
        User multi = realUser(1L);
        User single = realUser(2L);
        when(users.findAllById(anyCollection())).thenReturn(List.of(multi, single));
    }

    private static User realUser(long id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getAccountType()).thenReturn(AccountType.REAL);
        return u;
    }

    @Test
    void multiPetAuthorUsesDefaultPetSpecies() {
        // 已按 created_at, id 升序：作者 1 先建 DOG 再建 CAT；作者 2 只有 CAT。
        when(pets.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(anyCollection())).thenReturn(List.of(
                pet(1L, 10L, PetType.DOG, T0), pet(2L, 20L, PetType.CAT, T0.plusSeconds(10)),
                pet(1L, 11L, PetType.CAT, T0.plusSeconds(60))));

        var out = resolver.resolveAll(List.of(
                new ContentSpeciesResolver.Input(100L, null, 1L),
                new ContentSpeciesResolver.Input(200L, null, 2L)));

        assertThat(out.get(100L)).isEqualTo(new ResolvedSpecies("DOG", SpeciesSource.PET_PROFILE));
        assertThat(out.get(200L)).isEqualTo(new ResolvedSpecies("CAT", SpeciesSource.PET_PROFILE));
    }

    @Test
    void authorWithoutPetIsNone() {
        when(pets.findAllByOwnerIdInOrderByCreatedAtAscIdAsc(anyCollection())).thenReturn(List.of());

        var out = resolver.resolveAll(List.of(new ContentSpeciesResolver.Input(100L, null, 1L)));

        assertThat(out.get(100L)).isEqualTo(ResolvedSpecies.NONE);
    }
}
