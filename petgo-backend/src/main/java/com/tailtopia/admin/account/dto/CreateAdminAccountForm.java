package com.tailtopia.admin.account.dto;

import com.tailtopia.admin.account.domain.AdminRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/** 创建后台账号表单（Story 1.5；V165 改为选岗位角色）。 */
public class CreateAdminAccountForm {

    @NotBlank
    @Email
    private String larkEmail;

    @NotBlank
    private String displayName;

    /** 岗位角色（V165）。账号类型由 {@link AdminRole#accountType()} 推导，表单不再单独选类型。 */
    @NotNull
    private AdminRole role = AdminRole.OPERATIONS;

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

    public AdminRole getRole() {
        return role;
    }

    public void setRole(AdminRole role) {
        this.role = role;
    }

    public List<String> getPermissionCodes() {
        return permissionCodes;
    }

    public void setPermissionCodes(List<String> permissionCodes) {
        this.permissionCodes = permissionCodes;
    }
}
