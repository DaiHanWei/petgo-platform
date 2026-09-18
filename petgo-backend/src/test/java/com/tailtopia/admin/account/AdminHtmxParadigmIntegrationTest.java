package com.tailtopia.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：Story 2.3a AC8 范式接入四条——整页 POST 仍 PRG 302；HX-Request + 成功 → 200 toast fragment +
 * HX-Trigger；HX-Request + 业务错（停用自己）→ 422 inline-error；HX-Request + 无权限 → 403 forbidden fragment（带权限名）。
 */
class AdminHtmxParadigmIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    @Test
    void deactivateFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 940000L + seq;
        accountService.createAccount("hx-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("hx-super-" + seq + "@tailtopia.test", false);
        long target1 = accountService.createAccount("hx-t1-" + seq + "@tailtopia.test", "T1", AdminRole.CUSTOM, List.of(), actor);
        long target2 = accountService.createAccount("hx-t2-" + seq + "@tailtopia.test", "T2", AdminRole.CUSTOM, List.of(), actor);
        accountService.createAccount("hx-staff-" + seq + "@tailtopia.test", "员工", AdminRole.CUSTOM,
                List.of(AdminPermissions.ADMIN_VIEW_ACCOUNTS), actor);
        AdminUserDetails staff = userDetailsService.loadByEmail("hx-staff-" + seq + "@tailtopia.test", false);

        // ① 整页：PRG 302
        mvc.perform(post("/admin/accounts/" + target1 + "/deactivate").with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/accounts"));

        // ② htmx 成功：200 toast + HX-Trigger 刷角标
        String ok = mvc.perform(post("/admin/accounts/" + target2 + "/deactivate").with(user(superAdmin)).with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ok).contains("class=\"toast\"");

        // ③ htmx 业务错（停用自己）：422 inline-error，不 302
        String err = mvc.perform(post("/admin/accounts/" + superAdmin.getAdminAccountId() + "/deactivate")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true").header("HX-Target", "row-x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#row-x"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("role=\"alert\"").contains("inline-error");

        // ④ htmx 无权限：403 forbidden fragment（带所缺权限名）
        String forbidden = mvc.perform(post("/admin/accounts/" + target1 + "/deactivate").with(user(staff)).with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("forbidden");
    }
}
