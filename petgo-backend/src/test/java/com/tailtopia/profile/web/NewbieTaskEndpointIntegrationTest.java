package com.tailtopia.profile.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneCompletionService;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 7.3 · FR-47）：{@code GET /api/v1/me/newbie-tasks} 真跑 + Lulus Pemula 聚合解锁。
 * 真 Spring 上下文 + PostgreSQL。验证 6 任务独立计数、无档案 404、S1–S5 全完成需健康记录才解锁。
 */
class NewbieTaskEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private HealthRecordRepository healthRecords;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private MilestoneCompletionService completionService;
    @Autowired
    private com.tailtopia.profile.service.NewbieTaskService newbieTaskService;

    private PetProfile seedPetWithRoster(long ownerId, PetType type) {
        long seq = SEQ.incrementAndGet();
        PetProfile pet = petProfiles.save(PetProfile.create(
                ownerId, type, "Momo", null, null, null, null, "TOK-" + seq));
        milestoneService.assignRoster(pet.getId(), type); // 物化 roster（含 Lulus Pemula）
        return pet;
    }

    @Test
    void freshProfileReportsSixTasksNoneDone() throws Exception {
        User u = newUser();
        seedPetWithRoster(u.getId(), PetType.CAT);

        mvc.perform(get("/api/v1/me/newbie-tasks").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(6)))
                .andExpect(jsonPath("$.completedCount", is(0)))
                .andExpect(jsonPath("$.lulusPemulaUnlocked", is(false)))
                .andExpect(jsonPath("$.items.length()", is(6)));
    }

    @Test
    void noProfileReturns404() throws Exception {
        User u = newUser(PetStatus.PLANNING); // 无 pet_profiles 行 → 404

        mvc.perform(get("/api/v1/me/newbie-tasks").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void allFiveMilestonesWithoutHealthRecordDoesNotUnlockLulusPemula() {
        User u = newUser();
        PetProfile pet = seedPetWithRoster(u.getId(), PetType.CAT);

        for (String suffix : new String[] {"S1", "S2", "S3", "S4", "S5"}) {
            completionService.completeForOwner(u.getId(), suffix, MilestoneCompletionSource.SYSTEM_AUTO);
        }

        // 无健康记录 → 5/6，Lulus Pemula 未解锁。
        var progress = newbieTaskService.progress(u.getId());
        org.assertj.core.api.Assertions.assertThat(progress.completedCount()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(progress.lulusPemulaUnlocked()).isFalse();

        // 补一条健康记录 → 触发聚合解锁。
        healthRecords.save(HealthRecord.create(
                pet.getId(), HealthRecordType.CUSTOM, "体重", null, LocalDate.now(), null));
        completionService.maybeUnlockLulusPemulaForOwner(u.getId());

        var after = newbieTaskService.progress(u.getId());
        org.assertj.core.api.Assertions.assertThat(after.completedCount()).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThat(after.lulusPemulaUnlocked()).isTrue();
    }
}
