package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.4.0 multi-pet Story 2.1 · AC7 / AC8 —— 新增类端点打只读宠物 → 409 {@code pet-read-only}（ProblemDetail 带
 * {@code petId}、traceId），库里无新增行；GET / 删除照常。只读状态由测试直接写列造（主代码只经 reconcile 改）。
 */
class PetReadOnlyGuardIntegrationTest extends ApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private long active;
    private long readOnly;
    private String auth;

    @BeforeEach
    void setUp() {
        owner = newUser();
        auth = userBearer(owner.getId());
        active = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "A", "CAT", T0);
        readOnly = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "B", "CAT", T0.plusSeconds(60));
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", readOnly);
    }

    private static void expectReadOnly(ResultActions r, long petId) throws Exception {
        r.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-read-only"))
                .andExpect(jsonPath("$.petId").value(petId))
                .andExpect(jsonPath("$.traceId").exists());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void editProfileIs409AndUnchanged() throws Exception {
        expectReadOnly(mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(readOnly))
                .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"B2\"}")), readOnly);
        assertThat(jdbc.queryForObject("SELECT name FROM pet_profiles WHERE id = ?", String.class, readOnly))
                .isEqualTo("B");
    }

    @Test
    void healthRecordCreateIs409WithoutRow() throws Exception {
        expectReadOnly(mvc.perform(post("/api/v1/pet-profiles/me/health-records")
                .param("petId", String.valueOf(readOnly)).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"VACCINE\",\"eventDate\":\"2026-01-02\"}")), readOnly);
        assertThat(count("SELECT count(*) FROM health_records WHERE pet_profile_id = ?", readOnly)).isZero();
    }

    @Test
    void diaryOnReadOnlyPetIs409WithoutPost() throws Exception {
        expectReadOnly(mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"GROWTH_MOMENT\",\"petId\":" + readOnly + ",\"text\":\"hi\"}")), readOnly);
        assertThat(count("SELECT count(*) FROM content_posts WHERE pet_id = ?", readOnly)).isZero();
    }

    @Test
    void consultArchiveIs409ButSkipIsRecorded() throws Exception {
        expectReadOnly(mvc.perform(post("/api/v1/health-events/archive-decisions")
                .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"AI_TRIAGE\",\"sourceRef\":\"ro-" + readOnly
                        + "\",\"petId\":" + readOnly + ",\"decision\":\"ARCHIVED\"}")), readOnly);
        assertThat(count("SELECT count(*) FROM health_events WHERE pet_id = ?", readOnly)).isZero();
        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"AI_TRIAGE\",\"sourceRef\":\"ro-skip-" + readOnly
                                + "\",\"petId\":" + readOnly + ",\"decision\":\"SKIPPED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void tailsonalitySubmitIs409WithoutRow() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        TailsonalityCatalog.QUESTION_IDS.forEach(q -> body.put(q, 0));
        expectReadOnly(mvc.perform(post("/api/v1/pet-profiles/me/tailsonality/results")
                .param("petId", String.valueOf(readOnly)).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))), readOnly);
        assertThat(count("SELECT count(*) FROM tailsonality_results WHERE pet_profile_id = ?", readOnly)).isZero();
    }

    /** 他人宠物仍 404（归属先于可写，不泄露状态）。 */
    @Test
    void foreignReadOnlyPetIs404Not409() throws Exception {
        User other = newUser();
        mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(readOnly))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    /** 查看 / 删除不受影响；正常宠物照常可写；默认宠物落在 ACTIVE 的那只。 */
    @Test
    void readsAndDeleteUnaffected() throws Exception {
        mvc.perform(get("/api/v1/pet-profiles/me").param("petId", String.valueOf(readOnly))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/pet-profiles/me/health-records").param("petId", String.valueOf(readOnly))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(active))
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A2\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/pet-profiles/me").param("petId", String.valueOf(readOnly))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().is2xxSuccessful());
        assertThat(count("SELECT count(*) FROM pet_profiles WHERE id = ?", readOnly)).isZero();
    }

    @Test
    void defaultPetIsEarliestActive() throws Exception {
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", active);
        jdbc.update("UPDATE pet_profiles SET status = 'ACTIVE' WHERE id = ?", readOnly);
        mvc.perform(get("/api/v1/pet-profiles/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(readOnly));
    }
}
