package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.4.0 multi-pet AC5）：{@code getMilestones(owner, B)} 只读 B 的 roster；缺省 = 默认宠物 A。 */
class MilestoneServiceTest {

    private static final long OWNER = 1L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final PetResolver petResolver = mock(PetResolver.class);
    private final PetMilestoneRepository milestones = mock(PetMilestoneRepository.class);
    private final MilestoneCompletionRepository completions = mock(MilestoneCompletionRepository.class);
    private final MilestoneService service = new MilestoneService(petResolver, milestones, completions);

    @Test
    void petIdBReadsOnlyBsRoster() {
        when(petResolver.resolve(OWNER, 11L)).thenReturn(pet(OWNER, 11L, T0.plusSeconds(60)));
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());

        service.getMilestones(OWNER, 11L);

        verify(milestones, org.mockito.Mockito.atLeastOnce()).findByPetProfileIdOrderBySortOrderAsc(11L);
        verify(milestones, never()).findByPetProfileIdOrderBySortOrderAsc(10L);
    }

    @Test
    void legacySignatureFallsBackToDefaultPet() {
        when(petResolver.resolve(OWNER, null)).thenReturn(pet(OWNER, 10L, T0));
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());

        service.getMilestones(OWNER);

        verify(milestones, org.mockito.Mockito.atLeastOnce()).findByPetProfileIdOrderBySortOrderAsc(10L);
    }

    @Test
    void foreignPetId404() {
        when(petResolver.resolve(OWNER, 99L)).thenThrow(AppException.notFound("宠物档案不存在"));
        assertThatThrownBy(() -> service.getMilestones(OWNER, 99L)).isInstanceOf(AppException.class);
    }
}
