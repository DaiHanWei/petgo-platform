package com.tailtopia.admin.account.dto;

/**
 * Lark OAuth 回调解析出的用户身份（Story 1.2）。仅承载门控所需字段，不落库、不入日志（脱敏）。
 *
 * @param email          个人邮箱（可能为空）
 * @param enterpriseEmail 企业邮箱（租户内已验证，优先用作白名单匹配键）
 * @param tenantKey      企业租户标识（校验是否本企业）
 * @param openId         Lark open_id（仅日志关联用，不作身份键）
 * @param emailVerified  邮箱是否企业已验证
 */
public record LarkIdentity(
        String email,
        String enterpriseEmail,
        String tenantKey,
        String openId,
        boolean emailVerified) {

    /** 白名单匹配用邮箱：优先企业邮箱，回退个人邮箱。 */
    public String resolvedEmail() {
        if (enterpriseEmail != null && !enterpriseEmail.isBlank()) {
            return enterpriseEmail;
        }
        return email;
    }
}
