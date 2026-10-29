package com.tailtopia.collect.web;

import com.tailtopia.collect.dto.CollectedPlaceRow;
import com.tailtopia.collect.dto.CollectedPostRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.service.MyCollectionQuery;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的收藏列表（V1.4.0 ④ Story 2.2 · AD-11）。账号级 {@code /api/v1/me/…}（决策 C1），仅 role=USER
 * （SecurityConfig 精确规则）。<b>只作用于 JWT {@code sub} 本人</b>，不接受任何 userId / token 查询参数（收藏仅本人可见）。
 */
@RestController
@RequestMapping("/api/v1/me/collections")
public class MyCollectionController {

    private final MyCollectionQuery query;

    public MyCollectionController(MyCollectionQuery query) {
        this.query = query;
    }

    @GetMapping("/posts")
    public CollectionPage<CollectedPostRow> posts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        return query.posts(currentUserId(jwt), cursor);
    }

    @GetMapping("/places")
    public CollectionPage<CollectedPlaceRow> places(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        return query.places(currentUserId(jwt), cursor);
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
