package com.tailtopia.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Apple 登录请求体（FR-44）：客户端 sign_in_with_apple 取得的 identity token（JWT）。 */
public record AppleLoginRequest(@NotBlank String identityToken) {
}
