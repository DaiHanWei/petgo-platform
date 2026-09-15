package com.tailtopia.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发表评论/回复请求（Story 3.5）。author 取自 JWT，不在 DTO。服务端权威校验 ≤200 字。
 *
 * @param body             评论正文 ≤200。里面的「@昵称」只是**给人读的文本**，
 *                         可点的身份在 {@link #mentionedUserIds()}（AD-10 Rule 4）。
 * @param mentionedUserIds V1.3.0 batch-b1 Story 3.2 · AC4/AC5：评论里 @ 到的 userId，
 *                         最多 5 人。🔴 <b>存 userId 不存昵称</b> —— 存昵称的话对方改名后
 *                         历史 @ 全部失效、点不动，也无法判断拉黑关系。
 *                         省略 / null = 没 @ 人（老客户端行为不变）。
 *                         ⚠️ 这里只是入口校验，权威过滤在 {@code MentionSanitizer}。
 */
public record CommentCreateRequest(
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 200, message = "评论不能超过 200 字") String body,
        @Size(max = 5, message = "最多只能提及 5 人") List<Long> mentionedUserIds) {

    /** 兼容不带 @ 名单的调用（老客户端 / 既有测试）：视为没 @ 任何人。 */
    public CommentCreateRequest(String body) {
        this(body, null);
    }
}
