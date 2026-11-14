package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.healthreminder.service.HealthReminderRebuilder;
import com.tailtopia.healthreminder.service.RebuildTrigger;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 2.1 · AC4 / AC5）：保存记录 → 提醒行落库；删记录 → record_deleted；重建幂等。 */
class HealthReminderRebuildIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private HealthReminderRebuilder rebuilder;

    private long createDog(long userId) throws Exception {
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rex\",\"petType\":\"DOG\",\"sex\":\"MALE\",\"birthday\":\"2022-01-01\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private List<String> pendingKinds(long petId) {
        return jdbc.queryForList("SELECT reminder_kind FROM health_reminders WHERE pet_profile_id = ? "
                + "AND status = 'PENDING' ORDER BY due_date, reminder_kind", String.class, petId);
    }

    @Test
    void customRecordRebuildsAndDeleteCancels() throws Exception {
        User u = newUser();
        long petId = createDog(u.getId());
        LocalDate next = LocalDate.now().plusMonths(2);
        String resp = mvc.perform(post("/api/v1/pet-profiles/me/health-records")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header(ClientAppVersion.HEADER, "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CUSTOM\",\"customName\":\"Grooming\",\"eventDate\":\""
                                + LocalDate.now().minusDays(1) + "\",\"nextDate\":\"" + next + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long recordId = json.readTree(resp).get("id").asLong();
        // 监听是 AFTER_COMMIT + @Async：直接再调一次重建（幂等）保证断言稳定。
        rebuilder.rebuildPet(petId, RebuildTrigger.of("test"));
        assertThat(pendingKinds(petId)).containsExactly("CUSTOM_D_DAY");
        rebuilder.rebuildPet(petId, RebuildTrigger.of("test"));
        assertThat(pendingKinds(petId)).containsExactly("CUSTOM_D_DAY");

        mvc.perform(delete("/api/v1/pet-profiles/me/health-records/" + recordId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isNoContent());
        rebuilder.rebuildPet(petId, RebuildTrigger.recordDeleted(recordId));
        assertThat(pendingKinds(petId)).isEmpty();
        assertThat(jdbc.queryForList("SELECT cancel_reason FROM health_reminders WHERE pet_profile_id = ?",
                String.class, petId)).containsOnly("record_deleted");
    }
}
