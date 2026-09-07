package com.tailtopia.admin.account.dto;

import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminRole;
import java.util.List;

/**
 * 后台账号列表行视图（Story 1.5；V165 增 {@code role}）。
 *
 * <p>{@code permissionCodes} 是该账号<b>实际生效</b>的权限码，与登录时装载的一致：
 * SUPER_ADMIN 为空（隐式全权）、模板角色取角色定义、{@code CUSTOM} 取勾选行。
 */
public record AdminAccountView(
        Long id,
        String larkEmail,
        String displayName,
        AdminAccountType accountType,
        AdminRole role,
        AdminAccountStatus status,
        List<String> permissionCodes) {

    /** 权限是否由岗位角色模板决定（UI 据此把勾选框置为只读）。 */
    public boolean templated() {
        return role != null && role.isTemplated();
    }
}
