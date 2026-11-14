package com.tailtopia.healthreminder.web;

import com.tailtopia.healthreminder.delivery.HealthNotificationOpenService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点开健康提醒（V1.5.0 Story 2.4 · AC5，架构增量 §5.4）：{@code GET /api/v1/me/health-notifications/{token}/open}。
 * JWT；token 不存在或不属于本人 → 404。{@code source}（push / inbox）只由 App 用来报埋点，后端不分支。
 */
@RestController
public class HealthNotificationOpenController {

    private final HealthNotificationOpenService service;

    public HealthNotificationOpenController(HealthNotificationOpenService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/me/health-notifications/{token}/open")
    public HealthNotificationOpenResponse open(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @RequestParam(name = "source", required = false) String source) {
        return service.open(currentUserId(jwt), token);
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
