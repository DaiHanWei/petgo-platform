package com.tailtopia.admin.roles.dto;

import com.tailtopia.admin.account.domain.AdminRole;

/**
 * 账号页角色下拉的一项（V1.3.0 Story 1.6）。顺序：超管、运营主管、预置四岗、自定义若干、自定义勾选（CUSTOM）。
 *
 * @param value     选项值编码：{@code enum:<NAME>} / {@code tpl:<id>}
 * @param label     已本地化的显示名
 * @param descKey   职责说明 i18n key（枚举 / 预置角色有；自定义为 null）
 * @param fromTable 是否来自 admin_roles 表（预置四岗 + 自定义）
 * @param custom    是否运营自建（ROLE_TEMPLATE 引用）
 * @param roleId    表行 id（枚举无表行为 null）
 * @param enumRole  对应的 role 列枚举值
 */
public record RoleOption(String value, String label, String descKey, boolean fromTable, boolean custom,
        Long roleId, AdminRole enumRole) {

    public boolean pickPermissions() {
        return enumRole == AdminRole.CUSTOM;
    }

    public boolean showPreview() {
        return enumRole != AdminRole.CUSTOM && enumRole != AdminRole.SUPER_ADMIN;
    }
}
