package com.petgo.auth.web;

import com.petgo.auth.dto.GoogleLoginRequest;
import com.petgo.auth.dto.LoginResponse;
import com.petgo.auth.dto.RefreshRequest;
import com.petgo.auth.dto.TokenResponse;
import com.petgo.auth.service.AuthService;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * auth 端点（放行，无需 JWT）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/google}：Google ID Token → 建号/取号 → 签发自签 JWT。</li>
 *   <li>{@code POST /api/v1/auth/refresh}：refresh 轮换。</li>
 * </ul>
 * 两端点接 Redis 令牌桶限流；超限 429 ProblemDetail。日志严禁记录 idToken/JWT/email/refresh。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RedisRateLimiter rateLimiter;

    public AuthController(AuthService authService, RedisRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/google")
    public LoginResponse google(@Valid @RequestBody GoogleLoginRequest req, HttpServletRequest http) {
        rateLimiter.check("rl:auth:google:" + clientIp(http), 10, Duration.ofMinutes(1));
        return authService.loginWithGoogle(req.idToken());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        rateLimiter.check("rl:auth:refresh:" + clientIp(http), 30, Duration.ofMinutes(1));
        return authService.rotateRefresh(req.refreshToken());
    }

    /** 退出登录（Story 7.3 AC1）：作废 refresh 句柄，不删任何数据。放行（与 google/refresh 同链）。 */
    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
    }

    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
