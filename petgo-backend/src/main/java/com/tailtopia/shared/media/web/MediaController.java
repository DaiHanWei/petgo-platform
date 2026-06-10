package com.petgo.shared.media.web;

import com.petgo.shared.error.AppException;
import com.petgo.shared.media.StsService;
import com.petgo.shared.media.dto.StsCredentialRequest;
import com.petgo.shared.media.dto.StsCredentialResponse;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体基建 STS 端点（Story 2.1 · AC1）。跨模块共享基础设施，置于 {@code shared/media}。
 *
 * <p>{@code POST /api/v1/media/sts-credentials}：需 JWT；按 scope 签发收窄凭证。套写端点限流防滥发。
 * 护栏：响应含 secret/token 但**绝不进 INFO 日志**（仅 DEBUG 本地）。
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final int RATE_LIMIT = 30;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final StsService stsService;
    private final RedisRateLimiter rateLimiter;

    public MediaController(StsService stsService, RedisRateLimiter rateLimiter) {
        this.stsService = stsService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/sts-credentials")
    public StsCredentialResponse issueCredential(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StsCredentialRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:media:sts:" + userId, RATE_LIMIT, RATE_WINDOW);
        return stsService.issueUploadCredential(req.scope(), userId);
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
