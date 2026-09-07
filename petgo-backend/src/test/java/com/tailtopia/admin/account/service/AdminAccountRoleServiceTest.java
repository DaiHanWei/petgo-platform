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
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.dto.AdminAccountView;
import com.tailtopia.admin.account.repository.AdminAccountPermissionRepository;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：岗位角色在账号服务层的行为（V165）——建号、改角色、模板角色的权限护栏，均写审计。 */
class AdminAccountRoleServiceTest {

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

    private AdminAccount account(long id, AdminRole role) {
        AdminAccount a = AdminAccount.create("x@y", "X", role, 1L);
        ReflectionTestUtils.setField(a, "id", id);
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    // ---------- 建号 ----------

    @Test
    void createWithTemplatedRoleDoesNotWritePermissionRows() {
        // 模板角色的权限在登录时按角色解析，落表反而会造出第二份可能过期的真相。
        service.createAccount("ops@x", "运营", AdminRole.OPERATIONS,
                List.of(AdminPermissions.SHOP_COST_VIEW), 1L);

        verify(permissions, never()).saveAll(any());
        ArgumentCaptor<AdminAccount> saved = ArgumentCaptor.forClass(AdminAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(AdminRole.OPERATIONS);
        assertThat(saved.getValue().getAccountType()).isEqualTo(AdminAccountType.STAFF);
    }

    @Test
    void createWithTemplatedRoleIgnoresTickedPermissions() {
        // 勾了 shop.cost_view 也不该生效——否则建号页就能绕开 NFR-11 的角色边界。
        service.createAccount("ops@x", "运营", AdminRole.FULFILLMENT,
                List.of(AdminPermissions.SHOP_COST_VIEW, AdminPermissions.SHOP_FINANCE_VIEW), 1L);
        verify(permissions, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createWithCustomRoleWritesTickedPermissions() {
        service.createAccount("c@x", "特例", AdminRole.CUSTOM,
                List.of(AdminPermissions.USER_VIEW, AdminPermissions.CONTENT_VIEW), 1L);

        ArgumentCaptor<List<AdminAccountPermission>> rows = ArgumentCaptor.forClass(List.class);
        verify(permissions).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(AdminAccountPermission::getPermissionCode)
                .containsExactlyInAnyOrder(AdminPermissions.USER_VIEW, AdminPermissions.CONTENT_VIEW);
    }

    @Test
    void createRejectsNullRole() {
        assertThatThrownBy(() -> service.createAccount("x@y", "X", null, List.of(), 1L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void createSuperAdminRoleStillChecksCap() {
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(5L);
        assertThatThrownBy(() -> service.createAccount("boss@x", "老板", AdminRole.SUPER_ADMIN,
                List.of(), 1L)).isInstanceOf(AppException.class);
    }

    // ---------- 改角色 ----------

    @Test
    void changeRoleUpdatesRoleAndAccountTypeTogether() {
        AdminAccount a = account(7L, AdminRole.OPERATIONS);
        service.changeRole(7L, AdminRole.FINANCE, 1L);

        assertThat(a.getRole()).isEqualTo(AdminRole.FINANCE);
        assertThat(a.getAccountType()).isEqualTo(AdminAccountType.STAFF);
        verify(auditService).record(eq(1L), eq(AuditActions.ACCOUNT_ROLE_CHANGED),
                eq("ADMIN_ACCOUNT"), eq("7"), any());
    }

    @Test
    void changeRoleToTemplatedClearsStalePermissionRows() {
        // 从自定义转模板角色时，库里那份勾选行已经不生效了；留着只会被日后误读成「他还有这些权限」。
        account(7L, AdminRole.CUSTOM);
        when(permissions.findByAccountId(7L)).thenReturn(
                List.of(new AdminAccountPermission(7L, AdminPermissions.USER_VIEW)));

        service.changeRole(7L, AdminRole.SUPPORT, 1L);

        verify(permissions).deleteByAccountId(7L);
        verify(permissions, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeRoleToCustomCarriesCurrentPermissionsAsStartingPoint() {
        // 「基于发货岗再减两个权限」是最常见的诉求；转自定义时若清空，就得从 60 个复选框里从头勾。
        account(7L, AdminRole.FULFILLMENT);

        service.changeRole(7L, AdminRole.CUSTOM, 1L);

        ArgumentCaptor<List<AdminAccountPermission>> rows = ArgumentCaptor.forClass(List.class);
        verify(permissions).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(AdminAccountPermission::getPermissionCode)
                .containsExactlyInAnyOrderElementsOf(AdminRole.FULFILLMENT.permissionCodes());
    }

    @Test
    void changeRoleIsIdempotent() {
        account(7L, AdminRole.SUPPORT);
        service.changeRole(7L, AdminRole.SUPPORT, 1L);
        verify(accounts, never()).save(any());
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cannotDemoteLastActiveSuperAdmin() {
        // 降级最后一个超管等于变相停用他——同 A3 死锁，只是换了个入口。
        account(7L, AdminRole.SUPER_ADMIN);
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(7L, AdminRole.OPS_MANAGER, 1L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void promotingToSuperAdminChecksCap() {
        account(7L, AdminRole.OPS_MANAGER);
        when(accounts.countByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.changeRole(7L, AdminRole.SUPER_ADMIN, 1L))
                .isInstanceOf(AppException.class);
    }

    // ---------- 逐码改权限的护栏 ----------

    @Test
    void templatedRoleRejectsPerCodePermissionEdit() {
        account(7L, AdminRole.OPERATIONS);
        assertThatThrownBy(() -> service.updatePermissions(7L,
                List.of(AdminPermissions.SHOP_COST_VIEW), 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("自定义");
    }

    @Test
    void customRoleStillAllowsPerCodePermissionEdit() {
        account(7L, AdminRole.CUSTOM);
        service.updatePermissions(7L, List.of(AdminPermissions.USER_VIEW), 1L);
        verify(permissions).saveAll(any());
    }

    // ---------- 列表回显 ----------

    @Test
    void listShowsEffectivePermissionsForTemplatedRoles() {
        AdminAccount ops = AdminAccount.create("a@x", "A", AdminRole.OPERATIONS, 1L);
        ReflectionTestUtils.setField(ops, "id", 1L);
        AdminAccount custom = AdminAccount.create("b@x", "B", AdminRole.CUSTOM, 1L);
        ReflectionTestUtils.setField(custom, "id", 2L);
        when(accounts.findAll()).thenReturn(List.of(ops, custom));
        when(permissions.findByAccountId(2L)).thenReturn(
                List.of(new AdminAccountPermission(2L, AdminPermissions.USER_VIEW)));

        List<AdminAccountView> views = service.list();

        assertThat(views.get(0).role()).isEqualTo(AdminRole.OPERATIONS);
        assertThat(views.get(0).templated()).isTrue();
        assertThat(views.get(0).permissionCodes())
                .containsExactlyInAnyOrderElementsOf(AdminRole.OPERATIONS.permissionCodes());
        assertThat(views.get(1).templated()).isFalse();
        assertThat(views.get(1).permissionCodes()).containsExactly(AdminPermissions.USER_VIEW);
    }
}
