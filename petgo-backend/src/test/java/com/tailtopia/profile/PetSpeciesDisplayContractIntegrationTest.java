package com.tailtopia.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.5.0 pet-species Story 1.3 · AC5.2 —— 一只兔子经各显示接口：{@code petType = OTHER} 不变、{@code species = RABBIT} 追加。
 * 登机牌 / 订单详情需额外造打卡与订单数据，留 Story 4.1 全链路联调覆盖。
 */
class PetSpeciesDisplayContractIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long createRabbit(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                .header("X-App-Version", "1.5.0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Momo\",\"birthday\":\"2022-01-01\",\"species\":\"RABBIT\"}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM pet_profiles WHERE owner_id = ?", Long.class, owner.getId());
    }

    @Test
    void rabbitAcrossDisplayEndpoints() throws Exception {
        User owner = newUser();
        long petId = createRabbit(owner);
        User viewer = newUser();
        String bearer = userBearer(viewer.getId());

        mvc.perform(get("/api/v1/pet-profiles/me/list").header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].petType").value("OTHER"))
                .andExpect(jsonPath("$[0].species").value("RABBIT"));
        mvc.perform(get("/api/v1/pets/{petId}/visitor/profile", petId).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petType").value("OTHER"))
                .andExpect(jsonPath("$.species").value("RABBIT"));
        mvc.perform(get("/api/v1/users/{userId}/pets", owner.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/gifts/catalog").param("petId", String.valueOf(petId))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.petType").value("OTHER"))
                .andExpect(jsonPath("$.recipient.species").value("RABBIT"));
        mvc.perform(get("/api/v1/pets/{petId}/gifts", petId).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pet.species").value("RABBIT"));
    }
}
