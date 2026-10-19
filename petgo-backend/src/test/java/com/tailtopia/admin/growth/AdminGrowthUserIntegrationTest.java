package com.tailtopia.admin.growth;

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
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：用户抽屉「成长值」页签与手动调整（V1.4.0 ① Story 4.2 · AC2 ~ AC9）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 * 本地跑法：{@code DB_NAME=<新建空库> ./mvnw -B test -Dtest=AdminGrowthUserIntegrationTest}（先 flush Redis DB0）。
 */
class AdminGrowthUserIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("growth-user-" + n + "@tailtopia.test",
                "成长调整运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
        List<GrantedAuthority> auths = new ArrayList<>(p.getAuthorities());
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new TestingAuthenticationToken(p, null, auths);
    }

    /** 枚举角色 OPS_MANAGER（D-13，不读表）：P-5 预授 growth.user_adjust。 */
    private Authentication opsManager() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.create("growth-opsm-" + n + "@tailtopia.test", "运营主管",
                AdminRole.OPS_MANAGER, 1L));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.copyOf(AdminRole.OPS_MANAGER.permissionCodes()));
        List<GrantedAuthority> auths = new ArrayList<>(p.getAuthorities());
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new TestingAuthenticationToken(p, null, auths);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, String target) {
        return b.header("HX-Request", "true").header("HX-Target", target).param("lang", "zh_CN");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private MockHttpServletRequestBuilder adjust(String path, long userId, Authentication a, String type, String delta,
            String note, String token) {
        return htmx(post("/admin/users/" + userId + "/growth/" + path).with(authentication(a)).with(csrf())
                .param("type", type).param("delta", delta).param("note", note).param("grantToken", token),
                path.equals("adjust") ? "confirm-modal-err" : "user-drawer-err");
    }

    @Test
    void viewerSeesOverviewLedgerAndGreyedAdjustCard() throws Exception {
        User u = newUser();
        String html = mvc.perform(htmx(get("/admin/users/" + u.getId() + "/drawer")
                        .with(authentication(staffWith(AdminPermissions.USER_VIEW))), "user-drawer"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"user-growth-panel\"").contains("data-growth-state=\"REAL\"")
                .contains("Lv1").contains("id=\"user-growth-ledger\"").contains("该用户还没有成长值记录")
                .contains("data-growth-adjust-forbidden").contains("需要「调整用户 EXP」权限")
                .contains("data-utab=\"growth\"");
        mvc.perform(adjust("adjust/confirm", u.getId(), staffWith(AdminPermissions.USER_VIEW), "ADMIN_ADJUST", "5",
                "x", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(adjust("adjust", u.getId(), staffWith(AdminPermissions.USER_VIEW), "ADMIN_ADJUST", "5", "x",
                UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    @Test
    void confirmThenAdjustWritesLedgerAuditAndRefreshesOnlyTheGrowthPanel() throws Exception {
        User u = newUser();
        Authentication op = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.GROWTH_USER_ADJUST);
        String token = UUID.randomUUID().toString();
        long audits = count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_USER_ADJUSTED' "
                + "AND target_id = ?", String.valueOf(u.getId()));

        String modal = mvc.perform(adjust("adjust/confirm", u.getId(), op, "ADMIN_ADJUST", "50", "补发漏账", token))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("确认调整 EXP").contains("+50 EXP").contains("普通调整 · 补发漏账")
                .contains("0").contains("50").contains("/admin/users/" + u.getId() + "/growth/adjust");
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", u.getId())).isZero();

        String panel = mvc.perform(adjust("adjust", u.getId(), op, "ADMIN_ADJUST", "50", "补发漏账", token))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#user-growth-panel"))
                .andExpect(header().string("HX-Reswap", "outerHTML"))
                .andExpect(header().string("HX-Trigger-After-Swap", "admin:confirm-close"))
                .andReturn().getResponse().getContentAsString();
        assertThat(panel).contains("id=\"user-growth-panel\"").contains("运营调整 · 补发漏账").contains("已调整 +50 EXP")
                .doesNotContain("value=\"" + token + "\"");
        Map<String, Object> row = jdbc.queryForMap("SELECT rule_code, delta, note, operator_admin_id, idem_key, reason "
                + "FROM growth_ledger WHERE user_id = ?", u.getId());
        assertThat(row.get("rule_code")).isEqualTo("ADMIN_ADJUST");
        assertThat(((Number) row.get("delta")).intValue()).isEqualTo(50);
        assertThat(row.get("note")).isEqualTo("补发漏账");
        assertThat(row.get("operator_admin_id")).isNotNull();
        assertThat(row.get("idem_key")).isEqualTo("ADMIN:" + token);
        assertThat(row.get("reason")).isEqualTo("ADMIN_ADJUST");
        assertThat(count("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", u.getId())).isEqualTo(50);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_USER_ADJUSTED' "
                + "AND target_id = ?", String.valueOf(u.getId()))).isEqualTo(audits + 1);

        // 同 token 重放：不新增流水、不重复审计，toast 照常
        mvc.perform(adjust("adjust", u.getId(), op, "ADMIN_ADJUST", "50", "补发漏账", token)).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", u.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_USER_ADJUSTED' "
                + "AND target_id = ?", String.valueOf(u.getId()))).isEqualTo(audits + 1);

        // 负向超额 → 422，不入账
        mvc.perform(adjust("adjust/confirm", u.getId(), op, "ADMIN_ADJUST", "-51", "撤回", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(adjust("adjust", u.getId(), op, "ADMIN_ADJUST", "-51", "撤回", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE user_id = ?", u.getId())).isEqualTo(1);
    }

    @Test
    void campaignLevelUpSendsGrowthLevelUpNotification() throws Exception {
        User u = newUser();
        long threshold = count("SELECT threshold FROM growth_levels WHERE level = 2");
        mvc.perform(adjust("adjust", u.getId(), opsManager(), "OPS_CAMPAIGN", String.valueOf(threshold), "周年庆",
                UUID.randomUUID().toString())).andExpect(status().isOk());

        assertThat(count("SELECT level FROM growth_accounts WHERE user_id = ?", u.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT rule_code FROM growth_ledger WHERE user_id = ?", String.class,
                u.getId())).isEqualTo("OPS_CAMPAIGN");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND type = 'GROWTH_LEVEL_UP'", u.getId()) == 0
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND type = 'GROWTH_LEVEL_UP'", u.getId()))
                .isEqualTo(1);
    }

    @Test
    void virtualAccountShowsDisplayLevelOnlyAndRejectsAdjust() throws Exception {
        User v = newUser();
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                v.getId());
        Authentication op = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.GROWTH_USER_ADJUST);

        String html = mvc.perform(htmx(get("/admin/users/" + v.getId() + "/drawer").with(authentication(op)),
                "user-drawer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-growth-state=\"VIRTUAL\"").contains("data-virtual-badge")
                .contains("/admin/virtual-accounts?open=" + v.getId()).doesNotContain("user-growth-adjust-form")
                .doesNotContain("id=\"user-growth-ledger\"");
        mvc.perform(adjust("adjust/confirm", v.getId(), op, "ADMIN_ADJUST", "5", "x", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(adjust("adjust", v.getId(), op, "ADMIN_ADJUST", "5", "x", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deactivatedUserCardIsGreyedAndServerRejects() throws Exception {
        User u = newUser();
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", u.getId());
        Authentication op = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.GROWTH_USER_ADJUST);

        String html = mvc.perform(htmx(get("/admin/users/" + u.getId() + "/drawer").with(authentication(op)),
                "user-drawer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-growth-adjust-blocked").contains("账号已停用，不可调整 EXP");
        mvc.perform(adjust("adjust/confirm", u.getId(), op, "OPS_CAMPAIGN", "5", "x", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ledgerFilterEndpointReturnsOnlyTheLedgerFragment() throws Exception {
        User u = newUser();
        Authentication op = staffWith(AdminPermissions.USER_VIEW, AdminPermissions.GROWTH_USER_ADJUST);
        mvc.perform(adjust("adjust", u.getId(), op, "ADMIN_ADJUST", "5", "补", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        String hit = mvc.perform(htmx(get("/admin/users/" + u.getId() + "/growth/ledger").with(authentication(op))
                .param("rule", "ADMIN_ADJUST"), "user-growth-ledger")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(hit).contains("id=\"user-growth-ledger\"").contains("运营调整 · 补").doesNotContain("user-growth-panel");
        String miss = mvc.perform(htmx(get("/admin/users/" + u.getId() + "/growth/ledger").with(authentication(op))
                .param("rule", "DAILY_LOGIN"), "user-growth-ledger")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(miss).contains("没有符合条件的记录");
        mvc.perform(get("/admin/users/" + u.getId() + "/growth/ledger").with(authentication(op)))
                .andExpect(status().is3xxRedirection());
    }
}
