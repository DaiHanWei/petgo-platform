package com.tailtopia.admin.account.dto;

import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import java.util.List;

/**
 * 后台账号列表行视图（Story 1.5）。{@code permissionCodes} 为该账号已授模块权限（SUPER_ADMIN 为空——隐式全权）。
 */
public record AdminAccountView(
        Long id,
        String larkEmail,
        String displayName,
        AdminAccountType accountType,
        AdminAccountStatus status,
        List<String> permissionCodes) {
}
