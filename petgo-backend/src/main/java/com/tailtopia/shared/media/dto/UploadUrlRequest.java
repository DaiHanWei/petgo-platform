package com.tailtopia.shared.media.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 预签名上传 URL 请求（{@code POST /api/v1/media/upload-url}）。
 *
 * @param scope       目标隐私域（PUBLIC/PRIVATE），决定桶、前缀与是否公开读
 * @param contentType 拟上传 MIME（如 {@code image/jpeg}）；计入预签名，客户端 PUT 须发同名 Content-Type
 */
public record UploadUrlRequest(
        @NotNull(message = "scope 不能为空") com.tailtopia.shared.media.MediaScope scope,
        String contentType) {
}
