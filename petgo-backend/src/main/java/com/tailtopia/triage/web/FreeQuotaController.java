package com.tailtopia.triage.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.dto.FreeQuotaView;
import com.tailtopia.triage.service.FreeQuotaService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本月免费额度端点（Story 2.1）。当前用户主体 {@code /api/v1/me}（决策 C1）。
 *
 * <p>{@code GET /free-quota}：JWT 门控，返回 {@code {period, limit, used, remaining}}。只读、不建行。
 * {@code userId} 一律取自 JWT，不信任客户端（照 {@code PawCoinController} / {@code TriageController} 范式）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class FreeQuotaController {

    private final FreeQuotaService freeQuota;

    public FreeQuotaController(FreeQuotaService freeQuota) {
        this.freeQuota = freeQuota;
    }

    @GetMapping("/free-quota")
    public FreeQuotaView freeQuota(@AuthenticationPrincipal Jwt jwt) {
        return freeQuota.status(currentUserId(jwt));
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
