package com.tailtopia.starterpack.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.starterpack.dto.StarterPackPurchaseRequest;
import com.tailtopia.starterpack.dto.StarterPackPurchaseResponse;
import com.tailtopia.starterpack.dto.StarterPackPurchaseStatusResponse;
import com.tailtopia.starterpack.dto.StarterPackView;
import com.tailtopia.starterpack.service.StarterPackPurchaseService;
import java.time.Duration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超值礼包（V1.5.0 gift-pack Story 4-2 · 架构 §4.2）。三条路由只给 {@code role=USER}（{@code SecurityConfig} 精确 matcher）。
 */
@RestController
public class StarterPackController {

    static final int BUY_LIMIT = 10;
    static final Duration BUY_WINDOW = Duration.ofMinutes(1);

    private final StarterPackPurchaseService service;
    private final RedisRateLimiter rateLimiter;

    public StarterPackController(StarterPackPurchaseService service, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/starter-pack")
    public StarterPackView view(@AuthenticationPrincipal Jwt jwt) {
        return service.view(currentUserId(jwt));
    }

    @PostMapping("/api/v1/starter-pack/purchases")
    public StarterPackPurchaseResponse purchase(@AuthenticationPrincipal Jwt jwt,
            @RequestBody StarterPackPurchaseRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:starterpack:buy:" + userId, BUY_LIMIT, BUY_WINDOW);
        try {
            return service.purchase(userId, req);
        } catch (DataIntegrityViolationException e) {
            // 并发同 requestId：另一请求已插入同幂等键的单 → 新事务重读、按那笔返回；撞的不是幂等键则原样抛出。
            return service.replayExisting(userId, req == null ? null : req.requestId()).orElseThrow(() -> e);
        }
    }

    @GetMapping("/api/v1/me/starter-pack-purchases/{token}")
    public StarterPackPurchaseStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return service.status(currentUserId(jwt), token);
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
