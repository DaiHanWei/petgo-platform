package com.tailtopia.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.share.repository.IdCardShareRewardRepository;
import com.tailtopia.share.service.IdCardShareRewardService;
import com.tailtopia.share.service.ShareRewardService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：身份证卡面分享奖励的三层控制与去重（Story 18.2）。
 *
 * <p>🔴 本类最要紧的两条是 AC4：**按档案去重**（不按卡）与**未绑档案不发**。
 * {@code createCard()} 无数量限制且不要求档案 ⇒ 按卡去重等于无去重。
 */
class IdCardShareRewardIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private IdCardShareRewardService service;

    @Autowired
    private IdCardShareRewardRepository rewards;

    @Autowired
    private IdCardRepository cards;

    @Autowired
    private PawCoinConfigRepository configs;

    @Autowired
    private PawCoinWalletService wallet;

    @Autowired
    private ShareRewardService shareReward;

    @Autowired
    private com.tailtopia.profile.repository.PetProfileRepository pets;

    @Autowired
    private com.tailtopia.profile.service.CardTokenGenerator tokens;

    private long newPetProfile(long ownerId) {
        return pets.save(com.tailtopia.profile.domain.PetProfile.create(ownerId,
                com.tailtopia.profile.domain.PetType.DOG, "分享测试宠", "http://a/x.jpg", "柴犬",
                null, null, tokens.generate())).getId();
    }

    private Long petProfileIdOf(long userId) {
        return pets.findByOwnerId(userId).orElseThrow().getId();
    }

    private Long savedReward;
    private Integer savedDaily;
    private Long savedMonthly;
    private Boolean savedEnabled;

    private void configure(boolean enabled, long monthlyCap, long perShare, int dailyCap) {
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
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
        configs.saveAndFlush(c);
    }

    /** 🛡 单行配置表全局共享，测试库不回滚 —— 不还原会污染同一次 run 里的其它测试类。 */
    @AfterEach
    void restore() {
        if (savedReward == null) {
            return;
        }
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setIdCardShareReward(savedReward);
        c.setIdCardShareDailyCap(savedDaily);
        c.setShareRewardMonthlyCap(savedMonthly);
        c.setShareRewardEnabled(savedEnabled);
        configs.saveAndFlush(c);
        savedReward = null;
    }

    private IdCard card(long userId) {
        return cards.save(IdCard.snapshot(userId, SEQ.incrementAndGet() + 900000000L,
                "分享测试", "DOG", null, null, null, null, "UNKNOWN", null, null, null, null,
                null, null, "KTP", null, null));
    }

    // ── 🔴 AC4：按档案去重，不按卡 ──────────────────────────────────

    @Test
    void firstShareOfAProfileIsRewarded() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 3);

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(200);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(200);
    }

    @Test
    void secondShareOfTheSameProfileIsNotRewarded() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 3);
        IdCard c = card(u.getId());

        assertThat(service.rewardAfterShare(u.getId(), c.getId(), Instant.now())).isEqualTo(200);
        assertThat(service.rewardAfterShare(u.getId(), c.getId(), Instant.now()))
                .as("同一档案第二次不该再发").isEqualTo(0);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(200);
    }

    /**
     * 🔴 这条是 AC4 的要害：**同一档案下的第二张卡**分享也不发。
     *
     * <p>createCard() 无数量限制 ⇒ 按卡去重的话，用户建 100 张卡就能领 100 次。
     */
    @Test
    void aSecondCardUnderTheSameProfileIsNotRewardedEither() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 3);

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(200);
        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .as("🔴 换一张卡就能再领 = 按卡去重 = 无去重").isEqualTo(0);
        assertThat(rewards.findByPetProfileId(
                java.util.Objects.requireNonNull(profileIdOf(u.getId())))).isPresent();
    }

    /** 🛡 未绑档案的独立建卡不发奖励 —— 无档案的卡可无限造，是刷量直接入口。 */
    @Test
    void cardWithoutAPetProfileIsNeverRewarded() {
        User u = newUser(); // 刻意不建档案
        configure(true, 10000, 200, 3);

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(0);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(0);
    }

    /** 🛡 拿别人的卡 id 来上报 → 不发（否则可以用别人的卡刷自己的奖励）。 */
    @Test
    void sharingSomeoneElsesCardIsNotRewarded() {
        User owner = newUser();
        User attacker = newUser();
        newPetProfile(owner.getId());
        newPetProfile(attacker.getId());
        configure(true, 10000, 200, 3);
        IdCard theirs = card(owner.getId());

        assertThat(service.rewardAfterShare(attacker.getId(), theirs.getId(), Instant.now()))
                .isEqualTo(0);
        assertThat(wallet.balanceOf(attacker.getId())).isEqualTo(0);
    }

    // ── AC3 第二层：渠道日上限 ───────────────────────────────────

    /**
     * 🛡 日上限配成 0 → 不发。
     *
     * <p>⚠️ 日上限对本渠道其实是**冗余的保险**（档案去重已经更强），
     * 这条测的是那一层真的在起作用 —— 后续渠道接入时靠的就是它。
     */
    @Test
    void zeroDailyCapBlocksTheReward() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 0);

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(0);
    }

    @Test
    void dailyCapCountsPerWibLocalDay() {
        Instant lateWib = ZonedDateTime.of(2026, 8, 31, 23, 30, 0, 0, WIB).toInstant();
        assertThat(IdCardShareRewardService.shareDateOf(lateWib))
                .isEqualTo(LocalDate.of(2026, 8, 31));
        // 同一绝对时刻在 UTC 下还是 8-31 的下午 —— 但换到 WIB 次日凌晨就该是新的一天。
        Instant earlyNextWib = ZonedDateTime.of(2026, 9, 1, 0, 30, 0, 0, WIB).toInstant();
        assertThat(IdCardShareRewardService.shareDateOf(earlyNextWib))
                .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    // ── AC3 第三层 + AC6：月度上限与总开关 ───────────────────────

    /** 🛡 总开关关闭 → 不发币，且**不留痕**（否则档案被标成已拿过，开关打开后再也拿不到）。 */
    @Test
    void masterSwitchOffGrantsNothingAndLeavesNoTrace() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(false, 10000, 200, 3);

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(0);
        assertThat(rewards.findByPetProfileId(profileIdOf(u.getId())))
                .as("🛡 没发成就不该留痕 —— 否则开关打开后这个档案再也拿不到了").isEmpty();

        // 开关打开后应该还能拿到。
        configure(true, 10000, 200, 3);
        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(200);
    }

    /** 🛡 月度额度不够 → 不发，同样不留痕。 */
    @Test
    void monthlyCapExhaustedGrantsNothingAndLeavesNoTrace() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 100, 200, 3); // 单次 200 > 月上限 100

        assertThat(service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now()))
                .isEqualTo(0);
        assertThat(rewards.findByPetProfileId(profileIdOf(u.getId()))).isEmpty();
    }

    /** 🛡 记账与实际余额必须一致（18.1 立的护栏，这里不能被破坏）。 */
    @Test
    void quotaLedgerStaysConsistentWithTheWallet() {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 3);

        service.rewardAfterShare(u.getId(), card(u.getId()).getId(), Instant.now());
        assertThat(shareReward.grantedThisMonth(u.getId())).isEqualTo(200);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(200);
    }

    // ── 🛡 AC7：并发只发一次 ────────────────────────────────────

    /**
     * 🛡 同档案并发上报只发一次，且**额度只被占一次**。
     *
     * <p>后半句钉的是 18.1 立的那条「记账与实际余额必须一致」不被这个渠道破坏。
     * ⚠️ 它由外层 REQUIRES_NEW 事务保证（撞唯一键整体回滚，已占额度一并退回），
     * <b>不是</b>由「先留痕后占额度」这个顺序保证 —— 两步对调后本类仍全绿，实测过。
     */
    @Test
    void concurrentSharesOfTheSameProfileRewardExactlyOnce() throws Exception {
        User u = newUser();
        newPetProfile(u.getId());
        configure(true, 10000, 200, 10);
        long cardId = card(u.getId()).getId();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long total = 0;
        try {
            List<Callable<Long>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> service.rewardAfterShare(u.getId(), cardId, Instant.now()));
            }
            for (Future<Long> f : pool.invokeAll(tasks)) {
                total += f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(total).as("并发只该发一次").isEqualTo(200);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(200);
        assertThat(shareReward.grantedThisMonth(u.getId()))
                .as("额度也只该被占一次（记账与余额必须一致）").isEqualTo(200);
    }

    private Long profileIdOf(long userId) {
        return petProfileIdOf(userId);
    }
}
