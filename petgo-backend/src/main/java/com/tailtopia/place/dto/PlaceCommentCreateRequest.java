package com.tailtopia.place.dto;

import com.tailtopia.place.domain.PlaceCommentAttitude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表场所评论请求（V1.3.0 batch-b1 Story 1.7 · AC2/AC3）。作者取自 JWT，不在 DTO。
 *
 * <p>🔴 **没有 parentId** —— 一级 only（AC2）。端点形状上就没有回复的位置。
 *
 * <p>🔴 **attitude 可以为 null**（AC3：可以不表态）。反过来
 * **不允许"只表态不写评论"**：`body` 是 {@code @NotBlank} 的（B1-D3：不写评论不能表态）。
 *
 * @param body     评论正文，≤200 字（服务端权威，与既有内容评论同一上限）
 * @param attitude 二元态度，可空
 */
public record PlaceCommentCreateRequest(
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 200, message = "评论不能超过 200 字") String body,
        PlaceCommentAttitude attitude) {
}
