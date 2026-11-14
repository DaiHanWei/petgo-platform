package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 2.1 · AC6）：删宠 / 注销清掉提醒、健康通知与个人上线行。 */
class HealthReminderCleanupIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileDeletionService deletion;

    @Test
    void deletingPetAndAccountRemovesReminderRows() throws Exception {
        User u = newUser();
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"CAT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long petId = json.readTree(resp).get("id").asLong();
        jdbc.update("INSERT INTO health_reminders (pet_profile_id, owner_id, reminder_kind, push_point, origin, "
                + "due_date, send_date, tier, movable) VALUES (?, ?, 'CHECKUP', 'NONE', 'PROFILE', DATE '2030-01-01', "
                + "DATE '2030-01-01', 7, TRUE)", petId, u.getId());
        jdbc.update("INSERT INTO health_reminder_user_launch (user_id, launch_date, queue_start_date) "
                + "VALUES (?, DATE '2030-01-01', DATE '2030-01-01')", u.getId());

        deletion.deleteAllForAccount(u.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_reminders WHERE pet_profile_id = ?",
                Integer.class, petId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_reminder_user_launch WHERE user_id = ?",
                Integer.class, u.getId())).isZero();
    }
}
