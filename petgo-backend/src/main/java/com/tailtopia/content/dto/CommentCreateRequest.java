package com.petgo.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表评论/回复请求（Story 3.5）。author 取自 JWT，不在 DTO。服务端权威校验 ≤200 字。
 */
public record CommentCreateRequest(
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 200, message = "评论不能超过 200 字") String body) {
}
