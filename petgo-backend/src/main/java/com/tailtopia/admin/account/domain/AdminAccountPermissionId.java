package com.tailtopia.admin.account.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link AdminAccountPermission} 复合主键（Story 1.5）：{@code account_id} + {@code permission_code}。
 */
public class AdminAccountPermissionId implements Serializable {

    private Long accountId;
    private String permissionCode;

    public AdminAccountPermissionId() {
    }

    public AdminAccountPermissionId(Long accountId, String permissionCode) {
        this.accountId = accountId;
        this.permissionCode = permissionCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdminAccountPermissionId that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(permissionCode, that.permissionCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, permissionCode);
    }
}
