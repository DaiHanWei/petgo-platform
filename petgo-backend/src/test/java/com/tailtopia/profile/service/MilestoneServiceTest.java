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
    private final AccountMilestoneService accountMilestones = mock(AccountMilestoneService.class);
    private final MilestoneService service =
            new MilestoneService(petResolver, milestones, completions, accountMilestones);

    /** V1.4.2 Story 2.1 T6.1：真正物化 roster 后调账号级钩子。 */
    @Test
    void assignRosterAppliesAccountFlagsAfterMaterialising() {
        when(milestones.existsByPetProfileId(10L)).thenReturn(false);

        service.assignRoster(10L, com.tailtopia.profile.domain.PetType.CAT);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(milestones, accountMilestones);
        order.verify(milestones).saveAll(org.mockito.ArgumentMatchers.anyList());
        order.verify(accountMilestones).applyExistingFlagsToPet(10L);
    }

    /** V1.4.2 Story 2.1 T6.1：已有 roster 的短路分支不调钩子（存量宠物走触发 / reconcile 路径）。 */
    @Test
    void assignRosterShortCircuitDoesNotApplyAccountFlags() {
        when(milestones.existsByPetProfileId(10L)).thenReturn(true);

        service.assignRoster(10L, com.tailtopia.profile.domain.PetType.CAT);

        verify(accountMilestones, never()).applyExistingFlagsToPet(anyLong());
    }

    /** V1.4.2 Story 2.1 T6.1：并发兜底（唯一约束冲突）分支不调钩子 —— 赢的那个事务已调过。 */
    @Test
    void assignRosterConcurrentLoserDoesNotApplyAccountFlags() {
        when(milestones.existsByPetProfileId(10L)).thenReturn(false);
        when(milestones.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        service.assignRoster(10L, com.tailtopia.profile.domain.PetType.CAT);

        verify(accountMilestones, never()).applyExistingFlagsToPet(anyLong());
    }

    /** V1.4.2 Story 2.1 AC1.5：分母随清单 —— 猫 / 狗 35、其他 20。 */
    @Test
    void progressDenominatorFollowsCatalog() {
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThat(
                service.getProgress(10L, com.tailtopia.profile.domain.PetType.CAT).total()).isEqualTo(35);
        org.assertj.core.api.Assertions.assertThat(
                service.getProgress(10L, com.tailtopia.profile.domain.PetType.DOG).total()).isEqualTo(35);
        org.assertj.core.api.Assertions.assertThat(
                service.getProgress(10L, com.tailtopia.profile.domain.PetType.OTHER).total()).isEqualTo(20);
    }

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
