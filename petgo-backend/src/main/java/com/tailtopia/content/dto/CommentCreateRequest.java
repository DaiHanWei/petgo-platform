package com.tailtopia.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表评论/回复请求（Story 3.5）。author 取自 JWT，不在 DTO。服务端权威校验 ≤200 字。
 */
public record CommentCreateRequest(
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 200, message = "评论不能超过 200 字") String body,
        /**
         * V1.3.0 预留（D-35 / 契约 X-2）：二级回复所回复的目标评论 id。可空、无校验；
         * <b>本版服务端忽略</b>（不写库、不影响两级归并），App 不传照常 201。
         */
        Long replyToCommentId) {
}
