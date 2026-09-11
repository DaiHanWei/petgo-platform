package com.tailtopia.onboarding.web;

import com.tailtopia.onboarding.domain.OnboardingMarkKey;
import com.tailtopia.onboarding.dto.MarkOnboardingRequest;
import com.tailtopia.onboarding.dto.OnboardingMarksResponse;
import com.tailtopia.onboarding.service.OnboardingMarkService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一次性引导标记的读写（V1.3.0 批次 A · Story 5.4 · AD-A21）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/me/onboarding-marks}：当前用户已置位的键</li>
 *   <li>{@code POST /api/v1/me/onboarding-marks}：置位一个键（幂等）</li>
 * </ul>
 *
 * <p>挂在 {@code /api/v1/me} 下（决策 C1：当前用户主体统一走 /me，不用 /users/me）。
 * 只作用于当前 JWT {@code sub} 对应用户 —— 不接受任意 userId，防越权。
 */
@RestController
@RequestMapping("/api/v1/me/onboarding-marks")
public class OnboardingMarkController {

    private final OnboardingMarkService service;

    public OnboardingMarkController(OnboardingMarkService service) {
        this.service = service;
    }

    @GetMapping
    public OnboardingMarksResponse marks(@AuthenticationPrincipal Jwt jwt) {
        return new OnboardingMarksResponse(service.marksOf(currentUserId(jwt)));
    }

    /**
     * 置位一个键。**幂等**：重复置位是 204，不是错误。
     *
     * <p>🔴 未登记的键一律 422 —— 不写库、不静默吞掉。静默吞会让客户端以为置位成功，
     * 于是引导再也不弹，而表里一行都没有，排查时无迹可寻。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mark(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MarkOnboardingRequest req) {
        OnboardingMarkKey key = OnboardingMarkKey.fromWire(req.key())
                .orElseThrow(() -> AppException.validation("未登记的引导标记键"));
        service.mark(currentUserId(jwt), key);
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
