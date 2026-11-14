package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.healthreminder.service.VetConsultVisitWriter;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 2.2 · AC1）：到访行写入幂等；宠物不存在不写。 */
class VetConsultVisitIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VetConsultVisitWriter writer;

    @Test
    void recordIsIdempotentAndSkipsMissingPet() throws Exception {
        User u = newUser();
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rex\",\"petType\":\"DOG\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long petId = json.readTree(resp).get("id").asLong();
        long sessionId = -SEQ.incrementAndGet(); // 不存在的会话 → 用兜底日期
        assertThat(writer.record(sessionId, petId, LocalDate.parse("2027-03-05"))).isTrue();
        assertThat(writer.record(sessionId, petId, LocalDate.parse("2027-03-05"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT visit_date::text FROM health_vet_consult_visits WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("2027-03-05");
        assertThat(writer.record(sessionId - 1, 999_999_999L, LocalDate.parse("2027-03-05"))).isFalse();
    }
}
