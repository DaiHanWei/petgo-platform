package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.5.0 pet-species Story 1.2 · AC4 ~ AC6 / AC8 —— 经真实 {@code PATCH /pet-profiles/me} 改类型：
 * 冷却、组内换物种、清品种、里程碑清单切换（退役 / 复活 / 补记、不发成长值与通知）、并发双提交、删档级联。
 */
class PetSpeciesChangeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProfileDeletionService deletion;

    private long createPet(User owner, String species) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momo\",\"birthday\":\"2022-01-01\",\"breed\":\"Persia\",\"species\":\""
                        + species + "\"}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM pet_profiles WHERE owner_id = ?", Long.class, owner.getId());
    }

    private ResultActions patchPet(User owner, long petId, String body) throws Exception {
        return mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(petId))
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void backdateChange(long petId, String isoInstant) {
        jdbc.update("UPDATE pet_profiles SET pet_type_changed_at = ?::timestamptz WHERE id = ?", isoInstant, petId);
    }

    private List<String> activeCodes(long petId) {
        return jdbc.queryForList("SELECT code FROM pet_milestones WHERE pet_profile_id = ? AND retired_at IS NULL",
                String.class, petId);
    }

    private boolean done(long petId, String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM milestone_completions c JOIN pet_milestones m"
                + " ON m.id = c.pet_milestone_id WHERE m.pet_profile_id = ? AND m.code = ?", Integer.class, petId, code);
        return n != null && n > 0;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void catToRabbitSwitchesRosterKeepsHistoryAndGrantsNothing() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "CAT");
        // 等 C-S1（档案创建，AFTER_COMMIT 异步）落库后再改。
        for (int i = 0; i < 50 && !done(petId, "C-S1"); i++) {
            Thread.sleep(100);
        }
        int ledgerBefore = count("SELECT count(*) FROM growth_ledger WHERE user_id = ?", owner.getId());
        int notifBefore = count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", owner.getId());

        patchPet(owner, petId, "{\"species\":\"RABBIT\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.species").value("RABBIT"))
                .andExpect(jsonPath("$.petType").value("OTHER"))
                .andExpect(jsonPath("$.breed").doesNotExist())
                .andExpect(jsonPath("$.speciesChangeableFrom").exists());

        assertThat(activeCodes(petId)).isNotEmpty().allMatch(c -> c.startsWith("G-"));
        assertThat(done(petId, "G-S1")).isTrue();
        assertThat(done(petId, "C-S1")).isTrue(); // 退役行完成记录仍在
        assertThat(count("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ? AND code LIKE 'C-%'"
                + " AND retired_at IS NULL", petId)).isZero();
        Thread.sleep(500);
        assertThat(count("SELECT count(*) FROM growth_ledger WHERE user_id = ?", owner.getId())).isEqualTo(ledgerBefore);
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", owner.getId()))
                .isEqualTo(notifBefore);
        assertThat(jdbc.queryForObject("SELECT milestone_track FROM pet_profiles WHERE id = ?", String.class, petId))
                .isEqualTo("OTHER");
        assertThat(count("SELECT count(*) FROM pet_species_changes WHERE pet_profile_id = ? AND source = 'USER'"
                + " AND type_changed AND group_changed", petId)).isEqualTo(1);

        // 冷却中改回猫 → 409；只改名字 → 200；组内换物种 → 200。
        patchPet(owner, petId, "{\"species\":\"CAT\",\"name\":\"X\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-species-cooldown"))
                .andExpect(jsonPath("$.changeableFrom").exists());
        assertThat(jdbc.queryForObject("SELECT name FROM pet_profiles WHERE id = ?", String.class, petId))
                .isEqualTo("Momo");
        patchPet(owner, petId, "{\"name\":\"Nana\"}").andExpect(status().isOk());
        patchPet(owner, petId, "{\"species\":\"RABBIT\"}").andExpect(status().isOk());

        // 冷却过后改回猫 → C 行复活、G 行退役、完成态与改前一致。
        backdateChange(petId, "2020-01-01T00:00:00Z");
        patchPet(owner, petId, "{\"species\":\"CAT\"}").andExpect(status().isOk());
        assertThat(activeCodes(petId)).isNotEmpty().allMatch(c -> c.startsWith("C-"));
        assertThat(done(petId, "C-S1")).isTrue();
    }

    @Test
    void otherGroupSwitchIgnoresCooldownAndKeepsBreed() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "OTHER");
        backdateChange(petId, "2099-01-01T00:00:00Z"); // 冷却中
        patchPet(owner, petId, "{\"species\":\"CIVET\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.species").value("CIVET"))
                .andExpect(jsonPath("$.breed").value("Persia"));
    }

    @Test
    void rabbitToHamsterKeepsRosterAndLegacyClientKeepsSpecies() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "RABBIT");
        List<String> before = activeCodes(petId);
        patchPet(owner, petId, "{\"species\":\"HAMSTER\",\"breed\":\"Syrian\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.breed").value("Syrian"));
        assertThat(activeCodes(petId)).containsExactlyInAnyOrderElementsOf(before);
        // 旧版 App 不传 species → 保留。
        patchPet(owner, petId, "{\"name\":\"Old\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.species").value("HAMSTER"));
    }

    @Test
    void migratedCatToDogSwitchesFromOtherTrack() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "OTHER");
        // 模拟 FR-208：大类变 CAT、清单仍 OTHER。
        jdbc.update("UPDATE pet_profiles SET pet_species = 'CAT', pet_type = 'CAT' WHERE id = ?", petId);
        patchPet(owner, petId, "{\"species\":\"DOG\"}").andExpect(status().isOk());
        assertThat(activeCodes(petId)).isNotEmpty().allMatch(c -> c.startsWith("D-"));
    }

    @Test
    void concurrentDoubleSubmitOnlyOneChangesType() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "CAT");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> a = () -> patchPet(owner, petId, "{\"species\":\"DOG\"}").andReturn().getResponse()
                    .getStatus();
            Callable<Integer> b = () -> patchPet(owner, petId, "{\"species\":\"RABBIT\"}").andReturn().getResponse()
                    .getStatus();
            List<Future<Integer>> rs = pool.invokeAll(List.of(a, b));
            List<Integer> codes = List.of(rs.get(0).get(), rs.get(1).get());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingPetCascadesChangeRecords() throws Exception {
        User owner = newUser();
        long petId = createPet(owner, "CAT");
        patchPet(owner, petId, "{\"species\":\"RABBIT\"}").andExpect(status().isOk());
        deletion.deleteByPetId(owner.getId(), petId);
        assertThat(count("SELECT count(*) FROM pet_species_changes WHERE pet_profile_id = ?", petId)).isZero();
    }
}
