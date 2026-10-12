package com.tailtopia.social.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.dto.FollowListPage;
import com.tailtopia.social.dto.FollowResultResponse;
import com.tailtopia.social.dto.FollowSuggestionsResponse;
import com.tailtopia.social.service.FollowListQueryService;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.social.service.FollowSuggestionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注 / 取关端点（V1.3.4 follow · Story 1.1 · AD-4）。{@code /api/v1/users/{token}/follow}。
 *
 * <p>路径用<b>不可枚举用户 token</b>（D-2），不用数字 id。鉴权：{@code SecurityConfig} 对该路径
 * {@code hasRole("USER")}（兽医 token 403、游客 401）。两个动词都幂等。
 *
 * <p>Story 2.2：{@code GET …/following}、{@code GET …/followers} 关注 / 粉丝列表，游客可看
 * （{@code SecurityConfig} 两条精确 permitAll）；viewer 只认 {@code role=USER}（同 {@code PublicProfileController}）。
 *
 * <p>Story 2.3：{@code GET /api/v1/me/follow-suggestions} 推荐关注，{@code SecurityConfig} 精确 {@code hasRole("USER")}。
 */
@RestController
@RequestMapping("/api/v1")
public class FollowController {

    private final FollowService follows;
    private final FollowListQueryService lists;
    private final FollowSuggestionService suggestions;

    public FollowController(FollowService follows, FollowListQueryService lists,
            FollowSuggestionService suggestions) {
        this.follows = follows;
        this.lists = lists;
        this.suggestions = suggestions;
    }

    /** 推荐关注（Story 2.3）：最多 30 行，无分页。 */
    @GetMapping("/me/follow-suggestions")
    public FollowSuggestionsResponse followSuggestions(@AuthenticationPrincipal Jwt jwt) {
        return suggestions.suggestionsFor(currentUserId(jwt));
    }

    /** {@code token} 用户关注的人（Story 2.2）。 */
    @GetMapping("/users/{token}/following")
    public FollowListPage following(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @RequestParam(required = false) String cursor) {
        return lists.following(token, viewerId(jwt), cursor);
    }

    /** 关注 {@code token} 用户的人（Story 2.2）。 */
    @GetMapping("/users/{token}/followers")
    public FollowListPage followers(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @RequestParam(required = false) String cursor) {
        return lists.followers(token, viewerId(jwt), cursor);
    }

    @PutMapping("/users/{token}/follow")
    public FollowResultResponse follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return follows.follow(currentUserId(jwt), token);
    }

    @DeleteMapping("/users/{token}/follow")
    public FollowResultResponse unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return follows.unfollow(currentUserId(jwt), token);
    }

    /**
     * 列表的查看者：登录<b>用户</b> id；游客 / 无效 JWT / 非 USER 角色 → null（关系字段全 false）。
     * ⚠️ 兽医 {@code sub=vetId} 与 users.id 是会碰撞的两个命名空间（同 {@code PublicProfileController}）。
     */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
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
