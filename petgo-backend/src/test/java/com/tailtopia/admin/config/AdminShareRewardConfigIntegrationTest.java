package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.config.dto.ShareRewardOverview;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.share.service.ShareRewardService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：分享奖励后台配置（Story 18.3 · AB-3M）。
 *
 * <p>🛡 所有 POST 都带 {@code .with(csrf())}（AC5）—— {@code /admin/**} 保留 CSRF。
 */
class AdminShareRewardConfigIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private PawCoinConfigRepository configs;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private ShareRewardService shareReward;

    private Long savedCap;
    private Long savedReward;
    private Integer savedDaily;
    private Boolean savedEnabled;

    private void snapshot() {
        if (savedCap != null) {
            return;
        }
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        savedCap = c.getShareRewardMonthlyCap();
        savedReward = c.getIdCardShareReward();
        savedDaily = c.getIdCardShareDailyCap();
        savedEnabled = c.isShareRewardEnabled();
    }

    /** 🛡 单行配置表全局共享、测试库不回滚 —— 不还原会污染同一次 run 的其它测试类。 */
    @AfterEach
    void restore() {
        if (savedCap == null) {
            return;
        }
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setShareRewardMonthlyCap(savedCap);
        c.setIdCardShareReward(savedReward);
        c.setIdCardShareDailyCap(savedDaily);
        c.setShareRewardEnabled(savedEnabled);
        configs.saveAndFlush(c);
        savedCap = null;
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "sharecfg-" + n + "@tailtopia.test", "分享奖励超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null,
                new java.util.ArrayList<>(p.getAuthorities()));
    }

    /**
     * 带指定权限码的 STAFF。
     *
     * <p>⚠️ authorities 必须走 {@link AdminUserDetails#getAuthorities()} —— {@code /admin/**}
     * 在 URL 层就要 {@code ROLE_ADMIN}。只塞权限码的话每个请求都 403，
     * 而 403 恰好是权限断言想要的结果 ⇒ 所有权限测试都会**因为错误的原因通过**
     * （17.2 已经踩过一次，这里照修好的写法来）。
     */
    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "sharecfg-op-" + n + "@tailtopia.test", "分享奖励运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null,
                new java.util.ArrayList<>(p.getAuthorities()));
    }

    // ── 🔴 AC2：白嫖倍数（纯函数，含除零边界）─────────────────────

    /**
     * 🔴 「攒满几个月换一次 HD」这个数就是 OQ-C1 要运营看见的东西。
     *
     * <p>月上限 30、HD 60 ⇒ 2 个月白嫖一次。只看"30"看不出这件事。
     */
    @Test
    void freeRideMultipleIsComputedFromHdPriceAndMonthlyCap() {
        assertThat(ShareRewardOverview.monthsPerHdUnlock(60, 30)).isEqualTo(2.0);
        assertThat(ShareRewardOverview.monthsPerHdUnlock(5000, 2000)).isEqualTo(2.5);
        assertThat(ShareRewardOverview.monthsPerHdUnlock(5000, 3000)).isEqualTo(1.7);
    }

    /**
     * 🔴 上限为 0 时返回 null（= 界面显示「不发」），**绝不能算成 0**。
     *
     * <p>算成 0 会被读成「零个月就能换一次」也就是「立刻能白嫖」——
     * 正好与事实（一枚都不发）相反。这是这条 AC 里唯一能把结论读反的地方。
     */
    @Test
    void zeroMonthlyCapYieldsNoMultipleRatherThanZero() {
        assertThat(ShareRewardOverview.monthsPerHdUnlock(5000, 0)).isNull();
        assertThat(ShareRewardOverview.monthsPerHdUnlock(0, 0)).isNull();
        assertThat(ShareRewardOverview.monthsPerHdUnlock(5000, -1)).isNull();
    }

    /** HD 免费（价 0）时倍数是 0 —— 那是真的"零个月就能换"，与上一条不是同一回事。 */
    @Test
    void zeroHdPriceYieldsZeroMonths() {
        assertThat(ShareRewardOverview.monthsPerHdUnlock(0, 100)).isEqualTo(0.0);
    }

    // ── AC1/AC4：改配置与校验 ────────────────────────────────────

    @Test
    void savingUpdatesAllFourValuesAndTakesEffectOnTheNextGrant() throws Exception {
        snapshot();
        User u = newUser();

        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardEnabled", "true")
                        .param("shareRewardMonthlyCap", "777")
                        .param("idCardShareReward", "111")
                        .param("idCardShareDailyCap", "5"))
                .andExpect(status().is3xxRedirection());

        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        assertThat(c.isShareRewardEnabled()).isTrue();
        assertThat(c.getShareRewardMonthlyCap()).isEqualTo(777);
        assertThat(c.getIdCardShareReward()).isEqualTo(111);
        assertThat(c.getIdCardShareDailyCap()).isEqualTo(5);

        // 🛡 实时下发：不需重启，下一次发放就按新上限走。
        assertThat(shareReward.tryReward(u.getId(), 777, "TEST", null,
                "cfg-" + SEQ.incrementAndGet(), Instant.now())).isTrue();
        assertThat(shareReward.tryReward(u.getId(), 1, "TEST", null,
                "cfg-" + SEQ.incrementAndGet(), Instant.now())).isFalse();
    }

    /**
     * 🔴 取消勾选总开关必须**真的关掉**，而不是 400。
     *
     * <p>checkbox 未勾选时浏览器**不提交该参数** —— 少了 {@code defaultValue="false"}
     * 就会 400，而这个开关的全部意义就是「要能立刻关掉」。
     */
    @Test
    void uncheckingTheMasterSwitchActuallyTurnsItOff() throws Exception {
        snapshot();
        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardEnabled", "true")
                        .param("shareRewardMonthlyCap", "500")
                        .param("idCardShareReward", "100")
                        .param("idCardShareDailyCap", "3"))
                .andExpect(status().is3xxRedirection());

        // 不带 shareRewardEnabled 参数（= 取消勾选后浏览器的真实行为）
        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardMonthlyCap", "500")
                        .param("idCardShareReward", "100")
                        .param("idCardShareDailyCap", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow()
                .isShareRewardEnabled()).isFalse();
    }

    /** 🛡 AC4：负值拒绝保存，且**一项都不改**（不是部分生效）。 */
    @Test
    void negativeValuesAreRejectedAndNothingIsChanged() throws Exception {
        snapshot();
        long before = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow()
                .getShareRewardMonthlyCap();

        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardEnabled", "true")
                        .param("shareRewardMonthlyCap", "-1")
                        .param("idCardShareReward", "100")
                        .param("idCardShareDailyCap", "3"))
                .andExpect(status().is3xxRedirection()); // 失败走 flash 回列表，不是 500

        assertThat(configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow()
                .getShareRewardMonthlyCap()).isEqualTo(before);
    }

    /** ⚠️ 非整数在参数绑定阶段就被挡住（400），不会走到业务校验。 */
    @Test
    void nonIntegerValueIsRejectedAtBinding() throws Exception {
        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardEnabled", "true")
                        .param("shareRewardMonthlyCap", "1.5")
                        .param("idCardShareReward", "100")
                        .param("idCardShareDailyCap", "3"))
                .andExpect(status().is4xxClientError());
    }

    /** 🛡 0 是合法取值（= 不发），不该被当成错误拒绝。 */
    @Test
    void zeroIsAValidValueMeaningNoGrant() throws Exception {
        snapshot();
        mvc.perform(post("/admin/config/share-reward")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("shareRewardEnabled", "true")
                        .param("shareRewardMonthlyCap", "0")
                        .param("idCardShareReward", "0")
                        .param("idCardShareDailyCap", "0"))
                .andExpect(status().is3xxRedirection());

        assertThat(configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow()
                .getShareRewardMonthlyCap()).isZero();
    }

    // ── AC3：当月消耗同屏 ───────────────────────────────────────

    /** ⚠️ 统计口径是 WIB 自然月，页面上要带可见的「WIB」字样。 */
    @Test
    void configPageShowsThisMonthUsageWithWibLabel() throws Exception {
        snapshot();
        User u = newUser();
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setShareRewardEnabled(true);
        c.setShareRewardMonthlyCap(1000);
        configs.saveAndFlush(c);
        shareReward.tryReward(u.getId(), 250, "TEST", null, "cfg-" + SEQ.incrementAndGet(),
                Instant.now());

        String html = mvc.perform(get("/admin/config").with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-notice=\"share-reward-freeride\"");
        assertThat(html).contains("data-notice=\"share-reward-usage\"");
        assertThat(html).as("统计口径没标 WIB").contains("WIB");
        assertThat(html).as("当月 period 没显示")
                .contains(ShareRewardService.periodOf(Instant.now()));
    }

    /** 上个 WIB 月的发放不计入本月统计。 */
    @Test
    void lastMonthGrantsDoNotCountTowardThisMonth() {
        snapshot();
        User u = newUser();
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setShareRewardEnabled(true);
        c.setShareRewardMonthlyCap(1000);
        configs.saveAndFlush(c);

        Instant lastMonth = ZonedDateTime.now(WIB).minusMonths(1).toInstant();
        shareReward.tryReward(u.getId(), 300, "TEST", null, "cfg-" + SEQ.incrementAndGet(),
                lastMonth);

        assertThat(shareReward.grantedThisMonth(u.getId()))
                .as("上个 WIB 月的发放不该算进本月").isZero();
    }

    // ── 🛡 AC5：两个码真的分开 ──────────────────────────────────

    /** 🛡 只有 config.view（没有分享奖励查看权）→ 页面上看不到这一块。 */
    @Test
    void staffWithoutShareRewardViewSeesNoShareRewardBlock() throws Exception {
        String html = mvc.perform(get("/admin/config")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("data-notice=\"share-reward-freeride\"");
        assertThat(html).doesNotContain("/admin/config/share-reward");
    }

    /** 🛡 有查看权、无编辑权 → 看得到数，但改不了（403）。 */
    @Test
    void viewOnlyStaffCanSeeButCannotSave() throws Exception {
        Authentication viewOnly = staffWith(AdminPermissions.CONFIG_VIEW,
                AdminPermissions.CONFIG_SHARE_REWARD_VIEW);

        String html = mvc.perform(get("/admin/config").with(authentication(viewOnly)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-notice=\"share-reward-freeride\"");

        mvc.perform(post("/admin/config/share-reward").with(authentication(viewOnly)).with(csrf())
                        .param("shareRewardEnabled", "false")
                        .param("shareRewardMonthlyCap", "0")
                        .param("idCardShareReward", "0")
                        .param("idCardShareDailyCap", "0"))
                .andExpect(status().isForbidden());
    }

    /**
     * 🔴 AC5 的核心收益：**不握有 config.edit 也能关总开关**。
     *
     * <p>这条钉的是「发现被刷要能立刻关掉」真的做得到 ——
     * 如果它要 config.edit（那道门管着兽医单价与分成比例），"立刻"就是空话。
     */
    @Test
    void shareRewardEditAloneIsEnoughToFlipTheMasterSwitchWithoutConfigEdit() throws Exception {
        snapshot();
        Authentication growthOps = staffWith(AdminPermissions.CONFIG_SHARE_REWARD_VIEW,
                AdminPermissions.CONFIG_SHARE_REWARD_EDIT);

        mvc.perform(post("/admin/config/share-reward").with(authentication(growthOps)).with(csrf())
                        .param("shareRewardMonthlyCap", "500")
                        .param("idCardShareReward", "100")
                        .param("idCardShareDailyCap", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow()
                .isShareRewardEnabled()).as("没有 config.edit 也该能关掉总开关").isFalse();

        // 🛡 反过来：他改不了定价（那仍然要 config.edit）。
        mvc.perform(post("/admin/config/pricing").with(authentication(growthOps)).with(csrf())
                        .param("vetConsultPrice", "1")
                        .param("vetShareRate", "1")
                        .param("aiUnlockPrice", "1")
                        .param("idHdDownloadPrice", "1")
                        .param("monthlyFreeQuota", "1"))
                .andExpect(status().isForbidden());
    }

    /** 🛡 AC5：漏 CSRF 就该被拦。 */
    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/admin/config/share-reward").with(authentication(superAdmin()))
                        .param("shareRewardEnabled", "false")
                        .param("shareRewardMonthlyCap", "0")
                        .param("idCardShareReward", "0")
                        .param("idCardShareDailyCap", "0"))
                .andExpect(status().isForbidden());
    }
}
