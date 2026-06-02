package com.petgo.content.web;

import com.petgo.content.dto.FeedPageResponse;
import com.petgo.content.service.FeedService;
import com.petgo.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的发布」端点（Story 7.1，FR-36）。{@code GET /api/v1/me/posts}：当前用户三类混合内容时间倒序游标分页。
 *
 * <p>仅返回 JWT {@code sub} 本人 {@code author_id} 且未软删内容；删除复用 FR-36 既有端点（本故事不新写）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeContentController {

    private final FeedService feedService;

    public MeContentController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/posts")
    public FeedPageResponse myPosts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        return feedService.myPosts(currentUserId(jwt), cursor);
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
