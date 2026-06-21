package com.tailtopia.triage.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.triage.dto.TriageAcceptedResponse;
import com.tailtopia.triage.dto.TriageResultResponse;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.service.TriageService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分诊端点（Story 4.1）。资源化 {@code /api/v1/triage}。
 *
 * <p>{@code POST}：JWT；<b>202 异步受理</b> + {@link TriageAcceptedResponse}；{@code Idempotency-Key}
 * 头去重；写端点 Redis 令牌桶限流。{@code GET /{id}}：短轮询取结果，仅本人可读（越权/不存在均 403 防枚举）。
 * {@code userId} 一律取自 JWT，不信任客户端。
 */
@RestController
@RequestMapping("/api/v1/triage")
public class TriageController {

    private static final int SUBMIT_LIMIT = 10;
    private static final Duration SUBMIT_WINDOW = Duration.ofMinutes(1);

    private final TriageService triageService;
    private final RedisRateLimiter rateLimiter;

    public TriageController(TriageService triageService, RedisRateLimiter rateLimiter) {
        this.triageService = triageService;
        this.rateLimiter = rateLimiter;
    }

    /** 提交分诊：202 异步受理 + triageId（AC1）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TriageAcceptedResponse submit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Valid @RequestBody TriageSubmitRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:triage:submit:" + userId, SUBMIT_LIMIT, SUBMIT_WINDOW);
        return triageService.submit(userId, req, idempotencyKey, resolveLocale(acceptLanguage));
    }

    /**
     * 归一回复语言：仅 {@code id}/{@code en} 两种，默认 {@code en}（兜底英语，绝不中文）。
     * 取 Accept-Language 首选项的主语言子标签（如 {@code id-ID} → {@code id}）。
     */
    private static String resolveLocale(String acceptLanguage) {
        if (acceptLanguage != null && acceptLanguage.trim().toLowerCase().startsWith("id")) {
            return "id";
        }
        return "en";
    }

    /** 短轮询取结果：处理中仅回 status，DONE 回完整结构，FAILED 供降级（AC2）。 */
    @GetMapping("/{id}")
    public TriageResultResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return triageService.getResult(currentUserId(jwt), id);
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
