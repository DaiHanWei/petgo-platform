package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（V1.4.0 multi-pet Story 1.2 · AC1 / AC2 / AC3）：同一账号两只宠物 A（先建）/ B（后建），跨模块读口不 500、
 * 取对宠物；身份证分享两只各一次 + 账号月度上限；首页推荐多宠账号照常出页。
 *
 * <p>库里仍有 {@code uq_pet_profiles_owner_id}（Story 1.4 才删）→ 用 {@link MultiPetDbFixture} 类内即删即恢复。
 * 本类造出的派生行（护照 / Tailsonality / 问诊请求 / 分享留痕 / 身份证）在 {@code @AfterEach} 里先清，再删宠物。
 */
class MultiPetCrossModuleIntegrationTest extends ApiIntegrationTest {

    private static final String ME = "/api/v1/pet-profiles/me";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsultRequestRepository consultRequests;

    @Autowired
    private ConsultRequestService consultRequestService;

    @Autowired
    private AdminUserService adminUsers;

    @Autowired
    private IdCardRepository idCards;

    @Autowired
    private PawCoinConfigRepository pawcoinConfigs;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();
    private final List<Long> extraRequests = new ArrayList<>();
    private final List<Long> extraCards = new ArrayList<>();
    private long owner;
    private long petA;
    private long petB;
    private String auth;

