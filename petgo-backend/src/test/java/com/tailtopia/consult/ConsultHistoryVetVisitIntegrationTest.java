package com.tailtopia.consult;

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

/** L1（V1.5.0 Story 2.5）：问诊历史在 1.5.0 客户端带出 Kontrol dokter，旧版不带。 */
class ConsultHistoryVetVisitIntegrationTest extends ApiIntegrationTest {

    @Test
    void vetVisitAppearsOnlyForV150() throws Exception {
        User u = newUser();
        mvc.perform(post("/api/v1/pet-profiles").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"CAT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/pet-profiles/me/health-records").header(HttpHeaders.AUTHORIZATION,
                        userBearer(u.getId())).header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VET_VISIT\",\"eventDate\":\"2026-10-01\",\"note\":\"kontrol\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/consult/history").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0"))
                .andExpect(jsonPath("$.items[0].type").value("VET_VISIT"))
                .andExpect(jsonPath("$.items[0].petName").value("Mochi"));
        mvc.perform(get("/api/v1/consult/history").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
