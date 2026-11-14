package com.tailtopia.admin.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 5-2 · AC2.6 / AC3 —— 无权限 403；成功后 {@code entitlement_credits}
 * 一行 + 审计一条，同 token 重放不重复；用户抽屉「已购解锁」页签渲染礼包 / 次数 / 记录 / 补发卡。
 */
class AdminEntitlementGrantIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication admin(AdminAccountType type, String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("entgrant-" + n + "@tailtopia.test",
                "补发次数", "{bcrypt}x"));
        AdminUserDetails p = type == AdminAccountType.SUPER_ADMIN
                ? new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type)
                : new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type,
                        java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null, new java.util.ArrayList<>(p.getAuthorities()));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void forbiddenWithoutPermission() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/users/" + u.getId() + "/entitlements/grant")
                        .with(authentication(admin(AdminAccountType.STAFF, "user.view"))).with(csrf())
                        .param("creditType", "TAILSONALITY").param("quantity", "1").param("reason", "x")
                        .param("grantToken", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM entitlement_credits WHERE user_id = ?", u.getId())).isZero();
    }

    @Test
    void grantWritesOneBatchAndOneAuditAndReplayIsIdempotent() throws Exception {
        User u = newUser();
        Authentication ops = admin(AdminAccountType.STAFF, "user.view", "user.entitlement_grant");
        String token = UUID.randomUUID().toString();
        long audits = count("SELECT count(*) FROM admin_audit_logs WHERE action = 'ENTITLEMENT_ADMIN_GRANT'");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/admin/users/" + u.getId() + "/entitlements/grant").with(authentication(ops)).with(csrf())
                            .param("creditType", "GIFT_1K").param("quantity", "3").param("reason", "客诉补偿")
                            .param("grantToken", token))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(count("SELECT count(*) FROM entitlement_credits WHERE user_id = ? AND source_type = 'ADMIN'",
                u.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_logs WHERE action = 'ENTITLEMENT_ADMIN_GRANT'") - audits)
                .isEqualTo(1);
        String drawer = mvc.perform(get("/admin/users").param("open", String.valueOf(u.getId()))
                        .with(authentication(ops)).param("lang", "zh_CN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-ent-credits").contains("data-ent-history").contains("data-ent-grant")
                .contains("运营补发：客诉补偿");
    }
}
