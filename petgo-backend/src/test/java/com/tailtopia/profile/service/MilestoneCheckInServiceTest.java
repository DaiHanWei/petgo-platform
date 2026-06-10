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
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile()));
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
                List.of("img" + id), "text" + id);
    }

    @Test
    void candidatesMarkLinkedContents() {
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(10L))
                .thenReturn(List.of(milestone("C-S6", 1)));
        when(completions.findByPetMilestoneIdIn(List.of(1L)))
                .thenReturn(List.of(MilestoneCompletion.of(1L, MilestoneCompletionSource.USER_CHECKIN, 2L)));
        when(contentService.findRecentGrowthMomentsByEventDate(7L, 100))
                .thenReturn(List.of(moment(2), moment(3)));

        MilestoneCheckinCandidateResponse.Page page = service.candidates(7L);

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
        when(contentService.isOwnGrowthMoment(7L, 99L)).thenReturn(true);
        when(completions.existsByLinkedContentId(99L)).thenReturn(false);
        when(completionService.completeForOwner(7L, "S6", MilestoneCompletionSource.USER_CHECKIN, 99L))
                .thenReturn(true);
        when(completions.findByPetMilestoneId(5L)).thenReturn(
                Optional.of(MilestoneCompletion.of(5L, MilestoneCompletionSource.USER_CHECKIN, 99L)));

        MilestoneItemResponse resp = service.checkIn(7L, "C-S6", 99L);

        assertThat(resp.code()).isEqualTo("C-S6");
        assertThat(resp.completed()).isTrue();
    }

    @Test
    void checkInRejectsNonCheckinMilestone() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S1"))
                .thenReturn(Optional.of(milestone("C-S1", 6))); // SYSTEM_AUTO
        assertThatThrownBy(() -> service.checkIn(7L, "C-S1", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("非用户打卡");
    }

    @Test
    void checkInRejectsAlreadyCompleted() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(true);
        assertThatThrownBy(() -> service.checkIn(7L, "C-S6", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("已完成");
    }

    @Test
    void checkInRejectsForeignContent() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(false);
        when(contentService.isOwnGrowthMoment(7L, 99L)).thenReturn(false);
        assertThatThrownBy(() -> service.checkIn(7L, "C-S6", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("本人成长日历");
    }

    @Test
    void checkInRejectsAlreadyLinkedContent() {
        when(milestones.findByPetProfileIdAndCode(10L, "C-S6"))
                .thenReturn(Optional.of(milestone("C-S6", 5)));
        when(completions.existsByPetMilestoneId(5L)).thenReturn(false);
        when(contentService.isOwnGrowthMoment(7L, 99L)).thenReturn(true);
        when(completions.existsByLinkedContentId(99L)).thenReturn(true);
        assertThatThrownBy(() -> service.checkIn(7L, "C-S6", 99L))
                .isInstanceOf(AppException.class).hasMessageContaining("已关联其它里程碑");
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
}
