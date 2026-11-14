package com.tailtopia.healthreminder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 2.4 · AC5）：点开接口 —— 本人 FORM 预填、别人的 token 404。 */
class HealthNotificationOpenApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void openOwnTokenAndForeign404() throws Exception {
        User u = newUser();
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"CAT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long petId = json.readTree(resp).get("id").asLong();
        String token = "T" + Long.toString(SEQ.incrementAndGet(), 36);
        long hn = jdbc.queryForObject("INSERT INTO health_notifications (public_token, user_id, pet_profile_id, "
                + "notification_kind, push_point, origin, merged_count, route, push_result, send_date) VALUES "
                + "(?, ?, ?, 'CHECKUP', 'NONE', 'PROFILE', 1, 'FORM', 'SENT', DATE '2030-01-01') RETURNING id",
                Long.class, token, u.getId(), petId);
        jdbc.update("INSERT INTO health_reminders (pet_profile_id, owner_id, reminder_kind, push_point, origin, "
                + "due_date, send_date, tier, movable, status, health_notification_id) VALUES (?, ?, 'CHECKUP', "
                + "'NONE', 'PROFILE', DATE '2030-01-01', DATE '2030-01-01', 7, TRUE, 'SENT', ?)", petId, u.getId(), hn);

        mvc.perform(get("/api/v1/me/health-notifications/" + token + "/open?source=push")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.route").value("FORM"))
                .andExpect(jsonPath("$.prefill.type").value("VET_VISIT"))
                .andExpect(jsonPath("$.prefill.eventDateEmpty").value(true))
                .andExpect(jsonPath("$.analytics.pushPoint").value("none"));

        User other = newUser();
        mvc.perform(get("/api/v1/me/health-notifications/" + token + "/open")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isNotFound());
    }
}
