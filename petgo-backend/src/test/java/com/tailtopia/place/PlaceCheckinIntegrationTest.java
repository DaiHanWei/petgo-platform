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
 * V1.3.2 batch-a Story 1.1 · L1（需 postgres + redis）：打卡接口真跑 + 删档级联（AC2 / AC3 / AC6.1）。
 */
class PlaceCheckinIntegrationTest extends ApiIntegrationTest {

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

    private Place newPlace(long marker) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, marker, "Jakarta"));
    }

    private PetProfile newPet(long uid) {
        return petProfiles.save(PetProfile.create(uid, PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
    }

    private String body(double lat, long petId) {
        return "{\"latitude\":" + lat + ",\"longitude\":" + LNG + ",\"petIds\":[" + petId + "]}";
    }

    @Test
    void checkInThenSecondTimeTheSameDayIs409AndDetailFlagsIt() throws Exception {
        User u = newUser();
        PetProfile pet = newPet(u.getId());
        Place p = newPlace(u.getId());

        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT, pet.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isNewStamp").value(true))
                .andExpect(jsonPath("$.visitCount").value(1))
                .andExpect(jsonPath("$.placeToken").value(p.getPublicToken()));

        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT, pet.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/checkin-already-today"));

        mvc.perform(get("/api/v1/places/" + p.getPublicToken()).header("Authorization", userBearer(u.getId())))
                .andExpect(jsonPath("$.checkedInToday").value(true));
        mvc.perform(get("/api/v1/places/" + p.getPublicToken()))
                .andExpect(jsonPath("$.checkedInToday").doesNotExist());
    }

    @Test
    void tooFarIs422WithoutDistance() throws Exception {
        User u = newUser();
        PetProfile pet = newPet(u.getId());
        Place p = newPlace(u.getId());

        String res = mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT + 0.01, pet.getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/checkin-too-far"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("distance");
    }

    @Test
    void guestIsRejected() throws Exception {
        Place p = newPlace(newUser().getId());
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT, 1L)))
                .andExpect(status().isUnauthorized());
    }

    /** AC6.1：删档后两表无残留，同一天重建宠物可在同一场所再次打卡。 */
    @Test
    void profileDeletionCascadesAndRebuiltPetCanCheckInAgainTheSameDay() throws Exception {
        User u = newUser();
        PetProfile pet = newPet(u.getId());
        Place p = newPlace(u.getId());
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT, pet.getId())))
                .andExpect(status().isCreated());

        profileDeletion.deleteByUserId(u.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_checkin_pets WHERE pet_profile_id = ?",
                Long.class, pet.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_checkins WHERE user_id = ?",
                Long.class, u.getId())).isZero();

        PetProfile rebuilt = newPet(u.getId());
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(LAT, rebuilt.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isNewStamp").value(true));
    }
}
