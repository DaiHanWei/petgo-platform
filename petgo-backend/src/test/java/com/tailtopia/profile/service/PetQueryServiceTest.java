package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 multi-pet Story 3.1 · AC2.3 · L0：{@code PetQueryService} 薄封装（含只读、不校验归属）。 */
class PetQueryServiceTest {

    private final PetResolver resolver = mock(PetResolver.class);
    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetQueryService service = new PetQueryService(resolver, profiles);

    @Test
    void listByOwnerKeepsCreationOrderAndIncludesReadOnly() {
        var a = PetFixtures.pet(7L, 1L, PetType.CAT, Instant.parse("2026-01-01T00:00:00Z"));
        var b = PetFixtures.pet(7L, 2L, PetType.DOG, Instant.parse("2026-02-01T00:00:00Z"));
        ReflectionTestUtils.setField(b, "status", PetStatus.READ_ONLY);
        when(resolver.listOwned(7L)).thenReturn(List.of(a, b));

        var views = service.listByOwner(7L);

        assertThat(views).extracting(v -> v.id()).containsExactly(1L, 2L);
        assertThat(views.get(1).status()).isEqualTo(PetStatus.READ_ONLY);
        assertThat(views.get(1).petType()).isEqualTo(PetType.DOG);
        assertThat(views.get(0).ownerId()).isEqualTo(7L);
        assertThat(views.get(0).createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void getDoesNotCheckOwnershipAndEmptyWhenMissing() {
        when(profiles.findById(1L)).thenReturn(Optional.of(PetFixtures.pet(99L, 1L, Instant.EPOCH)));
        when(profiles.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.get(1L)).get().extracting(v -> v.ownerId()).isEqualTo(99L);
        assertThat(service.get(2L)).isEmpty();
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getAllIsOneBatchQueryAndSkipsMissing() {
        when(profiles.findAllById(java.util.Set.of(1L, 2L)))
                .thenReturn(List.of(PetFixtures.pet(7L, 1L, Instant.EPOCH)));

        var out = service.getAll(java.util.Arrays.asList(1L, 2L, null));

        assertThat(out).containsOnlyKeys(1L);
        assertThat(service.getAll(List.of())).isEmpty();
    }
}
