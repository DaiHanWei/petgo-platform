package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.auth.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：登录时按<b>岗位角色</b>装载 authority（V165）。
 *
 * <p>这是角色改动里唯一真正决定「能不能进某个页面」的一环——服务层怎么存都不算数，
 * 最终生效的是这里装了哪些 authority。三条路径各测一遍。
 */
class AdminUserDetailsRoleTest {

    private AdminAccountRepository accounts;
    private UserRepository users;
    private AdminAccountPermissionRepository permissions;
    private AdminUserDetailsService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AdminAccountRepository.class);
        users = mock(UserRepository.class);
        permissions = mock(AdminAccountPermissionRepository.class);
        service = new AdminUserDetailsService(accounts, users, permissions);
        when(users.findByEmailAndRole(any(), any())).thenReturn(Optional.empty());
        when(permissions.findByAccountId(anyLong())).thenReturn(List.of());
    }

    private AdminUserDetails load(AdminRole role) {
        AdminAccount a = AdminAccount.create("x@y", "X", role, 1L);
        ReflectionTestUtils.setField(a, "id", 7L);
        when(accounts.findByLarkEmail("x@y")).thenReturn(Optional.of(a));
        return service.loadByEmail("x@y", false);
    }

    private List<String> authorities(AdminUserDetails d) {
        return d.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void templatedRoleLoadsItsCodesWithoutTouchingThePermissionTable() {
        AdminUserDetails d = load(AdminRole.FULFILLMENT);

        assertThat(authorities(d))
                .containsAll(AdminRole.FULFILLMENT.permissionCodes())
                .contains("ROLE_ADMIN")
                .doesNotContain("ROLE_SUPER_ADMIN");
        // 模板角色不查表：角色定义改了，存量账号下次登录就跟上，不会漂移。
        verify(permissions, never()).findByAccountId(anyLong());
    }

    @Test
    void fulfilmentCannotReachCostOrFinanceOrPhoneSearch() {
        // NFR-11 的实际效果断言：门控判的是 authority，这里没有就是真的进不去。
        assertThat(authorities(load(AdminRole.FULFILLMENT)))
                .doesNotContain(AdminPermissions.SHOP_COST_VIEW,
                        AdminPermissions.SHOP_FINANCE_VIEW,
                        AdminPermissions.SHOP_ORDER_PHONE_SEARCH,
                        AdminPermissions.USER_VIEW);
    }

    @Test
    void customRoleReadsTheTickedRows() {
        when(permissions.findByAccountId(7L)).thenReturn(
                List.of(new AdminAccountPermission(7L, AdminPermissions.USER_VIEW)));

        assertThat(authorities(load(AdminRole.CUSTOM)))
                .contains(AdminPermissions.USER_VIEW, "ROLE_ADMIN");
        verify(permissions).findByAccountId(7L);
    }

    @Test
    void superAdminGetsImplicitRoleNotTheFullCodeSet() {
        AdminUserDetails d = load(AdminRole.SUPER_ADMIN);

        assertThat(authorities(d)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        // 不注入全集：新增权限码时无需同步，抗遗漏。
        assertThat(authorities(d)).doesNotContain(AdminPermissions.SHOP_FINANCE_VIEW);
    }

    @Test
    void supportGetsPhoneSearchAndSubmitButNeitherApprovalNorPayout() {
        List<String> auth = authorities(load(AdminRole.SUPPORT));
        assertThat(auth).contains(AdminPermissions.SHOP_ORDER_PHONE_SEARCH,
                AdminPermissions.REFUND_SUBMIT);
        assertThat(auth).doesNotContain(AdminPermissions.REFUND_APPROVE,
                AdminPermissions.REFUND_PAYOUT);
    }
}
