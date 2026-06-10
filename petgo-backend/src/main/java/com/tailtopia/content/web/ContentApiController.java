package com.petgo.content.web;

import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.content.service.ContentService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容发布端点（Story 2.3）。资源化 {@code /api/v1/content-posts}。
 *
 * <p>{@code POST}：JWT；201 + {@link ContentPostResponse}；{@code Idempotency-Key} 头去重；写端点限流。
 * author 取自 JWT，不信任客户端。
 */
@RestController
@RequestMapping("/api/v1/content-posts")
public class ContentApiController {

    private static final int PUBLISH_LIMIT = 20;
    private static final Duration PUBLISH_WINDOW = Duration.ofMinutes(1);

    private final ContentService contentService;
    private final RedisRateLimiter rateLimiter;

    public ContentApiController(ContentService contentService, RedisRateLimiter rateLimiter) {
        this.contentService = contentService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentPostResponse publish(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContentPostCreateRequest req) {
        long authorId = currentUserId(jwt);
        rateLimiter.check("rl:content:publish:" + authorId, PUBLISH_LIMIT, PUBLISH_WINDOW);
        return contentService.publish(authorId, req, idempotencyKey);
    }

    /**
     * 内容删除（Story 3.6，FR-36）。仅作者本人；软删 + 级联清评论点赞；已删幂等。
     * 删除后 Feed/时间线（{@code deleted_at IS NULL} 过滤）即时移除，详情走 404（Story 3.3）。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        contentService.deleteByAuthor(id, currentUserId(jwt));
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
