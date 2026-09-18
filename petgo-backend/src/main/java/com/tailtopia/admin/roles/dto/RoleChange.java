package com.tailtopia.admin.roles.dto;

/** 角色权限保存结果（Story 1.5）：增删条数与受影响账号数，供横幅文案。 */
public record RoleChange(long roleId, String displayCode, int added, int removed, long affectedAccounts) {

    public boolean noOp() {
        return added == 0 && removed == 0;
    }
}
