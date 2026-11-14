package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.5.0 pet-species Story 1.1 · AC4.5 —— 建档五种请求（旧版只带 petType / 细分类型 / 「其他」组物种 /
 * 大类不一致 / 非法细分类型）、三列落库、里程碑 roster code 前缀。
 */
class PetSpeciesCreateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private ResultActions create(User owner, String typeJson) throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles")
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momo\",\"birthday\":\"2022-01-01\"," + typeJson + "}"));
    }

    private Map<String, Object> row(long ownerId) {
        return jdbc.queryForMap(
                "SELECT id, pet_species, pet_type, milestone_track, pet_type_changed_at FROM pet_profiles WHERE owner_id = ?",
                ownerId);
    }

    private List<String> rosterCodes(long petId) {
        return jdbc.queryForList("SELECT code FROM pet_milestones WHERE pet_profile_id = ?", String.class, petId);
    }

    @Test
    void legacyPetTypeOnly() throws Exception {
        User owner = newUser();
        create(owner, "\"petType\":\"OTHER\"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.petType").value("OTHER"))
                .andExpect(jsonPath("$.species").value("OTHER"))
                .andExpect(jsonPath("$.speciesChangeableFrom").doesNotExist());
        Map<String, Object> r = row(owner.getId());
        assertThat(r.get("pet_species")).isEqualTo("OTHER");
        assertThat(r.get("pet_type")).isEqualTo("OTHER");
        assertThat(r.get("milestone_track")).isEqualTo("OTHER");
        assertThat(r.get("pet_type_changed_at")).isNull();
    }

    @Test
    void rabbitIsOtherGroupWithGRoster() throws Exception {
        User owner = newUser();
        create(owner, "\"species\":\"RABBIT\"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.petType").value("OTHER"))
                .andExpect(jsonPath("$.species").value("RABBIT"));
        Map<String, Object> r = row(owner.getId());
        assertThat(r.get("pet_species")).isEqualTo("RABBIT");
        assertThat(r.get("pet_type")).isEqualTo("OTHER");
        assertThat(r.get("milestone_track")).isEqualTo("OTHER");
        assertThat(rosterCodes(((Number) r.get("id")).longValue())).isNotEmpty().allMatch(c -> c.startsWith("G"));
    }

    @Test
    void civetIsOther() throws Exception {
        User owner = newUser();
        create(owner, "\"species\":\"CIVET\"").andExpect(status().isCreated());
        assertThat(row(owner.getId()).get("pet_type")).isEqualTo("OTHER");
    }

    @Test
    void catWithSpeciesCatGetsCRoster() throws Exception {
        User owner = newUser();
        create(owner, "\"petType\":\"CAT\",\"species\":\"CAT\"").andExpect(status().isCreated());
        Map<String, Object> r = row(owner.getId());
        assertThat(r.get("milestone_track")).isEqualTo("CAT");
        assertThat(rosterCodes(((Number) r.get("id")).longValue())).isNotEmpty().allMatch(c -> c.startsWith("C"));
    }

    @Test
    void mismatchRejected() throws Exception {
        create(newUser(), "\"petType\":\"CAT\",\"species\":\"RABBIT\"").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownSpeciesRejectedByBeanValidation() throws Exception {
        create(newUser(), "\"species\":\"DRAGON\"").andExpect(status().isUnprocessableEntity());
    }
}
