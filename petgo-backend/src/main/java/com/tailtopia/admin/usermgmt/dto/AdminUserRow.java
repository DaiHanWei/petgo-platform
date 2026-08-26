package com.tailtopia.admin.usermgmt.dto;

import java.time.Instant;

/**
 * 后台用户搜索结果行（Story 3.1，只读）。{@code deactivated} 由 Story 3.2 落地；3.2 前恒 false（显示「正常」）。
 * {@code deleted}=已注销：{@code displayName}/{@code email} 取注销前快照列（仅后台展示，见 User.anonymizeForDeletion）。
 */
/**
 * 后台用户列表的一行。
 *
 * @param phoneFilled 手机号是否已填写（V1.1.6 Story 11.4）。
 *                    🛡 **只带布尔、不带号码本身** —— 列表页不需要明文，
 *                    少一个地方出现 PII 就少一个泄漏面。号码只在详情页且有权限时才装。
 *                    判据是 `phone` 非 null 且非空串（FR-70 允许留空保存以撤回号码）。
 */
public record AdminUserRow(long id, String displayName, String email, Instant createdAt,
        boolean deactivated, boolean deleted, boolean phoneFilled) {
}