    private Long savedReward;
    private Integer savedDaily;
    private Long savedMonthly;
    private Boolean savedEnabled;

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
            for (Long id : extraRequests) {
                consultRequests.deleteById(id);
            }
            for (Long pet : extraPets) {
                jdbc.update("DELETE FROM id_card_share_rewards WHERE pet_profile_id = ?", pet);
                jdbc.update("DELETE FROM tailsonality_badges WHERE pet_profile_id = ?", pet);
                jdbc.update("DELETE FROM tailsonality_results WHERE pet_profile_id = ?", pet);
                jdbc.update("DELETE FROM pet_passports WHERE pet_profile_id = ?", pet);
            }
            for (Long id : extraCards) {
                jdbc.update("DELETE FROM id_card_share_rewards WHERE card_id = ?", id);
                idCards.deleteById(id);
            }
            restorePawcoinConfig();
        } finally {
            MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
        }
    }

    // ── AC1：/me/* 带 petId → 该宠物；不带 → 默认宠物（A） ─────────────────────

    @Test
    void passportPageAndSnapshotsFollowPetId() throws Exception {
        mvc.perform(get(ME + "/passport").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.petName").value("Aaa"));
        mvc.perform(get(ME + "/passport").param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.petName").value("Bbb"));
        mvc.perform(get(ME + "/passport/snapshots").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(get(ME + "/boarding-passes").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.petName").value("Bbb"));
    }

    @Test
    void tailsonalityResultsStayOnTheirPetAndTokenReadNeedsNoPetId() throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":1")
                .collect(Collectors.joining(",", "{", "}"));
        String created = mvc.perform(post(ME + "/tailsonality/results").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(created).get("token").asText();

        mvc.perform(get(ME + "/tailsonality/results").param("petId", String.valueOf(petB))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(ME + "/tailsonality/results").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(ME + "/tailsonality/results/" + token).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        User other = newUser();
        mvc.perform(get(ME + "/tailsonality/results/" + token).header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void idCardEndpointsAddressByPetId() throws Exception {
        mvc.perform(get(ME + "/id-card").param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(post(ME + "/id-card").param("petId", String.valueOf(petB)).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generated").value(true));
        Long serialB = jdbc.queryForObject("SELECT serial_id FROM pet_profiles WHERE id = ?", Long.class, petB);
        Long serialA = jdbc.queryForObject("SELECT serial_id FROM pet_profiles WHERE id = ?", Long.class, petA);
        assertThat(serialB).isNotNull();
        assertThat(serialA).as("只给 B 分配流水号").isNull();
    }

    @Test
    void vetQueueShowsThePetOnTheRequest() {
        ConsultRequest r = consultRequests.save(ConsultRequest.queue(owner, petB,
                "mp" + Long.toHexString(SEQ.incrementAndGet()) + "q", Instant.now().plusSeconds(60)));
        extraRequests.add(r.getId());

        var queue = consultRequestService.vetQueue(Long.MAX_VALUE - 7);

        assertThat(queue.available()).filteredOn(i -> i.requestToken().equals(r.getRequestToken()))
                .singleElement().satisfies(i -> {
                    assertThat(i.petName()).isEqualTo("Bbb");
                    assertThat(i.petSpecies()).isEqualTo("DOG");
                });
    }

    @Test
    void adminUserDetailListsAllPets() {
        var detail = adminUsers.detail(owner);
        assertThat(detail.pets()).extracting(p -> p.id()).containsExactly(petA, petB);
    }

    // ── AC2：身份证分享奖励每只宠物各一次 + 账号级月度上限 ─────────────────────────

    @Test
    void idCardShareRewardOncePerPet() throws Exception {
        configurePawcoin(true, 10_000, 200, 10);
        long cardId = newCard();

        assertThat(shareCoins(cardId, petA)).isEqualTo(200);
        assertThat(shareCoins(cardId, petB)).as("另一只宠物各一次").isEqualTo(200);
        assertThat(shareCoins(cardId, petA)).as("同一只第二次").isZero();
        User other = newUser();
        long foreign = MultiPetDbFixture.insertPet(jdbc, other.getId(), "Zzz", "CAT", Instant.now());
        extraPets.add(foreign);
        mvc.perform(post(ME + "/id-cards/" + cardId + "/share-rewards").param("petId", String.valueOf(foreign))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void idCardShareRewardRespectsAccountMonthlyCap() throws Exception {
        configurePawcoin(true, 200, 200, 10); // 账号当月只装得下一次
        long cardId = newCard();

        assertThat(shareCoins(cardId, petA)).isEqualTo(200);
        assertThat(shareCoins(cardId, petB)).as("换宠物不绕过账号月度上限").isZero();
    }

    // ── AC3：多宠账号首页推荐照常出页、不抛异常 ───────────────────────────────

    @Test
    void homeFeedWorksForMultiPetViewer() throws Exception {
        mvc.perform(get("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private long shareCoins(long cardId, long petId) throws Exception {
        String res = mvc.perform(post(ME + "/id-cards/" + cardId + "/share-rewards").param("petId", String.valueOf(petId))
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("coins").asLong();
    }

    private long newCard() {
        IdCard c = idCards.save(IdCard.snapshot(owner, SEQ.incrementAndGet() + 900000000L,
                "多宠分享", "DOG", null, null, null, null, "UNKNOWN", null, null, null, null,
                null, null, "KTP", null, null, null));
        extraCards.add(c.getId());
        return c.getId();
    }

    private void configurePawcoin(boolean enabled, long monthlyCap, long perShare, int dailyCap) {
        PawCoinConfig c = pawcoinConfigs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        if (savedReward == null) {
            savedReward = c.getIdCardShareReward();
            savedDaily = c.getIdCardShareDailyCap();
            savedMonthly = c.getShareRewardMonthlyCap();
            savedEnabled = c.isShareRewardEnabled();
        }
        c.setShareRewardEnabled(enabled);
        c.setShareRewardMonthlyCap(monthlyCap);
        c.setIdCardShareReward(perShare);
        c.setIdCardShareDailyCap(dailyCap);
        pawcoinConfigs.saveAndFlush(c);
    }

    /** 🛡 单行配置表全局共享，测试库不回滚 —— 不还原会污染同一次 run 里的其它测试类。 */
    private void restorePawcoinConfig() {
        if (savedReward == null) {
            return;
        }
        PawCoinConfig c = pawcoinConfigs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setIdCardShareReward(savedReward);
        c.setIdCardShareDailyCap(savedDaily);
        c.setShareRewardMonthlyCap(savedMonthly);
        c.setShareRewardEnabled(savedEnabled);
        pawcoinConfigs.saveAndFlush(c);
        savedReward = null;
    }
}
