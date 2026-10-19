package com.tailtopia.collect.web;

import com.tailtopia.collect.dto.CollectResponse;
import com.tailtopia.collect.service.CollectService;
import com.tailtopia.collect.service.PlaceCollectService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏接口（V1.4.0 ④ Story 2.1 · AD-8 / AD-9）。仅 role=USER（SecurityConfig 精确规则），只作用于 JWT {@code sub} 本人。
 *
 * <p>🔴 场所的两个端点也放这里、<b>绝不放进 {@code PlaceController}</b>：{@code PlaceControllerNoEditEndpointTest}
 * 禁止 {@code PlaceController} 带任何 {@code @DeleteMapping}。
 */
@RestController
public class CollectController {

    private final CollectService posts;
    private final PlaceCollectService places;

    public CollectController(CollectService posts, PlaceCollectService places) {
        this.posts = posts;
        this.places = places;
    }

    @PostMapping("/api/v1/content-posts/{id}/collect")
    public CollectResponse collectPost(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return new CollectResponse(posts.collect(id, currentUserId(jwt)));
    }

    @DeleteMapping("/api/v1/content-posts/{id}/collect")
    public CollectResponse uncollectPost(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return new CollectResponse(posts.uncollect(id, currentUserId(jwt)));
    }

    @PostMapping("/api/v1/places/{token}/collect")
    public CollectResponse collectPlace(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return new CollectResponse(places.collect(token, currentUserId(jwt)));
    }

    @DeleteMapping("/api/v1/places/{token}/collect")
    public CollectResponse uncollectPlace(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return new CollectResponse(places.uncollect(token, currentUserId(jwt)));
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
