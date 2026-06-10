package com.tailtopia.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 兽医账密登录请求（Story 5.1）。明文密码绝不落日志。 */
public record VetLoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
