package com.tailtopia.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Google 登录请求体：客户端从系统账号选择器取得的 Google ID Token。 */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
