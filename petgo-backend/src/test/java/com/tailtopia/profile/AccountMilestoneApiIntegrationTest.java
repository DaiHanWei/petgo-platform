package com.tailtopia.profile;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：V1.5.0 onboarding-recall Story 2.3 —— 账号级里程碑接口、回报置位、≥ 1.5.0 列表账号组 / S 组 / 计数、
 * 旧版形状不变、无宠物后建宠账号成就保持。
 */
class AccountMilestoneApiIntegrationTest extends ApiIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PetProfileRepository petProfiles;
    @Autowired private MilestoneService milestoneService;
    @Autowired private PlatformTransactionManager txManager;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private long rosteredPet(User u, PetType type) {
        long id = new TransactionTemplate(txManager).execute(st -> {
            long pid = petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                    "TOK-AM-" + SEQ.incrementAndGet())).getId();
            milestoneService.assignRoster(pid, type);
            return pid;
        });
        extraPets.add(id);
        return id;
    }

    private void flag(User u, String key) {
        jdbc.update("INSERT INTO user_milestone_flags (user_id, flag_key, occurred_at) VALUES (?, ?, now())",
                u.getId(), key);
    }

    @Test
    void noPetAccountListsThreeItemsWithCatCodes() throws Exception {
        User u = newUser();
        flag(u, "LULUS_PEMULA");
        mvc.perform(get("/api/v1/me/account-milestones").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount", is(3)))
                .andExpect(jsonPath("$.completedCount", is(1)))
                .andExpect(jsonPath("$.items[2].code", is("C-S16")))
                .andExpect(jsonPath("$.items[2].pendingCelebration", is(true)));

        mvc.perform(post("/api/v1/me/account-milestones/celebrations").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"codes\":[\"G-S9\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked", is(1)));

        mvc.perform(get("/api/v1/me/account-milestones").header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.items[2].pendingCelebration", is(false)));
    }

    @Test
    void v150ListHasAccountGroupAndExcludesItFromCounts() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long other = rosteredPet(u, PetType.OTHER);
        flag(u, "FIRST_FOLLOW_USER");

        mvc.perform(get("/api/v1/pet-profiles/me/milestones").param("petId", String.valueOf(cat))
                        .header("Authorization", userBearer(u.getId())).header("X-App-Version", "1.5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount", is(32)))
                .andExpect(jsonPath("$.accountGroup.level", is("ACCOUNT")))
                .andExpect(jsonPath("$.accountGroup.items.length()", is(3)))
                .andExpect(jsonPath("$.accountGroup.items[0].completed", is(true)));
        mvc.perform(get("/api/v1/pet-profiles/me/milestones").param("petId", String.valueOf(other))
                        .header("Authorization", userBearer(u.getId())).header("X-App-Version", "1.5.0"))
                .andExpect(jsonPath("$.totalCount", is(17)))
                .andExpect(jsonPath("$.accountGroup.items[0].completed", is(true)));

        // 旧版（1.4.2 ≤ v < 1.5.0）原样：35 项、无 accountGroup。
        mvc.perform(get("/api/v1/pet-profiles/me/milestones").param("petId", String.valueOf(cat))
                        .header("Authorization", userBearer(u.getId())).header("X-App-Version", "1.4.2"))
                .andExpect(jsonPath("$.totalCount", is(35)))
                .andExpect(jsonPath("$.accountGroup").doesNotExist());
    }

    @Test
    void accountAchievementSurvivesFirstPetCreation() throws Exception {
        User u = newUser();
        flag(u, "FIRST_FOLLOW_USER");
        long cat = rosteredPet(u, PetType.CAT);
        mvc.perform(get("/api/v1/pet-profiles/me/milestones").param("petId", String.valueOf(cat))
                        .header("Authorization", userBearer(u.getId())).header("X-App-Version", "1.5.0"))
                .andExpect(jsonPath("$.accountGroup.items[0].completed", is(true)))
                .andExpect(jsonPath("$.accountGroup.items[0].celebratedAt").exists());
    }
}
