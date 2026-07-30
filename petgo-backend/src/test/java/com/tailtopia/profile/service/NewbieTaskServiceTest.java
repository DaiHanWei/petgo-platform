package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * L0（Story 7.3 · FR-47）：新手任务独立计数 + Lulus Pemula 解锁态推导 + 无档案 404。纯 Mockito。
 */
class NewbieTaskServiceTest {

    private PetProfileRepository profiles;
    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private HealthRecordRepository healthRecords;
    private NewbieTaskService service;

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        milestones = Mockito.mock(PetMilestoneRepository.class);
        completions = Mockito.mock(MilestoneCompletionRepository.class);
        healthRecords = Mockito.mock(HealthRecordRepository.class);
        service = new NewbieTaskService(profiles, milestones, completions, healthRecords);
    }

    private PetProfile profile(PetType type, long id) {
        PetProfile p = PetProfile.create(7L, type, "Momo", null, null, null, null, "TOK");
        setField(p, "id", id);
        return p;
    }

    /** 注册 roster 行并按 done 设置完成态。 */
    private void stub(long petProfileId, String code, boolean done) {
        long id = nextId++;
        PetMilestone m = PetMilestone.of(petProfileId, MilestoneCatalog.byCode(code));
        setField(m, "id", id);
        when(milestones.findByPetProfileIdAndCode(petProfileId, code)).thenReturn(Optional.of(m));
        when(completions.existsByPetMilestoneId(id)).thenReturn(done);
    }

    @Test
    void countsTasksIndependently_S1S2DoneAndHealthRecord() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.CAT, 10)));
        stub(10, "C-S1", true);
        stub(10, "C-S2", true);
        stub(10, "C-S3", false);
        stub(10, "C-S4", false);
        stub(10, "C-S5", false);
        stub(10, "C-S16", false); // Lulus Pemula 未解锁
        when(healthRecords.existsByPetProfileId(10)).thenReturn(true); // 第 6 任务达成

        NewbieTaskResponse r = service.progress(7L);

        assertThat(r.total()).isEqualTo(6);
        assertThat(r.completedCount()).isEqualTo(3); // S1 + S2 + 健康记录
        assertThat(r.lulusPemulaUnlocked()).isFalse();
        Map<String, Boolean> byKey = r.items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        NewbieTaskResponse.Item::key, NewbieTaskResponse.Item::done));
        assertThat(byKey).containsEntry("CREATE_PROFILE", true)
                .containsEntry("FIRST_PHOTO", true)
                .containsEntry("SHARE_CARD", false)
                .containsEntry("SAVE_CONSULT", false)
                .containsEntry("FIRST_DAILY", false)
                .containsEntry("FIRST_HEALTH_RECORD", true);
    }

    @Test
    void reportsLulusPemulaUnlockedWhenCompletionExists() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(profile(PetType.OTHER, 10)));
        stub(10, "G-S1", true);
        stub(10, "G-S2", true);
        stub(10, "G-S3", true);
        stub(10, "G-S4", true);
        stub(10, "G-S5", true);
        stub(10, "G-S9", true); // OTHER 的 Lulus Pemula = G-S9，已完成
        when(healthRecords.existsByPetProfileId(10)).thenReturn(true);

        NewbieTaskResponse r = service.progress(7L);

        assertThat(r.completedCount()).isEqualTo(6);
        assertThat(r.lulusPemulaUnlocked()).isTrue();
    }

    @Test
    void noProfileThrowsNotFound() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.progress(7L)).isInstanceOf(AppException.class);
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
