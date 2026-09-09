package com.tailtopia.admin.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

/** 创建后台账号表单（Story 1.5；V165 改为选岗位角色）。 */
public class CreateAdminAccountForm {

    @NotBlank
    @Email
    private String larkEmail;

    @NotBlank
    private String displayName;

    /**
     * 岗位角色下拉选项值（V1.3.0 Story 1.6）：{@code enum:<NAME>} 或 {@code tpl:<id>}，
     * 由 {@code AdminRoleService.resolveSelection} 解析成 role + role_id；账号类型由角色推导。
     */
    @NotBlank
    private String roleValue = "enum:OPERATIONS";

    /** 勾选的模块权限码——<b>仅 {@code CUSTOM} 角色生效</b>；模板角色与超管忽略此项。 */
    private List<String> permissionCodes = new ArrayList<>();

    public String getLarkEmail() {
        return larkEmail;
    }

    public void setLarkEmail(String larkEmail) {
        this.larkEmail = larkEmail;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRoleValue() {
        return roleValue;
    }

    public void setRoleValue(String roleValue) {
        this.roleValue = roleValue;
    }

    public List<String> getPermissionCodes() {
        return permissionCodes;
    }

    public void setPermissionCodes(List<String> permissionCodes) {
        this.permissionCodes = permissionCodes;
    }
}
