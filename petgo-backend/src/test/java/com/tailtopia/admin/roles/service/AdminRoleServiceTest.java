package com.tailtopia.admin.roles.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.domain.RoleType;
import com.tailtopia.admin.roles.dto.RoleChange;
import com.tailtopia.admin.roles.repository.AdminRolePermissionRepository;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：角色配置服务（Story 1.5 AC3～AC5）——建号校验、diff + 批量 bump、no-op、systemImmutable、inUse。 */
class AdminRoleServiceTest {

    private AdminRoleRepository roles;
    private AdminRolePermissionRepository rolePerms;
    private AdminAccountRepository accounts;
    private AdminAuditService audit;
    private AdminRoleService service;

    @BeforeEach
    void setUp() {
        roles = mock(AdminRoleRepository.class);
        rolePerms = mock(AdminRolePermissionRepository.class);
        accounts = mock(AdminAccountRepository.class);
        audit = mock(AdminAuditService.class);
        service = new AdminRoleService(roles, rolePerms, accounts, audit);
        when(roles.save(any(AdminRoleEntity.class))).thenAnswer(inv -> {
            AdminRoleEntity r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 42L);
            }
            return r;
        });
        when(rolePerms.findByRoleId(anyLong())).thenReturn(List.of());
        when(roles.findAll()).thenReturn(List.of());
    }

    private AdminRoleEntity system(long id, String code) {
        AdminRoleEntity r = AdminRoleEntity.newCustom(code, code, null);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "roleType", RoleType.SYSTEM);
        ReflectionTestUtils.setField(r, "nameKey", "role." + code);
        when(roles.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private AdminRoleEntity custom(long id, String name) {
        AdminRoleEntity r = AdminRoleEntity.newCustom("role-" + id, name, 1L);
        ReflectionTestUtils.setField(r, "id", id);
        when(roles.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void createAssignsCodeFromIdAndAudits() {
        long id = service.create("  兽医管理员 ", List.of(AdminPermissions.VET_VIEW, AdminPermissions.VET_EDIT), 1L);
        assertThat(id).isEqualTo(42L);
        verify(rolePerms).saveAll(any());
        verify(audit).record(eq(1L), eq(AuditActions.ROLE_CREATED), eq("ADMIN_ROLE"), eq("42"), any());
    }

    @Test
    void createRejectsBlankOverlongNoPermissionAndBadCode() {
        assertThatThrownBy(() -> service.create("  ", List.of(AdminPermissions.VET_VIEW), 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create("x".repeat(61), List.of(AdminPermissions.VET_VIEW), 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create("ok", List.of(), 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create("ok", null, 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.create("ok", List.of("not.a.code"), 1L)).isInstanceOf(AppException.class);
        verify(roles, never()).save(any());
    }

    @Test
    void createRejectsDuplicateNameIgnoringCase() {
        AdminRoleEntity existing = AdminRoleEntity.newCustom("role-9", "Ops Lead", 1L);
        ReflectionTestUtils.setField(existing, "id", 9L);
        when(roles.findAll()).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.create("ops lead", List.of(AdminPermissions.VET_VIEW), 1L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void updatePermissionsDiffsBumpsAllAccountsAndAudits() {
        system(4L, "FINANCE");
        when(rolePerms.findByRoleId(4L)).thenReturn(List.of(
                new AdminRolePermission(4L, AdminPermissions.CONFIG_VIEW),
                new AdminRolePermission(4L, AdminPermissions.ORDER_VIEW)));
        when(accounts.countByRoleId(4L)).thenReturn(3L);

        RoleChange c = service.updatePermissions(4L,
                List.of(AdminPermissions.CONFIG_VIEW, AdminPermissions.PAYMENT_VIEW), 1L);

        assertThat(c.added()).isEqualTo(1);
        assertThat(c.removed()).isEqualTo(1);
        assertThat(c.affectedAccounts()).isEqualTo(3);
        verify(rolePerms).deleteByRoleId(4L);
        verify(rolePerms).saveAll(any());
        verify(accounts).bumpSecurityVersionByRoleId(4L);
        verify(audit).record(eq(1L), eq(AuditActions.ROLE_UPDATED), eq("ADMIN_ROLE"), eq("4"),
                org.mockito.ArgumentMatchers.contains("+1 / −1"));
    }

    @Test
    void updatePermissionsNoChangeIsNoOp() {
        system(4L, "FINANCE");
        when(rolePerms.findByRoleId(4L)).thenReturn(List.of(new AdminRolePermission(4L, AdminPermissions.CONFIG_VIEW)));
        RoleChange c = service.updatePermissions(4L, List.of(AdminPermissions.CONFIG_VIEW), 1L);
        assertThat(c.noOp()).isTrue();
        verify(accounts, never()).bumpSecurityVersionByRoleId(anyLong());
        verify(rolePerms, never()).deleteByRoleId(anyLong());
        verify(audit, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void updatePermissionsRejectsEmptyAndBadCodes() {
        system(4L, "FINANCE");
        assertThatThrownBy(() -> service.updatePermissions(4L, List.of(), 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.updatePermissions(4L, List.of("bogus.code"), 1L)).isInstanceOf(AppException.class);
        verify(accounts, never()).bumpSecurityVersionByRoleId(anyLong());
    }

    @Test
    void systemRoleCannotBeRenamedOrDeleted() {
        system(2L, "SUPPORT");
        assertThatThrownBy(() -> service.rename(2L, "客服二组", 1L)).isInstanceOf(AppException.class)
                .hasMessageContaining("预置");
        assertThatThrownBy(() -> service.delete(2L, 1L)).isInstanceOf(AppException.class);
        verify(roles, never()).delete(any(AdminRoleEntity.class));
    }

    @Test
    void customRoleRenameIsIdempotentAndAudited() {
        AdminRoleEntity r = custom(7L, "旧名");
        service.rename(7L, " 旧名 ", 1L);
        verify(audit, never()).record(anyLong(), any(), any(), any(), any());
        service.rename(7L, "新名", 1L);
        assertThat(r.getName()).isEqualTo("新名");
        verify(audit).record(eq(1L), eq(AuditActions.ROLE_UPDATED), eq("ADMIN_ROLE"), eq("7"), any());
    }

    @Test
    void deleteRejectsWhenInUseAndDeletesOtherwise() {
        AdminRoleEntity r = custom(7L, "临时");
        when(accounts.countByRoleId(7L)).thenReturn(2L);
        assertThatThrownBy(() -> service.delete(7L, 1L)).isInstanceOf(AppException.class);
        verify(roles, never()).delete(any(AdminRoleEntity.class));

        when(accounts.countByRoleId(7L)).thenReturn(0L);
        service.delete(7L, 1L);
        verify(roles).delete(r);
        verify(audit).record(eq(1L), eq(AuditActions.ROLE_DELETED), eq("ADMIN_ROLE"), eq("7"), any());
    }

    @Test
    void auditSummaryTruncatedTo500() {
        assertThat(AdminRoleService.truncate("x".repeat(600))).hasSize(500);
        assertThat(AdminRoleService.truncate("short")).isEqualTo("short");
    }

    @Test
    void updateCustomRenamesAndUpdatesPermissionsTogetherButRejectsSystemRole() {
        AdminRoleEntity r = custom(7L, "旧名");
        when(rolePerms.findByRoleId(7L)).thenReturn(List.of(new AdminRolePermission(7L, AdminPermissions.VET_VIEW)));
        RoleChange c = service.updateCustom(7L, "新名", List.of(AdminPermissions.VET_VIEW, AdminPermissions.RATING_VIEW), 1L);
        assertThat(r.getName()).isEqualTo("新名");
        assertThat(c.added()).isEqualTo(1);
        verify(accounts).bumpSecurityVersionByRoleId(7L);

        // 校验失败（空勾选）→ 抛出，事务整体回滚（mock 下断言不落 bump）。
        assertThatThrownBy(() -> service.updateCustom(7L, "再改", List.of(), 1L)).isInstanceOf(AppException.class);

        system(4L, "FINANCE");
        assertThatThrownBy(() -> service.updateCustom(4L, "FINANCE", List.of(AdminPermissions.CONFIG_VIEW), 1L))
                .isInstanceOf(AppException.class);
    }
}
