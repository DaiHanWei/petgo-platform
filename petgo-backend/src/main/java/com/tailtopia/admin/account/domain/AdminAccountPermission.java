package com.tailtopia.admin.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * STAFF 后台账号的模块权限授权行（Story 1.5）。映射 1.1 已建的 {@code admin_account_permissions} 表
 * （V31，复合主键 {@code account_id}+{@code permission_code}，FK→admin_accounts ON DELETE CASCADE）。
 *
 * <p>{@code permission_code} 为全小写点分 {@code <模块>.<动作>}（见 {@link AdminPermissions}），
 * 装载为 Spring {@code SimpleGrantedAuthority} 供 {@code @PreAuthorize("hasAuthority('...')")} 门控。
 * SUPER_ADMIN 隐式全权、不进本表。
 */
@Entity
@Table(name = "admin_account_permissions")
@IdClass(AdminAccountPermissionId.class)
public class AdminAccountPermission {

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Id
    @Column(name = "permission_code", nullable = false, length = 64)
    private String permissionCode;

    protected AdminAccountPermission() {
    }

    public AdminAccountPermission(Long accountId, String permissionCode) {
        this.accountId = accountId;
        this.permissionCode = permissionCode;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }
}
