package com.petgo.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.profile.domain.MilestoneCatalog;
import com.petgo.profile.domain.MilestoneCompletion;
import com.petgo.profile.domain.MilestoneCompletionSource;
import com.petgo.profile.domain.PetMilestone;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.repository.MilestoneCompletionRepository;
import com.petgo.profile.repository.PetMilestoneRepository;
import com.petgo.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * L0（Story 8.3，FR-42）：自动完成幂等 + 前缀/后缀解析 + 计数阈值 + 健康组合依赖。纯 Mockito，无 DB。
 */
class MilestoneCompletionServiceTest {

    private PetProfileRepository profiles;
    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private MilestoneCompletionService service;

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        milestones = Mockito.mock(PetMilestoneRepository.class);
        completions = Mockito.mock(MilestoneCompletionRepository.class);
        service = new MilestoneCompletionService(profiles, milestones, completions,
                Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private PetProfile profile(PetType type, long id) {
        PetProfile p = PetProfile.create(7L, type, "Momo", null, null, null, null, "TOK");
        setField(p, "id", id);
        return p;
    }

    /** 注册一个 roster 行（按 code）并返回其 id；默认未完成。 */
    private long stubRoster(long petProfileId, String code) {
        long id = nextId++;
        PetMilestone m = PetMilestone.of(petProfileId, MilestoneCatalog.byCode(code));
        setField(m, "id", id);
        when(milestones.findByPetProfileIdAndCode(petProfileId, code)).thenReturn(Optional.of(m));
        when(completions.existsByPetMilestoneId(id)).thenReturn(false);
        return id;
    }

    @Test
    void completesByPrefixAndSuffix() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.CAT, 10)));
        long m = stubRoster(10, "C-S1");

        boolean done = service.completeForOwner(7L, "S1", MilestoneCompletionSource.SYSTEM_AUTO);

        assertThat(done).isTrue();
        ArgumentCaptor<MilestoneCompletion> cap = ArgumentCaptor.forClass(MilestoneCompletion.class);
        verify(completions).save(cap.capture());
        assertThat(cap.getValue().getPetMilestoneId()).isEqualTo(m);
    }

    @Test
    void idempotentWhenAlreadyCompleted() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.CAT, 10)));
        long m = stubRoster(10, "C-S1");
        when(completions.existsByPetMilestoneId(m)).thenReturn(true); // 已完成

        boolean done = service.completeForOwner(7L, "S1", MilestoneCompletionSource.SYSTEM_AUTO);

        assertThat(done).isFalse();
        verify(completions, never()).save(any());
    }

    @Test
    void noopWhenSuffixNotInThisPetCatalog() {
        // OTHER 清单无 S6（第一次洗澡）。
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.OTHER, 10)));
        when(milestones.findByPetProfileIdAndCode(10, "G-S6")).thenReturn(Optional.empty());

        boolean done = service.completeForOwner(7L, "S6", MilestoneCompletionSource.SYSTEM_AUTO);

        assertThat(done).isFalse();
        verify(completions, never()).save(any());
    }

    @Test
    void growthCountUnlocksS2AndM10AtTen_butNotL5() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.DOG, 10)));
        stubRoster(10, "D-S2");
        stubRoster(10, "D-M10");

        service.onGrowthMomentCount(7L, 10);

        verify(milestones).findByPetProfileIdAndCode(10, "D-S2");
        verify(milestones).findByPetProfileIdAndCode(10, "D-M10");
        verify(milestones, never()).findByPetProfileIdAndCode(10, "D-L5");
        verify(completions, times(2)).save(any());
    }

    @Test
    void growthCountUnlocksL5AtThirty() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.DOG, 10)));
        stubRoster(10, "D-S2");
        stubRoster(10, "D-M10");
        stubRoster(10, "D-L5");

        service.onGrowthMomentCount(7L, 30);

        verify(completions, times(3)).save(any()); // S2 + M10 + L5
    }

    @Test
    void dateGatedPublishCompletesBirthdayL1AndCompanionL2() {
        // 档案：生日今天（month/day 命中）+ 建档 120 天前（≥100 → L2，<365 → 不 L3）。
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Momo", null, null,
                java.time.LocalDate.of(2024, today.getMonthValue(), today.getDayOfMonth()), null, "TOK");
        setField(p, "id", 10L);
        setField(p, "createdAt", Instant.now().minus(java.time.Duration.ofDays(120)));
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));
        long l1 = stubRoster(10, "C-L1");
        long l2 = stubRoster(10, "C-L2");
        stubRoster(10, "C-L3");

        service.completeDateGatedLNodesOnPublish(7L);

        ArgumentCaptor<MilestoneCompletion> cap = ArgumentCaptor.forClass(MilestoneCompletion.class);
        verify(completions, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().stream().map(MilestoneCompletion::getPetMilestoneId))
                .containsExactlyInAnyOrder(l1, l2);
    }

    @Test
    void healthComboUnlocksL4WhenAllThreeDone() {
        PetProfile cat = profile(PetType.CAT, 10);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(cat));
        long m5 = stubRoster(10, "C-M5");
        long m3 = stubRoster(10, "C-M3");
        long m4 = stubRoster(10, "C-M4");
        long l4 = stubRoster(10, "C-L4");
        // M3/M4 已完成；M5 本次完成（guard 先 false 放行，combo 校验时已存在 → true）。
        when(completions.existsByPetMilestoneId(m3)).thenReturn(true);
        when(completions.existsByPetMilestoneId(m4)).thenReturn(true);
        when(completions.existsByPetMilestoneId(m5)).thenReturn(false, true);

        boolean done = service.completeForOwner(7L, "M5", MilestoneCompletionSource.USER_CHECKIN);

        assertThat(done).isTrue();
        // 既保存 M5 完成，也自动解锁 L4。
        ArgumentCaptor<MilestoneCompletion> cap = ArgumentCaptor.forClass(MilestoneCompletion.class);
        verify(completions, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().stream().map(MilestoneCompletion::getPetMilestoneId))
                .containsExactlyInAnyOrder(m5, l4);
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
