package com.tailtopia.passport;

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
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.3.2 batch-a Story 1.2 · L1（需 postgres + redis）：护照签发 / 章聚合 / 删档（AC1 / AC2 / AC3.4 / AC7.1）。
 */
class PetPassportIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vets;

    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private IdCardRepository idCards;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private JdbcTemplate jdbc;

    private Place newPlace(long marker) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, marker, "Jakarta"));
    }

    private PetProfile newPet(long uid, PetType type) {
        return petProfiles.save(PetProfile.create(uid, type, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
    }

    private void checkIn(long uid, Place p, long petId) throws Exception {
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + petId + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passportNo").isNotEmpty())
                .andExpect(jsonPath("$.stampCount").isNumber());
    }

    private String passportNo(long uid) throws Exception {
        String body = mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("passportNo").asText();
    }

    @Test
    void getIssuesOnceAndReusesKtpNumberWithoutTouchingIdCards() throws Exception {
        User u = newUser();
        newPet(u.getId(), PetType.CAT);
        String ktpNo = "TT02P26" + String.format("%05d", SEQ.incrementAndGet() % 100000);
        idCards.save(IdCard.snapshot(u.getId(), SEQ.incrementAndGet() + 900000000L, "Momo", "CAT", null, null,
                null, null, "UNKNOWN", null, ktpNo, null, null, null, null, "KTP", null, null));
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM id_cards WHERE user_id = ?", u.getId());

        assertThat(passportNo(u.getId())).isEqualTo(ktpNo);
        assertThat(passportNo(u.getId())).isEqualTo(ktpNo);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_passports pp JOIN pet_profiles p "
                + "ON p.id = pp.pet_profile_id WHERE p.owner_id = ?", Long.class, u.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM pet_passports pp JOIN pet_profiles p "
                + "ON p.id = pp.pet_profile_id WHERE p.owner_id = ?", String.class, u.getId())).isEqualTo("KTP");
        // 🔴 只读 id_cards：签发前后逐字段不变。
        assertThat(jdbc.queryForList("SELECT * FROM id_cards WHERE user_id = ?", u.getId())).isEqualTo(before);
    }

    @Test
    void ktpOfAnotherSpeciesIsNotReused() throws Exception {
        User u = newUser();
        newPet(u.getId(), PetType.DOG);
        idCards.save(IdCard.snapshot(u.getId(), SEQ.incrementAndGet() + 900000000L, "Momo", "CAT", null, null,
                null, null, "UNKNOWN", null, "TT02P26" + String.format("%05d", SEQ.incrementAndGet() % 100000),
                null, null, null, null, "KTP", null, null));

        assertThat(passportNo(u.getId())).startsWith("TT01P");
    }

    @Test
    void mergedPlacesCollapseIntoOneStampAndDelistedStampStays() throws Exception {
        User u = newUser();
        PetProfile pet = newPet(u.getId(), PetType.CAT);
        Place a = newPlace(u.getId());
        Place b = newPlace(u.getId());
        Place c = newPlace(u.getId());
        checkIn(u.getId(), a, pet.getId());
        checkIn(u.getId(), b, pet.getId());
        checkIn(u.getId(), c, pet.getId());

        // 模拟后台合并 b → a（改挂 place_id）+ 下架 c。
        jdbc.update("UPDATE place_checkins SET place_id = ? WHERE place_id = ?", a.getId(), b.getId());
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", a.getId(), b.getId());
        jdbc.update("UPDATE places SET status = 'DELISTED' WHERE id = ?", c.getId());

        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stampCount").value(2))
                .andExpect(jsonPath("$.stamps[0].placeToken").value(a.getPublicToken()))
                .andExpect(jsonPath("$.stamps[0].visitCount").value(2))
                .andExpect(jsonPath("$.stamps[0].addressText").value("Jl. Test"))
                .andExpect(jsonPath("$.stamps[1].placeStatus").value("UNAVAILABLE"))
                // Story 1.3 · AC2：下架场所的章不带地址。
                .andExpect(jsonPath("$.stamps[1].addressText").doesNotExist());
    }

    @Test
    void noPetIs404AndVetTokenIsForbidden() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNotFound());
        // 兽医 token 须对应 DB 里真实 ACTIVE 的兽医行：BannedVetFilter 对查无此兽医的 token 回 401，
        // 拿普通用户 id 冒充兽医 id 测不到「角色不对 → 403」这条（L1 本地验收 2026-10-05 发现）。
        long vetId = vets.newActiveVet("passport-it").getId();
        mvc.perform(get("/api/v1/pet-profiles/me/passport").header("Authorization", vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletionRemovesPassportAndRebuiltPetGetsANewIssuedOne() throws Exception {
        User u = newUser();
        newPet(u.getId(), PetType.CAT);
        idCards.save(IdCard.snapshot(u.getId(), SEQ.incrementAndGet() + 900000000L, "Momo", "CAT", null, null,
                null, null, "UNKNOWN", null, "TT02P26" + String.format("%05d", SEQ.incrementAndGet() % 100000),
                null, null, null, null, "KTP", null, null));
        String first = passportNo(u.getId());

        profileDeletion.deleteByUserId(u.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_passports WHERE passport_no = ?",
                Long.class, first)).isZero();

        newPet(u.getId(), PetType.CAT);
        String second = passportNo(u.getId());
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT source FROM pet_passports WHERE passport_no = ?",
                String.class, second)).isEqualTo("ISSUED");
    }
}
