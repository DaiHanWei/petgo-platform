package com.tailtopia.content.web;

import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注流（V1.3.4 Following 流）：{@code GET /api/v1/me/following-feed}。
 * 仅登录（{@code /api/v1/me/**} 走默认 authenticated，游客 401）。
 */
@RestController
public class FollowingFeedController {

    private final FeedService feedService;

    public FollowingFeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/api/v1/me/following-feed")
    public FeedPageResponse followingFeed(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = com.tailtopia.shared.web.ClientCapabilities.HEADER, required = false) String capabilities) {
        return feedService.loadFollowingFeed(currentUserId(jwt), cursor, com.tailtopia.shared.web.ClientCapabilities.includesVideo(capabilities));
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
