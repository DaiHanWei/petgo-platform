package com.tailtopia.content.web;

import com.tailtopia.content.dto.LikeResponse;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.content.service.PostLikersService;
import com.tailtopia.social.dto.FollowListPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容点赞开关端点（Story 3.4）。**需 JWT**（/api/v1 默认认证，未登录 → 401 → 前端 FR-0C）。
 * 幂等：重复 POST 已赞不叠加、DELETE 未赞亦成功；返回 {@code {liked, likeCount}}。
 */
@RestController
public class LikeController {

    private final LikeService likeService;
    private final PostLikersService likersService;

    public LikeController(LikeService likeService, PostLikersService likersService) {
        this.likeService = likeService;
        this.likersService = likersService;
    }

    /** 点赞人列表（V1.3.4）：仅 role=USER；帖子不可见 → 404。 */
    @GetMapping("/api/v1/content-posts/{id}/likers")
    public FollowListPage likers(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = com.tailtopia.shared.web.ClientCapabilities.HEADER, required = false) String capabilities) {
        return likersService.likers(id, currentUserId(jwt), cursor, com.tailtopia.shared.web.ClientCapabilities.includesVideo(capabilities));
    }

    @PostMapping("/api/v1/content-posts/{id}/like")
    public LikeResponse like(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return likeService.like(id, currentUserId(jwt));
    }

    @DeleteMapping("/api/v1/content-posts/{id}/like")
    public LikeResponse unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return likeService.unlike(id, currentUserId(jwt));
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
