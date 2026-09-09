package com.tailtopia.admin.roles.dto;

import com.tailtopia.admin.account.domain.AdminRole;

/**
 * 账号页「选了哪个角色」（V1.3.0 Story 1.6）：{@code role} 列与 {@code role_id} 成对写（1-4 对照表）。
 * 由 {@code AdminRoleService.resolveSelection} 从下拉选项值（{@code enum:<NAME>} / {@code tpl:<id>}）解析：
 * 预置四岗 → {@code role=<CODE>, roleId=<表 id>}；自定义 → {@code role=ROLE_TEMPLATE, roleId=<id>}；其余 → {@code roleId=null}。
 *
 * @param label 审计 / 横幅用的可读名（预置记 code，自定义记 name(code)）
 */
public record RoleSelection(AdminRole role, Long roleId, String label) {

    public static RoleSelection ofEnum(AdminRole role, Long roleId) {
        return new RoleSelection(role, roleId, role.name());
    }

    /** 下拉选项值：预置 / 枚举用 {@code enum:<NAME>}，自定义用 {@code tpl:<id>}。 */
    public String value() {
        return role == AdminRole.ROLE_TEMPLATE ? "tpl:" + roleId : "enum:" + role.name();
    }
}
