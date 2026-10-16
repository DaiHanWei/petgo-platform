package com.tailtopia.content.web;

import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.MediaAclReconciler;
import com.tailtopia.content.service.VideoPostValidator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
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
 *
 * <p>视频帖（专题线 video Story 1.3）：先在<b>本层（事务外）</b>做 {@link VideoPostValidator#verifyObjects}
 * （等级、归属、OSS HEAD），再进 {@code @Transactional} 的 {@code publish} —— HEAD 不许在事务里发（一致性规则 7）。
 */
@RestController
@RequestMapping("/api/v1/content-posts")
public class ContentApiController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContentApiController.class);

    private static final int PUBLISH_LIMIT = 20;
    private static final Duration PUBLISH_WINDOW = Duration.ofMinutes(1);

    private final ContentService contentService;
    private final RedisRateLimiter rateLimiter;
    private final VideoPostValidator videoPostValidator;
    private final MediaAclReconciler mediaAclReconciler;

    public ContentApiController(ContentService contentService, RedisRateLimiter rateLimiter,
            VideoPostValidator videoPostValidator, MediaAclReconciler mediaAclReconciler) {
        this.contentService = contentService;
        this.rateLimiter = rateLimiter;
        this.videoPostValidator = videoPostValidator;
        this.mediaAclReconciler = mediaAclReconciler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentPostResponse publish(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContentPostCreateRequest req) {
        long authorId = currentUserId(jwt);
        rateLimiter.check("rl:content:publish:" + authorId, PUBLISH_LIMIT, PUBLISH_WINDOW);
        if (req.mediaTypeOrImage() == MediaType.VIDEO) {
            VideoPostValidator.VerifiedVideo verified = videoPostValidator.verifyObjects(authorId, req);
            ContentPostResponse resp = contentService.publish(authorId, req, idempotencyKey, verified);
            // Story 1.4（AD-6）：发布事务此刻已提交 → 事务外尽力即时对账，让封面和视频马上可见；
            // 失败不影响发布结果，定时任务每分钟兜底。
            try {
                mediaAclReconciler.reconcileOne(resp.id());
            } catch (RuntimeException e) {
                log.warn("video_acl_reconcile_inline_failed postId={} cause={}",
                        resp.id(), e.getClass().getSimpleName());
            }
            return resp;
        }
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
        // 专题线 video Story 3.1：事务已提交，视频帖立即把文件改回私有（失败由定时任务兜底）。
        mediaAclReconciler.reconcileQuietly(id);
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
