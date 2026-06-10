package com.tailtopia.auth.dto;

/**
 * 兽医登录响应（Story 5.1）：自签令牌 + 展示名 + 角色（恒 VET）。
 * 前端按 {@code role=VET} 分流到兽医工作台壳。绝不回显任何密码/哈希。
 */
public record VetLoginResponse(
        String accessToken,
        String refreshToken,
        String displayName,
        String role) {
}
