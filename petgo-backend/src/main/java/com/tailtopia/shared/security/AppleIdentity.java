package com.tailtopia.shared.security;

/**
 * 校验通过的 Apple 身份（从 identity token 解析）。仅承载建号所需字段，不含敏感令牌。
 *
 * <p>注意：Apple 的 identity token <b>不含用户姓名</b>（name 仅在首次授权时由客户端
 * 凭证单独返回，不进 token），故此处只有 {@code sub} 与可选 {@code email}；昵称由新用户引导补齐。
 */
public record AppleIdentity(String sub, String email) {
}
