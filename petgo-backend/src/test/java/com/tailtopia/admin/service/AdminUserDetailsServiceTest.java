package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyLong;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：后台认证加载（AC3/AC6）—— 从 admin_accounts 取、状态/密码过滤、operatorUserId 解析、authorities。 */
class AdminUserDetailsServiceTest {

    private AdminAccountRepository adminAccounts;
    private UserRepository users;
    private AdminAccountPermissionRepository permissions;
    private AdminUserDetailsService service;

    @BeforeEach
    void setUp() {
        adminAccounts = mock(AdminAccountRepository.class);
        users = mock(UserRepository.class);
        permissions = mock(AdminAccountPermissionRepository.class);
        when(permissions.findByAccountId(anyLong())).thenReturn(List.of());
        service = new AdminUserDetailsService(adminAccounts, users, permissions);
    }

    /**
     * 用真实实体 + 反射设字段（避免 mock JPA 实体的不稳）。
     *
     * <p>⚠️ {@code accountType} 与 {@code role} 是一对不变式（{@code AdminRole.accountType()} 单向推导，
     * 库里另有 {@code ck_admin_accounts_role_type} 兜底），反射改一个就必须改另一个 ——
     * 只改 {@code accountType} 会造出「类型 STAFF、角色 SUPER_ADMIN」这种生产上不可能存在的组合，
     * 测的就不是真实行为了。STAFF 固定给 {@code CUSTOM}（即按 {@code admin_account_permissions}
     * 勾选行授权，正是这些用例要考察的那条路径）。
     */
    private AdminAccount account(long id, String email, AdminAccountStatus status,
            String passwordHash, AdminAccountType type) {
        AdminAccount a = AdminAccount.newSuperAdmin(email, "运营", passwordHash);
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "status", status);
        ReflectionTestUtils.setField(a, "accountType", type);
        ReflectionTestUtils.setField(a, "role",
                type == AdminAccountType.SUPER_ADMIN ? AdminRole.SUPER_ADMIN : AdminRole.CUSTOM);
        return a;
    }

    private static List<String> authorities(UserDetails ud) {
        return ud.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
    }

    @Test
    void loadsActiveSuperAdminWithPasswordAndResolvesOperatorUserId() {
        when(adminAccounts.findByLarkEmail("ops@tailtopia.id")).thenReturn(Optional.of(
                account(7L, "ops@tailtopia.id", AdminAccountStatus.ACTIVE, "{bcrypt}h", AdminAccountType.SUPER_ADMIN)));
        User official = mock(User.class);
        when(official.getId()).thenReturn(99L);
        when(users.findByEmailAndRole("ops@tailtopia.id", Role.ADMIN)).thenReturn(Optional.of(official));

        UserDetails ud = service.loadUserByUsername("ops@tailtopia.id");

        assertThat(ud.getUsername()).isEqualTo("ops@tailtopia.id");
        assertThat(ud.getPassword()).isEqualTo("{bcrypt}h");
        assertThat(authorities(ud)).contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        AdminUserDetails aud = (AdminUserDetails) ud;
        assertThat(aud.getAdminAccountId()).isEqualTo(7L);
        assertThat(aud.getUserId()).isEqualTo(99L); // 官方内容作者 shim（AC5）
        assertThat(aud.getAccountType()).isEqualTo(AdminAccountType.SUPER_ADMIN);
    }

    @Test
    void staffHasOnlyRoleAdminNotSuperAdmin() {
        when(adminAccounts.findByLarkEmail("staff@tailtopia.id")).thenReturn(Optional.of(
                account(8L, "staff@tailtopia.id", AdminAccountStatus.ACTIVE, "{bcrypt}s", AdminAccountType.STAFF)));
        when(users.findByEmailAndRole("staff@tailtopia.id", Role.ADMIN)).thenReturn(Optional.empty());

        UserDetails ud = service.loadUserByUsername("staff@tailtopia.id");

        assertThat(authorities(ud)).contains("ROLE_ADMIN").doesNotContain("ROLE_SUPER_ADMIN");
        assertThat(((AdminUserDetails) ud).hasOperatorUserId()).isFalse();
    }

    @Test
    void staffLoadsModulePermissionAuthorities() {
        when(adminAccounts.findByLarkEmail("staff@tailtopia.id")).thenReturn(Optional.of(
                account(8L, "staff@tailtopia.id", AdminAccountStatus.ACTIVE, "{bcrypt}s", AdminAccountType.STAFF)));
        when(users.findByEmailAndRole("staff@tailtopia.id", Role.ADMIN)).thenReturn(Optional.empty());
        when(permissions.findByAccountId(8L)).thenReturn(List.of(
                new AdminAccountPermission(8L, "vet.view"),
                new AdminAccountPermission(8L, "admin.view_logs")));

        UserDetails ud = service.loadUserByUsername("staff@tailtopia.id");

        assertThat(authorities(ud)).contains("ROLE_ADMIN", "vet.view", "admin.view_logs")
                .doesNotContain("ROLE_SUPER_ADMIN");
    }

    @Test
    void superAdminDoesNotLoadPermissionTableImplicitFullAccess() {
        when(adminAccounts.findByLarkEmail("ops@tailtopia.id")).thenReturn(Optional.of(
                account(7L, "ops@tailtopia.id", AdminAccountStatus.ACTIVE, "{bcrypt}h", AdminAccountType.SUPER_ADMIN)));
        when(users.findByEmailAndRole("ops@tailtopia.id", Role.ADMIN)).thenReturn(Optional.empty());

        UserDetails ud = service.loadUserByUsername("ops@tailtopia.id");

        assertThat(authorities(ud)).contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        // 隐式全权：不查权限表（经表达式判定，不注入全集）。
        org.mockito.Mockito.verify(permissions, org.mockito.Mockito.never()).findByAccountId(7L);
    }

    @Test
    void rejectsDisabledAccount() {
        when(adminAccounts.findByLarkEmail("x@tailtopia.id")).thenReturn(Optional.of(
                account(9L, "x@tailtopia.id", AdminAccountStatus.DISABLED, "{bcrypt}h", AdminAccountType.SUPER_ADMIN)));
        assertThatThrownBy(() -> service.loadUserByUsername("x@tailtopia.id"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void rejectsAccountWithoutPassword() {
        when(adminAccounts.findByLarkEmail("oauth@tailtopia.id")).thenReturn(Optional.of(
                account(10L, "oauth@tailtopia.id", AdminAccountStatus.ACTIVE, null, AdminAccountType.STAFF)));
        assertThatThrownBy(() -> service.loadUserByUsername("oauth@tailtopia.id"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void rejectsUnknownAccount() {
        when(adminAccounts.findByLarkEmail("nope@tailtopia.id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("nope@tailtopia.id"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
