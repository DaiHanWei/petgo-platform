package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.healthreminder.schedule.HealthReminderJob;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（V1.5.0 Story 2.3 · AC1 / AC2）：开关关闭 → 不动；打开 → 发送日已过的行 missed_run；旧版用户当天的行 old_app。
 * 直接调 {@code runFor(LocalDate)}（PRD AC36 注入日期）。
 */
class HealthReminderDailyRunIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private HealthReminderJob job;

    @AfterEach
    void reset() {
        jdbc.update("UPDATE health_reminder_settings SET enabled = FALSE, launch_date = NULL WHERE id = 1");
    }

    private long createPet(long userId) throws Exception {
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .header(ClientAppVersion.HEADER, "1.4.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rex\",\"petType\":\"DOG\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long insertPending(long petId, long ownerId, String sendDate) {
        return jdbc.queryForObject("INSERT INTO health_reminders (pet_profile_id, owner_id, source_record_id, "
                + "reminder_kind, push_point, origin, next_date_ref, due_date, send_date, tier, movable) VALUES "
                + "(?, ?, 1, 'CUSTOM_D_DAY', 'D_DAY', 'RECORD', ?::date, ?::date, ?::date, 2, FALSE) RETURNING id",
                Long.class, petId, ownerId, sendDate, sendDate, sendDate);
    }

    @Test
    void missedRunAndOldAppCancellation() throws Exception {
        User u = newUser();
        long petId = createPet(u.getId());
        long stale = insertPending(petId, u.getId(), "2030-02-27");
        long today = insertPending(petId, u.getId(), "2030-03-01");

        assertThat(job.runFor(LocalDate.parse("2030-03-01")).enabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM health_reminders WHERE id = ?", String.class, stale))
                .isEqualTo("PENDING");

        jdbc.update("UPDATE health_reminder_settings SET enabled = TRUE, switched_on_at = now() WHERE id = 1");
        job.runFor(LocalDate.parse("2030-03-01"));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM health_reminders WHERE id = ?", String.class, stale))
                .isEqualTo("missed_run");
        // 用户最后上报 1.4.2 → 当天待发作废 old_app
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM health_reminders WHERE id = ?", String.class, today))
                .isEqualTo("old_app");
    }
}
