package com.tailtopia.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin 开户表单（Story 5.1；2.3 加联系手机号 + username 语义=登录邮箱）。明文密码仅一次性提交，绝不回显/落日志。
 */
public class CreateVetForm {

    @NotBlank(message = "兽医昵称不能为空")
    @Size(max = 64)
    private String displayName;

    /** 登录邮箱（沿用 username 列，不改名；2.3 起加邮箱格式校验）。 */
    @NotBlank(message = "登录邮箱不能为空")
    @Email(message = "登录邮箱格式不正确")
    @Size(max = 64)
    private String username;

    /** 运营联系手机号（非登录凭证）。 */
    @NotBlank(message = "联系手机号不能为空")
    @Size(max = 32, message = "联系手机号过长")
    private String contactPhone;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 72, message = "密码至少 8 位")
    private String password;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
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
