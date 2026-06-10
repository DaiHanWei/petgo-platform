package com.petgo.shared.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * STS 上传凭证请求（{@code POST /api/v1/media/sts-credentials}）。
 *
 * @param scope       目标隐私域（PUBLIC/PRIVATE），决定桶与前缀
 * @param contentType 拟上传 MIME（如 {@code image/jpeg}），仅用于前端校验回显，可空
 * @param count       本批拟上传张数（1..9，默认 1）
 */
public record StsCredentialRequest(
        @NotNull(message = "scope 不能为空") com.petgo.shared.media.MediaScope scope,
        String contentType,
        @Min(value = 1, message = "count 至少为 1") @Max(value = 9, message = "count 最多 9") Integer count) {
}
