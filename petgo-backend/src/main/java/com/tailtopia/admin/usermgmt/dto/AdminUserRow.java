package com.tailtopia.admin.usermgmt.dto;

import java.time.Instant;

/**
 * 后台用户搜索结果行（Story 3.1，只读）。{@code deactivated} 由 Story 3.2 落地；3.2 前恒 false（显示「正常」）。
 */
public record AdminUserRow(long id, String displayName, String email, Instant createdAt,
        boolean deactivated) {
}
