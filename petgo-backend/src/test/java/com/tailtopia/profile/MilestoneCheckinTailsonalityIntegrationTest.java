package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.KeepsakeGranterRegistry;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.service.TailsonalityKeepsakeGranter;
import com.tailtopia.tailsonality.service.TailsonalityMatchKeepsakeGranter;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * V1.3.4 Story 4.2 · AC6 · L1（需 postgres + redis）：真接口打卡 / PawCoin 解锁完整解读后，
 * 打卡项 / 性格测试项异步自动完成（{@code celebratedAt} 留空）；配型单买、只答题不解锁不算；
 * 已完成再触发幂等。
 *
 * <p>注册表照 {@code TailsonalityUnlockIntegrationTest} 用 {@link MockitoBean} 顶掉，但指向<b>真实</b>发放口
 * （真置 {@code unlocked_at} / {@code match_unlocked_at}、真发 {@code KeepsakeUnlockedEvent}）。
 */
class MilestoneCheckinTailsonalityIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;
    private static final String TS_BASE = "/api/v1/pet-profiles/me/tailsonality/results";

    @MockitoBean
    private KeepsakeGranterRegistry granters;

    @Autowired
    private TailsonalityKeepsakeGranter granter;
    @Autowired
    private TailsonalityMatchKeepsakeGranter matchGranter;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        when(granters.forSku(KeepsakeSku.TAILSONALITY)).thenReturn(granter);
        when(granters.forSku(KeepsakeSku.TS_MATCH)).thenReturn(matchGranter);
    }

    // ===== 造数 =====

    private PetProfile newCatWithRoster(long uid) {
        PetProfile pet = petProfiles.save(PetProfile.create(uid, PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        milestoneService.assignRoster(pet.getId(), pet.getPetType());
        return pet;
    }

    private Place newPlace(long marker) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, marker, "Jakarta"));
    }

    private void checkIn(User u, Place p, long petId) throws Exception {
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + petId + "]}"))
                .andExpect(status().isCreated());
    }

    private String submit(User u) throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":1")
                .collect(Collectors.joining(",", "{", "}"));
        String res = mvc.perform(post(TS_BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private ResultActions pawcoin(User u, String token, String action) throws Exception {
        return mvc.perform(post(TS_BASE + "/" + token + "/" + action).header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"PAWCOIN\"}"));
    }

    private void topUp(User u) {
        wallet.credit(u.getId(), 500_000L, PawCoinTxnType.TOPUP, "TEST", null, "ms42-topup:" + SEQ.incrementAndGet());
    }

    // ===== 读口 =====

    private int completionRows(long petId, String code) {
        return jdbc.queryForObject("SELECT count(*) FROM milestone_completions c "
                + "JOIN pet_milestones m ON m.id = c.pet_milestone_id "
                + "WHERE m.pet_profile_id = ? AND m.code = ?", Integer.class, petId, code);
    }

    private int smNotifications(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'MILESTONE_SM_NODE'", Integer.class, uid);
    }

    /** 接口视角：该 code 的条目。 */
    private JsonNode item(User u, String code) throws Exception {
        String res = mvc.perform(get("/api/v1/pet-profiles/me/milestones").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode g : json.readTree(res).get("groups")) {
            for (JsonNode it : g.get("items")) {
                if (code.equals(it.get("code").asText())) {
                    return it;
                }
            }
        }
        throw new AssertionError("清单里没有 " + code);
    }

    /** 轮询至条件满足（≤5 秒、每 100ms），照 CardPageRenderIntegrationTest.renderUntil。 */
    private static boolean until(BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    // ===== 打卡 =====

    @Test
    void checkinCompletesCS17OnceAndRepeatIsIdempotent() throws Exception {
        User u = newUser();
        PetProfile pet = newCatWithRoster(u.getId());
        int notesBefore = smNotifications(u.getId());

        checkIn(u, newPlace(u.getId()), pet.getId());

        assertThat(until(() -> completionRows(pet.getId(), "C-S17") == 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT c.source FROM milestone_completions c JOIN pet_milestones m "
                + "ON m.id = c.pet_milestone_id WHERE m.pet_profile_id = ? AND m.code = 'C-S17'",
                String.class, pet.getId())).isEqualTo("SYSTEM_AUTO");
        assertThat(until(() -> smNotifications(u.getId()) == notesBefore + 1)).isTrue();
        JsonNode it = item(u, "C-S17");
        assertThat(it.get("completed").asBoolean()).isTrue();
        assertThat(it.hasNonNull("celebratedAt")).isFalse();

        // 「次日」在另一场所再打卡：把上一条挪到昨天（绕开每日唯一），已完成 → 幂等跳过。
        jdbc.update("UPDATE place_checkins SET visit_date = visit_date - 1 WHERE user_id = ?", u.getId());
        jdbc.update("UPDATE place_checkin_pets SET visit_date = visit_date - 1 WHERE pet_profile_id = ?",
                pet.getId());
        checkIn(u, newPlace(u.getId()), pet.getId());
        Thread.sleep(1500);
        assertThat(completionRows(pet.getId(), "C-S17")).isEqualTo(1);
        assertThat(smNotifications(u.getId())).isEqualTo(notesBefore + 1);
    }

    // ===== Tailsonality =====

    @Test
    void matchOnlyDoesNotCountButUpgradeDoesAndRetestIsIdempotent() throws Exception {
        User u = newUser();
        PetProfile pet = newCatWithRoster(u.getId());
        topUp(u);
        String token = submit(u);

        // 配型单买 → 不算。
        pawcoin(u, token, "match-unlock").andExpect(status().isOk());
        Thread.sleep(1500);
        assertThat(completionRows(pet.getId(), "C-S18")).isZero();

        // 补差价升级（同一 TAILSONALITY SKU）→ 完成。
        pawcoin(u, token, "unlock").andExpect(status().isOk());
        assertThat(until(() -> completionRows(pet.getId(), "C-S18") == 1)).isTrue();
        JsonNode it = item(u, "C-S18");
        assertThat(it.get("completed").asBoolean()).isTrue();
        assertThat(it.hasNonNull("celebratedAt")).isFalse();

        // 重测再解锁新结果 → 已完成，幂等。
        String retest = submit(u);
        pawcoin(u, retest, "unlock").andExpect(status().isOk());
        Thread.sleep(1500);
        assertThat(completionRows(pet.getId(), "C-S18")).isEqualTo(1);
    }

    @Test
    void answeringWithoutUnlockDoesNotComplete() throws Exception {
        User u = newUser();
        PetProfile pet = newCatWithRoster(u.getId());

        submit(u);
        Thread.sleep(1500);
        assertThat(completionRows(pet.getId(), "C-S18")).isZero();
    }
}
