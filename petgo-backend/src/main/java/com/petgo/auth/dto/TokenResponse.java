package com.petgo.auth.dto;

/** 令牌响应（refresh 轮换后返回新的 access + refresh）。 */
public record TokenResponse(String accessToken, String refreshToken) {
}
