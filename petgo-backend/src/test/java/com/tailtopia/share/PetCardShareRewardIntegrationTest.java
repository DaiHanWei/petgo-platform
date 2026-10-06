package com.tailtopia.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.share.service.PassportShareRewardService;
import com.tailtopia.share.service.ShareRewardDeletionService;
import com.tailtopia.share.service.TailsonalityShareRewardService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.3.2 batch-a Story 4.5 · L1（需 postgres + redis）：Tailsonality / 护照两渠道分享奖励。
 *
 * <p>覆盖 AC2.4 清单：首次发、同卡类型二次不发、另一卡类型再发一次、重测后不发、带水印（接口无差别）照发、
 * 日上限、月度上限、总开关关、渠道额 0 不发、无资格不发、并发两次只发一次、发放失败不留痕；
 * AC3 接口（只收 cardType、非法值 422、只回 coins）；AC5 删档置空 / 重建可再领 / 注销物理删除。
 */
class PetCardShareRewardIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = -6.2351;
    private static final double LNG = 106.8101;

    @Autowired
    private TailsonalityShareRewardService tailsonality;
    @Autowired
    private PassportShareRewardService passport;
    @Autowired
    private PawCoinConfigRepository configs;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private ShareRewardDeletionService shareDeletion;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;

    private PawCoinConfig saved;

    private void configure(boolean enabled, long monthlyCap, long perShare, int dailyCap) {
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        if (saved == null) {
            saved = c;
            savedValues = new long[] {c.isShareRewardEnabled() ? 1 : 0, c.getShareRewardMonthlyCap(),
                c.getTailsonalityShareReward(), c.getTailsonalityShareDailyCap(),
                c.getPassportShareReward(), c.getPassportShareDailyCap()};
        }
        c.setShareRewardEnabled(enabled);
        c.setShareRewardMonthlyCap(monthlyCap);
        c.setTailsonalityShareReward(perShare);
        c.setTailsonalityShareDailyCap(dailyCap);
        c.setPassportShareReward(perShare);
        c.setPassportShareDailyCap(dailyCap);
        configs.saveAndFlush(c);
    }

    private long[] savedValues;

    /** 🛡 单行配置表全局共享、测试库不回滚 —— 不还原会污染同一次 run 里的其它测试类。 */
    @AfterEach
    void restore() {
        if (saved == null) {
            return;
        }
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setShareRewardEnabled(savedValues[0] == 1);
        c.setShareRewardMonthlyCap(savedValues[1]);
        c.setTailsonalityShareReward(savedValues[2]);
        c.setTailsonalityShareDailyCap((int) savedValues[3]);
        c.setPassportShareReward(savedValues[4]);
        c.setPassportShareDailyCap((int) savedValues[5]);
        configs.saveAndFlush(c);
        saved = null;
    }

    private User userWithPet() {
        User u = newUser();
        pets.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, "Anggora", null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return u;
    }

    private void submitResult(User u, int idx) throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":" + idx)
                .collect(Collectors.joining(",", "{", "}"));
        mvc.perform(post("/api/v1/pet-profiles/me/tailsonality/results").header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    private void setOwnerType(User u) throws Exception {
        mvc.perform(put("/api/v1/me/tailsonality/owner-type").header("Authorization", userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"INFP\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private void checkIn(User u) throws Exception {
        Place p = places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Taman " + SEQ.incrementAndGet(), PlaceType.PARK, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                LAT, LNG, "Jl. Test", null, 1L, "Jakarta"));
        long petId = pets.findByOwnerId(u.getId()).orElseThrow().getId();
        mvc.perform(post("/api/v1/places/" + p.getPublicToken() + "/checkins")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG + ",\"petIds\":[" + petId + "]}"))
                .andExpect(status().isCreated());
    }

    private long ts(User u, TailsonalityShareRewardService.CardType t) {
        return tailsonality.rewardAfterShare(u.getId(), t, Instant.now());
    }

    private long pp(User u, PassportShareRewardService.CardType t) {
        return passport.rewardAfterShare(u.getId(), t, Instant.now());
    }

    private long rows(String table, long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    // ── Tailsonality ─────────────────────────────────────────────

    @Test
    void resultFirstShareRewardedSecondNotMatchOnceMoreRetakeNotAgain() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 100, 10);
        submitResult(u, 0);
        setOwnerType(u);

        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).isEqualTo(100);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("同卡类型二次不发").isZero();
        assertThat(ts(u, TailsonalityShareRewardService.CardType.MATCH)).as("另一卡类型再发一次").isEqualTo(100);
        submitResult(u, 3); // 重测
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("重测后不发（键是宠物不是结果）").isZero();
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(200);
    }

    @Test
    void noEligibilityNoReward() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 100, 10);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("没有结果不发").isZero();
        submitResult(u, 0);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.MATCH)).as("未设主人类型不发配型卡").isZero();
        assertThat(pp(u, PassportShareRewardService.CardType.PAGE)).as("没有章不发护照卡").isZero();
        assertThat(pp(u, PassportShareRewardService.CardType.BOARDING)).as("没有打卡不发登机牌卡").isZero();
        User noPet = newUser();
        assertThat(tailsonality.rewardAfterShare(noPet.getId(), TailsonalityShareRewardService.CardType.RESULT,
                Instant.now())).as("无档案不发").isZero();
        assertThat(rows("tailsonality_share_rewards", u.getId())).isZero();
    }

    @Test
    void dailyCapMonthlyCapSwitchAndZeroAmountAllBlockWithoutTrace() throws Exception {
        User u = userWithPet();
        submitResult(u, 0);
        setOwnerType(u);
        checkIn(u);

        configure(false, 10_000, 100, 10);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("总开关关").isZero();
        configure(true, 10_000, 0, 10);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("渠道额 0").isZero();
        assertThat(rows("tailsonality_share_rewards", u.getId())).as("没发成不留痕").isZero();

        configure(true, 10_000, 100, 1);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).isEqualTo(100);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.MATCH)).as("渠道日上限 1").isZero();

        configure(true, 150, 100, 10);
        assertThat(pp(u, PassportShareRewardService.CardType.PAGE)).as("月度上限（已发 100，再发 100 超 150）").isZero();
        assertThat(rows("passport_share_rewards", u.getId())).as("月度拦下不留痕").isZero();
    }

    // ── 护照 / 登机牌 ─────────────────────────────────────────────

    @Test
    void pageAndBoardingEachOnceBoardingNotPerPlace() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 50, 10);
        checkIn(u);
        checkIn(u);

        assertThat(pp(u, PassportShareRewardService.CardType.PAGE)).isEqualTo(50);
        assertThat(pp(u, PassportShareRewardService.CardType.BOARDING)).isEqualTo(50);
        assertThat(pp(u, PassportShareRewardService.CardType.BOARDING)).as("登机牌整体一个类型，不按张计").isZero();
        assertThat(pp(u, PassportShareRewardService.CardType.PAGE)).isZero();
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(100);
    }

    @Test
    void concurrentSharesRewardExactlyOnce() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 70, 10);
        checkIn(u);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long total = 0;
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> pp(u, PassportShareRewardService.CardType.PAGE));
            }
            for (Future<Long> f : pool.invokeAll(calls)) {
                total += f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(total).isEqualTo(70);
        assertThat(rows("passport_share_rewards", u.getId())).isEqualTo(1);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(70);
    }

    // ── 接口 ─────────────────────────────────────────────────────

    @Test
    void endpointsAcceptOnlyCardTypeAndReturnOnlyCoins() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 30, 10);
        submitResult(u, 0);
        // 带水印与否接口无差别：结果未解锁照样发。
        mvc.perform(post("/api/v1/pet-profiles/me/tailsonality/share-rewards")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardType\":\"RESULT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coins").value(30))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/pet-profiles/me/tailsonality/share-rewards")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardType\":\"PAGE\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/pet-profiles/me/passport/share-rewards")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardType\":\"RESULT\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/pet-profiles/me/passport/share-rewards")
                        .header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardType\":\"BOARDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coins").value(0));
    }

    // ── AC5 删档 / 注销 ───────────────────────────────────────────

    @Test
    void petDeletionNullsPetIdRebuiltPetMayClaimAgainAccountDeletionRemovesRows() throws Exception {
        User u = userWithPet();
        configure(true, 10_000, 20, 10);
        submitResult(u, 0);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).isEqualTo(20);

        new TransactionTemplate(txManager).executeWithoutResult(s -> profileDeletion.deleteByUserId(u.getId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tailsonality_share_rewards "
                + "WHERE user_id = ? AND pet_profile_id IS NULL", Long.class, u.getId())).isEqualTo(1);

        pets.save(PetProfile.create(u.getId(), PetType.DOG, "Baru", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        submitResult(u, 1);
        assertThat(ts(u, TailsonalityShareRewardService.CardType.RESULT)).as("重建宠物后可再领一次（AD-17 已接受）")
                .isEqualTo(20);

        shareDeletion.deleteByUserId(u.getId());
        assertThat(rows("tailsonality_share_rewards", u.getId())).isZero();
        assertThat(rows("passport_share_rewards", u.getId())).isZero();
    }
}
