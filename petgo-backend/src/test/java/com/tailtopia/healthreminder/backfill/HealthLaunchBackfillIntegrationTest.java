package com.tailtopia.healthreminder.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 1.3 · AC1）：scratch 库注入存量行 → 回填字段，PRD AC50 / AC51 / AC53；再跑一次幂等。 */
class HealthLaunchBackfillIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    private long createPet(long userId, String type, String sex) throws Exception {
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .header("X-App-Version", "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"" + type + "\",\"sex\":\"" + sex + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private long insertBackfill(long petId, String type, String vaccineName, String eventDate) {
        return jdbc.queryForObject("INSERT INTO health_records (pet_profile_id, type, vaccine_name, event_date, "
                + "record_origin) VALUES (?, ?, ?, ?::date, 'BACKFILL') RETURNING id", Long.class,
                petId, type, vaccineName, eventDate);
    }

    @Test
    void backfillWritesPresetNextAndNeuter() {
        User u = newUser();
        long cat = assertDoesNotThrowPet(u.getId(), "CAT", "FEMALE");
        long tricat = insertBackfill(cat, "VACCINE", "Vaksin tricat", "2026-02-01");
        long combo = insertBackfill(cat, "VACCINE", "F4 + rabies", "2026-02-01");
        User u2 = newUser();
        long dog = assertDoesNotThrowPet(u2.getId(), "DOG", "FEMALE");
        long legacy = insertBackfill(dog, "DEWORM", null, "2026-10-15");
        insertBackfill(dog, "NEUTER", null, "2025-05-01");

        HealthLaunchBackfillRunner runner = new HealthLaunchBackfillRunner(
                ctx.getBean(com.tailtopia.profile.repository.HealthRecordRepository.class),
                ctx.getBean(com.tailtopia.profile.repository.PetProfileRepository.class),
                ctx.getBean(org.springframework.transaction.support.TransactionTemplate.class));
        runner.runBackfill(java.util.List.of(cat, dog));

        assertThat(jdbc.queryForObject("SELECT vaccine_preset FROM health_records WHERE id = ?", String.class, tricat))
                .isEqualTo("F3");
        assertThat(jdbc.queryForObject("SELECT next_date::text FROM health_records WHERE id = ?", String.class,
                tricat)).isEqualTo("2027-02-01");
        assertThat(jdbc.queryForObject("SELECT vaccine_preset FROM health_records WHERE id = ?", String.class, combo))
                .isEqualTo("OTHER");
        assertThat(jdbc.queryForObject("SELECT next_date::text FROM health_records WHERE id = ?", String.class,
                legacy)).isEqualTo("2027-01-15");
        assertThat(jdbc.queryForObject("SELECT neuter_status FROM pet_profiles WHERE id = ?", String.class, dog))
                .isEqualTo("NEUTERED");
        assertThat(jdbc.queryForObject("SELECT vaccine_name FROM health_records WHERE id = ?", String.class, tricat))
                .isEqualTo("Vaksin tricat");

        // 幂等：再跑一次，这几只宠物没有可改的行。
        String before = jdbc.queryForObject("SELECT updated_at::text FROM health_records WHERE id = ?", String.class,
                tricat);
        runner.runBackfill(java.util.List.of(cat, dog));
        assertThat(jdbc.queryForObject("SELECT updated_at::text FROM health_records WHERE id = ?", String.class,
                tricat)).isEqualTo(before);
    }

    private long assertDoesNotThrowPet(long userId, String type, String sex) {
        try {
            return createPet(userId, type, sex);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
