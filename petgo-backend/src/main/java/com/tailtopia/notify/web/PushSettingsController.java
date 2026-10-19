package com.tailtopia.notify.web;

import com.tailtopia.notify.dto.UpdatePushSettingsRequest;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.preference.PushSettingsView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推送分类设置（V1.4.0 ④ Story 1.1 · AD-4）。账号级，挂 {@code /api/v1/me/…}（决策 C1）；仅 {@code role=USER}
 * （SecurityConfig 精确规则）。只作用于 JWT {@code sub} 本人。业务校验全部在 service。
 */
@RestController
@RequestMapping("/api/v1/me/push-settings")
public class PushSettingsController {

    /** 写入限流：30 次 / 分钟 / 用户。 */
    private static final int PATCH_LIMIT = 30;
    private static final Duration PATCH_WINDOW = Duration.ofMinutes(1);

    private final PushPreferenceService service;
    private final RedisRateLimiter rateLimiter;

    public PushSettingsController(PushPreferenceService service, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public PushSettingsView get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(currentUserId(jwt));
    }

    @PatchMapping
    public PushSettingsView patch(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePushSettingsRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:push-settings:" + userId, PATCH_LIMIT, PATCH_WINDOW);
        return service.update(userId, req.categories());
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
