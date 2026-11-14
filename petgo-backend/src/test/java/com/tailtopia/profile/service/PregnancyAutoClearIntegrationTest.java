package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 1.3 · AC3）：库里 pregnant_set_at 早于 4 个月 → clearExpired 置否、清时间戳。 */
class PregnancyAutoClearIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PregnancyAutoClearService service;

    @Test
    void clearsExpiredFlag() throws Exception {
        User u = newUser();
        String resp = mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .header("X-App-Version", "1.5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mochi\",\"petType\":\"DOG\",\"sex\":\"FEMALE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long petId = json.readTree(resp).get("id").asLong();
        jdbc.update("UPDATE pet_profiles SET pregnant_nursing = TRUE, pregnant_set_at = TIMESTAMPTZ "
                + "'2027-01-01 05:00:00+00' WHERE id = ?", petId);
        service.clearExpired(LocalDate.parse("2027-05-01"));
        assertThat(jdbc.queryForObject("SELECT pregnant_nursing FROM pet_profiles WHERE id = ?", Boolean.class,
                petId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT pregnant_set_at FROM pet_profiles WHERE id = ?", Object.class,
                petId)).isNull();
    }
}
