package com.tailtopia.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（V1.4.0 multi-pet Story 1.1 AC3 / AC4 / AC5）：同账号两只宠物 A（先建）/ B（后建）各接口不串；缺省 = A。
 *
 * <p>库里仍有 {@code uq_pet_profiles_owner_id}（Story 1.4 才删）→ 用 {@link MultiPetDbFixture} 类内即删即恢复。
 */
class MultiPetAddressingIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();
    private long owner;
    private long petA;
    private long petB;
    private String auth;

    @BeforeEach
    void setUpPets() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        User u = newUser();
        owner = u.getId();
        auth = userBearer(owner);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        petA = MultiPetDbFixture.insertPet(jdbc, owner, "Aaa", "CAT", t0);
        petB = MultiPetDbFixture.insertPet(jdbc, owner, "Bbb", "DOG", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    @Test
    void profileGetAndPatchAddressByPetId() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(petA));
        mvc.perform(get("/api/v1/pet-profiles/me").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bbb"));
        mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bnew\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(petB));
        mvc.perform(get("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.name").value("Aaa"));
    }

    @Test
    void foreignAndMissingPetIdGiveSame404AndNonNumeric400() throws Exception {
        User other = newUser();
        long foreign = MultiPetDbFixture.insertPet(jdbc, other.getId(), "Zzz", "CAT", Instant.now());
        extraPets.add(foreign);
        String foreignBody = mvc.perform(get("/api/v1/pet-profiles/me/timeline")
                        .param("petId", String.valueOf(foreign)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(get("/api/v1/pet-profiles/me/timeline")
                        .param("petId", "987654321").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(stripVolatile(foreignBody)).isEqualTo(stripVolatile(missingBody));
        mvc.perform(get("/api/v1/pet-profiles/me/timeline").param("petId", "abc")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthRecordsDoNotLeakAcrossPets() throws Exception {
        String created = mvc.perform(post("/api/v1/pet-profiles/me/health-records")
                        .param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VACCINE\",\"eventDate\":\"2026-01-02\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long recordId = json.readTree(created).get("id").asLong();

        mvc.perform(get("/api/v1/pet-profiles/me/health-records").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/pet-profiles/me/health-records").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(patch("/api/v1/pet-profiles/me/health-records/" + recordId)
                        .param("petId", String.valueOf(petA)).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/pet-profiles/me/health-records/" + recordId)
                        .param("petId", String.valueOf(petA)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pet-profiles/me/archive-stats").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.healthRecordCount").value(1));
        mvc.perform(get("/api/v1/pet-profiles/me/archive-stats").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.healthRecordCount").value(0));
    }

    @Test
    void timelineCalendarDayMilestonesAndCardShareAcceptPetId() throws Exception {
        for (String path : List.of("/api/v1/pet-profiles/me/timeline", "/api/v1/pet-profiles/me/milestones")) {
            mvc.perform(get(path).param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/pet-profiles/me/calendar").param("year", "2026").param("month", "1")
                        .param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/pet-profiles/me/day").param("date", "2026-01-02")
                        .param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pet-profiles/me/card-shares").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        // card-shares 发 CardSharedEvent → @Async AFTER_COMMIT 给 B 点亮「首次分享名片」（DOG = D-S3）。
        // 必须等这条异步写落定再结束：否则它会插在 @AfterEach 清理「先删 completions、再删 pet_milestones」之间，
        // 撞 milestone_completions_pet_milestone_id_fkey（CI 偶发红的根因）。顺带断言分享按 petId 落在 B、不串到 A。
        awaitCompleted(petB, "D-S3");
        org.assertj.core.api.Assertions.assertThat(completed(petB, "D-S3")).isTrue();
        org.assertj.core.api.Assertions.assertThat(completed(petA, "C-S3")).isFalse();
    }

    private boolean completed(long petId, String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM milestone_completions c JOIN pet_milestones m"
                + " ON m.id = c.pet_milestone_id WHERE m.pet_profile_id = ? AND m.code = ?", Long.class, petId, code);
        return n != null && n > 0;
    }

    private void awaitCompleted(long petId, String code) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !completed(petId, code)) {
            Thread.sleep(100);
        }
    }

    @Test
    void listReturnsBothPetsOrderedWithDefaultFlag() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me/list").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(petA))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].id").value(petB))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    /**
     * 旧版兼容（V1.4.0 上线前兼容检查）：多宠账号不带 petId 的 PATCH / DELETE /me → 409 pet-id-required，
     * 两只宠物都原样保留（旧版 App 缓存的默认宠物可能已过期，按默认宠物落地会改错 / 删错）。
     */
    @Test
    void multiPetWriteWithoutPetIdRejectedAndNothingChanges() throws Exception {
        mvc.perform(patch("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Overwrite\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pet-id-required")));
        mvc.perform(delete("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pet-id-required")));

        Long left = jdbc.queryForObject("SELECT count(*) FROM pet_profiles WHERE owner_id = ?", Long.class, owner);
        org.assertj.core.api.Assertions.assertThat(left).isEqualTo(2L);
        mvc.perform(get("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.name").value("Aaa"));
        mvc.perform(get("/api/v1/pet-profiles/me").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.name").value("Bbb"));
    }

    /** ProblemDetail 的 instance / traceId 每次不同，比较前去掉。 */
    private static String stripVolatile(String body) {
        return body.replaceAll("\"(instance|traceId)\"\\s*:\\s*\"[^\"]*\"", "");
    }
}
