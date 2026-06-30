package com.tailtopia.admin.account.dto;

import com.tailtopia.admin.account.domain.AdminAccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/** 创建后台账号表单（Story 1.5）。 */
public class CreateAdminAccountForm {

    @NotBlank
    @Email
    private String larkEmail;

    @NotBlank
    private String displayName;

    @NotNull
    private AdminAccountType accountType = AdminAccountType.STAFF;

    /** 勾选的模块权限码（STAFF 用；SUPER_ADMIN 忽略）。 */
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

    public AdminAccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AdminAccountType accountType) {
        this.accountType = accountType;
    }

    public List<String> getPermissionCodes() {
        return permissionCodes;
    }

    public void setPermissionCodes(List<String> permissionCodes) {
        this.permissionCodes = permissionCodes;
    }
}
