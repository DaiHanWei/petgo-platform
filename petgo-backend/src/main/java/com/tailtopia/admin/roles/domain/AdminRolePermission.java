package com.tailtopia.admin.roles.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * 角色 → 权限码授权行（V1.3.0 Story 1.4，表 {@code admin_role_permissions}，复合主键，FK→admin_roles ON DELETE CASCADE）。
 * {@code permission_code} 须属 {@code AdminPermissions.ALL}；登录时按 {@code admin_accounts.role_id} 装载为 authority。
 */
@Entity
@Table(name = "admin_role_permissions")
@IdClass(AdminRolePermissionId.class)
public class AdminRolePermission {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Id
    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;

    protected AdminRolePermission() {
    }

    public AdminRolePermission(Long roleId, String permissionCode) {
        this.roleId = roleId;
        this.permissionCode = permissionCode;
    }

    public Long getRoleId() {
        return roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }
}
