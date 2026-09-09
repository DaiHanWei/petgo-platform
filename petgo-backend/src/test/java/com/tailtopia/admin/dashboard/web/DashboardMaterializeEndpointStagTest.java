package com.tailtopia.admin.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * L1（真库 + stag profile）：手动跑批端点只在 stag 注册（V1.3.0 Story 3.3 AC3）。独立上下文（profile 不同），
 * 与 {@code StagBadgeRenderIntegrationTest} 同一 profile 组合以复用上下文缓存。
 * 端点会真跑一次「找缺日 → 逐日物化」（回填起点默认 2026-07-17，共享库上首次可能耗时数十秒），跑完按返回的日期删行。
 * ⚠️ 真实 staging 容器的 profile 见 runbook（当前为 {@code prod}）；{@code @StagOnly} 要生效须 {@code SPRING_PROFILES_ACTIVE=prod,stag}。
 */
@ActiveProfiles({"dev", "stag"})
class DashboardMaterializeEndpointStagTest extends ApiIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private AdminAccountService accountService;

    @Autowired
    private AdminUserDetailsService userDetailsService;

    /** 真建账号再取 principal（admin 链每请求复核账号状态 / security_version，手造 id 会被踢回登录页）。 */
    private AdminUserDetails account(AdminRole role, String tag) {
        long seq = SEQ.incrementAndGet();
        String email = "mat-" + tag + "-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "跑批 " + tag, role, List.of(), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private AdminUserDetails superAdmin() {
        return account(AdminRole.SUPER_ADMIN, "super");
    }

    private AdminUserDetails staff() {
        return account(AdminRole.CUSTOM, "staff");
    }

    @Test
    void registeredInStagAndSuperAdminOnly() throws Exception {
        assertThat(context.getBeansOfType(AdminDashboardMaterializeController.class)).hasSize(1);
        mvc.perform(post("/admin/dashboard/materialize").with(user(staff())).with(csrf()))
                .andExpect(status().isForbidden());
        String body = mvc.perform(post("/admin/dashboard/materialize").with(user(superAdmin())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materializedDates").isArray())
                .andExpect(jsonPath("$.skipped").isNumber())
                .andExpect(jsonPath("$.failedDates").isArray())
                .andExpect(jsonPath("$.tookMs").isNumber())
                .andReturn().getResponse().getContentAsString();
        // 共享库不回滚：把本次端点真跑物化出来的日子删掉（默认回填起点 2026-07-17，可能是几十天 × 27 行）
        tools.jackson.databind.JsonNode dates = json.readTree(body).get("materializedDates");
        for (tools.jackson.databind.JsonNode d : dates) {
            jdbc.update("DELETE FROM ops_daily_metrics WHERE report_date = ?", java.sql.Date.valueOf(d.asText()));
        }
    }
}
