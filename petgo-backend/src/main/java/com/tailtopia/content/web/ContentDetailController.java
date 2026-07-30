package com.tailtopia.content.web;

import com.tailtopia.content.dto.CommentPageResponse;
import com.tailtopia.content.dto.ContentDetailResponse;
import com.tailtopia.content.service.CommentQueryService;
import com.tailtopia.content.service.ContentDetailService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容详情 + 评论只读端点（Story 3.3）。**只读对游客可见**（/api/v1 例外，FR-0A）。
 *
 * <ul>
 *   <li>{@code GET /content-posts/{id}} — 详情（多态：404 统一文案 / 注销作者 200 匿名化）。</li>
 *   <li>{@code GET /content-posts/{id}/comments} — 一级评论分页（内嵌前 3 二级 + replyCount）。</li>
 *   <li>{@code GET /comments/{parentId}/replies} — 展开某一级评论全部二级回复。</li>
 * </ul>
 * 评论写入在 Story 3.5；点赞计数/liked 在 Story 3.4 接入。
 */
@RestController
public class ContentDetailController {

    private final ContentDetailService detailService;
    private final CommentQueryService commentQueryService;

    public ContentDetailController(ContentDetailService detailService,
            CommentQueryService commentQueryService) {
        this.detailService = detailService;
        this.commentQueryService = commentQueryService;
    }

    @GetMapping("/api/v1/content-posts/{id}")
    public ContentDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return detailService.getDetail(id, viewerId(jwt));
    }

    @GetMapping("/api/v1/content-posts/{id}/comments")
    public CommentPageResponse comments(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return commentQueryService.topLevel(id, cursor, viewerId(jwt));
    }

    @GetMapping("/api/v1/comments/{parentId}/replies")
    public CommentPageResponse replies(@AuthenticationPrincipal Jwt jwt, @PathVariable long parentId,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return commentQueryService.replies(parentId, cursor, viewerId(jwt));
    }

    /** 登录用户 id（游客 null）。 */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
