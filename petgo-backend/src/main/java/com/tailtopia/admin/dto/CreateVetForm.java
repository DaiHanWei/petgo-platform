package com.tailtopia.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin 开户表单（Story 5.1；2.3 加联系手机号 + username 语义=登录邮箱）。明文密码仅一次性提交，绝不回显/落日志。
 */
public class CreateVetForm {

    @NotBlank(message = "{admin.vets.validation.displayNameRequired}")
    @Size(max = 64, message = "{admin.vets.validation.displayNameTooLong}")
    private String displayName;

    /** 登录邮箱（沿用 username 列，不改名；2.3 起加邮箱格式校验）。 */
    @NotBlank(message = "{admin.vets.validation.usernameRequired}")
    @Email(message = "{admin.vets.validation.usernameInvalid}")
    @Size(max = 64, message = "{admin.vets.validation.usernameTooLong}")
    private String username;

    /** 运营联系手机号（非登录凭证）。 */
    @NotBlank(message = "{admin.vets.validation.contactPhoneRequired}")
    @Size(max = 32, message = "{admin.vets.validation.contactPhoneTooLong}")
    private String contactPhone;

    @NotBlank(message = "{admin.vets.validation.passwordRequired}")
    @Size(min = 8, max = 72, message = "{admin.vets.validation.passwordLength}")
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
