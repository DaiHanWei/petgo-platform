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
        List<String> permissionCodes,
        Long roleId,
        String roleName) {

    /** Story 1.6 前的构造形态（无表角色信息）。 */
    public AdminAccountView(Long id, String larkEmail, String displayName, AdminAccountType accountType,
            AdminRole role, AdminAccountStatus status, List<String> permissionCodes) {
        this(id, larkEmail, displayName, accountType, role, status, permissionCodes, null, null);
    }

    /** 权限是否由岗位角色模板决定（UI 据此把勾选框置为只读）。 */
    public boolean templated() {
        return role != null && role.isTemplated();
    }

    /** 是否引用运营自建的自定义角色（Story 1.6：列表加「自定义」徽标）。 */
    public boolean roleCustom() {
        return role == AdminRole.ROLE_TEMPLATE;
    }

    /** 角色名 i18n key（枚举 / 预置角色）；自定义角色用 {@link #roleName()}。 */
    public String roleNameKey() {
        return role == null ? null : role.titleCode();
    }

    /** 与角色下拉选项值同编码（Story 1.6 AC1）：自定义 {@code tpl:<id>}，其余 {@code enum:<NAME>}。 */
    public String selectedValue() {
        if (role == null) {
            return null;
        }
        return role == AdminRole.ROLE_TEMPLATE ? "tpl:" + roleId : "enum:" + role.name();
    }
}
