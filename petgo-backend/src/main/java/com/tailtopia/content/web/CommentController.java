package com.tailtopia.content.web;

import com.tailtopia.content.dto.CommentCreateRequest;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论写入/删除端点（Story 3.5）。**需 JWT**（未登录 → 401 → 前端 FR-0C）。读取在 Story 3.3。
 *
 * <ul>
 *   <li>{@code POST /content-posts/{postId}/comments} — 一级评论（≤200 字，服务端权威）。</li>
 *   <li>{@code POST /comments/{parentId}/replies} — 二级回复（归并到一级，绝不三级）。</li>
 *   <li>{@code DELETE /comments/{id}} — 删除（评论作者 / 内容作者，删一级级联删二级）。</li>
 * </ul>
 */
@RestController
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping("/api/v1/content-posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse comment(@AuthenticationPrincipal Jwt jwt, @PathVariable long postId,
            @Valid @RequestBody CommentCreateRequest req) {
        return commentService.createTopLevel(postId, currentUserId(jwt), req.body());
    }

    @PostMapping("/api/v1/comments/{parentId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse reply(@AuthenticationPrincipal Jwt jwt, @PathVariable long parentId,
            @Valid @RequestBody CommentCreateRequest req) {
        return commentService.createReply(parentId, currentUserId(jwt), req.body());
    }

    @DeleteMapping("/api/v1/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        commentService.delete(id, currentUserId(jwt));
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
