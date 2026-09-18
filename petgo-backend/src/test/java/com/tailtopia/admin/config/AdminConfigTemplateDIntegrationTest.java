package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（V1.3.0 Story 6.3 · D2 套模板 D）：整页 200 四卡各自成表单（端点 / 参数逐字不变，组头不标「高危」）；htmx 提交成功回该卡 fragment
 * （HX-Retarget / HX-Reswap 原位替换 + toast oob）；htmx 校验失败 422 行内 err（HX-Retarget 到卡的 err 槽，带 data-code）；
 * 整页提交路径 PRG 不变；只读态（config.view 无 config.edit）输入禁用 + 保存钮禁用 + 注明所缺权限，分享奖励卡按 share_reward_edit 判；
 * 无任何 config.* → 403。
 */
class AdminConfigTemplateDIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private PricingConfigRepository pricingRepo;

    private long[] saved;

    private void snapshot() {
        if (saved != null) {
            return;
        }
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        saved = new long[] {c.getVetConsultPrice(), c.getVetShareRate(), c.getAiUnlockPrice(), c.getMonthlyFreeQuota()};
    }

    @AfterEach
    void restore() {
        if (saved == null) {
            return;
        }
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        c.setVetConsultPrice(saved[0]);
        c.setVetShareRate((int) saved[1]);
        c.setAiUnlockPrice(saved[2]);
        c.setMonthlyFreeQuota((int) saved[3]);
        pricingRepo.saveAndFlush(c);
        saved = null;
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("cfgd-" + n + "@tailtopia.test", "模板D超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("cfgd-op-" + n + "@tailtopia.test", "模板D运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private static int count(String html, String needle) {
        return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** AC1：四卡各自成 form[data-config-card]，action / hx-post / 参数名逐字不变；组头无「高危」。 */
    @Test
    void pageRendersFourIndependentCardsWithUnchangedEndpoints() throws Exception {
        String html = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(count(html, "data-config-card")).isEqualTo(4);
        for (String[] card : new String[][] {
                {"cfg-pricing", "/admin/config/pricing", "vetConsultPrice"}, {"cfg-ktp", "/admin/config/ktp-pricing", "passportPagePrice"},
                {"cfg-pawcoin", "/admin/config/pawcoin", "premiumRate"}, {"cfg-share-reward", "/admin/config/share-reward", "shareRewardMonthlyCap"}}) {
            assertThat(html).as(card[0]).contains("id=\"" + card[0] + "\"").contains("action=\"" + card[1] + "\"").contains("hx-post=\"" + card[1] + "\"")
                    .contains("hx-target=\"#" + card[0] + "-err\"").contains("id=\"" + card[0] + "-err\"").contains("name=\"" + card[2] + "\"")
                    .containsPattern("id=\"" + card[0] + "\"[^>]*data-confirm-diff=\"true\"").containsPattern("id=\"" + card[0] + "\"[^>]*data-error-fields=");
        }
        // 保存钮初始禁用 + 「已修改」标；确认弹层文案模板挂在表单上（前端拼旧值 → 新值）
        assertThat(count(html, "data-save")).isEqualTo(4);
        assertThat(count(html, "data-dirty-flag")).isEqualTo(4);
        assertThat(html).contains("data-confirm-diff-line=\"{0}：{1} → {2}\"").contains("确认修改以下配置");
        assertThat(html).doesNotContain("高危");
        // PawCoin 卡附属：档位两表 + 新建（Story 6.2）端点不变
        assertThat(html).contains("id=\"cfg-tiers\"").contains("/admin/config/tiers/").contains("action=\"/admin/config/tiers\"").contains("id=\"tiers-enabled\"");
        // 卡顺序按 UI 稿 7-1
        assertThat(html.indexOf("id=\"cfg-pricing\"")).isLessThan(html.indexOf("id=\"cfg-ktp\""));
        assertThat(html.indexOf("id=\"cfg-ktp\"")).isLessThan(html.indexOf("id=\"cfg-pawcoin\""));
        assertThat(html.indexOf("id=\"cfg-pawcoin\"")).isLessThan(html.indexOf("id=\"cfg-share-reward\""));
    }

    /** AC3：htmx 提交成功 → 该卡 fragment（HX-Retarget #cfg-pricing + outerHTML）+ toast oob；失败 → 422 行内 err 带 data-code，Retarget 到 err 槽。 */
    @Test
    void htmxSubmitReturnsCardFragmentOnSuccessAndInlineErrorOn422() throws Exception {
        snapshot();
        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        String ok = mvc.perform(post("/admin/config/pricing").with(authentication(superAdmin())).with(csrf())
                        .param("vetConsultPrice", String.valueOf(c.getVetConsultPrice() + 1000)).param("vetShareRate", String.valueOf(c.getVetShareRate()))
                        .param("aiUnlockPrice", String.valueOf(c.getAiUnlockPrice())).param("monthlyFreeQuota", String.valueOf(c.getMonthlyFreeQuota()))
                        .param("lang", "zh_CN").header("HX-Request", "true").header("HX-Target", "cfg-pricing-err"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-pricing")).andExpect(header().string("HX-Reswap", "outerHTML"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("id=\"cfg-pricing\"").contains("data-config-card").contains("value=\"" + (c.getVetConsultPrice() + 1000) + "\"")
                .contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("定价已更新").doesNotContain("id=\"cfg-ktp\"");
        assertThat(pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow().getVetConsultPrice()).isEqualTo(c.getVetConsultPrice() + 1000);

        String err = mvc.perform(post("/admin/config/pricing").with(authentication(superAdmin())).with(csrf())
                        .param("vetConsultPrice", "-1").param("vetShareRate", "60").param("aiUnlockPrice", "10000").param("monthlyFreeQuota", "1")
                        .param("lang", "zh_CN").header("HX-Request", "true").header("HX-Target", "cfg-pricing-err"))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#cfg-pricing-err"))
                .andExpect(header().string("HX-Reswap", "innerHTML")).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("data-code=\"admin.err.config.priceNegative\"").contains("价格不可为负");
        assertThat(pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow().getVetConsultPrice()).isEqualTo(c.getVetConsultPrice() + 1000);
        // 整页提交路径维持 PRG（非 htmx）
        mvc.perform(post("/admin/config/pricing").with(authentication(superAdmin())).with(csrf())
                        .param("vetConsultPrice", "-1").param("vetShareRate", "60").param("aiUnlockPrice", "10000").param("monthlyFreeQuota", "1"))
                .andExpect(status().is3xxRedirection());
        // 其它三卡 htmx 成功各回自己的卡
        PricingConfig now = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        String ktp = mvc.perform(post("/admin/config/ktp-pricing").with(authentication(superAdmin())).with(csrf())
                        .param("idHdDownloadPrice", String.valueOf(now.getIdHdDownloadPrice())).param("passportPagePrice", String.valueOf(now.getPassportPageUnlockPrice()))
                        .param("passportBoardingPrice", String.valueOf(now.getPassportBoardingUnlockPrice())).header("HX-Request", "true"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-ktp")).andReturn().getResponse().getContentAsString();
        assertThat(ktp).contains("id=\"cfg-ktp\"").doesNotContain("id=\"cfg-pricing\"");
    }

    /** AC4：config.view 无 config.edit → 三卡只读（输入禁用、保存钮禁用、注明「编辑配置」）；分享奖励卡按 share_reward_edit；无 config.* → 403。 */
    @Test
    void readonlyStateFollowsEachCardsOwnEditPermission() throws Exception {
        String viewOnly = mvc.perform(get("/admin/config").param("lang", "zh_CN")
                        .with(authentication(staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_SHARE_REWARD_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(count(viewOnly, "data-readonly=\"true\"")).isEqualTo(4);
        assertThat(viewOnly).containsPattern("name=\"vetConsultPrice\"[^>]*disabled").containsPattern("name=\"shareRewardMonthlyCap\"[^>]*disabled")
                .contains("需要「编辑配置」权限").contains("需要「改分享奖励配置（含总开关）」权限").doesNotContain("action=\"/admin/config/tiers\"");
        // 只持 share_reward_edit 的应急操作员：分享奖励卡可编辑，其它三卡不渲染
        String srOnly = mvc.perform(get("/admin/config").param("lang", "zh_CN").with(authentication(staffWith(AdminPermissions.CONFIG_SHARE_REWARD_EDIT))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(count(srOnly, "data-config-card")).isEqualTo(1);
        assertThat(srOnly).contains("id=\"cfg-share-reward\"").doesNotContainPattern("id=\"cfg-share-reward\"[^>]*data-readonly").doesNotContain("id=\"cfg-pricing\"");
        // 无任何 config.* → 403
        mvc.perform(get("/admin/config").with(authentication(staffWith(AdminPermissions.CONTENT_VIEW)))).andExpect(status().isForbidden());
        // 只读账号 htmx 提交 → 403 fragment
        mvc.perform(post("/admin/config/pricing").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))).with(csrf())
                        .param("vetConsultPrice", "1").param("vetShareRate", "1").param("aiUnlockPrice", "1").param("monthlyFreeQuota", "1")
                        .header("HX-Request", "true").header("HX-Target", "cfg-pricing-err"))
                .andExpect(status().isForbidden());
    }
}
