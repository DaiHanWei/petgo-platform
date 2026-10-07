package com.tailtopia.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.3.2 batch-a Story 1.5 · L1（需 postgres + redis）：打卡后顺手发帖（AC4 / AC5）。
 */
class PostCheckinLinkIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private JdbcTemplate jdbc;

    private String checkIn(User u, PetProfile pet, Place p) throws Exception {
        String body = mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + pet.getId() + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("checkinToken").asText();
    }

    private String publish(User u, String token, int expect) throws Exception {
        String body = "{\"type\":\"DAILY\",\"text\":\"Ngopi bareng anabul\""
                + (token == null ? "" : ",\"placeCheckinToken\":\"" + token + "\"") + "}";
        return mvc.perform(post("/api/v1/content-posts").header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    private User userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return u;
    }

    @Test
    void ownCheckinLinksAndDetailShowsTheStripThenDeletionNullsTheLink() throws Exception {
        User u = userWithPet();
        PetProfile pet = petProfiles.findByOwnerId(u.getId()).orElseThrow();
        Place p = places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""), "Kopi Kucing",
                PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE), LAT, LNG, "Jl. Test", null, u.getId(), "Jakarta"));
        String token = checkIn(u, pet, p);

        long postId = json.readTree(publish(u, token, 201)).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT place_checkin_id FROM content_posts WHERE id = ?", Long.class, postId))
                .isNotNull();
        mvc.perform(get("/api/v1/content-posts/" + postId).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.checkinPlace.token").value(p.getPublicToken()))
                .andExpect(jsonPath("$.checkinPlace.status").value("ACTIVE"));

        // 普通帖无此键。
        long plain = json.readTree(publish(u, null, 201)).get("id").asLong();
        mvc.perform(get("/api/v1/content-posts/" + plain).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.checkinPlace").doesNotExist());

        // 删档：打卡行被删 → FK ON DELETE SET NULL，帖子保留。
        profileDeletion.deleteByUserId(u.getId());
        assertThat(jdbc.queryForObject("SELECT place_checkin_id FROM content_posts WHERE id = ?", Long.class, postId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE id = ?", Long.class, postId)).isOne();
    }

    @Test
    void someoneElsesOrUnknownTokenIs422() throws Exception {
        User owner = userWithPet();
        PetProfile pet = petProfiles.findByOwnerId(owner.getId()).orElseThrow();
        Place p = places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""), "Taman",
                PlaceType.PARK, List.of(PlaceTag.LEASH_REQUIRED), LAT, LNG, "Jl. Test", null, owner.getId(), "Jakarta"));
        String token = checkIn(owner, pet, p);

        User stranger = userWithPet();
        assertThat(publish(stranger, token, 422)).contains("post-checkin-invalid");
        assertThat(publish(stranger, "x".repeat(32), 422)).contains("post-checkin-invalid");
    }
}
