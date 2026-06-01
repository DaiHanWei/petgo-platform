package com.petgo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** refresh 请求体：客户端持有的 refresh 明文。 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
