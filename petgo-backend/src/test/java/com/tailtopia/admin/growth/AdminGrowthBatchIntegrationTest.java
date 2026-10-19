package com.tailtopia.admin.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：批量发放活动 EXP（V1.4.0 ① Story 4.3 · AC1 ~ AC7）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 * 本地跑法：{@code DB_NAME=<新建空库> ./mvnw -B test -Dtest=AdminGrowthBatchIntegrationTest}（先 flush Redis DB0）。
 */
class AdminGrowthBatchIntegrationTest extends ApiIntegrationTest {

    private static final Pattern BATCH_NO = Pattern.compile("GB-\\d{12}-[A-Z0-9]{6}");

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("growth-batch-" + n + "@tailtopia.test",
                "批量发放运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
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

    @Test
    void entryVisibilityAndGates() throws Exception {
        Authentication configOnly = staffWith(AdminPermissions.GROWTH_CONFIG_VIEW);
        String page = mvc.perform(get("/admin/growth").with(authentication(configOnly)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("data-growth-batch-open");
        mvc.perform(htmx(get("/admin/growth/batch/drawer").with(authentication(configOnly)), "growthbatch-drawer-body"))
                .andExpect(status().isForbidden());

        Authentication adjuster = staffWith(AdminPermissions.GROWTH_USER_ADJUST);
        String ro = mvc.perform(get("/admin/growth").with(authentication(adjuster)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ro).contains("data-growth-batch-open").contains("disabled").doesNotContain("data-rule-edit=");
        mvc.perform(get("/admin/growth/batch/template.csv").with(authentication(adjuster)))
                .andExpect(status().isOk());
    }

    @Test
    void previewConfirmExecuteAndReplay() throws Exception {
        List<User> real = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            real.add(newUser());
        }
        User virtual = newUser();
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                virtual.getId());
        User deactivated = newUser();
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", deactivated.getId());
        long missing = 9_000_000_000L + SEQ.incrementAndGet();
        StringBuilder ids = new StringBuilder();
        real.forEach(u -> ids.append(u.getId()).append(", "));
        ids.append(virtual.getId()).append('\n').append(deactivated.getId()).append(' ').append(missing);
        Authentication op = staffWith(AdminPermissions.GROWTH_USER_ADJUST);
        long threshold = count("SELECT threshold FROM growth_levels WHERE level = 2");

        String preview = mvc.perform(multipart("/admin/growth/batch/preview").with(authentication(op)).with(csrf())
                        .header("HX-Request", "true").header("HX-Target", "growthbatch-drawer-body").param("lang", "zh_CN")
                        .param("campaign", "周年庆").param("expPerUser", String.valueOf(threshold))
                        .param("userIds", ids.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = BATCH_NO.matcher(preview);
        assertThat(m.find()).isTrue();
        String batchNo = m.group();
        assertThat(preview).contains("用户不存在").contains("已停用").contains("虚拟号，自动排除");
        Matcher idsField = Pattern.compile("name=\"userIds\" value=\"([^\"]+)\"").matcher(preview);
        assertThat(idsField.find()).isTrue();
        String normalized = idsField.group(1);

        mvc.perform(htmx(post("/admin/growth/batch/confirm").with(authentication(op)).with(csrf())
                        .param("batchNo", batchNo).param("campaign", "周年庆")
                        .param("expPerUser", String.valueOf(threshold)).param("userIds", normalized), "growthbatch-err"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"));

        long audits = count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_BATCH_GRANTED' "
                + "AND target_id = ?", batchNo);
        String result = mvc.perform(htmx(post("/admin/growth/batch").with(authentication(op)).with(csrf())
                        .param("batchNo", batchNo).param("campaign", "周年庆")
                        .param("expPerUser", String.valueOf(threshold)).param("userIds", normalized), "confirm-modal-err"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#growthbatch-drawer-body"))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).contains("已发放：批次 " + batchNo + "，5 位用户");
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE batch_no = ? AND rule_code = 'OPS_CAMPAIGN' "
                + "AND note = '周年庆' AND operator_admin_id IS NOT NULL", batchNo)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_BATCH_GRANTED' "
                + "AND target_id = ?", batchNo)).isEqualTo(audits + 1);

        // 同一批次重提：0 新行、「已发放过」、审计不增
        String again = mvc.perform(htmx(post("/admin/growth/batch").with(authentication(op)).with(csrf())
                        .param("batchNo", batchNo).param("campaign", "周年庆")
                        .param("expPerUser", String.valueOf(threshold)).param("userIds", normalized), "confirm-modal-err"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(again).contains("该批次已发放过，未重复发放");
        assertThat(count("SELECT COUNT(*) FROM growth_ledger WHERE batch_no = ?", batchNo)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'GROWTH_BATCH_GRANTED' "
                + "AND target_id = ?", batchNo)).isEqualTo(audits + 1);

        // 跨级升级者有 GROWTH_LEVEL_UP 通知
        long uid = real.get(0).getId();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND type = 'GROWTH_LEVEL_UP'", uid) == 0
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND type = 'GROWTH_LEVEL_UP'", uid))
                .isEqualTo(1);
    }

    @Test
    void zeroValidIsRejectedAtConfirm() throws Exception {
        Authentication op = staffWith(AdminPermissions.GROWTH_USER_ADJUST);
        mvc.perform(htmx(post("/admin/growth/batch/confirm").with(authentication(op)).with(csrf())
                        .param("batchNo", "GB-202610181200-AAAAAA").param("campaign", "x").param("expPerUser", "5")
                        .param("userIds", String.valueOf(9_100_000_000L + SEQ.incrementAndGet())), "growthbatch-err"))
                .andExpect(status().isUnprocessableEntity());
    }
}
