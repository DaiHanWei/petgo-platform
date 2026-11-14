package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1 集成（V1.5.0 健康记录提醒 Story 1.1 · AC1–AC5）：Flyway {@code V20261115_0100/0110} + validate 能启动；
 * 带 / 不带 {@code X-App-Version} 两套请求的读写分流；NEUTER 记录置 NEUTERED；档案健康状态字段。
 */
class HealthRecordApiIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/health-records";
    private static final String V150 = "1.5.0";
    private static final String OLD = "1.4.2";

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long createPet(long userId, String type, String sex) throws Exception {
        String body = "{\"name\":\"Mochi\",\"petType\":\"" + type + "\",\"birthday\":\"2025-01-01\""
                + (sex == null ? "" : ",\"sex\":\"" + sex + "\"") + "}";
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .header(ClientAppVersion.HEADER, V150)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private ResultActions write(long userId, String version, String body) throws Exception {
        var req = post(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (version != null) {
            req.header(ClientAppVersion.HEADER, version);
        }
        return mvc.perform(req);
    }

    @Test
    void v150ClientWritesNewFieldsAppOrigin() throws Exception {
        User u = newUser();
        createPet(u.getId(), "CAT", "FEMALE");
        String resp = write(u.getId(), V150, """
                {"type":"VACCINE","vaccinePreset":"F4","vaccineName":"F4 (Tetracat)","dose":2,
                 "eventDate":"2026-10-01","nextDate":"2026-10-29"}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.vaccinePreset").value("F4"))
                .andExpect(jsonPath("$.dose").value(2))
                .andExpect(jsonPath("$.nextDate").value("2026-10-29"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(resp).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT record_origin FROM health_records WHERE id = ?", String.class, id))
                .isEqualTo("APP");
    }

    @Test
    void legacyClientIgnoresNewFieldsAndCannotWriteVetVisit() throws Exception {
        User u = newUser();
        createPet(u.getId(), "CAT", null);
        String resp = write(u.getId(), null, """
                {"type":"VACCINE","vaccinePreset":"F4","dose":2,"eventDate":"2026-10-01","nextDate":"2026-10-29"}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(resp).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT record_origin FROM health_records WHERE id = ?", String.class, id))
                .isEqualTo("LEGACY_APP");
        assertThat(jdbc.queryForObject("SELECT dose FROM health_records WHERE id = ?", Integer.class, id)).isNull();

        write(u.getId(), OLD, """
                {"type":"VET_VISIT","eventDate":"2026-10-01"}
                """).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listFiltersVetVisitForOldClients() throws Exception {
        User u = newUser();
        createPet(u.getId(), "DOG", null);
        write(u.getId(), V150, """
                {"type":"VET_VISIT","eventDate":"2026-10-01","nextDate":"2026-11-30"}
                """).andExpect(status().isCreated());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, V150))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("VET_VISIT"));
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void v150DewormRequiresType() throws Exception {
        User u = newUser();
        createPet(u.getId(), "DOG", null);
        write(u.getId(), V150, """
                {"type":"DEWORM","eventDate":"2026-10-01"}
                """).andExpect(status().isUnprocessableEntity());
        write(u.getId(), V150, """
                {"type":"DEWORM","dewormType":"EXTERNAL","eventDate":"2026-10-01"}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.productDurationMonths").value(1));
    }

    @Test
    void neuterRecordMarksPetNeutered() throws Exception {
        User u = newUser();
        long petId = createPet(u.getId(), "CAT", "MALE");
        write(u.getId(), null, """
                {"type":"NEUTER","eventDate":"2026-10-01"}
                """).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT neuter_status FROM pet_profiles WHERE id = ?", String.class, petId))
                .isEqualTo("NEUTERED");
    }

    @Test
    void profileHealthStatusFields() throws Exception {
        User u = newUser();
        createPet(u.getId(), "DOG", "FEMALE");
        mvc.perform(patch("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, V150)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catInHome\":true,\"pregnantNursing\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catInHome").value(true))
                .andExpect(jsonPath("$.pregnantNursing").value(true))
                .andExpect(jsonPath("$.pregnantAutoOffDate").isNotEmpty());
    }
}
