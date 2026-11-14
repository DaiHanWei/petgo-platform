package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.5.0 pet-species Story 1.3 · AC3.6 / AC3.7 —— 建卡快照 pet_type 大类 + pet_species 细分类型、
 * 卡号物种段按大类、不一致 422、旧卡不带 species、宠物改类型后已生成的卡不变。
 */
class IdCardSpeciesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions createCard(User owner, String typeJson) throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-cards/v2")
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momo\",\"birthday\":\"2024-03-10\",\"gender\":\"FEMALE\",\"cardType\":\"PASSPORT\","
                        + typeJson + "}"));
    }

    @Test
    void rabbitCardSnapshotsGroupAndSpecies() throws Exception {
        User owner = newUser();
        createCard(owner, "\"species\":\"RABBIT\"").andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.petType").value("OTHER"))
                .andExpect(jsonPath("$.species").value("RABBIT"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pet_type, pet_species, card_no FROM id_cards WHERE user_id = ?", owner.getId());
        assertThat(row.get("pet_type")).isEqualTo("OTHER");
        assertThat(row.get("pet_species")).isEqualTo("RABBIT");
    }

    @Test
    void legacyCatAndMismatch() throws Exception {
        User owner = newUser();
        createCard(owner, "\"petType\":\"CAT\"").andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.species").value("CAT"));
        createCard(newUser(), "\"petType\":\"DOG\",\"species\":\"CAT\"").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void oldCardHasNoSpeciesAndCardUnchangedAfterPetTypeChange() throws Exception {
        User owner = newUser();
        createCard(owner, "\"petType\":\"CAT\"").andExpect(status().is2xxSuccessful());
        jdbc.update("UPDATE id_cards SET pet_species = NULL WHERE user_id = ?", owner.getId()); // 模拟旧卡
        mvc.perform(get("/api/v1/pet-profiles/me/id-cards").header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].species").doesNotExist());

        // 建档 + 改类型：已生成的卡快照不变。
        mvc.perform(post("/api/v1/pet-profiles").header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momo\",\"birthday\":\"2022-01-01\",\"species\":\"CAT\"}"))
                .andExpect(status().isCreated());
        long petId = jdbc.queryForObject("SELECT id FROM pet_profiles WHERE owner_id = ?", Long.class, owner.getId());
        mvc.perform(patch("/api/v1/pet-profiles/me").param("petId", String.valueOf(petId))
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())).header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON).content("{\"species\":\"RABBIT\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT pet_type FROM id_cards WHERE user_id = ?", String.class, owner.getId()))
                .isEqualTo("CAT");
    }
}
