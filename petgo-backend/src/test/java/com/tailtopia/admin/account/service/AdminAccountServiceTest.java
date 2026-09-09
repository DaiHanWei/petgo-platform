package com.tailtopia.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：账号管理服务（AC3/AC4/AC5/AC7）——创建/唯一性/超管上限/权限 diff/停用激活，均写审计。 */
class AdminAccountServiceTest {

    private AdminAccountRepository accounts;
    private AdminAccountPermissionRepository permissions;
    private AdminAuditService auditService;
    private AdminAccountService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AdminAccountRepository.class);
        permissions = mock(AdminAccountPermissionRepository.class);
        auditService = mock(AdminAuditService.class);
        service = new AdminAccountService(accounts, permissions, auditService);
        when(accounts.findByLarkEmail(any())).thenReturn(Optional.empty());
        when(accounts.save(any(AdminAccount.class))).thenAnswer(inv -> {
            AdminAccount a = inv.getArgument(0);
            if (a.getId() == null) {
                ReflectionTestUtils.setField(a, "id", 42L);
            }
            return a;
        });
        when(permissions.findByAccountId(anyLong())).thenReturn(List.of());
    }

    private AdminAccount staff(long id, AdminAccountStatus status) {
        AdminAccount a = AdminAccount.create("s@x", "S", AdminRole.CUSTOM, 1L);
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "status", status);
        return a;
    }

    @Test
    void createStaffPersistsAccountPermissionsAndAudits() {
        long id = service.createAccount("new@x", "新人", AdminRole.CUSTOM,
                List.of("vet.view", "admin.view_logs"), 1L);

        assertThat(id).isEqualTo(42L);
        verify(permissions).saveAll(any());
        verify(auditService).record(eq(1L), eq(AuditActions.ACCOUNT_CREATED), eq("ADMIN_ACCOUNT"),
                eq("42"), any());
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(accounts.findByLarkEmail("dup@x")).thenReturn(Optional.of(staff(9L, AdminAccountStatus.ACTIVE)));
        assertThatThrownBy(() -> service.createAccount("dup@x", "X", AdminRole.CUSTOM, List.of(), 1L))
                .isInstanceOf(AppException.class);
        verify(accounts, never()).save(any());
    }

    @Test
    void createRejectsInvalidPermissionCode() {
        assertThatThrownBy(() -> service.createAccount("p@x", "X", AdminRole.CUSTOM,
                List.of("not.a.real.code"), 1L)).isInstanceOf(AppException.class);
    }

    @Test
    void createSuperAdminBeyondCapRejected() {
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(5L);
        assertThatThrownBy(() -> service.createAccount("boss@x", "老板", AdminRole.SUPER_ADMIN,
                List.of(), 1L)).isInstanceOf(AppException.class);
    }

    @Test
    void createSuperAdminIgnoresPermissionCodes() {
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(1L);
        service.createAccount("boss@x", "老板", AdminRole.SUPER_ADMIN,
                List.of("vet.view"), 1L);
        // 超管隐式全权，不写权限表。
        verify(permissions, never()).saveAll(any());
    }

    @Test
    void updatePermissionsDiffsAndAuditsGrantAndRevoke() {
        when(accounts.findById(8L)).thenReturn(Optional.of(staff(8L, AdminAccountStatus.ACTIVE)));
        when(permissions.findByAccountId(8L)).thenReturn(List.of(
                new AdminAccountPermission(8L, "vet.view"),
                new AdminAccountPermission(8L, "vet.ban")));

        // 目标：保留 vet.view，去掉 vet.ban，新增 content.takedown。
        service.updatePermissions(8L, List.of("vet.view", "content.takedown"), 1L);

        verify(permissions).deleteByAccountId(8L);
        verify(permissions).saveAll(any());
        verify(auditService).record(eq(1L), eq(AuditActions.PERMISSION_GRANTED), any(), eq("8"), any());
        verify(auditService).record(eq(1L), eq(AuditActions.PERMISSION_REVOKED), any(), eq("8"), any());
    }

    @Test
    void updatePermissionsNoChangeSkips() {
        when(accounts.findById(8L)).thenReturn(Optional.of(staff(8L, AdminAccountStatus.ACTIVE)));
        when(permissions.findByAccountId(8L)).thenReturn(List.of(
                new AdminAccountPermission(8L, "vet.view")));
        service.updatePermissions(8L, List.of("vet.view"), 1L);
        verify(permissions, never()).deleteByAccountId(anyLong());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cannotEditSuperAdminPermissions() {
        AdminAccount sa = AdminAccount.create("b@x", "B", AdminRole.SUPER_ADMIN, 1L);
        ReflectionTestUtils.setField(sa, "id", 2L);
        when(accounts.findById(2L)).thenReturn(Optional.of(sa));
        assertThatThrownBy(() -> service.updatePermissions(2L, List.of("vet.view"), 1L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void deactivateSetsDisabledAndAudits() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.deactivate(8L, 1L);
        assertThat(a.getStatus()).isEqualTo(AdminAccountStatus.DISABLED);
        verify(auditService).record(eq(1L), eq(AuditActions.ACCOUNT_DEACTIVATED), any(), eq("8"), any());
    }

    @Test
    void cannotDeactivateLastActiveSuperAdmin() {
        AdminAccount sa = AdminAccount.create("b@x", "B", AdminRole.SUPER_ADMIN, null);
        ReflectionTestUtils.setField(sa, "id", 1L);
        when(accounts.findById(1L)).thenReturn(Optional.of(sa));
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(1L);
        assertThatThrownBy(() -> service.deactivate(1L, 1L)).isInstanceOf(AppException.class);
        assertThat(sa.getStatus()).isEqualTo(AdminAccountStatus.ACTIVE);
    }

    @Test
    void reactivateSetsActiveAndAudits() {
        AdminAccount a = staff(8L, AdminAccountStatus.DISABLED);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.reactivate(8L, 1L);
        assertThat(a.getStatus()).isEqualTo(AdminAccountStatus.ACTIVE);
        verify(auditService).record(eq(1L), eq(AuditActions.ACCOUNT_REACTIVATED), any(), eq("8"), any());
    }

    // ---- V1.3.0 Story 1.1（AD-1）：安全版本号 bump ----

    @Test
    void deactivateBumpsSecurityVersionOnlyWhenChanged() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.deactivate(8L, 1L);
        assertThat(a.getSecurityVersion()).isEqualTo(1);
        service.deactivate(8L, 1L); // 幂等 no-op 不加
        assertThat(a.getSecurityVersion()).isEqualTo(1);
    }

    @Test
    void reactivateDoesNotBumpSecurityVersion() {
        AdminAccount a = staff(8L, AdminAccountStatus.DISABLED);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.reactivate(8L, 1L);
        assertThat(a.getSecurityVersion()).isZero();
    }

    @Test
    void changeRoleBumpsSecurityVersionOnlyWhenChanged() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.changeRole(8L, AdminRole.OPERATIONS, 1L);
        assertThat(a.getSecurityVersion()).isEqualTo(1);
        service.changeRole(8L, AdminRole.OPERATIONS, 1L); // 同角色幂等
        assertThat(a.getSecurityVersion()).isEqualTo(1);
    }

    @Test
    void updatePermissionsBumpsSecurityVersionOnlyWhenDiffNonEmpty() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        when(permissions.findByAccountId(8L)).thenReturn(List.of(
                new AdminAccountPermission(8L, "vet.view")));
        service.updatePermissions(8L, List.of("vet.view"), 1L); // 无 diff
        assertThat(a.getSecurityVersion()).isZero();
        service.updatePermissions(8L, List.of("vet.view", "content.takedown"), 1L);
        assertThat(a.getSecurityVersion()).isEqualTo(1);
    }

    @Test
    void bumpSecurityVersionIsPublicAndNoAudit() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.bumpSecurityVersion(8L);
        service.bumpSecurityVersion(8L);
        assertThat(a.getSecurityVersion()).isEqualTo(2);
        verify(accounts, org.mockito.Mockito.times(2)).save(a);
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void bumpSecurityVersionUnknownAccountRejected() {
        when(accounts.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bumpSecurityVersion(404L)).isInstanceOf(AppException.class);
    }

    // ---- V1.3.0 Story 1.2：改名 + self 护栏 ----

    @Test
    void renamePersistsTrimmedNameAndAudits() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.rename(8L, "  新名字  ", 1L);
        assertThat(a.getDisplayName()).isEqualTo("新名字");
        verify(accounts).save(a);
        verify(auditService).record(eq(1L), eq(AuditActions.ACCOUNT_RENAMED), eq("ADMIN_ACCOUNT"),
                eq("8"), org.mockito.ArgumentMatchers.contains("S → 新名字"));
    }

    @Test
    void renameRejectsBlank() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.rename(8L, "   ", 1L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.rename(8L, null, 1L)).isInstanceOf(AppException.class);
        assertThat(a.getDisplayName()).isEqualTo("S");
        verify(accounts, never()).save(any());
    }

    @Test
    void renameRejectsOver100() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.rename(8L, "x".repeat(101), 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("100");
        service.rename(8L, "x".repeat(100), 1L); // 边界 100 允许
        assertThat(a.getDisplayName()).hasSize(100);
    }

    @Test
    void renameSameValueIsNoOp() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.rename(8L, " S ", 1L);
        verify(accounts, never()).save(any());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void renameDoesNotBumpSecurityVersion() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        service.rename(8L, "改了", 1L);
        assertThat(a.getSecurityVersion()).isZero();
    }

    @Test
    void cannotDeactivateSelf() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE);
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.deactivate(8L, 8L)).isInstanceOf(AppException.class);
        assertThat(a.getStatus()).isEqualTo(AdminAccountStatus.ACTIVE);
        verify(accounts, never()).save(any());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cannotChangeOwnRoleEvenToSameRole() {
        AdminAccount a = staff(8L, AdminAccountStatus.ACTIVE); // CUSTOM
        when(accounts.findById(8L)).thenReturn(Optional.of(a));
        assertThatThrownBy(() -> service.changeRole(8L, AdminRole.OPERATIONS, 8L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.changeRole(8L, AdminRole.CUSTOM, 8L)).isInstanceOf(AppException.class);
        assertThat(a.getRole()).isEqualTo(AdminRole.CUSTOM);
        verify(accounts, never()).save(any());
    }
}
