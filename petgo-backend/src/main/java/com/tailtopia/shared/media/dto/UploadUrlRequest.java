package com.tailtopia.shared.media.dto;

import com.tailtopia.shared.media.MediaScope;
import com.tailtopia.shared.media.UploadPurpose;
import jakarta.validation.constraints.NotNull;

/**
 * 预签名上传 URL 请求（{@code POST /api/v1/media/upload-url}）。
 *
 * @param scope       目标隐私域（PUBLIC/PRIVATE），决定桶、前缀与是否公开读
 * @param contentType 拟上传 MIME，只接受图片（jpeg/png/webp/heic）；计入预签名，客户端 PUT 须发同名 Content-Type
 * @param purpose     可选用途；不带 = 普通图片。{@code VIDEO_COVER} = 视频封面（专题线 video Story 1.2）
 */
public record UploadUrlRequest(
        @NotNull(message = "scope 不能为空") MediaScope scope,
        String contentType,
        UploadPurpose purpose) {

    /** 不带用途的普通图片上传（兼容既有调用方）。 */
    public UploadUrlRequest(MediaScope scope, String contentType) {
        this(scope, contentType, null);
    }
}
