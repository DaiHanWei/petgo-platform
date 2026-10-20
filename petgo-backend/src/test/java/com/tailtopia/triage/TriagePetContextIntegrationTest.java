package com.tailtopia.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.service.TriageProcessor;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1（需 Docker；V1.4.0 multi-pet Story 4.1 · AC1 / AC6）：分诊按所选宠物提交（{@code StubGeminiClient}）。
 * 两只宠物账号：带 B 提交 → {@code triage_tasks.pet_id = B}；他人宠物 → 404 且无任务；只读 B 可提交（只读可问）；
 * 对只读 B 存档 ARCHIVED → 409 {@code pet-read-only} 且 {@code health_events} 无行。
 */
class TriagePetContextIntegrationTest extends ApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TriageProcessor processor;

    private User owner;
    private long petA;
    private long petB;
    private String auth;

    @BeforeEach
    void setUp() {
        owner = newUser();
        auth = userBearer(owner.getId());
        petA = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "A", "CAT", T0);
        petB = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "B", "DOG", T0.plusSeconds(60));
    }

    private ResultActions submit(String bearer, Long petId) throws Exception {
        return mvc.perform(post("/api/v1/triage").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TriageSubmitRequest("muntah 2 kali", null, petId))));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void submitWithSecondPetBindsTaskToThatPet() throws Exception {
        var res = submit(auth, petB).andExpect(status().isAccepted()).andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("triageId").asLong();

        assertThat(jdbc.queryForObject("SELECT pet_id FROM triage_tasks WHERE id = ?", Long.class, id))
                .isEqualTo(petB);
        processor.process(id); // 带上下文照常 DONE（stub 固定 GREEN）
        assertThat(jdbc.queryForObject("SELECT status FROM triage_tasks WHERE id = ?", String.class, id))
                .isEqualTo("DONE");
    }

    @Test
    void submitWithoutPetIdKeepsNullPet() throws Exception {
        var res = submit(auth, null).andExpect(status().isAccepted()).andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("triageId").asLong();
        assertThat(jdbc.queryForObject("SELECT pet_id FROM triage_tasks WHERE id = ?", Long.class, id)).isNull();
    }

    @Test
    void foreignPetIs404AndNoTask() throws Exception {
        User other = newUser();
        long foreign = MultiPetDbFixture.insertPet(jdbc, other.getId(), "X", "CAT", T0);
        int before = count("SELECT count(*) FROM triage_tasks WHERE user_id = ?", owner.getId());

        submit(auth, foreign).andExpect(status().isNotFound());

        assertThat(count("SELECT count(*) FROM triage_tasks WHERE user_id = ?", owner.getId())).isEqualTo(before);
    }

    @Test
    void readOnlyPetCanSubmitButCannotArchive() throws Exception {
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", petB);

        var res = submit(auth, petB).andExpect(status().isAccepted()).andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("triageId").asLong();

        mvc.perform(post("/api/v1/health-events/archive-decisions")
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"AI_TRIAGE\",\"sourceRef\":\"triage:" + id
                                + "\",\"petId\":" + petB + ",\"decision\":\"ARCHIVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/pet-read-only"))
                .andExpect(jsonPath("$.petId").value(petB));
        assertThat(count("SELECT count(*) FROM health_events WHERE pet_id = ?", petB)).isZero();
        assertThat(petA).isNotEqualTo(petB);
    }
}
