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
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（V1.3.0 Story 6.4 · D3 套模板 D + 变更记录抽屉）：整页单卡 14 项 + 页头「变更记录」按钮、无页尾常驻表；抽屉 fragment 200 / 403 / 非 htmx 302；
 * 按参数与 WIB 时间段筛选命中；操作人显示名；分页每页 20；{@code POST /admin/algo-params} 回归（htmx 成功回卡、校验码不变 422、PRG 不变）。
 */
class AdminAlgoParamDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private ConfigChangeLogRepository changeLogs;
    @Autowired
    private PlatformConfigService read;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminAccount superAccount() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin("algo-" + n + "@tailtopia.test", "算法校准员 " + n, "{bcrypt}x"));
    }

    private Authentication auth(AdminAccount acc) {
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("algo-op-" + n + "@tailtopia.test", "算法运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private static String q(String s) {
        return java.util.regex.Pattern.quote(s);
    }

    @Test
    void pageIsOneTemplateDCardWithDrawerButtonAndNoInlineChangeTable() throws Exception {
        String html = mvc.perform(get("/admin/algo-params").param("lang", "zh_CN").with(authentication(auth(superAccount()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"cfg-algo\"").contains("hx-post=\"/admin/algo-params\"").contains("action=\"/admin/algo-params\"")
                .contains("data-drawer-open=\"/admin/algo-params/changes/drawer\"").contains("id=\"algo-drawer\"")
                .contains("data-notice=\"algo-not-for-ops\"").contains("data-notice=\"algo-no-ab\"").contains("data-notice=\"algo-scope\"")
                .contains("cfg-grid-2").doesNotContain("data-section=\"algo-changelog\"").doesNotContain("FR-95").doesNotContain("打分参数");
        for (String name : new String[] {"freshnessWeight", "interactionWeight", "commentWeight", "exposureDecay", "shuffleStrength", "throttleFactor",
                "seenWindowDays", "windowSize", "attrFunQuota", "attrEduQuota", "attrLifeQuota", "speciesMainQuota", "speciesOtherQuota", "speciesGeneralQuota"}) {
            assertThat(html).as(name).contains("name=\"" + name + "\"");
        }
        assertThat(html.split(q("class=\"field\""), -1).length - 1).isEqualTo(14);
        // 只看不改：输入禁用 + 只读注释
        String ro = mvc.perform(get("/admin/algo-params").param("lang", "zh_CN").with(authentication(staffWith(AdminPermissions.CONFIG_ALGO_PARAM_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ro).containsPattern("id=\"cfg-algo\"[^>]*data-readonly=\"true\"").containsPattern("name=\"throttleFactor\"[^>]*disabled").contains("改算法参数");
    }

    @Test
    void drawerListsFiltersAndPaginatesFeedRankChanges() throws Exception {
        AdminAccount actor = superAccount();
        // 造 3 条 FEED_RANK 变更（两条 throttle_factor、一条 window_size），其中一条拨到 40 天前
        ConfigChangeLog a = changeLogs.save(ConfigChangeLog.of(ConfigChangeLog.ConfigType.FEED_RANK, "throttle_factor", "0.2", "0.25", actor.getId()));
        ConfigChangeLog b = changeLogs.save(ConfigChangeLog.of(ConfigChangeLog.ConfigType.FEED_RANK, "window_size", "10", "12", actor.getId()));
        ConfigChangeLog old = changeLogs.save(ConfigChangeLog.of(ConfigChangeLog.ConfigType.FEED_RANK, "throttle_factor", "0.1", "0.2", actor.getId()));
        jdbc.update("UPDATE config_change_logs SET changed_at = changed_at - interval '40 days' WHERE id = ?", old.getId());

        String all = mvc.perform(get("/admin/algo-params/changes/drawer").param("lang", "zh_CN").with(authentication(auth(actor))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("id=\"algo-changes\"").contains("data-change-id=\"" + a.getId() + "\"").contains("data-change-id=\"" + b.getId() + "\"")
                .contains("限流系数").contains("配比窗口").contains(actor.getDisplayName()).contains("WIB").contains("name=\"field\"").contains("name=\"from\"");
        assertThat(all.split(q("data-change-id="), -1).length - 1).isLessThanOrEqualTo(20);
        // 按参数筛选：只剩 throttle_factor
        String byField = mvc.perform(get("/admin/algo-params/changes/drawer").param("field", "throttle_factor").param("lang", "zh_CN")
                        .with(authentication(auth(actor))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(byField).contains("data-change-id=\"" + a.getId() + "\"").doesNotContain("data-change-id=\"" + b.getId() + "\"")
                .containsPattern("value=\"throttle_factor\"[^>]*selected");
        // 按 WIB 时间段：今天起 → 40 天前那条不在；只到 30 天前 → 40 天前那条也不在、今天的也不在
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        String recent = mvc.perform(get("/admin/algo-params/changes/drawer").param("field", "throttle_factor").param("from", today.minusDays(1).toString())
                        .with(authentication(auth(actor))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(recent).contains("data-change-id=\"" + a.getId() + "\"").doesNotContain("data-change-id=\"" + old.getId() + "\"");
        String window = mvc.perform(get("/admin/algo-params/changes/drawer").param("field", "throttle_factor")
                        .param("from", today.minusDays(45).toString()).param("to", today.minusDays(30).toString())
                        .with(authentication(auth(actor))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(window).contains("data-change-id=\"" + old.getId() + "\"").doesNotContain("data-change-id=\"" + a.getId() + "\"");
        // 无命中空态
        String none = mvc.perform(get("/admin/algo-params/changes/drawer").param("field", "window_size").param("to", today.minusDays(400).toString()).param("lang", "zh_CN")
                        .with(authentication(auth(actor))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(none).contains("还没有改动过");
        // 非 htmx → 302 整页；无权限 403
        mvc.perform(get("/admin/algo-params/changes/drawer").with(authentication(auth(actor)))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/algo-params/changes/drawer").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }

    /** 13 个不变参数（限流系数由各用例单独给）；每次 perform 新建 builder——MockHttpServletRequestBuilder.param 是就地累加的（复审 #1）。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder saveReq(AdminAccount actor, FeedRankConfig fr, String throttle) {
        return post("/admin/algo-params").with(authentication(auth(actor))).with(csrf())
                .param("freshnessWeight", String.valueOf(fr.getFreshnessWeight())).param("interactionWeight", String.valueOf(fr.getInteractionWeight()))
                .param("commentWeight", String.valueOf(fr.getCommentWeight())).param("exposureDecay", String.valueOf(fr.getExposureDecay()))
                .param("shuffleStrength", String.valueOf(fr.getShuffleStrength())).param("seenWindowDays", String.valueOf(fr.getSeenWindowDays()))
                .param("windowSize", String.valueOf(fr.getWindowSize())).param("attrFunQuota", String.valueOf(fr.getAttrFunQuota()))
                .param("attrEduQuota", String.valueOf(fr.getAttrEduQuota())).param("attrLifeQuota", String.valueOf(fr.getAttrLifeQuota()))
                .param("speciesMainQuota", String.valueOf(fr.getSpeciesMainQuota())).param("speciesOtherQuota", String.valueOf(fr.getSpeciesOtherQuota()))
                .param("speciesGeneralQuota", String.valueOf(fr.getSpeciesGeneralQuota())).param("throttleFactor", throttle).param("lang", "zh_CN");
    }

    @Test
    void saveKeepsValidationAndReturnsCardOnHtmx() throws Exception {
        AdminAccount actor = superAccount();
        FeedRankConfig fr = read.feedRank();
        double original = fr.getThrottleFactor();
        double changed = Math.abs(original - 0.25) < 1e-9 ? 0.3 : 0.25;
        try {
            // htmx + 真改限流系数 → 落库 + 200 卡 fragment + Retarget + toast；变更记录随即可在抽屉里看到
            String ok = mvc.perform(saveReq(actor, fr, String.valueOf(changed)).header("HX-Request", "true").header("HX-Target", "cfg-algo-err"))
                    .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#cfg-algo")).andExpect(header().string("HX-Reswap", "outerHTML"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(ok).contains("id=\"cfg-algo\"").contains("hx-swap-oob=\"beforeend:#admin-toast-host\"").contains("value=\"" + changed + "\"");
            assertThat(read.feedRank().getThrottleFactor()).isEqualTo(changed);
            String drawer = mvc.perform(get("/admin/algo-params/changes/drawer").param("field", "throttle_factor").with(authentication(auth(actor))).header("HX-Request", "true"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(drawer).contains("<b>" + changed + "</b>").contains(actor.getDisplayName());
            // htmx + 限流系数取 1（两端不取）→ 422 行内 err，码不变，库值不变
            String err = mvc.perform(saveReq(actor, fr, "1").header("HX-Request", "true").header("HX-Target", "cfg-algo-err"))
                    .andExpect(status().isUnprocessableEntity()).andExpect(header().string("HX-Retarget", "#cfg-algo-err"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(err).contains("data-code=\"admin.err.config.throttleFactorRange\"");
            assertThat(read.feedRank().getThrottleFactor()).isEqualTo(changed);
            // 整页 PRG 不变（非 htmx）
            mvc.perform(saveReq(actor, fr, "1")).andExpect(status().is3xxRedirection());
            // 非法日期 / 页码手改 URL → 宽松处理成不筛，仍 200 fragment
            mvc.perform(get("/admin/algo-params/changes/drawer").param("from", "2026-13-40").param("page", "abc").with(authentication(auth(actor))).header("HX-Request", "true"))
                    .andExpect(status().isOk());
        } finally {
            mvc.perform(saveReq(actor, fr, String.valueOf(original))).andExpect(status().is3xxRedirection()); // 还原共享单行
        }
    }
}
