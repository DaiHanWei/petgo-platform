package com.tailtopia.admin.roles;

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
import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.repository.AdminRolePermissionRepository;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：权限来源唯一出口的四路（Story 1.4 AC3）+ codesOf / roleIdFor。 */
class RolePermissionResolverTest {

    private AdminAccountPermissionRepository accountPerms;
    private AdminRoleRepository roles;
    private AdminRolePermissionRepository rolePerms;
    private RolePermissionResolver resolver;

    @BeforeEach
    void setUp() {
        accountPerms = mock(AdminAccountPermissionRepository.class);
        roles = mock(AdminRoleRepository.class);
        rolePerms = mock(AdminRolePermissionRepository.class);
        resolver = new RolePermissionResolver(accountPerms, roles, rolePerms);
        when(accountPerms.findByAccountId(anyLong())).thenReturn(List.of());
        AdminRoleEntity fin = AdminRoleEntity.newCustom("FINANCE", "财务", null);
        ReflectionTestUtils.setField(fin, "id", 4L);
        when(roles.findByCode("FINANCE")).thenReturn(Optional.of(fin));
        when(rolePerms.findByRoleId(4L)).thenReturn(
                AdminRoleSeedSnapshot.FINANCE.stream().map(c -> new AdminRolePermission(4L, c)).toList());
    }

    private AdminAccount account(AdminRole role, Long roleId) {
        AdminAccount a = AdminAccount.create("x@y", "X", role, 1L);
        ReflectionTestUtils.setField(a, "id", 7L);
        a.setRoleId(roleId);
        return a;
    }

    @Test
    void superAdminIsEmpty() {
        assertThat(resolver.resolve(account(AdminRole.SUPER_ADMIN, null))).isEmpty();
        verify(rolePerms, never()).findByRoleId(any());
        verify(accountPerms, never()).findByAccountId(anyLong());
    }

    @Test
    void opsManagerReadsEnum() {
        assertThat(resolver.resolve(account(AdminRole.OPS_MANAGER, null)))
                .containsExactlyInAnyOrderElementsOf(AdminRole.OPS_MANAGER.permissionCodes());
        verify(rolePerms, never()).findByRoleId(any());
    }

    @Test
    void customReadsAccountRows() {
        when(accountPerms.findByAccountId(7L)).thenReturn(
                List.of(new AdminAccountPermission(7L, AdminPermissions.USER_VIEW)));
        assertThat(resolver.resolve(account(AdminRole.CUSTOM, null))).containsExactly(AdminPermissions.USER_VIEW);
        verify(rolePerms, never()).findByRoleId(any());
    }

    @Test
    void tableBackedReadsRoleRows() {
        assertThat(resolver.resolve(account(AdminRole.FINANCE, 4L)))
                .containsExactlyInAnyOrderElementsOf(AdminRoleSeedSnapshot.FINANCE);
        verify(accountPerms, never()).findByAccountId(anyLong());
    }

    @Test
    void tableBackedWithNullRoleIdIsEmptyNotError() {
        assertThat(resolver.resolve(account(AdminRole.FINANCE, null))).isEmpty();
        verify(rolePerms, never()).findByRoleId(any());
    }

    @Test
    void codesOfAndRoleIdFor() {
        assertThat(resolver.codesOf(AdminRole.FINANCE)).containsExactlyElementsOf(AdminRoleSeedSnapshot.FINANCE);
        assertThat(resolver.codesOf(AdminRole.OPS_MANAGER)).isEqualTo(AdminRole.OPS_MANAGER.permissionCodes());
        assertThat(resolver.codesOf(AdminRole.SUPER_ADMIN)).isEmpty();
        assertThat(resolver.codesOf(AdminRole.CUSTOM)).isEmpty();
        assertThat(resolver.roleIdFor(AdminRole.FINANCE)).contains(4L);
        assertThat(resolver.roleIdFor(AdminRole.OPS_MANAGER)).isEmpty();
        assertThat(resolver.roleIdFor(AdminRole.CUSTOM)).isEmpty();
        // 表里缺行（异常）→ empty，不抛。
        assertThat(resolver.roleIdFor(AdminRole.SUPPORT)).isEmpty();
        assertThat(resolver.codesOf(AdminRole.SUPPORT)).isEmpty();
    }

    /** Story 1.5：ROLE_TEMPLATE + role_id → 读表；roleIdFor 不按 code 查（empty）。 */
    @Test
    void roleTemplateReadsRoleRowsById() {
        when(rolePerms.findByRoleId(77L)).thenReturn(List.of(new AdminRolePermission(77L, AdminPermissions.VET_VIEW)));
        assertThat(resolver.resolve(account(AdminRole.ROLE_TEMPLATE, 77L))).containsExactly(AdminPermissions.VET_VIEW);
        assertThat(resolver.resolve(account(AdminRole.ROLE_TEMPLATE, null))).isEmpty();
        assertThat(resolver.roleIdFor(AdminRole.ROLE_TEMPLATE)).isEmpty();
        assertThat(resolver.codesOf(AdminRole.ROLE_TEMPLATE)).isEmpty();
    }
}
