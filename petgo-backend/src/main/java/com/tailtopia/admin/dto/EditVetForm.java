package com.tailtopia.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 编辑兽医资料表单（Story 2.4）。不含密码（重置走独立端点）。 */
public class EditVetForm {

    @NotBlank(message = "{admin.vets.validation.displayNameRequired}")
    @Size(max = 64, message = "{admin.vets.validation.displayNameTooLong}")
    private String displayName;

    @NotBlank(message = "{admin.vets.validation.usernameRequired}")
    @Email(message = "{admin.vets.validation.usernameInvalid}")
    @Size(max = 64, message = "{admin.vets.validation.usernameTooLong}")
    private String username;

    @Size(max = 32, message = "{admin.vets.validation.contactPhoneTooLong}")
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
