package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.3 · AC2 / AC4（L0）：切换 404 / 422 / upsert、卸下幂等、小标只读口。 */
class TailsonalityBadgeServiceTest {

    private final TailsonalityBadgeRepository badges = mock(TailsonalityBadgeRepository.class);
    private final TailsonalityResultRepository results = mock(TailsonalityResultRepository.class);
    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final TailsonalityBadgeService service = new TailsonalityBadgeService(badges, results, pets);

    private TailsonalityResult row(Instant unlockedAt) {
        TailsonalityResult r = TailsonalityResult.create("tok", 3L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 42L);
        ReflectionTestUtils.setField(r, "unlockedAt", unlockedAt);
        return r;
    }

    @Test
    void equipUnlockedUpserts() {
        when(pets.ownsPet(7L, 3L)).thenReturn(true);
        when(results.findByPublicToken("tok")).thenReturn(Optional.of(row(Instant.EPOCH)));
        service.equip(7L, "tok");
        verify(badges).upsert(3L, 42L);
    }

    @Test
    void equipLockedIs422WithDedicatedType() {
        when(pets.ownsPet(7L, 3L)).thenReturn(true);
        when(results.findByPublicToken("tok")).thenReturn(Optional.of(row(null)));
        assertThatThrownBy(() -> service.equip(7L, "tok")).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getType()).isEqualTo(ErrorTypes.TAILSONALITY_BADGE_LOCKED);
        });
        verify(badges, never()).upsert(anyLong(), anyLong());
    }

    @Test
    void equipUnknownOrForeignIs404() {
        when(pets.ownsPet(7L, 3L)).thenReturn(true);
        when(results.findByPublicToken("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.equip(7L, "x")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unequipIsIdempotentDelete() {
        when(pets.findOwnedPet(7L, null)).thenReturn(Optional.of(new OwnedPetRef(3L, PetType.CAT)));
        service.unequip(7L, null);
        service.unequip(7L, null);
        verify(badges, org.mockito.Mockito.times(2)).deleteByPetProfileId(3L);
    }

    // ===== V1.4.0 multi-pet Story 1.2 · X14 =====

    @Test
    void equipForeignTokenIs404() {
        // 结果行属于别人的宠物（3L 不属于 7L）→ 同一个 404，不佩戴。
        when(pets.ownsPet(7L, 3L)).thenReturn(false);
        when(results.findByPublicToken("tok")).thenReturn(Optional.of(row(Instant.EPOCH)));
        assertThatThrownBy(() -> service.equip(7L, "tok")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(badges, never()).upsert(anyLong(), anyLong());
    }

    @Test
    void equipTargetsThePetOnTheResultRowNotTheDefault() {
        // 结果属于第二只宠物 B（id 4）：佩戴到 B，不需要 petId，也不查默认宠物。
        TailsonalityResult r = TailsonalityResult.create("tokB", 4L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 43L);
        ReflectionTestUtils.setField(r, "unlockedAt", Instant.EPOCH);
        when(results.findByPublicToken("tokB")).thenReturn(Optional.of(r));
        when(pets.ownsPet(7L, 4L)).thenReturn(true);
        service.equip(7L, "tokB");
        verify(badges).upsert(4L, 43L);
        verify(pets, never()).findOwnedPet(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unequipSpecificPet() {
        when(pets.findOwnedPet(7L, 4L)).thenReturn(Optional.of(new OwnedPetRef(4L, PetType.DOG)));
        service.unequip(7L, 4L);
        verify(badges).deleteByPetProfileId(4L);
        when(pets.findOwnedPet(7L, 999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unequip(7L, 999L)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void badgeQueryReturnsFourLettersOnly() {
        when(badges.findEquippedUnlockedLetters(3L)).thenReturn(Optional.of("ENTJ"));
        when(badges.findEquippedUnlockedLetters(4L)).thenReturn(Optional.empty());
        TailsonalityBadgeQuery q = new TailsonalityBadgeQuery(badges);
        assertThat(q.badgeOf(3L)).contains("ENTJ");
        assertThat(q.badgeOf(4L)).isEmpty();
    }

    @Test
    void petDeletionRemovesBadgesBeforeResults() {
        TailsonalityResultRepository r = mock(TailsonalityResultRepository.class);
        var order = org.mockito.Mockito.inOrder(badges, r);
        new TailsonalityDeletionService(r, mock(com.tailtopia.tailsonality.repository.TailsonalityOwnerTypeRepository.class),
                badges).deleteForPet(3L);
        order.verify(badges).deleteByPetProfileId(3L);
        order.verify(r).deleteByPetProfileId(3L);
    }
}
