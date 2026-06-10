package com.petgo.content.web;

import com.petgo.content.dto.LikeResponse;
import com.petgo.content.service.LikeService;
import com.petgo.shared.error.AppException;
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

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
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
