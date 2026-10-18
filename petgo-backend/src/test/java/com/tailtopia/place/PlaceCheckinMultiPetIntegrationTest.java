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
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 1.3 · AC4 · L1（需 postgres + redis）：逐宠物「今日已打卡」与逐宠物盖章。
 *
 * <p>库里仍有 {@code uq_pet_profiles_owner_id}（Story 1.4 才删）→ {@link MultiPetDbFixture} 即删即恢复。
 */
class PlaceCheckinMultiPetIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @Autowired
    private PlaceRepository places;
    @Autowired
    private JdbcTemplate jdbc;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();
    private long owner;
    private long petA;
    private long petB;
    private String auth;

    @BeforeEach
    void setUpPets() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        User u = newUser();
        owner = u.getId();
        auth = userBearer(owner);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        petA = MultiPetDbFixture.insertPet(jdbc, owner, "Aaa", "CAT", t0);
        petB = MultiPetDbFixture.insertPet(jdbc, owner, "Bbb", "DOG", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
    }

    @AfterEach
    void restore() {
        try {
            for (Long pet : extraPets) {
                jdbc.update("DELETE FROM place_checkin_pets WHERE pet_profile_id = ?", pet);
                jdbc.update("DELETE FROM pet_passports WHERE pet_profile_id = ?", pet);
            }
            jdbc.update("DELETE FROM place_checkins WHERE user_id = ?", owner);
        } finally {
            MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
        }
    }

    private Place newPlace() {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, owner, "Jakarta"));
    }

    private static String body(long... petIds) {
        StringBuilder ids = new StringBuilder();
        for (long id : petIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        return "{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + ids + "]}";
    }

    /** AC4.4 前半：都没打过，同时选 A、B → 一条打卡行 + 两条关联，两只各自护照、各自新章。 */
    @Test
    void bothPetsCheckInTogetherEachGetsAStamp() throws Exception {
        Place p = newPlace();

        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body(petA, petB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isNewStamp").value(true))
                .andExpect(jsonPath("$.pets.length()").value(2))
                .andExpect(jsonPath("$.pets[0].petId").value(petA))
                .andExpect(jsonPath("$.pets[0].isNewStamp").value(true))
                .andExpect(jsonPath("$.pets[1].petId").value(petB))
                .andExpect(jsonPath("$.pets[1].isNewStamp").value(true))
                .andExpect(jsonPath("$.pets[1].visitCount").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_checkins WHERE user_id = ? AND place_id = ?",
                Long.class, owner, p.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM place_checkin_pets WHERE pet_profile_id IN (?, ?)", Long.class, petA, petB))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pet_passports WHERE pet_profile_id IN (?, ?)", Long.class, petA, petB))
                .isEqualTo(2L);
        mvc.perform(get("/api/v1/places/" + p.getPublicToken()).header("Authorization", auth))
                .andExpect(jsonPath("$.checkedInToday").value(true))
                .andExpect(jsonPath("$.checkedInPetIds.length()").value(2));
    }

    /** AC4.4 后半：A 已打 → 状态 [A] / false；A + B 再打 → 409；只选 B → 成功；游客两键都省略。 */
    @Test
    void perPetTodayStatusAndConflict() throws Exception {
        Place p = newPlace();
        String url = "/api/v1/places/" + p.getPublicToken() + "/checkins";
        mvc.perform(post(url).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body(petA)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/places/" + p.getPublicToken()).header("Authorization", auth))
                .andExpect(jsonPath("$.checkedInToday").value(false))
                .andExpect(jsonPath("$.checkedInPetIds.length()").value(1))
                .andExpect(jsonPath("$.checkedInPetIds[0]").value(petA));
        mvc.perform(get("/api/v1/places/" + p.getPublicToken()))
                .andExpect(jsonPath("$.checkedInToday").doesNotExist())
                .andExpect(jsonPath("$.checkedInPetIds").doesNotExist());

        mvc.perform(post(url).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body(petA, petB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/checkin-already-today"));

        mvc.perform(post(url).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body(petB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pets[0].petId").value(petB))
                .andExpect(jsonPath("$.isNewStamp").value(true));

        mvc.perform(get("/api/v1/places/" + p.getPublicToken()).header("Authorization", auth))
                .andExpect(jsonPath("$.checkedInToday").value(true));
    }
}
