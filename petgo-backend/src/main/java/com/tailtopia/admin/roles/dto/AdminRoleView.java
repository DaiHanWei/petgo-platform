package com.tailtopia.admin.roles.dto;

import com.tailtopia.admin.roles.domain.RoleType;

/** 角色配置页列表行（Story 1.5）。SYSTEM 行名称用 {@code nameKey} 三语，CUSTOM 行用 {@code name} 单语。 */
public record AdminRoleView(long id, String code, String name, String nameKey, RoleType roleType,
        int permissionCount, long accountCount) {

    public boolean system() {
        return roleType == RoleType.SYSTEM;
    }
}
