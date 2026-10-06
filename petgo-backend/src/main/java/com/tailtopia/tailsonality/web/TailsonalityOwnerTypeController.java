package com.tailtopia.tailsonality.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.tailsonality.dto.OwnerTypeRequest;
import com.tailtopia.tailsonality.dto.OwnerTypeResponse;
import com.tailtopia.tailsonality.service.TailsonalityOwnerTypeService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主人类型接口（V1.3.2 Story 2.5）。账号级，挂 {@code /api/v1/me/…}（决策 C1）；仅 {@code role=USER}。
 */
@RestController
@RequestMapping("/api/v1/me/tailsonality/owner-type")
public class TailsonalityOwnerTypeController {

    /** 写入限流：20 次 / 分钟 / 用户。 */
    private static final int SET_LIMIT = 20;
    private static final Duration SET_WINDOW = Duration.ofMinutes(1);

    private final TailsonalityOwnerTypeService service;
    private final RedisRateLimiter rateLimiter;

    public TailsonalityOwnerTypeController(TailsonalityOwnerTypeService service, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public OwnerTypeResponse get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(currentUserId(jwt));
    }

    @PutMapping
    public OwnerTypeResponse set(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OwnerTypeRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:tailsonality:owner-type:" + userId, SET_LIMIT, SET_WINDOW);
        return service.set(userId, req.typeCode());
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
