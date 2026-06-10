package com.petgo.shared.security;

/**
 * 校验通过的 Google 身份（从 ID Token 解析）。仅承载建号所需字段，不含敏感令牌。
 */
public record GoogleIdentity(String sub, String email, String displayName, String avatarUrl) {
}
