package com.tailtopia.auth.web;

import com.tailtopia.auth.dto.VetLoginRequest;
import com.tailtopia.auth.dto.VetLoginResponse;
import com.tailtopia.auth.service.AuthService;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医账密登录端点（Story 5.1，第二条登录路径，放行无需 token）。
 *
 * <p>{@code POST /api/v1/auth/vet/login}：账号密码 → BCrypt 比对 → 签发 {@code role=VET} JWT。
 * 失败统一 401 ProblemDetail（不区分账号不存在/密码错，防枚举）。接 Redis 限流防爆破。
 * 日志严禁记录 username/password/JWT/refresh。无「忘记密码」（重置走 Admin）。
 */
@RestController
@RequestMapping("/api/v1/auth/vet")
public class VetAuthController {

    private final AuthService authService;
    private final RedisRateLimiter rateLimiter;

    public VetAuthController(AuthService authService, RedisRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public VetLoginResponse login(@Valid @RequestBody VetLoginRequest req, HttpServletRequest http) {
        rateLimiter.check("rl:auth:vet:" + clientIp(http), 10, Duration.ofMinutes(1));
        return authService.loginVet(req.username(), req.password());
    }

    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
