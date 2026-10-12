package com.tailtopia.social.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.dto.FollowResultResponse;
import com.tailtopia.social.service.FollowService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注 / 取关端点（V1.3.4 follow · Story 1.1 · AD-4）。{@code /api/v1/users/{token}/follow}。
 *
 * <p>路径用<b>不可枚举用户 token</b>（D-2），不用数字 id。鉴权：{@code SecurityConfig} 对该路径
 * {@code hasRole("USER")}（兽医 token 403、游客 401）。两个动词都幂等。
 */
@RestController
@RequestMapping("/api/v1/users/{token}/follow")
public class FollowController {

    private final FollowService follows;

    public FollowController(FollowService follows) {
        this.follows = follows;
    }

    @PutMapping
    public FollowResultResponse follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return follows.follow(currentUserId(jwt), token);
    }

    @DeleteMapping
    public FollowResultResponse unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return follows.unfollow(currentUserId(jwt), token);
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
