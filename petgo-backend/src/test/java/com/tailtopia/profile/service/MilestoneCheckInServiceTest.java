package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.MilestoneCheckinCandidateResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 8.4）：打卡候选 linked 标记 + 打卡成功 + 各类拒绝（非打卡类/已完成/非本人/已关联）。 */
class MilestoneCheckInServiceTest {

    private PetProfileRepository profiles;
    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private MilestoneCompletionService completionService;
    private ContentService contentService;
    private MilestoneCheckInService service;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        milestones = Mockito.mock(PetMilestoneRepository.class);
        completions = Mockito.mock(MilestoneCompletionRepository.class);
        completionService = Mockito.mock(MilestoneCompletionService.class);
        contentService = Mockito.mock(ContentService.class);
        service = new MilestoneCheckInService(profiles, milestones, completions,
                completionService, contentService);
        when(profiles.findByIdAndOwnerId(10L, 7L)).thenReturn(Optional.of(profile()));
    }

    private PetProfile profile() {
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Momo", null, null, null, null, "TOK");
        setField(p, "id", 10L);
        return p;
    }

    private PetMilestone milestone(String code, long id) {
        PetMilestone m = PetMilestone.of(10L, MilestoneCatalog.byCode(code));
        setField(m, "id", id);
        return m;
    }

    private GrowthMomentView moment(long id) {
        return new GrowthMomentView(id, Instant.now(), LocalDate.of(2026, 5, 1),
                List.of("img" + id), "text" + id,
                com.tailtopia.content.domain.ContentVisibility.PUBLIC,
                com.tailtopia.content.domain.PostStatus.PUBLISHED);
    }

    @Test
    void candidatesMarkLinkedContents() {
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(10L))
                .thenReturn(List.of(milestone("C-S6", 1)));
        when(completions.findByPetMilestoneIdIn(List.of(1L)))
                .thenReturn(List.of(MilestoneCompletion.of(1L, MilestoneCompletionSource.USER_CHECKIN, 2L)));
        when(contentService.findRecentGrowthMomentsByEventDate(7L, 10L, 100))
                .thenReturn(List.of(moment(2), moment(3)));

        MilestoneCheckinCandidateResponse.Page page = service.candidates(7L, 10L);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).contentId()).isEqualTo(2L);
        assertThat(page.items().get(0).linked()).isTrue(); // 已关联 → 置灰
        assertThat(page.items().get(1).linked()).isFalse();
    }

    @Test
    void checkInSucceedsForCheckinMilestone() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(false);
        when(contentService.isOwnGrowthMomentOfPet(7L, 10L, 99L)).thenReturn(true);
        when(completions.existsByLinkedContentId(99L)).thenReturn(false);
        when(completionService.completeCodeForPet(
                10L, "C-S6", MilestoneCompletionSource.USER_CHECKIN, 99L))
                .thenReturn(true);
        when(completions.findByPetMilestoneId(5L)).thenReturn(
                Optional.of(MilestoneCompletion.of(5L, MilestoneCompletionSource.USER_CHECKIN, 99L)));

        MilestoneItemResponse resp = service.checkIn(7L, 10L, "C-S6", 99L);

        assertThat(resp.code()).isEqualTo("C-S6");
        assertThat(resp.completed()).isTrue();
    }

    @Test
    void checkInRejectsNonCheckinMilestone() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S1"))
                .thenReturn(Optional.of(milestone("C-S1", 6))); // SYSTEM_AUTO
        assertThatThrownBy(() -> service.checkIn(7L, 10L, "C-S1", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("非用户打卡");
    }

    @Test
    void checkInRejectsAlreadyCompleted() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(true);
        assertThatThrownBy(() -> service.checkIn(7L, 10L, "C-S6", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("已完成");
    }

    @Test
    void checkInRejectsForeignContent() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(false);
        when(contentService.isOwnGrowthMomentOfPet(7L, 10L, 99L)).thenReturn(false);
        assertThatThrownBy(() -> service.checkIn(7L, 10L, "C-S6", 99L))
                .isInstanceOf(AppException.class)
                // V1.4.0 multi-pet（Story 1.3 · AC5）：文案按 story 改为「只能关联该宠物的成长日历内容」。
                .hasMessageContaining("该宠物的成长日历");
    }

    @Test
    void checkInRejectsAlreadyLinkedContent() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(false);
        when(contentService.isOwnGrowthMomentOfPet(7L, 10L, 99L)).thenReturn(true);
        when(completions.existsByLinkedContentId(99L)).thenReturn(true);
        assertThatThrownBy(() -> service.checkIn(7L, 10L, "C-S6", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("已关联其它里程碑");
    }

    /** V1.4.0 multi-pet（Story 1.2 · P7 / P10）：petId=B 打卡只查 B 的 roster、只完成 B；不属于本人 → 404。 */
    @Test
    void checkInOnSecondPetCompletesOnlyThatPet() {
        PetProfile b = PetProfile.create(7L, PetType.DOG, "Bobo", null, null, null, null, "TOKB");
        setField(b, "id", 11L);
        when(profiles.findByIdAndOwnerId(11L, 7L)).thenReturn(Optional.of(b));
        PetMilestone m = PetMilestone.of(11L, MilestoneCatalog.byCode("D-S6"));
        setField(m, "id", 8L);
        when(milestones.findByPetProfileIdAndCode(11L, "D-S6")).thenReturn(Optional.of(m));
        when(completions.existsByPetMilestoneId(8L)).thenReturn(false);
        when(contentService.isOwnGrowthMomentOfPet(7L, 11L, 99L)).thenReturn(true);
        when(completions.existsByLinkedContentId(99L)).thenReturn(false);
        when(completionService.completeCodeForPet(11L, "D-S6", MilestoneCompletionSource.USER_CHECKIN, 99L))
                .thenReturn(true);

        service.checkIn(7L, 11L, "D-S6", 99L);

        org.mockito.Mockito.verify(completionService).completeCodeForPet(
                11L, "D-S6", MilestoneCompletionSource.USER_CHECKIN, 99L);
        org.mockito.Mockito.verify(milestones, org.mockito.Mockito.never()).findByPetProfileIdAndCode(10L, "D-S6");

        when(profiles.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkIn(7L, 99L, "C-S6", 99L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.candidates(7L, 99L)).isInstanceOf(AppException.class);
    }

    /** V1.4.0 multi-pet Story 1.3 · AC5：帖子只关联 A，用 B 打卡 → 422；用 A → 成功。 */
    @Test
    void checkInRejectsPostOfAnotherPet() {
        PetProfile b = PetProfile.create(7L, PetType.CAT, "Bobo", null, null, null, null, "TOKB");
        setField(b, "id", 11L);
        when(profiles.findByIdAndOwnerId(11L, 7L)).thenReturn(Optional.of(b));
        PetMilestone mb = PetMilestone.of(11L, MilestoneCatalog.byCode("C-S6"));
        setField(mb, "id", 8L);
        when(milestones.findByPetProfileIdAndCode(11L, "C-S6")).thenReturn(Optional.of(mb));
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6")).thenReturn(Optional.of(milestone("C-S6", 5)));
        when(contentService.isOwnGrowthMomentOfPet(7L, 10L, 99L)).thenReturn(true);
        when(contentService.isOwnGrowthMomentOfPet(7L, 11L, 99L)).thenReturn(false);
        when(completionService.completeCodeForPet(10L, "C-S6", MilestoneCompletionSource.USER_CHECKIN, 99L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.checkIn(7L, 11L, "C-S6", 99L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422))
                .hasMessageContaining("该宠物的成长日历");
        org.mockito.Mockito.verify(completionService, org.mockito.Mockito.never())
                .completeCodeForPet(org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThat(service.checkIn(7L, 10L, "C-S6", 99L).completed()).isTrue();
    }

    private static void setField(Object o, String name, Object value) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** V1.4.0 multi-pet 1.2 复审：无档案 + 健康类里程碑仍先 422（健康拒绝在宠物解析之前），不是 404。 */
    @Test
    void healthMilestoneWithoutProfileStill422() {
        org.mockito.Mockito.when(profiles.findDefault(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.checkIn(8L, null, "C-M3", 99L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void nullPetIdWithoutProfile404() {
        org.mockito.Mockito.when(profiles.findDefault(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.checkIn(8L, null, "C-S6", 99L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
