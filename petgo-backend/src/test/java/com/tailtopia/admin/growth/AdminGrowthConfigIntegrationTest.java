package com.tailtopia.admin.growth;

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
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Duration;
import java.time.Instant;

/**
 * L1：成长体系配置页（V1.4.0 ① Story 4.1 · AC1 ~ AC9）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 *
 * <p>⚠️ 共享测试库：规则 / 消费换算 / 等级表都是全局单份配置，每个用例改完在 finally 里还原；等级表用例只改一个高等级门槛
 * 再改回，并等异步重算结束（避免把共享库的进行中任务留给别的用例）。
 * 本地跑法：{@code DB_NAME=<新建空库> ./mvnw -B test -Dtest=AdminGrowthConfigIntegrationTest}（先 flush Redis DB0）。
 */
class AdminGrowthConfigIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("growth-op-" + n + "@tailtopia.test", "成长配置运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.STAFF,
                java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = ?", Long.class, action);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, String target) {
        return b.header("HX-Request", "true").header("HX-Target", target);
    }

    private void awaitNoActiveRecalc() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM growth_recalc_jobs WHERE status IN ('PENDING','RUNNING')",
                    Long.class);
            if (active == 0) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("growth recalc did not finish");
    }

    @Test
    void viewOnlySeesDisabledInputsAndCannotPost() throws Exception {
        Authentication viewer = staffWith(AdminPermissions.GROWTH_CONFIG_VIEW);
        String html = mvc.perform(get("/admin/growth").with(authentication(viewer)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-gtab=\"rules\"").contains("data-gtab=\"spend\"").contains("data-gtab=\"levels\"")
                .contains("disabled").doesNotContain("data-level-append").doesNotContain("data-rule-edit=");
        mvc.perform(post("/admin/growth/rules/COMMENT_POSTED").with(authentication(viewer)).with(csrf())
                .param("points", "3")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/growth/spend").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/growth/levels").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/growth").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorChangesARuleWithAudit() throws Exception {
        Authentication editor = staffWith(AdminPermissions.GROWTH_CONFIG_EDIT);
        Map<String, Object> before = jdbc.queryForMap("SELECT points, daily_cap, enabled FROM growth_rules WHERE code = 'COMMENT_POSTED'");
        long audits = auditCount("GROWTH_RULE_UPDATED");
        try {
            mvc.perform(htmx(post("/admin/growth/rules/COMMENT_POSTED").with(authentication(editor)).with(csrf())
                            .param("points", String.valueOf(((Number) before.get("points")).intValue() + 1))
                            .param("dailyCap", String.valueOf(before.get("daily_cap"))).param("enabled", "true"), "growthrule-form-err"))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT points FROM growth_rules WHERE code = 'COMMENT_POSTED'", Integer.class))
                    .isEqualTo(((Number) before.get("points")).intValue() + 1);
            assertThat(auditCount("GROWTH_RULE_UPDATED")).isEqualTo(audits + 1);
            // 两处错误一次返回（G3）。
            String err = mvc.perform(htmx(post("/admin/growth/rules/COMMENT_POSTED").with(authentication(editor)).with(csrf())
                            .param("points", "0").param("dailyCap", "-1"), "growthrule-form-err"))
                    .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
            assertThat(err).contains("grule-err-points").contains("grule-err-dailyCap");
            // SPEND_* 不在抽屉编辑。
            mvc.perform(htmx(get("/admin/growth/rules/SPEND_SHOP/drawer").with(authentication(editor)), "growthrule-drawer-body"))
                    .andExpect(status().isUnprocessableEntity());
        } finally {
            jdbc.update("UPDATE growth_rules SET points = ?, daily_cap = ?, enabled = ? WHERE code = 'COMMENT_POSTED'",
                    before.get("points"), before.get("daily_cap"), before.get("enabled"));
        }
    }

    @Test
    void lockedSpendPurposesStayUnchanged() throws Exception {
        Authentication editor = staffWith(AdminPermissions.GROWTH_CONFIG_EDIT);
        Map<String, Object> d = jdbc.queryForMap("SELECT unit_amount, points FROM growth_rules WHERE code = 'SPEND_DIGITAL'");
        Map<String, Object> t = jdbc.queryForMap("SELECT unit_amount, points FROM growth_rules WHERE code = 'SPEND_SHOP'");
        mvc.perform(htmx(post("/admin/growth/spend").with(authentication(editor)).with(csrf())
                        .param("digitalUnit", String.valueOf(d.get("unit_amount"))).param("digitalPoints", String.valueOf(d.get("points")))
                        .param("tokoUnit", String.valueOf(t.get("unit_amount"))).param("tokoPoints", String.valueOf(t.get("points")))
                        .param("tier.PAWCOIN_TOPUP", "DIGITAL").param("tier.GIFT", "DIGITAL"), "cfg-growth-spend-err"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT tier FROM growth_spend_tiers WHERE purpose = 'PAWCOIN_TOPUP'", String.class))
                .isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT tier FROM growth_spend_tiers WHERE purpose = 'GIFT'", String.class))
                .isEqualTo("GIFT");
    }

    @Test
    void levelTablePreviewShowsCountsAndSaveRunsTheRecalc() throws Exception {
        Authentication editor = staffWith(AdminPermissions.GROWTH_CONFIG_EDIT);
        awaitNoActiveRecalc();
        int top = jdbc.queryForObject("SELECT MAX(level) FROM growth_levels", Integer.class);
        long topThreshold = jdbc.queryForObject("SELECT threshold FROM growth_levels WHERE level = ?", Long.class, top);
        // 造一个刚好到最高级门槛的真实用户：门槛 +1 后它会降一级。
        long uid = newUser().getId();
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level, version, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, now(), now())", uid, topThreshold, top);
        try {
            String modal = mvc.perform(htmx(post("/admin/growth/levels/confirm").with(authentication(editor)).with(csrf())
                            .param("lv." + top, String.valueOf(topThreshold + 1)), "cfg-growth-levels-err"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(modal).contains("confirm-modal").contains("data-confirm-facts");
            mvc.perform(htmx(post("/admin/growth/levels").with(authentication(editor)).with(csrf())
                            .param("lv." + top, String.valueOf(topThreshold + 1)), "confirm-modal-err"))
                    .andExpect(status().isOk());
            awaitNoActiveRecalc();
            assertThat(jdbc.queryForObject("SELECT status FROM growth_recalc_jobs ORDER BY id DESC LIMIT 1", String.class))
                    .isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT level FROM growth_accounts WHERE user_id = ?", Integer.class, uid))
                    .isEqualTo(top - 1);
        } finally {
            awaitNoActiveRecalc();
            jdbc.update("UPDATE growth_levels SET threshold = ? WHERE level = ?", topThreshold, top);
            jdbc.update("UPDATE growth_accounts SET level = ? WHERE user_id = ?", top, uid);
        }
    }
}
