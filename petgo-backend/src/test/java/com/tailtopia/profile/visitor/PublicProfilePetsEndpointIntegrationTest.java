package com.tailtopia.profile.visitor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：V1.4.0 multi-pet Story 1.4 · AC5 —— {@code GET /users/{userId}/pets} 真实两只宠物、游客可访问、拉黑出口。 */
class PublicProfilePetsEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private long petA;
    private long petB;

    @BeforeEach
    void seed() {
        owner = newUser();
        Instant now = Instant.now();
        petA = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "Alpha", "CAT", now.minusSeconds(120));
        petB = MultiPetDbFixture.insertPet(jdbc, owner.getId(), "Beta", "DOG", now.minusSeconds(60));
    }

    @Test
    void guestSeesBothPetsOldestFirst() throws Exception {
        mvc.perform(get("/api/v1/users/{id}/pets", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].petId").value(petA))
                .andExpect(jsonPath("$[1].petId").value(petB))
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].cardToken").doesNotExist());
    }

    @Test
    void oldSingleEndpointStillReturnsDefaultPet() throws Exception {
        mvc.perform(get("/api/v1/users/{id}/pet", owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petId").value(petA));
    }

    @Test
    void iBlockedOwnerIs403BlockedUser() throws Exception {
        User viewer = newUser();
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                viewer.getId(), owner.getId());
        mvc.perform(get("/api/v1/users/{id}/pets", owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/blocked-user"));
    }

    @Test
    void ownerBlockedMeIsEmptyList() throws Exception {
        User viewer = newUser();
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                owner.getId(), viewer.getId());
        mvc.perform(get("/api/v1/users/{id}/pets", owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void userWithoutPetsIsEmptyList() throws Exception {
        User nobody = newUser();
        mvc.perform(get("/api/v1/users/{id}/pets", nobody.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
