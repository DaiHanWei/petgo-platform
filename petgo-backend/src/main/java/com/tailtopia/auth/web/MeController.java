package com.tailtopia.auth.web;

import com.tailtopia.auth.dto.UpdateMeRequest;
import com.tailtopia.auth.dto.UserProfileResponse;
import com.tailtopia.auth.service.MeService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户主体统一端点（决策 C1：全平台用 {@code /api/v1/me}，不用 {@code /users/me}）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/me}：当前用户聚合视图（引导预填 / 改状态回显）。</li>
 *   <li>{@code PATCH /api/v1/me}：改昵称 / 宠物状态（Story 1.6）；后续 Story 复用此端点。</li>
 * </ul>
 * 仅作用于当前 JWT {@code sub} 对应用户（不接受任意 userId，防越权）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "Accept-Language", required = false) String acceptLanguage) {
        long userId = currentUserId(jwt);
        // 捕获语言偏好（bug 20260625-105）：供系统推送按 id/en 渲染。best-effort，失败不影响 /me。
        meService.captureLocale(userId, acceptLanguage);
        return meService.getMe(userId);
    }

    @PatchMapping
    public UserProfileResponse update(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateMeRequest req) {
        return meService.updateMe(currentUserId(jwt), req);
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
