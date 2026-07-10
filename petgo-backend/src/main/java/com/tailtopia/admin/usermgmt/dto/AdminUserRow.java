package com.tailtopia.admin.usermgmt.dto;

import java.time.Instant;

/**
 * 后台用户搜索结果行（Story 3.1，只读）。{@code deactivated} 由 Story 3.2 落地；3.2 前恒 false（显示「正常」）。
 * {@code deleted}=已注销：{@code displayName}/{@code email} 取注销前快照列（仅后台展示，见 User.anonymizeForDeletion）。
 */
public record AdminUserRow(long id, String displayName, String email, Instant createdAt,
        boolean deactivated, boolean deleted) {
}
