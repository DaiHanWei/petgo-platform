package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.3.2 batch-a Story 1.6 · L1（需 postgres + redis）：Diary 打卡条目（AC1.3 / AC2.3-2.4 / AC3 / AC5.1 / AC6）。
 */
class TimelinePlaceCheckinIntegrationTest extends ApiIntegrationTest {

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

    private record Fixture(User user, PetProfile pet, Place place, String checkinToken) {
    }

    private Fixture checkedIn() throws Exception {
        User u = newUser();
        PetProfile pet = petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        Place p = places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""), "Kopi Kucing",
                PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE), LAT, LNG, "Jl. Test", null, u.getId(), "Jakarta"));
        String body = mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + pet.getId() + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Fixture(u, pet, p, json.readTree(body).get("checkinToken").asText());
    }

    private String timeline(User u, boolean supports) throws Exception {
        var req = get("/api/v1/pet-profiles/me/timeline").header("Authorization", userBearer(u.getId()));
        if (supports) {
            req = req.param("supports", "place_checkin");
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void withoutSupportsResponsesAreUnchangedAndWithSupportsTheBannerAppears() throws Exception {
        Fixture f = checkedIn();
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        assertThat(timeline(f.user(), false)).doesNotContain("PLACE_CHECKIN").doesNotContain("checkinPlace");
        assertThat(mvc.perform(get("/api/v1/pet-profiles/me/day").param("date", today)
                        .header("Authorization", userBearer(f.user().getId())))
                .andReturn().getResponse().getContentAsString()).doesNotContain("PLACE_CHECKIN");
        String cal = mvc.perform(get("/api/v1/pet-profiles/me/calendar")
                        .param("year", today.substring(0, 4)).param("month", String.valueOf(Integer.parseInt(today.substring(5, 7))))
                        .header("Authorization", userBearer(f.user().getId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(cal).doesNotContain("hasPlaceCheckin");

        assertThat(timeline(f.user(), true)).contains("PLACE_CHECKIN_BANNER").contains(f.place().getPublicToken());
    }

    @Test
    void growthPostLinkedToTheCheckinSuppressesTheBannerUntilDeleted() throws Exception {
        Fixture f = checkedIn();
        String post = mvc.perform(post("/api/v1/content-posts").header("Authorization", userBearer(f.user().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GROWTH_MOMENT\",\"petId\":" + f.pet().getId()
                                + ",\"text\":\"Ngopi\",\"placeCheckinToken\":\"" + f.checkinToken() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long postId = json.readTree(post).get("id").asLong();

        assertThat(timeline(f.user(), true)).doesNotContain("PLACE_CHECKIN_BANNER");

        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", postId);
        assertThat(timeline(f.user(), true)).contains("PLACE_CHECKIN_BANNER");
    }

    @Test
    void momentPostLinkedToTheCheckinDoesNotSuppress() throws Exception {
        Fixture f = checkedIn();
        mvc.perform(post("/api/v1/content-posts").header("Authorization", userBearer(f.user().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DAILY\",\"text\":\"Ngopi\",\"placeCheckinToken\":\"" + f.checkinToken() + "\"}"))
                .andExpect(status().isCreated());
        assertThat(timeline(f.user(), true)).contains("PLACE_CHECKIN_BANNER");
    }

    @Test
    void visitorEndpointsNeverCarryCheckins() throws Exception {
        Fixture f = checkedIn();
        String shared = mvc.perform(get("/api/v1/public/shared-pets/" + f.pet().getCardToken() + "/timeline")
                        .param("supports", "place_checkin"))
                .andReturn().getResponse().getContentAsString();
        assertThat(shared).doesNotContain("PLACE_CHECKIN").doesNotContain("checkinPlace");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String base = "/api/v1/public/shared-pets/" + f.pet().getCardToken();
        assertThat(mvc.perform(get(base + "/day").param("date", today).param("supports", "place_checkin"))
                .andReturn().getResponse().getContentAsString()).doesNotContain("PLACE_CHECKIN");
        String cal = mvc.perform(get(base + "/calendar").param("year", today.substring(0, 4))
                        .param("month", String.valueOf(Integer.parseInt(today.substring(5, 7))))
                        .param("supports", "place_checkin"))
                .andReturn().getResponse().getContentAsString();
        assertThat(cal).doesNotContain("hasPlaceCheckin").doesNotContain("\"day\":" + Integer.parseInt(today.substring(8)));
        String inApp = mvc.perform(get("/api/v1/pets/" + f.pet().getId() + "/visitor/timeline")
                        .param("supports", "place_checkin").header("Authorization", userBearer(newUser().getId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(inApp).doesNotContain("PLACE_CHECKIN").doesNotContain("checkinPlace");
    }

    @Test
    void rebuiltPetDoesNotInheritOldCheckins() throws Exception {
        Fixture f = checkedIn();
        profileDeletion.deleteByUserId(f.user().getId());
        petProfiles.save(PetProfile.create(f.user().getId(), PetType.CAT, "Momo2", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        assertThat(timeline(f.user(), true)).doesNotContain("PLACE_CHECKIN_BANNER");
    }
}
