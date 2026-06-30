package com.tailtopia.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 编辑兽医资料表单（Story 2.4）。不含密码（重置走独立端点）。 */
public class EditVetForm {

    @NotBlank(message = "兽医昵称不能为空")
    @Size(max = 64)
    private String displayName;

    @NotBlank(message = "登录邮箱不能为空")
    @Email(message = "登录邮箱格式不正确")
    @Size(max = 64)
    private String username;

    @Size(max = 32, message = "联系手机号过长")
    private String contactPhone;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }
}
