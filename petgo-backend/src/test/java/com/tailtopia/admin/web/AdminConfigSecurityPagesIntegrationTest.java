package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（V1.3.0 Story 6.5 · B23 / B24 / D6 套模板）：审计页只读 + WIB + 链状态徽标端点；账号页摘要条 + 行 + 抽屉（Epic 1 动作 hx-post 原端点，
 * 成功回抽屉 + oob 行 + toast，self 护栏禁用）+ 创建抽屉；角色页摘要条；三页 MockMvc 门控。写端点路径 / 参数不变。
 */
class AdminConfigSecurityPagesIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private AdminUserDetails superAdmin() {
        long seq = SEQ.incrementAndGet();
        String email = "cfgsec-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "配置安全超管 " + seq, AdminRole.SUPER_ADMIN, List.of(), 980000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private AdminUserDetails staff(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "cfgsec-op-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "配置安全运营 " + seq, AdminRole.CUSTOM, List.of(perms), 980000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private static int count(String html, String needle) {
        return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** AC1 · B23：模板 B 只读、WIB 表头、链状态异步徽标；筛选参数名不变；无权限 403。 */
    @Test
    void auditLogsPageIsReadonlyTemplateBWithChainBadge() throws Exception {
        AdminUserDetails admin = superAdmin();
        String html = mvc.perform(get("/admin/audit-logs").param("lang", "zh_CN").with(user(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-readonly-page").contains("data-list").contains("id=\"audit-summary\"").contains("id=\"audit-total\"")
                .doesNotContain("id=\"audit-drawer\"") // 只读页：模板 B 壳但不输出抽屉与遮罩（复审 #7）
                .contains("hx-get=\"/admin/audit-logs/chain-status\"").contains("时间(WIB)").doesNotContain("时间(UTC)")
                .contains("name=\"actor\"").contains("name=\"action\"").contains("name=\"from\"").contains("name=\"to\"").contains("id=\"results\"");
        assertThat(count(html, "id=\"audit-total\"")).isEqualTo(1); // 整页不带 oob 副本
        String frag = mvc.perform(get("/admin/audit-logs").param("lang", "zh_CN").with(user(admin)).header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("id=\"audit-total\" hx-swap-oob=\"true\"").doesNotContain("id=\"audit-summary\"");
        String chain = mvc.perform(get("/admin/audit-logs/chain-status").param("lang", "zh_CN").with(user(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(chain).containsPattern("data-chain=\"(intact|broken)\"");
        mvc.perform(get("/admin/audit-logs/chain-status").with(user(admin))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/audit-logs/chain-status").with(user(staff(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit-logs").with(user(staff(AdminPermissions.CONTENT_VIEW)))).andExpect(status().isForbidden());
    }

    /** AC2 · B24：摘要条 + 行 + 抽屉；抽屉内改名 / 改角色（htmx）回抽屉 + oob 行 + toast；self 护栏；创建抽屉预渲染与端点。 */
    @Test
    void accountsPageDrawerCarriesEpic1ActionsUnchanged() throws Exception {
        AdminUserDetails admin = superAdmin();
        long seq = SEQ.incrementAndGet();
        long target = accountService.createAccount("cfgsec-t-" + seq + "@tailtopia.test", "目标 " + seq, AdminRole.CUSTOM,
                List.of(AdminPermissions.CONTENT_VIEW), admin.getAdminAccountId());

        String page = mvc.perform(get("/admin/accounts").param("lang", "zh_CN").with(user(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"accounts-summary\"").contains("id=\"accounts-drawer\"").contains("id=\"account-new-drawer\"")
                .contains("id=\"account-row-" + target + "\"").contains("data-drawer-url=\"/admin/accounts/" + target + "/drawer\"")
                .contains("action=\"/admin/accounts\"").contains("name=\"permissionCodes\"").contains("data-drawer-res=\"account-new\"")
                .doesNotContain("account-rename-form"); // 行内动作已收进抽屉
        assertThat(page).contains("创建时间");

        String drawer = mvc.perform(get("/admin/accounts/" + target + "/drawer").param("lang", "zh_CN").with(user(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-account-id=\"" + target + "\"")
                .contains("hx-post=\"/admin/accounts/" + target + "/rename\"").contains("hx-post=\"/admin/accounts/" + target + "/rebind-email\"")
                .contains("hx-post=\"/admin/accounts/" + target + "/role\"").contains("hx-post=\"/admin/accounts/" + target + "/permissions\"")
                .contains("hx-post=\"/admin/accounts/" + target + "/deactivate\"").contains("id=\"accounts-drawer-err\"").contains("重新登录后生效");
        mvc.perform(get("/admin/accounts/" + target + "/drawer").with(user(admin))).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/accounts?open=" + target));

        // 改名（htmx）→ 抽屉体 oob + 行 oob + toast，端点 / 参数不变
        String renamed = mvc.perform(post("/admin/accounts/" + target + "/rename").param("displayName", "新名字 " + seq).param("lang", "zh_CN")
                        .with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(renamed).contains("hx-swap-oob=\"innerHTML:#accounts-drawer .drawer-body\"").contains("id=\"account-row-" + target + "\" ")
                .contains("hx-swap-oob=\"true\"").contains("class=\"toast\"").contains("新名字 " + seq);
        assertThat(adminAccounts.findById(target).orElseThrow().getDisplayName()).isEqualTo("新名字 " + seq);
        // 改角色（htmx）
        mvc.perform(post("/admin/accounts/" + target + "/role").param("role", "enum:SUPPORT").with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(adminAccounts.findById(target).orElseThrow().getRole()).isEqualTo(AdminRole.SUPPORT);
        // self 护栏：自己的抽屉「改角色 / 停用」禁用并注明；htmx 对自己停用 → 422 行内 err
        String self = mvc.perform(get("/admin/accounts/" + admin.getAdminAccountId() + "/drawer").param("lang", "zh_CN").with(user(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(self).contains("这是你自己").containsPattern("name=\"role\"[^>]*disabled").contains("不能对自己执行此操作");
        mvc.perform(post("/admin/accounts/" + admin.getAdminAccountId() + "/deactivate").with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        // 停用 / 重新激活（htmx）→ 行 oob 带停用态
        String off = mvc.perform(post("/admin/accounts/" + target + "/deactivate").param("lang", "zh_CN").with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(off).contains("已停用").contains("hx-post=\"/admin/accounts/" + target + "/reactivate\"")
                .contains("id=\"accounts-summary\" hx-swap-oob=\"true\""); // 启用中 / 已停用 不能停在旧值（复审 #3）
        mvc.perform(post("/admin/accounts/" + target + "/reactivate").with(user(admin)).with(csrf()).header("HX-Request", "true")).andExpect(status().isOk());
        // 创建抽屉端点 + htmx 创建成功 HX-Redirect ?open=<id>；校验错误 422 回表单片段
        mvc.perform(get("/admin/accounts/new/drawer").with(user(admin)).header("HX-Request", "true")).andExpect(status().isOk());
        String created = mvc.perform(post("/admin/accounts").param("larkEmail", "cfgsec-new-" + seq + "@tailtopia.test").param("displayName", "新建 " + seq)
                        .param("roleValue", "enum:SUPPORT").with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Redirect", org.hamcrest.Matchers.startsWith("/admin/accounts?open=")))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).contains("账号已创建").doesNotContain("class=\"toast\""); // HX-Redirect 直接跳转、响应体被丢弃，回 toast 是死代码（复审 #5）
        mvc.perform(post("/admin/accounts").param("larkEmail", "not-an-email").param("displayName", "").param("roleValue", "enum:SUPPORT")
                        .with(user(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        // 只看不改：admin.view_accounts 能开抽屉，动作表单不渲染
        String viewer = mvc.perform(get("/admin/accounts/" + target + "/drawer").with(user(staff(AdminPermissions.ADMIN_VIEW_ACCOUNTS))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewer).doesNotContain("/rename\"").doesNotContain("/deactivate\"");
        mvc.perform(get("/admin/accounts/" + target + "/drawer").with(user(staff(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true")).andExpect(status().isForbidden());
        // 🛡 只持 admin.view_accounts 的账号：整页不渲染创建抽屉，也就拿不到 perm-matrix 的权限码全集（复审 #2：
        //    sec:authorize 写在 th:replace 同一标签上会被静默忽略，门形同虚设）
        String viewerPage = mvc.perform(get("/admin/accounts").param("lang", "zh_CN").with(user(staff(AdminPermissions.ADMIN_VIEW_ACCOUNTS))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewerPage).contains("id=\"account-row-" + target + "\"")
                .doesNotContain("id=\"account-new-drawer\"").doesNotContain("id=\"account-create\"");
    }

    /** AC3 · D6：列表套模板 B + 摘要条；「编辑权限」仍是整页矩阵；非超管 403。 */
    @Test
    void rolesPageIsTemplateBWithSummary() throws Exception {
        AdminUserDetails admin = superAdmin();
        String html = mvc.perform(get("/admin/roles").param("lang", "zh_CN").with(user(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"roles-summary\"").contains("预置角色").contains("使用中账号").contains("/admin/roles/new").contains("/edit\"")
                .contains("id=\"roles-rows\"").doesNotContain("id=\"roles-drawer\"");
        mvc.perform(get("/admin/roles").with(user(staff(AdminPermissions.ADMIN_VIEW_ACCOUNTS)))).andExpect(status().isForbidden());
    }
}
