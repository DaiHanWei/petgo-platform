package com.tailtopia.content.web;

import com.tailtopia.content.dto.ContentShareLinkResponse;
import com.tailtopia.content.dto.SharedPostResponse;
import com.tailtopia.content.service.ContentShareService;
import com.tailtopia.shared.error.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单条内容分享（Story 9.3 · FR-73）。
 *
 * <p>两个端点，权限截然不同：
 * <ul>
 *   <li>{@code POST /api/v1/content-posts/{id}/share-link} —— <b>需登录</b>，且只能分享自己的内容。
 *       author 取自 JWT，不信任客户端。</li>
 *   <li>{@code GET /api/v1/public/shared-posts/{shareToken}} —— <b>公开无需鉴权</b>
 *       （{@code /api/v1/public/**} 已在 SecurityConfig 放行）。App 内点开分享链接时用它，
 *       未登录也能看 —— 否则会把人推回浏览器。</li>
 * </ul>
 *
 * <p>🛡 公开端点<b>只按 token 寻址</b>，返回的投影里没有任何 id（见 {@link SharedPostResponse}）。
 */
@RestController
public class ContentShareApiController {

    private final ContentShareService shareService;

    public ContentShareApiController(ContentShareService shareService) {
        this.shareService = shareService;
    }

    @PostMapping("/api/v1/content-posts/{id}/share-link")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentShareLinkResponse createLink(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        return shareService.createOrRefresh(currentUserId(jwt), id);
    }

    @GetMapping("/api/v1/public/shared-posts/{shareToken}")
    public SharedPostResponse sharedPost(@PathVariable String shareToken) {
        // 失效一律 404 同一文案：不区分"没这个 token" / "内容已删" / "作者注销"（防枚举）。
        return shareService.findSharedPost(shareToken)
                .orElseThrow(() -> AppException.notFound("这条内容已不存在"));
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
