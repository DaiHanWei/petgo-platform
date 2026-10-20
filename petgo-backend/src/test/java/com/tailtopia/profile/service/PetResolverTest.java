package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（V1.4.0 multi-pet AC1.4）：本人 petId / 他人 petId / 不存在 petId / 缺省默认宠物 / 无宠物。 */
class PetResolverTest {

    private static final long OWNER = 1L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetResolver resolver = new PetResolver(profiles);

    @BeforeEach
    void setUp() {
        // findDefault 是仓储接口的 default 方法：让 mock 走真实现，才能验「默认 = 列表第一只」。
        when(profiles.findDefault(Mockito.anyLong())).thenCallRealMethod();
    }

    @Test
    void ownedPetIdResolvesToThatPet() {
        PetProfile b = pet(OWNER, 11L, T0.plusSeconds(60));
        when(profiles.findByIdAndOwnerId(11L, OWNER)).thenReturn(Optional.of(b));
        assertThat(resolver.resolve(OWNER, 11L)).isSameAs(b);
    }

    @Test
    void othersPetIdAndMissingPetIdGiveIdentical404() {
        // 他人宠物：findByIdAndOwnerId 查不到；不存在：同样查不到 —— 两者不可区分。
        when(profiles.findByIdAndOwnerId(99L, OWNER)).thenReturn(Optional.empty());
        when(profiles.findByIdAndOwnerId(12345L, OWNER)).thenReturn(Optional.empty());
        AppException others = catchApp(() -> resolver.resolve(OWNER, 99L));
        AppException missing = catchApp(() -> resolver.resolve(OWNER, 12345L));
        assertThat(others.getStatus()).isEqualTo(missing.getStatus());
        assertThat(others.getStatus().value()).isEqualTo(404);
        assertThat(others.getType()).isEqualTo(missing.getType());
        assertThat(others.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    void nullPetIdPicksEarliestCreated() {
        PetProfile a = pet(OWNER, 10L, T0);
        PetProfile b = pet(OWNER, 11L, T0.plusSeconds(60));
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b));
        assertThat(resolver.resolve(OWNER, null)).isSameAs(a);
        assertThat(resolver.resolveOptional(OWNER, null)).containsSame(a);
    }

    @Test
    void nullPetIdSameCreatedAtPicksSmallerId() {
        // 仓储按 created_at, id 升序返回：同刻时 id 小的在前。
        PetProfile a = pet(OWNER, 10L, T0);
        PetProfile b = pet(OWNER, 11L, T0);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b));
        assertThat(resolver.resolve(OWNER, null).getId()).isEqualTo(10L);
    }

    @Test
    void nullPetIdWithoutPets404ButOptionalEmpty() {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of());
        AppException e = catchApp(() -> resolver.resolve(OWNER, null));
        assertThat(e.getStatus().value()).isEqualTo(404);
        assertThat(e.getMessage()).isEqualTo("尚未创建宠物档案");
        assertThat(resolver.resolveOptional(OWNER, null)).isEmpty();
    }

    @Test
    void resolveOptionalStill404sOnForeignPetId() {
        when(profiles.findByIdAndOwnerId(99L, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolveOptional(OWNER, 99L)).isInstanceOf(AppException.class);
    }

    @Test
    void listOwnedDelegatesOrderedQuery() {
        PetProfile a = pet(OWNER, 10L, T0);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a));
        assertThat(resolver.listOwned(OWNER)).containsExactly(a);
    }

    private static AppException catchApp(Runnable r) {
        try {
            r.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected AppException");
    }
}
