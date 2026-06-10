package com.tailtopia.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin 开户表单（Story 5.1）。明文密码仅一次性提交，绝不回显/落日志。
 */
public class CreateVetForm {

    @NotBlank(message = "兽医昵称不能为空")
    @Size(max = 64)
    private String displayName;

    @NotBlank(message = "登录账号不能为空")
    @Size(min = 3, max = 64, message = "登录账号 3-64 位")
    private String username;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 72, message = "密码至少 8 位")
    private String password;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
