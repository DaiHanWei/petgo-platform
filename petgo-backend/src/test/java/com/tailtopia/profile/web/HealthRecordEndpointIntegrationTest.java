package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1 集成：Story 7.1 结构化健康记录 CRUD（dev profile）。上下文启动验 Flyway V76 + validate（health_records 契约）。
 * 核心：落库、event_date 不可未来 422、超长 422、CUSTOM 缺名 422、列表倒序、他人记录 404、无档案 404、
 * 删档级联硬删（PDP）。
 */
class HealthRecordEndpointIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/health-records";

    @Autowired
    private HealthRecordRepository records;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long petIdOf(long userId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** 直插一条问诊存档健康事件（模拟 FR-16 存档），archive_decision 决定是否混排。 */
    private void seedConsultEvent(long petId, String decision, String date, String symptom) {
        jdbc.update(
                "INSERT INTO health_events (pet_id, source_type, source_ref, event_date, symptom_summary, "
                        + "ai_level, archive_decision) VALUES (?, 'AI_TRIAGE', ?, ?::date, ?, 'GREEN', ?)",
                petId, "seed-" + SEQ.incrementAndGet(), date, symptom, decision);
    }

    private void createProfile(long userId) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01"}
                                """))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions createRecord(long userId, String body)
            throws Exception {
        return mvc.perform(post(BASE)
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void createPersistsAndListsDesc() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        createRecord(u.getId(),
                """
                {"type":"VACCINE","vaccineName":"Rabies","eventDate":"2024-01-01","note":"ok"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("VACCINE"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.id").isNumber());
        createRecord(u.getId(), """
                {"type":"DEWORM","eventDate":"2024-06-01"}
                """).andExpect(status().isCreated());

        // 列表按 event_date 倒序：DEWORM(06-01) 在前。
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("DEWORM"))
                .andExpect(jsonPath("$[1].type").value("VACCINE"));
    }

    @Test
    void futureDateIs422() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        String future = LocalDate.now().plusDays(1).toString();
        createRecord(u.getId(), """
                {"type":"VACCINE","eventDate":"%s"}
                """.formatted(future)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void customWithoutNameIs422() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        createRecord(u.getId(), """
                {"type":"CUSTOM","eventDate":"2024-01-01"}
                """).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void noteTooLongIs422() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        String longNote = "a".repeat(101);
        createRecord(u.getId(), """
                {"type":"DEWORM","eventDate":"2024-01-01","note":"%s"}
                """.formatted(longNote)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listWithoutProfileIs404() throws Exception {
        User u = newUser();
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersRecordPatchDeleteIs404() throws Exception {
        User a = newUser();
        createProfile(a.getId());
        String body = createRecord(a.getId(), """
                {"type":"VACCINE","eventDate":"2024-01-01"}
                """).andReturn().getResponse().getContentAsString();
        long recordId = json.readTree(body).get("id").asLong();

        User b = newUser();
        createProfile(b.getId());
        // B 改/删 A 的记录 → 404（防枚举）。
        mvc.perform(patch(BASE + "/{id}", recordId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"hacked"}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/{id}", recordId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateAndDeleteOwnRecord() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        String body = createRecord(u.getId(), """
                {"type":"VACCINE","eventDate":"2024-01-01"}
                """).andReturn().getResponse().getContentAsString();
        long recordId = json.readTree(body).get("id").asLong();

        mvc.perform(patch(BASE + "/{id}", recordId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"updated","vaccineName":"Rabies"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("updated"))
                .andExpect(jsonPath("$.vaccineName").value("Rabies"));

        mvc.perform(delete(BASE + "/{id}", recordId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void mixedListMergesRecordsAndArchivedConsultDesc() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        long petId = petIdOf(u.getId());
        // 结构化记录（早）+ 问诊存档 ARCHIVED（晚）+ 问诊 SKIPPED（不应混排）。
        createRecord(u.getId(), """
                {"type":"VACCINE","vaccineName":"Rabies","eventDate":"2024-03-01"}
                """).andExpect(status().isCreated());
        seedConsultEvent(petId, "ARCHIVED", "2024-06-01", "呕吐观察");
        seedConsultEvent(petId, "SKIPPED", "2024-09-01", "已跳过");

        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)) // SKIPPED 不混排
                // event_date 倒序：ARCHIVED 问诊(06-01) 在前，结构化(03-01) 在后。
                .andExpect(jsonPath("$[0].kind").value("CONSULT"))
                .andExpect(jsonPath("$[0].editable").value(false))
                .andExpect(jsonPath("$[0].symptomSummary").value("呕吐观察"))
                .andExpect(jsonPath("$[1].kind").value("RECORD"))
                .andExpect(jsonPath("$[1].editable").value(true))
                .andExpect(jsonPath("$[1].type").value("VACCINE"));

        // 问诊条目不入 health_records 表（仅运行时混排）。
        assertThat(records.findByPetProfileIdOrderByEventDateDescIdDesc(petId)).hasSize(1);
    }

    @Test
    void profileDeletionCascadeHardDeletesRecords() throws Exception {
        User u = newUser();
        createProfile(u.getId());
        long petId = json.readTree(mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        createRecord(u.getId(), """
                {"type":"VACCINE","eventDate":"2024-01-01"}
                """).andExpect(status().isCreated());
        assertThat(records.findByPetProfileIdOrderByEventDateDescIdDesc(petId)).isNotEmpty();

        // 删档级联硬删健康记录（PDP）。
        profileDeletion.deleteByUserId(u.getId());
        assertThat(records.findByPetProfileIdOrderByEventDateDescIdDesc(petId)).isEmpty();
    }
}
