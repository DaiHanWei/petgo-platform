package com.tailtopia.profile.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** L1 集成（V1.5.0 Story 1.2 · AC7 / AC8）：预填接口 + 列表显示字段 + 旧版新建默认值。 */
class HealthRecordPreviewApiIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/health-records";

    private void createCat(long userId, String birthday) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"CAT\",\"birthday\":\"" + birthday + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void previewPrefillsKittenF4() throws Exception {
        User u = newUser();
        createCat(u.getId(), "2026-10-01");
        mvc.perform(post(BASE + "/preview")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VACCINE\",\"vaccinePreset\":\"F4\",\"dose\":1,\"eventDate\":\"2026-12-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedNextDate").value("2026-12-31"))
                .andExpect(jsonPath("$.nextDateMax").value("2029-12-03"))
                .andExpect(jsonPath("$.dueDose").value(2));
    }

    @Test
    void previewForeignPet404() throws Exception {
        User u = newUser();
        createCat(u.getId(), "2026-10-01");
        mvc.perform(post(BASE + "/preview?petId=999999999")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VACCINE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyCreateGetsKeywordPresetAndListShowsStatus() throws Exception {
        User u = newUser();
        createCat(u.getId(), "2020-01-01");
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VACCINE\",\"vaccineName\":\"Vaksin tricat\",\"eventDate\":\"2020-02-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vaccinePreset").value("F3"))
                .andExpect(jsonPath("$.nextDate").value("2021-02-01"));
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0"))
                .andExpect(jsonPath("$[0].recordStatus").value("OVERDUE"))
                .andExpect(jsonPath("$[0].displayNextDate").value("2021-02-01"));
    }
}
