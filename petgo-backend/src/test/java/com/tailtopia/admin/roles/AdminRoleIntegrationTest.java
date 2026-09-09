package com.tailtopia.admin.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import com.tailtopia.admin.roles.service.AdminRoleService;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;

/**
 * L1（真库）：角色配置闭环（Story 1.5）——预置角色改权限 → 该角色账号版本号 +1、其它角色不变、重登后权限含新码；
 * 新建自定义角色 code=role-&lt;id&gt;；删除被引用角色被拒；ROLE_TEMPLATE 账号解析读表；MockMvc 四条（200 / 403 / flash error / 302）。
 */
class AdminRoleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminRoleService roleService;
    @Autowired
    private AdminRoleRepository roles;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private RolePermissionResolver resolver;

    private static List<String> authorities(AdminUserDetails ud) {
        return ud.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void presetRolePermissionChangeBumpsOnlyThatRolesAccountsAndTakesEffectOnRelogin() {
        long seq = SEQ.incrementAndGet();
        long actor = 900000L + seq;
        long fin1 = accountService.createAccount("fin1-" + seq + "@tailtopia.test", "F1", AdminRole.FINANCE, List.of(), actor);
        long fin2 = accountService.createAccount("fin2-" + seq + "@tailtopia.test", "F2", AdminRole.FINANCE, List.of(), actor);
        long sup = accountService.createAccount("sup-" + seq + "@tailtopia.test", "S", AdminRole.SUPPORT, List.of(), actor);
        AdminRoleEntity finance = roles.findByCode("FINANCE").orElseThrow();
        List<String> before = List.copyOf(resolver.codesOfRoleId(finance.getId()));
        try {
            List<String> desired = new java.util.ArrayList<>(before);
            desired.add(AdminPermissions.USER_VIEW);
            var change = roleService.updatePermissions(finance.getId(), desired, actor);
            assertThat(change.added()).isEqualTo(1);
            assertThat(adminAccounts.findById(fin1).orElseThrow().getSecurityVersion()).isEqualTo(1);
            assertThat(adminAccounts.findById(fin2).orElseThrow().getSecurityVersion()).isEqualTo(1);
            assertThat(adminAccounts.findById(sup).orElseThrow().getSecurityVersion()).isZero();
            assertThat(authorities(userDetailsService.loadByEmail("fin1-" + seq + "@tailtopia.test", false)))
                    .contains(AdminPermissions.USER_VIEW);
        } finally {
            roleService.updatePermissions(finance.getId(), before, actor); // 共享库：恢复预置角色
        }
    }

    @Test
    void customRoleLifecycleAndRoleTemplateResolution() {
        long seq = SEQ.incrementAndGet();
        long actor = 910000L + seq;
        long id = roleService.create("自定义" + seq, List.of(AdminPermissions.VET_VIEW, AdminPermissions.RATING_VIEW), actor);
        AdminRoleEntity r = roles.findById(id).orElseThrow();
        assertThat(r.getCode()).isEqualTo("role-" + id);

        // ROLE_TEMPLATE 账号（1-6 才有页面入口；这里直接落库验证解析）
        long acc = accountService.createAccount("tpl-" + seq + "@tailtopia.test", "T", AdminRole.CUSTOM, List.of(), actor);
        AdminAccount a = adminAccounts.findById(acc).orElseThrow();
        a.setRole(AdminRole.ROLE_TEMPLATE);
        a.setRoleId(id);
        adminAccounts.save(a);
        assertThat(resolver.resolve(adminAccounts.findById(acc).orElseThrow()))
                .containsExactlyInAnyOrder(AdminPermissions.VET_VIEW, AdminPermissions.RATING_VIEW);

        assertThatThrownBy(() -> roleService.delete(id, actor)).isInstanceOf(AppException.class);
        a.setRole(AdminRole.CUSTOM);
        a.setRoleId(null);
        adminAccounts.save(a);
        roleService.delete(id, actor);
        assertThat(roles.findById(id)).isEmpty();
    }

    @Test
    void mockMvcGates() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 920000L + seq;
        long superId = accountService.createAccount("super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("super-" + seq + "@tailtopia.test", false);
        long staffId = accountService.createAccount("staff-" + seq + "@tailtopia.test", "员工", AdminRole.CUSTOM,
                List.of(AdminPermissions.ADMIN_CREATE_ACCOUNT), actor);
        AdminUserDetails staff = userDetailsService.loadByEmail("staff-" + seq + "@tailtopia.test", false);
        long finance = roles.findByCode("FINANCE").orElseThrow().getId();

        mvc.perform(get("/admin/roles").with(user(superAdmin))).andExpect(status().isOk());
        mvc.perform(get("/admin/roles/" + finance + "/edit").with(user(superAdmin))).andExpect(status().isOk());
        mvc.perform(get("/admin/roles").with(user(staff))).andExpect(status().isForbidden());
        // 422 类：空勾选 → flash error 回表单
        mvc.perform(post("/admin/roles").with(user(superAdmin)).with(csrf()).param("name", "x" + seq))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/roles/new"));
        // 成功保存（无变化 no-op 也 302 回列表）
        List<String> current = List.copyOf(resolver.codesOfRoleId(finance));
        var req = post("/admin/roles/" + finance + "/permissions").with(user(superAdmin)).with(csrf());
        for (String c : current) {
            req = req.param("permissionCodes", c);
        }
        mvc.perform(req).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/roles"));
        assertThat(Set.of(superId, staffId)).hasSize(2);
    }
}
