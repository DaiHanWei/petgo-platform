package com.tailtopia.admin.roles.domain;

import java.io.Serializable;
import java.util.Objects;

/** {@link AdminRolePermission} 复合主键（role_id + permission_code），与 {@code AdminAccountPermissionId} 同做法。 */
public class AdminRolePermissionId implements Serializable {

    private Long roleId;
    private String permissionCode;

    public AdminRolePermissionId() {
    }

    public AdminRolePermissionId(Long roleId, String permissionCode) {
        this.roleId = roleId;
        this.permissionCode = permissionCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdminRolePermissionId that)) {
            return false;
        }
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionCode, that.permissionCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionCode);
    }
}
