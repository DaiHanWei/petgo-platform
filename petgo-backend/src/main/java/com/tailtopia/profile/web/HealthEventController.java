package com.petgo.profile.web;

import com.petgo.profile.dto.ArchiveDecisionRequest;
import com.petgo.profile.dto.ArchiveDecisionResponse;
import com.petgo.profile.service.HealthEventService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问诊存档端点（Story 2.5）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/health-events/archive-decisions}：记录存档决策（一次性幂等）。</li>
 *   <li>{@code GET  /api/v1/health-events/decision?sourceRef=}：查该问诊是否已决策（控制弹窗只一次）。</li>
 * </ul>
 * 触发上游为 Epic 4（AI 分诊结束）/ Epic 5（兽医咨询结束）；本 Story 提供承接 API。
 */
@RestController
@RequestMapping("/api/v1/health-events")
public class HealthEventController {

    private static final int LIMIT = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final HealthEventService healthEventService;
    private final RedisRateLimiter rateLimiter;

    public HealthEventController(HealthEventService healthEventService, RedisRateLimiter rateLimiter) {
        this.healthEventService = healthEventService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/archive-decisions")
    public ArchiveDecisionResponse decide(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ArchiveDecisionRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:health:archive:" + ownerId, LIMIT, WINDOW);
        return healthEventService.recordDecision(ownerId, req);
    }

    @GetMapping("/decision")
    public Map<String, Boolean> hasDecision(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("sourceRef") String sourceRef) {
        currentUserId(jwt); // 需登录
        return Map.of("decided", healthEventService.hasDecision(sourceRef));
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
